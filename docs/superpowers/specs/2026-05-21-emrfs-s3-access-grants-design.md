# EMRFS → S3 Access Grants Migration Design

**Date:** 2026-05-21  
**Status:** Approved  
**Author:** Mert Hocanin

---

## Context

Amazon EMR clusters with Apache Ranger enabled use the **EMRFS Ranger plugin** (`amazon-emr-emrfs`) to enforce S3 object-level access control. This plugin defines policies over S3 bucket/prefix paths with four access types: `GetObject`, `PutObject`, `ListObjects`, `DeleteObject`.

The existing ApacheRangerToLF tool translates Ranger policies into AWS Lake Formation permissions. EMRFS policies cannot go into Lake Formation directly — S3 object-level access is not a Lake Formation resource type. The target service for EMRFS policies is **Amazon S3 Access Grants**, which accepts IAM principal ARNs, S3 prefixes, and a permission level (`READ`, `WRITE`, `READWRITE`).

The design goal is to extend the tool so that:
1. EMRFS Ranger policies are migrated to S3 Access Grants automatically as part of the normal sync cycle.
2. If LF builds native S3 object access support in the future, the Cedar IR is already in the correct shape — only the apply stage needs to change.
3. Operators are warned (during sync and proactively during assessment) when S3 Access Grants locations are not registered for a given prefix.

---

## Architecture Overview

The existing pipeline is: **Ranger → Cedar IR → `CedarToLFConverter` → LF apply**.

The new pipeline is a split apply after the Cedar stage:

```
Ranger policies (all service types)
        │
        ▼
RangerToCedarConverter  (unified Cedar IR)
        │
        ├──► CedarToLFConverter        → LF diff → LF apply      (unchanged)
        │
        └──► CedarToS3AccessGrantsConverter → S3AG diff → S3AG apply (new)
```

Both converters operate on the same `CedarPolicySet`. Cedar is the durable source of truth; S3 Access Grants is an implementation detail of the apply stage.

---

## Component Design

### 1. Service Definition

**File:** `conf/ranger-servicedef-amazon-emr-emrfs.json` (already present — provided by operator)

Key fields:
- `name`: `"amazon-emr-emrfs"` — the service type key used for adapter dispatch
- Resource type: `sthreeresource` — a `bucket/prefix` path, no URI scheme, wildcards supported, recursive flag supported
- Access types: `GetObject`, `PutObject`, `ListObjects`, `DeleteObject`
- `enableDenyAndExceptionsInPolicies: false` — deny policies are not supported by EMRFS

---

### 2. Cedar Schema Extension

**File:** `src/main/resources/cedar/datacatalog.cedarschema`

A new Cedar namespace `S3` is added alongside the existing `DataCatalog` namespace. It declares:

- **Entity type:** `S3::Object` — represents an S3 resource identified by an S3 ARN (`arn:aws:s3:::bucket/prefix`)
- **Entity type:** `IAM::Principal` — reused from the existing schema (no change needed if already declared)
- **Actions:**
  - `S3::Action::"s3:GetObject"` applies to `S3::Object`
  - `S3::Action::"s3:PutObject"` applies to `S3::Object`
  - `S3::Action::"s3:ListObjects"` applies to `S3::Object`
  - `S3::Action::"s3:DeleteObject"` applies to `S3::Object`

Cedar policies for EMRFS resources take the form:

```
permit(
  principal == IAM::Principal::"arn:aws:iam::123456789012:user/alice",
  action == S3::Action::"s3:GetObject",
  resource == S3::Object::"arn:aws:s3:::my-bucket/data/*"
);
```

`CedarSchemaProvider` must load both namespaces. If it currently validates against a single schema file, it either needs to load a merged file or use schema composition. The simplest approach is to append the `S3` namespace block to the existing `datacatalog.cedarschema` file.

---

### 3. EmrfsServiceAdapter

**File:** `src/main/java/com/amazonaws/policyconverters/ranger/EmrfsServiceAdapter.java`  
**Implements:** `SourcePolicyAdapter`  
**Service type:** `"amazon-emr-emrfs"`

**Access type → Cedar action mapping:**

| Ranger access type | Cedar action |
|---|---|
| `GetObject` | `s3:GetObject` |
| `PutObject` | `s3:PutObject` |
| `ListObjects` | `s3:ListObjects` |
| `DeleteObject` | `s3:DeleteObject` |

`mapAccessTypeToCedarActions(String sourceAccessType)` returns `Set.of("s3:" + sourceAccessType)` for these four values; logs an unmapped access type warning for anything else.

**Resource construction (`buildEntityRef`):**

- Resource key: `sthreeresource`
- Values are `bucket/prefix` paths (no URI scheme)
- Each value is converted to an S3 ARN: `arn:aws:s3:::bucket/prefix`
- If `RangerPolicyResource.getIsRecursive()` is `true`, append `/*`: `arn:aws:s3:::bucket/prefix/*`
- Wildcards in the path (`*`, `?`) are preserved as-is

The Cedar entity type is `S3::Object`. The returned `CedarEntityRef` uses entity type `"S3::Object"` and identifier `"arn:aws:s3:::bucket/prefix"`.

No `CatalogResolver` expansion — S3 paths are concrete values.

**Principal mapping:** Reuses the existing `PrincipalMapper` — same IAM ARN resolution as Lake Formation.

**Wiring:** A new `EmrfsRangerService extends BaseRangerService` is created in `src/main/java/com/amazonaws/policyconverters/ranger/service/EmrfsRangerService.java`. It returns `"amazon-emr-emrfs"` as the service type and `"ranger-servicedef-amazon-emr-emrfs.json"` as the service definition resource path. It is registered into `adapterRegistry` in `SyncServiceMain` alongside the existing adapters.

---

### 4. CedarToLFConverter — Skip S3 Actions

**File:** `src/main/java/com/amazonaws/policyconverters/cedar/CedarToLFConverter.java`

In `resolveEffectiveGrants`, when a Cedar action is not found in `ACTION_TO_PERMISSION` and the action starts with `"s3:"`, it is silently skipped — no `UNSUPPORTED_ACTION` gap is recorded. This prevents every EMRFS policy from polluting the LF gap report with false negatives.

All other unknown actions continue to record an `UNSUPPORTED_ACTION` gap as today.

---

### 5. CedarToS3AccessGrantsConverter

**File:** `src/main/java/com/amazonaws/policyconverters/cedar/CedarToS3AccessGrantsConverter.java`

Consumes a `CedarPolicySet` and produces `List<S3AccessGrantOperation>`.

**Filtering:** Only processes Cedar statements where the action is in the `s3:` namespace (i.e., entity type `S3::Action`) and the resource entity type is `S3::Object`. LF-namespace statements are ignored.

**Cedar action → S3 Access Grants permission aggregation:**

Read actions: `s3:GetObject`, `s3:ListObjects`  
Write actions: `s3:PutObject`, `s3:DeleteObject`

Per `(principal, s3Resource)` pair, collect all effective Cedar actions (permits minus forbids — no deny-exception logic needed since EMRFS does not support deny). Classify the effective action set:

| Effective action set | Permission |
|---|---|
| Contains only read actions | `READ` |
| Contains only write actions | `WRITE` |
| Contains at least one read action AND at least one write action | `READWRITE` |

Examples:
- `{s3:GetObject}` → `READ`
- `{s3:ListObjects}` → `READ`
- `{s3:GetObject, s3:ListObjects}` → `READ`
- `{s3:PutObject}` → `WRITE`
- `{s3:DeleteObject}` → `WRITE`
- `{s3:PutObject, s3:DeleteObject}` → `WRITE`
- `{s3:GetObject, s3:PutObject}` → `READWRITE`
- `{s3:ListObjects, s3:PutObject}` → `READWRITE`
- `{s3:GetObject, s3:PutObject, s3:DeleteObject}` → `READWRITE`
- All four actions → `READWRITE`

**Output model:**

```java
// src/main/java/com/amazonaws/policyconverters/s3accessgrants/S3AccessGrantOperation.java
public record S3AccessGrantOperation(
    OperationType type,              // GRANT or REVOKE
    String principalArn,             // resolved IAM ARN
    String s3Prefix,                 // e.g. "s3://my-bucket/data/*"
    S3AccessGrantPermission permission,  // READ, WRITE, READWRITE
    @Nullable String grantId         // AWS-assigned grant ID; null for converter-produced ops, populated from listGrants()
) {}
```

```java
// src/main/java/com/amazonaws/policyconverters/s3accessgrants/S3AccessGrantPermission.java
public enum S3AccessGrantPermission { READ, WRITE, READWRITE }
```

**Resource format:** Cedar entity refs carry `arn:aws:s3:::bucket/prefix`. The converter strips `arn:aws:s3:::` and prepends `s3://` to produce the S3 Access Grants location format (e.g., `s3://bucket/prefix`).

---

### 6. S3AccessGrantsClient

**File:** `src/main/java/com/amazonaws/policyconverters/s3accessgrants/S3AccessGrantsClient.java`

Wraps `software.amazon.awssdk.services.s3control.S3ControlClient`.

**Key methods:**

```java
// Creates a grant. Returns the AWS-assigned grantId.
String createGrant(S3AccessGrantOperation op);

// Deletes a grant by AWS-assigned ID.
void deleteGrant(String grantId);

// Lists all current grants. Returned operations have grantId populated.
List<S3AccessGrantOperation> listGrants();

// Lists registered location prefixes (e.g., "s3://bucket/prefix/").
// Result is cached on first call and reused within the same sync cycle.
Set<String> listRegisteredLocations();

// Applies a batch of GRANT and REVOKE operations with retry/backoff.
// For REVOKE operations, resolves grantId by matching (principalArn, s3Prefix, permission)
// against the current listGrants() result.
BatchResult applyBatch(List<S3AccessGrantOperation> operations, int maxRetries, long retryBackoffMs);
```

**Location validation:** Before `createGrant`, checks if the target `s3Prefix` is covered by any registered location (i.e., `s3Prefix.startsWith(registeredLocation)`). If not:
- Logs a `WARN`-level structured log entry: `UNREGISTERED_S3_LOCATION` with `s3Prefix` and `principalArn`
- Writes to the dead-letter log
- **Skips the API call** — does not attempt `CreateAccessGrant`. The grant is not applied this cycle; it will be retried on the next sync cycle (after the operator registers the location).

Retry behavior: uses the same `maxLfRetries` and `lfRetryBackoffMs` values from `SyncConfig` for transient API errors. Unregistered-location skips are not retried within the same cycle.

---

### 7. SyncService Integration

**File:** `src/main/java/com/amazonaws/policyconverters/sync/SyncService.java`

`S3AccessGrantsClient` is passed into `SyncService` as a nullable constructor parameter (alongside the existing `LakeFormationClient`). `SyncServiceMain` constructs it from `SyncConfig.s3AccessGrants` if that field is non-null, otherwise passes `null`. When `null`, the S3AG path is a no-op.

**State field added:**
```java
private List<S3AccessGrantOperation> previousS3AgOperations = Collections.emptyList();
```
Seeded from `CheckpointStore` on `start()` (see checkpoint section below).

**`executeSyncCycle()` additions** (after Cedar conversion):

```java
if (s3AccessGrantsClient != null) {
    List<S3AccessGrantOperation> currentS3AgOps = s3AgConverter.convert(cedarPolicySet);
    List<S3AccessGrantOperation> toGrant = computeS3AgDiff(previousS3AgOperations, currentS3AgOps).newGrants();
    List<S3AccessGrantOperation> toRevoke = computeS3AgDiff(previousS3AgOperations, currentS3AgOps).revocations();
    s3AccessGrantsClient.applyBatch(concat(toGrant, toRevoke), config.getMaxLfRetries(), config.getLfRetryBackoffMs());
    previousS3AgOperations = currentS3AgOps;
    checkpointStore.saveS3AgOperations(lastPolicyVersion, currentS3AgOps);
}
```

**`computeS3AgDiff` (new static method):**

Compares previous and current `S3AccessGrantOperation` lists by logical key `(principalArn, s3Prefix, permission)` (ignoring `grantId` and `type`). Produces:
- `newGrants`: GRANT ops in current but not in previous
- `revocations`: REVOKE ops in previous but not in current

**Checkpoint extension:**

`SyncCheckpoint` gains a new field:
```java
@JsonProperty("s3AgOperations")
List<S3AccessGrantOperation> s3AgOperations = Collections.emptyList();
```

`CheckpointStore` gains a new overload parallel to `saveTagState`:
```java
public void saveS3AgOperations(long policyVersion, List<S3AccessGrantOperation> ops) {
    // read current checkpoint, replace s3AgOperations field, write back
}
```
The call in `executeSyncCycle()` becomes `checkpointStore.saveS3AgOperations(lastPolicyVersion, currentS3AgOps)`. On `SyncService.start()`, `previousS3AgOperations` is restored from `checkpoint.getS3AgOperations()`. The `grantId` field in persisted operations is null (logical state only — grant IDs are looked up live at revoke time).

---

### 8. Configuration

**New sub-config on `SyncConfig`:**

```yaml
s3AccessGrants:
  instanceArn: "arn:aws:s3:us-east-1:123456789012:access-grants/default"
  accountId: "123456789012"
```

```java
// src/main/java/com/amazonaws/policyconverters/config/S3AccessGrantsConfig.java
public record S3AccessGrantsConfig(
    String instanceArn,
    String accountId
) {}
```

`SyncConfig` gains a nullable `@JsonProperty("s3AccessGrants") S3AccessGrantsConfig s3AccessGrants` field. Existing constructors are unchanged; the new field defaults to `null`.

**Ranger service config** (existing `rangerServices` list — no schema changes):

```yaml
rangerServices:
  - serviceType: "amazon-emr-emrfs"
    serviceName: "emrfs_prod"
```

---

### 9. Assessment Changes

**`AssessmentConfig`** gains a nullable `S3AccessGrantsConfig s3AccessGrants` field (same pattern as `SyncConfig`).

**`AssessmentRunner.run()`** additions:

After `CedarToLFConverter` runs, also instantiate `CedarToS3AccessGrantsConverter` and convert the same `CedarPolicySet`. For each resulting `S3AccessGrantOperation`:

- If `assessmentConfig.s3AccessGrants` is non-null: construct `S3AccessGrantsClient`, call `listRegisteredLocations()`, check coverage. Record `UNREGISTERED_S3_LOCATION` gap for any operation whose `s3Prefix` is not covered by a registered location.
- If `assessmentConfig.s3AccessGrants` is null but EMRFS service configs are present: record `CANNOT_VALIDATE_S3_LOCATION` gap.

These gaps flow into `GapReporter` and surface in `AssessmentResult.gapReport` as normal gaps. They cause affected policies to be classified as "partially convertible."

**New gap types** added to `GapEntry.GapType`:
- `UNREGISTERED_S3_LOCATION`
- `CANNOT_VALIDATE_S3_LOCATION`

**New entries in `AssessmentReporter.GAP_EXPLANATIONS`:**
- `UNREGISTERED_S3_LOCATION` → `"S3 Access Grants location not registered for this prefix. Register the location in your S3 Access Grants instance before migrating (s3control:CreateAccessGrantsLocation)."`
- `CANNOT_VALIDATE_S3_LOCATION` → `"No S3 Access Grants configuration provided. Cannot validate whether S3 locations are registered. Add s3AccessGrants config to enable validation."`

---

## New Files Summary

| File | Purpose |
|---|---|
| `conf/ranger-servicedef-amazon-emr-emrfs.json` | EMRFS service definition (already present) |
| `src/.../ranger/EmrfsServiceAdapter.java` | Ranger → Cedar adapter for EMRFS |
| `src/.../ranger/service/EmrfsRangerService.java` | `BaseRangerService` subclass for EMRFS |
| `src/.../cedar/CedarToS3AccessGrantsConverter.java` | Cedar → S3 Access Grants converter |
| `src/.../s3accessgrants/S3AccessGrantOperation.java` | Output operation model (with nullable `grantId`) |
| `src/.../s3accessgrants/S3AccessGrantPermission.java` | Permission enum |
| `src/.../s3accessgrants/S3AccessGrantsClient.java` | AWS S3 Control API wrapper |
| `src/.../config/S3AccessGrantsConfig.java` | Config sub-record |

## Modified Files Summary

| File | Change |
|---|---|
| `src/main/resources/cedar/datacatalog.cedarschema` | Add `S3` namespace with `S3::Object` entity and four S3 actions |
| `src/.../cedar/CedarToLFConverter.java` | Silently skip `s3:*` actions without recording `UNSUPPORTED_ACTION` gap |
| `src/.../sync/SyncService.java` | Add nullable `S3AccessGrantsClient` constructor param, S3AG apply loop, `computeS3AgDiff`, `previousS3AgOperations` state |
| `src/.../sync/SyncCheckpoint.java` | Add `s3AgOperations` field |
| `src/.../sync/CheckpointStore.java` | Persist/restore `s3AgOperations` |
| `src/.../assessment/AssessmentRunner.java` | Instantiate `CedarToS3AccessGrantsConverter`, add location validation, new gap types |
| `src/.../assessment/AssessmentReporter.java` | Add gap explanations for two new gap types |
| `src/.../assessment/AssessmentConfig.java` | Add nullable `S3AccessGrantsConfig s3AccessGrants` field |
| `src/.../model/GapEntry.java` | Add `UNREGISTERED_S3_LOCATION`, `CANNOT_VALIDATE_S3_LOCATION` to `GapType` enum |
| `src/.../config/SyncConfig.java` | Add nullable `S3AccessGrantsConfig s3AccessGrants` field |
| `src/.../app/SyncServiceMain.java` | Register `EmrfsRangerService`; construct and pass `S3AccessGrantsClient` |

---

## Verification

1. **Unit tests — `EmrfsServiceAdapterTest`:** One test per access type mapping; one test per resource construction case (plain path, recursive, wildcard). Verify `CedarEntityRef` entity type is `S3::Object`.
2. **Unit tests — `CedarToS3AccessGrantsConverterTest`:** One test per permission aggregation case covering all rows in the aggregation table above. Verify LF-action Cedar statements are ignored. Verify `grantId` is null in converter output.
3. **Unit tests — `S3AccessGrantsClientTest`:** Mock `S3ControlClient`. Verify: (a) GRANT with registered location → `CreateAccessGrant` called; (b) GRANT with unregistered location → `CreateAccessGrant` NOT called, warn logged, dead-letter written; (c) REVOKE → `DeleteAccessGrant` called with correct `grantId` resolved from `listGrants()`.
4. **Unit tests — `CedarToLFConverterTest`:** Add a test verifying that Cedar statements with `s3:GetObject` action produce no `UNSUPPORTED_ACTION` gap entry.
5. **Unit tests — `AssessmentRunnerTest`:** Verify `UNREGISTERED_S3_LOCATION` gap is recorded when mocked `listRegisteredLocations()` does not cover the EMRFS policy prefix. Verify `CANNOT_VALIDATE_S3_LOCATION` gap when `s3AccessGrants` config is absent.
6. **Integration test:** Add EMRFS policies to the Ranger admin fixture; verify `s3control:CreateAccessGrant` is called with correct `principalArn`, `s3Prefix`, and `permission`; verify dead-letter entry when location is not registered.
7. **End-to-end config test:** Verify no NPE on startup when `s3AccessGrants` block is absent from config. Verify existing LF-only sync cycle is unaffected.
