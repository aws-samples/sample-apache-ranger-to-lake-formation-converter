# Sync Pipeline

## `sync/SyncService.java`

**Purpose:** Core orchestrator. Converts Ranger policies to LF permissions through the two-stage Cedar pipeline and applies a diff-only batch of grant/revoke operations. Supports both single-plugin (push) and multi-service (pull) operation.

### Key fields

| Field | Type | Notes |
|---|---|---|
| `plugin` | `RangerPlugin` | Null in multi-service mode |
| `rangerServices` | `List<BaseRangerService>` | Empty in single-plugin mode |
| `previousOperations` | `volatile List<LFPermissionOperation>` | Diff base; empty on first startup; restored from checkpoint on restart |
| `lastCedarPolicyText` | `volatile String` | Source of truth for checkpoint persistence and `ReverseSyncService` |
| `lastCompletedCycle` | `AtomicLong` | Monotonic counter; feeds `StatusEndpoint` and `CycleWaiter` |
| `checkpointStore` | `CheckpointStore` | Nullable |
| `s3AccessGrantsClient` | `S3AccessGrantsClient` | Nullable |
| `initializedServices` | `ConcurrentHashMap.newKeySet()` | First-sync gate: defer diff-apply until all services have fetched at least once |
| `serviceVersions` | `ConcurrentHashMap<String,Long>` | Per-service version map for checkpoint |

### Key public methods

| Method | Entry point | Notes |
|---|---|---|
| `start(SyncConfig)` | Both modes | Restores checkpoint; starts daemon threads; sets `running=true` |
| `stop()` | Both modes | Clears listener; `running=false` |
| `onPoliciesUpdated(ServicePolicies)` | Single-plugin | Full convert-diff-apply cycle |
| `executeSyncCycle()` | Multi-service | Fetches all services, merges, convert-diff-apply; handles first-sync gate |
| `executeWildcardRefresh()` | Scheduled | Re-expands glob policies against live Glue; produces diff + apply for changed expansions |
| `executeTagMetadataSync()` | Scheduled | Conditional tag sync run; respects interval from config |
| `getLastCedarPolicySet()` | `ConversionServerMain` | Reconstructs `CedarPolicySet` from `lastCedarPolicyText`; consumed by `ReverseSyncService` |
| `setTagSync(RangerTagService, TagMetadataSyncer)` | Wiring | Must be called before `start()` |
| `setLFReconciler(LFReconciler)` | Wiring (Bug #1 fix) | Must be called before `start()`; nil = disabled |

### `computeDiff` logic

Builds a `PermissionKey` map for both `previous` and `current` operation lists. New grants = in current but not previous. Revocations = in previous but not current. Unchanged = in both. See [DESIGN.md](DESIGN.md) for the `PermissionKey` identity rule.

### Checkpoint restore path (startup)

`start()` calls `checkpointStore.load()`. If present and non-empty Cedar text found:
- `previousOperations = cedarToLFConverter.convert(CedarPolicySet.fromCedarString(cedarText))`
- `lastCedarPolicyText = cedarText`
- Per-service versions restored from `checkpoint.getServiceVersions()`

On restore failure: logs warning, starts from empty state (may trigger mass re-grant on first cycle, which is correct).

### Bug #1 gap

`SyncService` has no startup reconciliation against actual LF state. Permissions from prior sessions whose Ranger policies were later deleted are never revoked. The fix (`LFReconciler`) will be called from `start()` as a background daemon thread. See [02-sync-pipeline.md](02-sync-pipeline.md) and the Bug #1 memory note.

---

## `sync/ReverseSyncService.java`

**Purpose:** Fetches actual LF permissions, computes drift against Cedar-authoritative desired state, applies corrective GRANT/REVOKE operations. `AtomicBoolean` CAS guard prevents concurrent executions.

### Key fields

| Field | Type | Notes |
|---|---|---|
| `fetcher` | `LFPermissionFetcher` | Fetches actual LF state |
| `driftDetector` | `DriftDetector` | Computes desired vs actual diff |
| `cedarToLFConverter` | `CedarToLFConverter` | Derives desired state from Cedar |
| `lakeFormationClient` | `LakeFormationClient` | Applies corrective ops |
| `deadLetterLogger` | `DeadLetterLogger` | Logs failed corrective ops |
| `running` | `AtomicBoolean` | CAS concurrency guard; throws `IllegalStateException` if already running |

### `execute(ReverseSyncConfig config, CedarPolicySet cedarPolicySet)` flow

1. CAS `running` false → true; throws if already running
2. Empty Cedar set guard — skips to prevent mass revocation on startup
3. `cedarToLFConverter.convert(cedarPolicySet)` → desired ops
4. `fetcher.fetchPermissions(catalogId, filter)` → actual ops
5. `driftDetector.computeDrift(desired, actual, filter, reportOnly)` → `DriftResult`
6. **reportOnly=true**: return result with zero corrective counts, no writes
7. **dryRun=true**: serialize ops via `DryRunLakeFormationClient`
8. Normal: `orderCorrectiveOperations()` (REVOKEs first) → apply one-by-one; failures dead-lettered, do not abort batch
9. CAS `running` back to false in `finally`

### Current wiring gap

`ReverseSyncService` is wired in `ConversionServerMain` (after every forward sync cycle) but **not** in `SyncServiceMain`. Bug #1 fix adds wiring to `SyncServiceMain`.

### Reuse opportunity

`ReverseSyncService.execute()` is the canonical reconciliation implementation. The Bug #1 fix should **call this method** rather than reimplementing the drift logic. The key constraint is calling it **after at least one forward sync cycle** (so Cedar policy set is non-empty) and handling the slow `LFPermissionFetcher.fetchPermissions()` call in a background thread.

---

## `sync/DriftDetector.java`

**Purpose:** Stateless utility that diffs desired vs. actual `LFPermissionOperation` lists using `PermissionKey` maps. Produces corrective operations and a `DriftReport`.

### `computeDrift(desired, actual, filter, reportOnly) → DriftResult`

1. Partition both lists through `filter.shouldExclude()` → filtered and skipped sub-lists
2. Build `Map<PermissionKey, LFPermissionOperation>` for each filtered list
3. Missing grants = desiredMap keys not in actualMap → corrective GRANT ops (if `!reportOnly`)
4. Extra permissions = actualMap keys not in desiredMap → corrective REVOKE ops (if `!reportOnly`)
5. In-sync count = desiredMap keys in actualMap

**`PermissionKey`** (inner class): `(principalArn, resource, permissions Set<Object>, grantable)`. Op type and source policy ID excluded.

---

## `sync/CheckpointStore.java`

**Purpose:** Atomic (write-via-rename) persistence of sync state to JSON. Prevents corrupt checkpoint on crash.

### Key methods

| Method | Notes |
|---|---|
| `save(long policyVersion, String cedarText)` | Single-service save |
| `save(Map<String,Long> serviceVersions, String cedarText)` | Multi-service save; combined version = max of map values |
| `saveTagState(long tagVersion, Set<String> managedTagNames)` | Merges tag state into existing checkpoint |
| `saveS3AgOperations(long policyVersion, List<S3AccessGrantOperation>)` | Merges S3 AG ops into existing checkpoint |
| `load() → Optional<SyncCheckpoint>` | Returns empty if file absent or unparseable |

**Write pattern:** writes to `checkpointPath + ".tmp"`, then `Files.move()` with `ATOMIC_MOVE`.

---

## `sync/DeadLetterLogger.java`

**Purpose:** Append-only JSON Lines file for failed LF permission operations. All methods `synchronized`.

### JSON structure per entry
```json
{"timestamp":"...", "policyId":"...", "operation":"GRANT|REVOKE",
 "resource":{"database":"...","table":"..."},
 "principal":"arn:...", "permissions":["SELECT",...],
 "error":"...", "retryCount":0}
```

**Methods:** `logFailedOperation(op, errorMessage, retryCount)`, `logEntry(rawJsonLine)`.

---

## `sync/SyncCheckpoint.java`

**Purpose:** JSON-serializable snapshot of all persistent sync state.

| JSON key | Type | Notes |
|---|---|---|
| `policyVersion` | `long` | Combined version (max of all services) |
| `serviceVersions` | `Map<String,Long>` | Per-service; defaults to `{"lakeformation": policyVersion}` for legacy checkpoints |
| `timestamp` | `String` | ISO-8601 |
| `cedarPolicyText` | `String` | Full Cedar text; source of truth for restart |
| `lastKnownTagVersion` | `Long` | Nullable |
| `lastKnownRangerTagNames` | `Set<String>` | Tags managed by this pipeline |
| `s3AgOperations` | `List<S3AccessGrantOperation>` | Last S3 AG snapshot |
