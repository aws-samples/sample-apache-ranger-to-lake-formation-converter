# Tag Metadata Sync — Requirements

## Background

Ranger tag definitions and their resource-tag mappings are currently unhandled by the pipeline. This spec covers the first phase of tag support: synchronizing Ranger tag metadata (tag definitions + which Glue resources carry which tags) into AWS Lake Formation's LF-Tag system.

This is explicitly **not** tag policy sync (that's Phase 2). The goal here is establishing the LF-Tag infrastructure that makes TBAC possible, and producing the `tag → resource` mapping that Phase 2's named-resource fallback path depends on.

## Scope

**In scope:**
- Pulling Ranger tag definitions and resource-tag mappings via `RangerAdminRESTClient.getServiceTagsIfUpdated()`
- Creating/deleting LF-Tag keys in Lake Formation to mirror Ranger tag definitions
- Attaching/detaching LF-Tags on Glue resources to mirror Ranger resource-tag mappings
- Incremental sync (don't re-fetch or re-apply when nothing has changed)
- Checkpoint persistence of tag version and managed tag names

**Out of scope:**
- Atlas tag hierarchies or external tagging services — Ranger native tags only
- Tag-based policy conversion (Phase 2)
- Tags attached to LF resources outside this pipeline — we don't manage those
- Row-level or column-level tag policies

## Tag Mapping Convention

Ranger tag name → LF-Tag key, with value always `"true"`.

- Ranger tag `PII` → LF-Tag key `PII`, allowed values `["true"]`
- Ranger tag `SENSITIVE` → LF-Tag key `SENSITIVE`, allowed values `["true"]`

This is intentionally simple — no Atlas hierarchy to flatten, no multi-value mapping. Phase 2 Cedar entity IDs use the format `DataCatalog::LFTag::"PII=true"`.

---

## Requirements

### 1. Configuration

The tag sync must be opt-in. No existing deployments should be affected when upgrading.

- Add a `tagSync` section to `SyncConfig`:
  ```yaml
  tagSync:
    enabled: false              # must explicitly enable
    tagServiceName: "tagservice" # Ranger tag service instance name
    tagSyncIntervalMs: 0         # 0 = same cadence as policyRefreshIntervalMs
  ```
- `tagServiceName` is required when `enabled: true`. Missing or blank → startup validation error.
- `tagSyncIntervalMs < 0` → startup validation error.
- When disabled, no `RangerTagService` or `TagMetadataSyncer` is constructed at all.

### 2. Ranger Tag Retrieval

Use `RangerAdminRESTClient` (already in `ranger-plugins-common-2.4.0.jar`) rather than `RangerBasePlugin`. Rationale: `RangerBasePlugin` gives tag *policies* but not the tag-to-resource mapping. `getServiceTagsIfUpdated()` gives both in one call; our sync is already poll-based so the push model adds nothing, and `PolicyRefresher`'s backoff behavior is a known source of integration test instability.

- Call `RangerAdminRESTClient.getServiceTagsIfUpdated(lastKnownTagVersion, lastActivationTime)` each tag sync cycle.
- First call after startup (or after restoring a checkpoint with no tag version): use `lastKnownVersion = 0` for a full download.
- If the response has `isDelta = true`, merge the delta into in-memory state rather than replacing it.
- If the response has `op = "REPLACE"`, replace the entire in-memory state.
- If the call throws, log the error and **return the last known good state unchanged**. Do not proceed with reconciliation on a failed fetch — this prevents false "tag deleted" signals.
- After a successful retrieval, update `lastKnownTagVersion` to `ServiceTags.tagVersion`.
- Expose `Map<String, Set<LFResource>> getResourcesForTag()` for Phase 2's named-resource fallback.

### 3. Tag Definition Reconciliation

Keep LF-Tag keys in sync with Ranger tag definitions.

- **Create**: Any Ranger tag name not present in LF → call `CreateLFTag(key=name, values=["true"])`.
  - If LF returns `AlreadyExistsException` → treat as success (tag exists externally, that's fine).
- **Delete**: Any LF-Tag key that (a) we previously created (tracked in `lastKnownRangerTagNames`) AND (b) is no longer in the Ranger tag definitions → call `DeleteLFTag`.
  - If the tag still has resource attachments in LF → skip deletion, log INFO. Don't break anyone else's TBAC policies.
  - If LF returns `EntityNotFoundException` → treat as success (already gone).
  - **Never delete LF-Tags whose keys are not in `lastKnownRangerTagNames`.** We don't touch what we didn't create.
- **Rename**: If an old name disappears and a new name appears in the same cycle → `DeleteLFTag(old)` + `CreateLFTag(new)` + re-attach resources. Log the rename at INFO.

### 4. Resource-Tag Attachment Reconciliation

Keep LF resource tag attachments in sync with Ranger `serviceResources` + `resourceToTagIds`.

- Build the desired attachment map from `ServiceTags`: `Map<LFResource, Set<tagName>>`
- Read the actual attachment map from LF: call `GetResourceLFTags` per resource.
  - **If `GetResourceLFTags` fails → abort the entire reconciliation for this cycle and return a failure result.** Do not apply partial changes based on incomplete actual state.
- **Attach**: `(resource, tagName)` pairs in desired but not in actual → `AddLFTagsToResource`.
- **Detach**: `(resource, tagName)` pairs in actual but not in desired, **and** the tag name is in `lastKnownRangerTagNames` → `RemoveLFTagsFromResource`.
  - Never remove attachments for tag keys we didn't create.
- Individual attach/detach failures → log ERROR, record in `TagSyncResult.failed`, continue with remaining operations. Don't abort the whole cycle for one bad attachment.
- Map `RangerServiceResource.resourceElements` to `LFResource` (database/table/column) using the same key names as `RangerPolicy.resources`. Reuse the existing `LakeFormationClient.buildResource(LFResource)` method for SDK resource construction.

### 5. Incremental Sync and Checkpoint Persistence

Avoid redundant API calls across restarts and between cycles.

- Persist `lastKnownTagVersion` and `lastKnownRangerTagNames` in the existing `SyncCheckpoint` JSON file alongside the current policy fields.
- Backward compatibility: checkpoint files without these fields deserialize cleanly (treat as `null` / empty set). When `lastKnownTagVersion` is null, do a full download on next startup.
- `lastKnownRangerTagNames` is the mechanism that enforces "don't touch what we didn't create" across restarts — it must be kept accurate.
- When `tagSyncIntervalMs` is configured independently, track the last tag sync timestamp and skip tag sync on cycles where the interval hasn't elapsed.

### 6. Observability

- Log at INFO when a tag sync cycle starts: tag service name, number of tag definitions, number of resource-tag mappings in current Ranger state.
- Log at INFO when a cycle completes: duration, `tagsCreated`, `tagsDeleted`, `attachmentsAdded`, `attachmentsRemoved`, `failed`.
- Log at WARN when `failed > 0`, listing the first 10 failed operations with resource and tag key.
- Emit CloudWatch metrics after each cycle (all with `ServiceName=conversion-server` dimension):
  - `TagSyncSuccess` / `TagSyncFailure` (count=1)
  - `TagSyncDuration` (ms)
  - `TagsCreated`, `TagsDeleted`, `TagAttachmentsAdded`, `TagAttachmentsRemoved` (counts)
  - `TagSyncPartialFailure` (count=1) when the cycle succeeded overall but had individual operation failures

### 7. Integration with the Sync Cycle

- Run tag metadata sync as part of `executeSyncCycle()`, after the normal policy sync completes.
- A tag sync failure must not affect the policy sync result for the same cycle.
- If `getLatestTags()` returns null (no prior good state, failed on first call), skip the sync cycle and log an error — do not proceed with reconciliation.

---

## Open Questions

- [ ] What is the Ranger tag service instance name in your test/production environment? (Currently the skip gate in `RangerToCedarConverter` uses `serviceType.contains("tag")` — we need to know the actual string.)
- [ ] Should `tagSyncIntervalMs` default to `policyRefreshIntervalMs` when absent, or should it run every cycle regardless?
- [ ] For sustained tag sync failures (e.g., Ranger tag service unreachable for hours): should we emit a separate `TagSyncConsecutiveFailures` metric to make CloudWatch alarming easier, or is the `TagSyncFailure` count sufficient?
