# Models and Reporting

## `reporting/GapReporter.java`

**Purpose:** Stateful accumulator of `GapEntry` instances encountered during Ranger-to-Cedar conversion.

**Methods:** `recordGap(GapEntry)`, `getReport() → GapReport`, `toJson() → String`, `static fromJson(String) → GapReport`.

Inter-component: Constructed in entry points; passed to `RangerToCedarConverter` and `CedarToLFConverter`; read by `SyncService.logGapReportIfPresent()`.

---

## `model/GapEntry.java`

Immutable record of a single unsupported Ranger feature.

**Fields:** `policyId`, `policyName`, `gapType (GapEntry.GapType)`, `resourcePath`, `details`, `recommendation`.

### `GapType` enum (16 values)

`DATA_MASKING`, `TAG_BASED_POLICY`, `DENY_POLICY`, `DENY_EXCEPTION`, `VALIDITY_SCHEDULE`, `CUSTOM_CONDITION`, `SECURITY_ZONE`, `DELEGATED_ADMIN`, `WILDCARD_PATTERN`, `UNSUPPORTED_SERVICE_TYPE`, `UNSUPPORTED_ACTION`, `UNMAPPED_RESOURCE`, `SCHEMA_VALIDATION_FAILURE`, `UNREGISTERED_S3_LOCATION`, `CANNOT_VALIDATE_S3_LOCATION`, `EXCLUDES_PATTERN`.

---

## `model/GapReport.java`

Aggregated, immutable snapshot of all gap entries.

**Fields:** `entries (List<GapEntry>)` (unmodifiable), `summary (Map<GapType, Integer>)` (unmodifiable `EnumMap`), `generatedAt (String)` (ISO-8601 UTC).

**Static factory:** `computeSummary(List<GapEntry>) → Map<GapType, Integer>` — counts per type using `EnumMap`.

---

## `model/DriftReport.java`

Structured summary of detected differences from `DriftDetector.computeDrift()`.

**Fields:**

| Field | Type | Notes |
|---|---|---|
| `missingGrants` | `int` | In desired but not in actual |
| `extraPermissions` | `int` | In actual but not in desired |
| `inSyncCount` | `int` | In both |
| `skippedPermissions` | `List<LFPermissionOperation>` | Excluded by `PermissionFilter` |
| `failedOperations` | `List<FailedOperation>` | Corrective ops that failed during apply |

`getTotalDrift()` = `missingGrants + extraPermissions`.

**Inner class `FailedOperation`:** `(LFPermissionOperation operation, String error)`.

---

## `model/DriftResult.java`

Transport object from `DriftDetector.computeDrift()`.

**Fields:** `report (DriftReport)`, `correctiveOperations (List<LFPermissionOperation>)` (unmodifiable; empty in `reportOnly` mode).

---

## `model/ReverseSyncResult.java`

Summary of one reverse-sync execution.

| Field | Type |
|---|---|
| `driftReport` | `DriftReport` |
| `successfulGrants` | `int` |
| `successfulRevokes` | `int` |
| `failedOperations` | `int` |
| `durationMs` | `long` |

---

## `lakeformation/BatchResult.java`

Result of `LakeFormationClient.applyBatch()`.

| Field | Type | Notes |
|---|---|---|
| `succeededPolicyIds` | `List<String>` | Unmodifiable |
| `failedPolicyIds` | `List<String>` | Unmodifiable; used by `SyncService` to exclude from snapshot on partial failure |
| `totalOperations` | `int` | |
| `appliedOperations` | `int` | |
| `rolledBackOperations` | `int` | |

`hasFailures() → boolean`.

---

## `sync/SyncCheckpoint.java`

(See [02-sync-pipeline.md](02-sync-pipeline.md) for full field reference.)

---

## `model/SyncCycleResult.java`

Immutable outcome of one sync cycle. Factory methods:
- `success(long durationMs, int policiesProcessed, int grantsApplied, int revocationsApplied, int policiesSkipped)`
- `failure(long durationMs, Throwable error)` — records `errorClass` and `errorMessage`

Consumed by `ServerLifecycle` for metrics emission.

---

## `model/WildcardRefreshResult.java`

Outcome of one wildcard re-expansion cycle.

**Fields:** `boolean success`, `long durationMs`, `int policiesEvaluated`, `int newGrants`, `int revocations`, `int unchanged`, `Exception error`.

**Factory methods:** `success(durationMs, policiesEvaluated, newGrants, revocations, unchanged)`, `failure(durationMs, error)`.

---

## `model/TagSyncResult.java`

Outcome of a tag sync cycle.

**Fields:** `success`, `durationMs`, `tagsCreated`, `tagsDeleted`, `attachmentsAdded`, `attachmentsRemoved`, `failed`, `errorMessage` (nullable).

**Factory methods:** `success(...)`, `failure(durationMs, error)`.

---

## Dry-run output models

### `model/DryRunOutput.java`

JSON envelope for dry-run files.

**Fields:** `timestamp (String)`, `sequenceNumber (int)`, `operations (List<LFPermissionOperation>)`.

### `model/ReverseSyncDryRunOutput.java`

Extends `DryRunOutput`. Adds `driftSummary (DriftReport)`.

### `model/TagSyncOutput.java`

JSON envelope for dry-run tag sync output.

**Fields:** `timestamp`, `sequenceNumber`, `operationType (String)`, `tagKey (String)`, `tagValues (List<String>)`, `resource (Map<String,String>)`.
