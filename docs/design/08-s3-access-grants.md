# S3 Access Grants

## `s3accessgrants/S3AccessGrantsClient.java`

**Purpose:** Wrapper around S3 Control SDK for managing S3 Access Grants lifecycle (create, delete, list) with retry/backoff and dead-letter logging.

### Key fields

| Field | Type | Notes |
|---|---|---|
| `s3control` | `S3ControlClient` | AWS SDK client |
| `accountId` | `String` | from `S3AccessGrantsConfig` |
| `instanceArn` | `String` | from `S3AccessGrantsConfig` |
| `deadLetterLogger` | `DeadLetterLogger` | optional |
| `cachedLocations` | `Set<String>` | location scope cache; cleared at start of each `applyBatch` |

### Key methods

| Method | Notes |
|---|---|
| `listRegisteredLocations() → Set<String>` | Paginates `listAccessGrantsLocations`; results cached in `cachedLocations` |
| `createGrant(op) → String grantId` | Validates `op.s3Prefix()` is covered by a registered location (longest-prefix match); computes sub-prefix; calls `CreateAccessGrant`. Returns null if prefix uncovered → dead-letter |
| `deleteGrant(grantId)` | Calls `DeleteAccessGrant` |
| `listGrants() → List<S3AccessGrantOperation>` | Paginates `listAccessGrants`; uses `grantScope` as `s3Prefix` (falls back to `s3SubPrefix` if null) |
| `applyBatch(ops, maxRetries, retryBackoffMs)` | Clears cache; separate grants/revokes; builds `GrantKey→grantId` lookup from `listGrants()`; processes grants then revokes; exponential backoff |

### Permission mapping

| `S3AccessGrantPermission` | AWS SDK `Permission` |
|---|---|
| `READ` | `Permission.READ` |
| `WRITE` | `Permission.WRITE` |
| `READWRITE` | `Permission.READWRITE` |

All grantees are IAM type only.

---

## `s3accessgrants/S3AccessGrantOperation.java`

Immutable record.

| Field | Type | Notes |
|---|---|---|
| `type` | `OperationType` | `GRANT` or `REVOKE` |
| `principalArn` | `String` | IAM ARN |
| `s3Prefix` | `String` | e.g. `s3://bucket/prefix/` |
| `permission` | `S3AccessGrantPermission` | `READ`, `WRITE`, or `READWRITE` |
| `grantId` | `String` | null for desired state; populated from `listGrants()` |
| `sourcePolicyId` | `String` | format `"serviceType:policyId"`; null for live state |

---

## `s3accessgrants/S3AccessGrantPermission.java`

Enum: `READ`, `WRITE`, `READWRITE`.

---

## `s3accessgrants/OperationType.java`

Enum: `GRANT`, `REVOKE`.

---

## `cedar/CedarToS3AccessGrantsConverter.java`

**Purpose:** Converts a Cedar `CedarPolicySet` into `S3AccessGrantOperation` objects for the EMRFS path.

### Recognized S3 actions

- Read: `s3:GetObject`, `s3:ListObjects`
- Write: `s3:PutObject`, `s3:DeleteObject`

### Conversion flow

1. Split Cedar text into statements (brace-depth aware)
2. Parse: effect, principal, action, resource type + id
3. Filter to `S3_ALL_ACTIONS` and `S3::Object` entity type only; `DataCatalog::` statements silently ignored
4. Convert `arn:aws:s3:::bucket/path` → `s3://bucket/path`
5. Group by `(principal, s3Prefix)` key; `permit` adds actions, `forbid` removes them (forbid always wins — no deny-exception for S3AG)
6. Aggregate effective actions: read-only → `READ`, write-only → `WRITE`, mixed → `READWRITE`, empty → no grant
7. Extracts `@source("...")` annotation as `sourcePolicyId`

### Diff base

`SyncService` maintains `previousS3AgOperations` (parallel to `previousOperations` for LF). Diff logic in `SyncService.computeS3AgDiff()`.
