# Diff Algorithm Design — SyncService.computeDiff()

## 1. Overview

The Ranger–Lake Formation sync plugin keeps AWS Lake Formation permissions in sync with
Apache Ranger policies. On every policy update received from Ranger Admin, `SyncService`
must determine the *minimal* set of grant and revoke operations needed to bring Lake
Formation into alignment with the new policy state.

`SyncService.computeDiff()` is the core of this process. It compares two flat lists of
`LFPermissionOperation` objects — one representing the *previous* sync snapshot and one
representing the *current* snapshot derived from the latest Ranger policies — and produces
a `PolicyDiff` containing:

| Bucket | Meaning |
|---|---|
| **newGrants** | Permissions present in *current* but absent from *previous* → `GrantPermissions` API call |
| **revocations** | Permissions present in *previous* but absent from *current* → `RevokePermissions` API call |
| **unchanged** | Permissions present in both → no-op |

By emitting only the delta, the plugin avoids redundant API calls and minimises the risk
of throttling or conflicting concurrent modifications in Lake Formation.

---

## 2. The PermissionKey Concept

Two `LFPermissionOperation` objects are considered the **same logical permission** when
they share the same identity tuple:

```
(principalArn, resource, permissions, grantable)
```

The following fields are deliberately **excluded** from identity comparison:

| Excluded field | Reason |
|---|---|
| `operationType` | A permission's identity doesn't change because it is being granted vs. revoked. |
| `sourcePolicyId` | Multiple Ranger policies may produce the same effective LF permission; the source is metadata, not identity. |

### Implementation — `PermissionKey` (inner class of `SyncService`)

```java
static final class PermissionKey {
    private final String      principalArn;
    private final Object      resource;       // LFResource (equals/hashCode on all resource fields)
    private final Set<Object> permissions;    // copied into a HashSet for value-based equality
    private final boolean     grantable;

    static PermissionKey of(LFPermissionOperation op) {
        return new PermissionKey(
            op.getPrincipalArn(),
            op.getResource(),
            new HashSet<>(op.getPermissions()),
            op.isGrantable());
    }

    // equals: principalArn, resource, permissions, grantable
    // hashCode: Objects.hash(principalArn, resource, permissions, grantable)
}
```

`LFResource.equals()` compares `catalogId`, `databaseName`, `tableName`, `columnNames`,
and `rowFilterExpression`, so two resources targeting the same catalog object are equal
regardless of object identity.


---

## 3. Algorithm Steps

### 3.1 Build permission maps

```
previousMap = Map<PermissionKey, LFPermissionOperation>   // from previous snapshot
currentMap  = Map<PermissionKey, LFPermissionOperation>   // from current snapshot
```

Each map is built by `buildPermissionMap()`, which iterates the operation list and inserts
`PermissionKey.of(op) → op`. If duplicate keys exist (e.g. two Ranger policies that
produce the same effective LF permission), **last-write-wins**.

### 3.2 Identify new grants

```
for each key in currentMap:
    if key NOT in previousMap:
        → add to newGrants (force operationType = GRANT)
```

If the source operation happened to carry `REVOKE` as its type (unlikely but defensive),
`computeDiff` replaces it with a new `LFPermissionOperation` whose type is `GRANT`.

### 3.3 Identify revocations

```
for each key in previousMap:
    if key NOT in currentMap:
        → create a REVOKE operation and add to revocations
```

A new `LFPermissionOperation` is always constructed with `OperationType.REVOKE`,
preserving the original `sourcePolicyId`, `principalArn`, `resource`, `permissions`, and
`grantable` values.

### 3.4 Count unchanged

```
for each key in currentMap:
    if key IN previousMap:
        unchangedCount++
```

These permissions already exist in Lake Formation and require no API call.

### 3.5 Return PolicyDiff

```java
return new PolicyDiff(newGrants, revocations, unchangedCount);
```

`PolicyDiff` wraps the three buckets in unmodifiable lists and exposes them via getters.

### Complexity

| Step | Time |
|---|---|
| Build maps | O(P + C) where P = |previous|, C = |current| |
| New grants | O(C) |
| Revocations | O(P) |
| Unchanged | O(C) |
| **Total** | **O(P + C)** — linear in the number of permissions |

---

## 4. End-to-End Pipeline

The diff is one stage in a larger pipeline triggered by `SyncService.onPoliciesUpdated()`:

```
Ranger Admin
    │
    ▼
ServicePolicies (list of RangerPolicy)
    │
    ▼
PolicyConverter.convertBatch()
    │  Converts each RangerPolicy into one or more LFPermissionOperation objects.
    │  Skips tag-based policies and records unsupported features in GapReporter.
    │  Returns ConversionResult { operations, successCount, skippedCount }.
    │
    ▼
List<LFPermissionOperation>  (current snapshot)
    │
    ▼
SyncService.computeDiff(previousOperations, currentOperations)
    │  Produces PolicyDiff { newGrants, revocations, unchangedCount }.
    │
    ▼
Delta operations  (newGrants ++ revocations)
    │
    ▼
Audit logging  (each operation logged before application)
    │
    ▼
LakeFormationClient.applyBatch(deltaOperations, deadLetterLogger)
    │  Groups operations by sourcePolicyId (LinkedHashMap, insertion-order).
    │  For each policy group:
    │    • Apply operations sequentially (grant or revoke).
    │    • On failure: rollback all previously applied ops for that policy
    │      (reverse order, grants→revokes and vice-versa), log to dead-letter,
    │      then continue to the next policy group.
    │  Returns BatchResult { succeededPolicies, failedPolicies, counts }.
    │
    ▼
previousOperations = currentOperations   (update snapshot for next cycle)
```

### Atomicity guarantees

- Operations are grouped by `sourcePolicyId` so that a failure in one Ranger policy does
  not affect operations from other policies.
- Within a policy group, if any operation fails, all previously applied operations for
  that group are rolled back in reverse order (grants reversed by revokes, revokes
  reversed by grants).
- Rollback failures are logged but do not propagate — the system favours availability
  over strict consistency.
- Failed and skipped operations are written to the dead-letter log for manual review.


---

## 5. Data Location Permissions

### 5.1 What are data location permissions?

In AWS Lake Formation, `DATA_LOCATION_ACCESS` is a special permission type that controls
which IAM principals can create databases or tables at a particular S3 location. Unlike
database/table/column permissions, data location permissions target an **S3 path** rather
than a Glue Catalog object. The Lake Formation API uses a dedicated `DataLocationResource`
structure:

```
GrantPermissions(
    Resource: {
        DataLocation: {
            CatalogId:   "123456789012",
            ResourceArn: "arn:aws:s3:::my-bucket/path/to/data"
        }
    },
    Permissions: [DATA_LOCATION_ACCESS]
)
```

The only valid permission for this resource type is `DATA_LOCATION_ACCESS`.

### 5.2 Service definition

The Ranger service definition (`ranger-servicedef-lakeformation.json`) declares both a
resource type and an access type for data location:

**Resource:**
```json
{
    "itemId": 4,
    "name": "datalocation",
    "type": "string",
    "level": 10,
    "lookupSupported": true,
    "recursiveSupported": false,
    "label": "Data Location",
    "description": "Permissions to create database or table at a particular S3 Location."
}
```

**Access type:**
```json
{"itemId": 9, "name": "data_location_access", "label": "Data Location"}
```

### 5.3 How the pipeline handles data location

Data location policies flow through the same pipeline as database/table/column policies,
with resource-type-specific handling at each layer:

| Layer | Behaviour |
|---|---|
| `AccessTypeMapper` | Maps both `"datalocation"` and `"data_location_access"` → `{LFPermission.DATA_LOCATION_ACCESS}`. |
| `PolicyConverter.convert()` | Detects the `datalocation` resource key in the policy's resource map. For each S3 path value, creates an `LFResource` with `dataLocationPath` set (and `databaseName`/`tableName`/`columnNames` null). Only `DATA_LOCATION_ACCESS` is emitted — other permissions in the same policy item are ignored for the data location resource. |
| `LFResource` | Carries the S3 path in the `dataLocationPath` field. `equals()`/`hashCode()` include this field, so data location resources are correctly distinguished from database/table resources in the diff. |
| `LakeFormationClient.buildResource()` | When `dataLocationPath` is non-null, builds a `DataLocationResource` with `catalogId` and `resourceArn` set to the S3 path. |
| `SyncService.computeDiff()` | No special handling needed — the diff is resource-type-agnostic. Data location operations participate in the same `PermissionKey` map as all other operations. |

A single Ranger policy may contain both `database` and `datalocation` resources. In that
case, `PolicyConverter` generates operations for both: data location operations for the
S3 paths, and database/table/column operations for the catalog resources.

### 5.4 Interaction with the diff

Because `LFResource.equals()` includes `dataLocationPath`, a data location permission and
a database permission are always different `PermissionKey` values — even if they share the
same principal and permission set. This means:

- Adding a data location policy produces new grants (data location operations appear in
  `currentMap` but not `previousMap`).
- Removing a data location policy produces revocations (data location operations appear
  in `previousMap` but not `currentMap`).
- Modifying the S3 path produces both a grant (new path) and a revocation (old path),
  since the `LFResource` identity changes.

---

## 6. Worked Examples

All examples use the following shorthand:

| Shorthand | Meaning |
|---|---|
| `db=finance` | `LFResource(catalogId="123456789012", databaseName="finance", ...)` |
| `db=finance/t=transactions` | `LFResource(catalogId="123456789012", databaseName="finance", tableName="transactions", ...)` |
| `arn:aws:iam::123456789012:role/analyst` | IAM role ARN used as `principalArn` |
| `[SELECT]` | `permissions = {LFPermission.SELECT}` |
| `grantable=false` | `grantable` flag on the operation |

---

### 6.1 Initial Sync (empty previous → all grants)

**Scenario:** First sync cycle. No previous snapshot exists (`previousOperations = []`).

**Ranger policy input:**

```
Policy "fin-read" (id=1):
  resources: database=finance, table=transactions
  allow:     role/analyst → SELECT
```

**After `PolicyConverter.convertBatch()`:**

```
currentOperations = [
  LFPermissionOperation(GRANT, policyId="1",
      principal="arn:aws:iam::123456789012:role/analyst",
      resource=db=finance/t=transactions,
      permissions=[SELECT], grantable=false)
]
```

**`computeDiff(previous=[], current=[above])`:**

```
previousMap = {}
currentMap  = { Key(analyst, finance/transactions, [SELECT], false) → op }

newGrants   = [ GRANT analyst → SELECT on finance/transactions ]
revocations = []
unchanged   = 0
```

**LF API calls:**

```
GrantPermissions(
    Principal: arn:aws:iam::123456789012:role/analyst,
    Resource:  Database="finance", Table="transactions",
    Permissions: [SELECT])
```

---

### 6.2 Policy Added (new grants appear)

**Scenario:** A second policy `"fin-write"` is added while `"fin-read"` remains unchanged.

**Ranger policy input (cycle 2):**

```
Policy "fin-read" (id=1):   [unchanged]
Policy "fin-write" (id=2):
  resources: database=finance, table=transactions
  allow:     role/etl-job → INSERT, DELETE
```

**After `PolicyConverter.convertBatch()`:**

```
currentOperations = [
  LFPermissionOperation(GRANT, "1", analyst,  finance/transactions, [SELECT],         false),
  LFPermissionOperation(GRANT, "2", etl-job,  finance/transactions, [INSERT, DELETE], false)
]
```

**`computeDiff(previous=[op from 6.1], current=[above])`:**

```
previousMap = { Key(analyst, ..., [SELECT], false) → op1 }
currentMap  = { Key(analyst, ..., [SELECT], false) → op1,
                Key(etl-job, ..., [INSERT,DELETE], false) → op2 }

newGrants   = [ GRANT etl-job → INSERT,DELETE on finance/transactions ]
revocations = []
unchanged   = 1   (analyst SELECT — already in LF)
```

**LF API calls:**

```
GrantPermissions(
    Principal: arn:aws:iam::123456789012:role/etl-job,
    Resource:  Database="finance", Table="transactions",
    Permissions: [INSERT, DELETE])
```

---

### 6.3 Policy Removed (revocations generated)

**Scenario:** Policy `"fin-write"` (id=2) is deleted from Ranger. Only `"fin-read"` remains.

**Ranger policy input (cycle 3):**

```
Policy "fin-read" (id=1):   [unchanged]
```

**After `PolicyConverter.convertBatch()`:**

```
currentOperations = [
  LFPermissionOperation(GRANT, "1", analyst, finance/transactions, [SELECT], false)
]
```

**`computeDiff(previous=[ops from 6.2], current=[above])`:**

```
previousMap = { Key(analyst, ..., [SELECT], false) → op1,
                Key(etl-job, ..., [INSERT,DELETE], false) → op2 }
currentMap  = { Key(analyst, ..., [SELECT], false) → op1 }

newGrants   = []
revocations = [ REVOKE etl-job → INSERT,DELETE on finance/transactions ]
unchanged   = 1
```

**LF API calls:**

```
RevokePermissions(
    Principal: arn:aws:iam::123456789012:role/etl-job,
    Resource:  Database="finance", Table="transactions",
    Permissions: [INSERT, DELETE])
```


---

### 6.4 Policy Modified (some grants + some revocations)

**Scenario:** Policy `"fin-read"` is modified — the analyst role now gets `SELECT` and
`DESCRIBE` instead of just `SELECT`, and the resource scope changes to include columns.

**Ranger policy input (cycle 4):**

```
Policy "fin-read" (id=1):
  resources: database=finance, table=transactions, columns=[amount, currency]
  allow:     role/analyst → SELECT, DESCRIBE
```

**After `PolicyConverter.convertBatch()`:**

`DESCRIBE` is **not valid at the column level** in Lake Formation. `PolicyConverter`
automatically strips `DESCRIBE` from the permission set when the target resource includes
columns (see `buildResourceOperations`). The resulting operation contains only `SELECT`:

```
currentOperations = [
  LFPermissionOperation(GRANT, "1", analyst,
      resource=db=finance/t=transactions/cols=[amount,currency],
      permissions=[SELECT], grantable=false)
]
```

**`computeDiff(previous=[op from 6.3], current=[above])`:**

The previous snapshot has:
```
Key(analyst, finance/transactions,                        [SELECT], false)
```

The current snapshot has:
```
Key(analyst, finance/transactions/cols=[amount,currency], [SELECT], false)
```

These are **different keys** because the `resource` fields differ (column set changed).

```
newGrants   = [ GRANT analyst → SELECT on finance/transactions/[amount,currency] ]
revocations = [ REVOKE analyst → SELECT on finance/transactions ]
unchanged   = 0
```

**LF API calls (grouped by policyId="1", applied sequentially):**

```
GrantPermissions(
    Principal: arn:aws:iam::123456789012:role/analyst,
    Resource:  Database="finance", Table="transactions", ColumnNames=["amount","currency"],
    Permissions: [SELECT])

RevokePermissions(
    Principal: arn:aws:iam::123456789012:role/analyst,
    Resource:  Database="finance", Table="transactions",
    Permissions: [SELECT])
```

If the `GrantPermissions` call succeeds but `RevokePermissions` fails, `applyBatch` rolls
back the grant by issuing a compensating `RevokePermissions` for the first operation.

---

### 6.5 No Change (empty delta)

**Scenario:** Ranger Admin pushes a new policy version but the effective policies are
identical to the previous cycle.

**`computeDiff(previous=[ops], current=[identical ops])`:**

```
newGrants   = []
revocations = []
unchanged   = N   (all permissions match)
```

**LF API calls:** *none*

The log emits:
```
SyncService: no changes to apply for policy version <V>
```

---

## 7. Known Limitations

### 7.1 No delta support from Ranger Admin

Ranger Admin sends the **full** policy list on every update — there is no incremental
change feed. The plugin must therefore convert *all* policies every cycle and diff locally.
For large policy sets this is CPU-proportional but not network-expensive since the diff
avoids unnecessary LF API calls.

### 7.2 No persistence of previous state

The `previousOperations` list is held **in memory only**. If the sync process restarts,
the previous snapshot is lost and the first cycle after restart behaves like an initial
sync (Section 6.1) — every current permission is treated as a new grant. This is safe
(grants are idempotent in LF) but produces a burst of API calls.

### 7.3 No reconciliation with Lake Formation's actual state

The diff compares two Ranger-derived snapshots. It does **not** query Lake Formation to
discover permissions that may have been granted or revoked out-of-band (e.g. via the AWS
Console or another tool). Permissions created outside Ranger will not be revoked by the
plugin, and permissions revoked outside Ranger will be re-granted on the next cycle.

### 7.4 Last-write-wins on duplicate keys

If two Ranger policies produce the same `PermissionKey` (same principal, resource,
permissions, grantable), `buildPermissionMap` keeps whichever operation was inserted last.
The discarded operation's `sourcePolicyId` is lost. This is generally harmless — the
effective LF permission is the same — but it means audit logs may attribute the permission
to a different policy than expected.

### 7.5 Connectivity-loss behaviour

When Ranger Admin connectivity is lost (detected by null/empty policy list while a
previous snapshot exists), `SyncService` continues operating with the last known policy
set and does not clear Lake Formation permissions. This prevents accidental mass
revocation during transient network failures but means stale policies remain in effect
until connectivity is restored.

---

## 8. Source File Reference

| File | Key elements |
|---|---|
| `SyncService.java` | `computeDiff()`, `buildPermissionMap()`, `PermissionKey`, `PolicyDiff`, `onPoliciesUpdated()` |
| `LakeFormationClient.java` | `applyBatch()`, `rollbackOperations()`, `grantPermission()`, `revokePermission()`, `buildResource()` |
| `PolicyConverter.java` | `convert()`, `convertBatch()`, `buildResourceOperations()` |
| `AccessTypeMapper.java` | Ranger access-type → `LFPermission` mapping table |
| `LFPermissionOperation.java` | `OperationType` enum, operation fields |
| `LFResource.java` | Resource identity (`equals`/`hashCode` on catalog, database, table, columns, row filter) |
| `BatchResult.java` | Result envelope for `applyBatch()` |
| `ranger-servicedef-lakeformation.json` | Resource types (database, table, column, datalocation), access types, service configs |
