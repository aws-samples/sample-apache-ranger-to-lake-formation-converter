# Models and Reporting

## `reporting/GapReporter.java`

**Purpose:** Stateful accumulator of `GapEntry` instances encountered during Ranger-to-Cedar conversion.

**Methods:** `recordGap(GapEntry)`, `getReport() → GapReport`, `toJson() → String`, `static fromJson(String) → GapReport`.

Inter-component: Constructed in entry points; passed to `RangerToCedarConverter` and `CedarToLFConverter`; read by `SyncService.logGapReportIfPresent()`.

---

## `model/GapEntry.java`

Immutable record of a single unsupported Ranger feature.

**Fields:** `policyId`, `policyName`, `gapType (GapEntry.GapType)`, `resourcePath`, `details`, `recommendation`, `serviceType`, `accessTypes (List<String>)`.

`serviceType` and `accessTypes` are populated only for the two action-related gap types
(`UNSUPPORTED_ACTION`, `KNOWN_UNMAPPABLE_ACTION`); null/absent (`@JsonInclude(NON_NULL)`) for the
other fifteen. `accessTypes` replaces a former single `accessType` string — one entry is now
built per `(policy, gapType)`, conflating every affected access type for that policy into one
sorted list, rather than one entry per access type.

### `GapType` enum (17 values)

`DATA_MASKING`, `TAG_BASED_POLICY`, `DENY_POLICY`, `DENY_EXCEPTION`, `VALIDITY_SCHEDULE`, `CUSTOM_CONDITION`, `SECURITY_ZONE`, `DELEGATED_ADMIN`, `WILDCARD_PATTERN`, `UNSUPPORTED_SERVICE_TYPE`, `UNSUPPORTED_ACTION`, `KNOWN_UNMAPPABLE_ACTION`, `UNMAPPED_RESOURCE`, `SCHEMA_VALIDATION_FAILURE`, `UNREGISTERED_S3_LOCATION`, `CANNOT_VALIDATE_S3_LOCATION`, `EXCLUDES_PATTERN`.

`KNOWN_UNMAPPABLE_ACTION` is informational — never demotes a policy's convertibility
classification. `UNSUPPORTED_ACTION` is now restricted to access types no adapter recognises at
all (an adapter coverage gap); access types an adapter recognises but deliberately never maps
(Lake Formation has no equivalent) go to `KNOWN_UNMAPPABLE_ACTION` instead, via
`SourcePolicyAdapter.getKnownUnmappableAccessTypes()` / `isKnownUnmappableAccessType(String)`.

---

## `assessment/KnownUnmappableActionSummary.java`

Per-`(serviceType, accessType)` rollup of `KNOWN_UNMAPPABLE_ACTION` occurrences, built by
`AssessmentRunner.buildKnownUnmappableRollup()` and exposed via
`AssessmentResult.getKnownUnmappableActions()` (`@JsonInclude(NON_EMPTY)`).

**Fields:** `serviceType (String)`, `accessType (String)`, `occurrenceCount (int)` — number of
*policies* that used this access type, not a raw entry count — `samplePolicyIds (List<String>)`.

`samplePolicyIds` is capped at `SAMPLE_LIMIT` (10); `occurrenceCount` stays exact regardless of
the cap. Sorted by descending `occurrenceCount` so the console/report leads with the worst
offender.

`AssessmentRunner.collapseKnownUnmappableEntries()` uses this rollup to replace every per-policy
`KNOWN_UNMAPPABLE_ACTION` entry in the assessment JSON's `gapReport.entries` with one collapsed
entry per row (`policyId: null`), to keep the file bounded on large estates. Because
`GapReport.computeSummary` counts entries, `gapReport.summary`'s `KNOWN_UNMAPPABLE_ACTION` count
after collapsing is the number of distinct `(serviceType, accessType)` pairs, not the occurrence
count — that lives in `KnownUnmappableActionSummary.occurrenceCount`.

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
