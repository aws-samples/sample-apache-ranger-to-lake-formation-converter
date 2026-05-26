# Implementation Plan: Tag Metadata Sync

## Overview

Implement Ranger tag definition and resource-tag attachment synchronization to AWS Lake Formation using `RangerAdminRESTClient.getServiceTagsIfUpdated()`. The implementation follows a bottom-up approach: data models and config first, then the Ranger retrieval layer, then the LF reconciliation logic, then sync service wiring and checkpoint persistence, then metrics and observability. All components are Java 17; property tests use jqwik (minimum 100 iterations); unit tests use JUnit 5 + Mockito.

## Tasks

- [ ] 1. Create TagSyncConfig, TagSyncResult data model, and SyncConfig extension
  - [ ] 1.1 Create `TagSyncConfig` configuration POJO
    - Create `src/main/java/com/amazonaws/policyconverters/config/TagSyncConfig.java`
    - Fields: `enabled` (boolean, default `false`), `tagServiceName` (String, nullable), `tagSyncIntervalMs` (long, default `0`)
    - `@JsonCreator` constructor with `@JsonProperty` for each field
    - Getters, `equals()`, `hashCode()`, `toString()`
    - _Requirements: 1.1_

  - [ ] 1.2 Add `tagSync` field to `SyncConfig`
    - Add `@JsonProperty("tagSync") private final TagSyncConfig tagSync` to `SyncConfig`
    - Default to `new TagSyncConfig(false, null, 0)` when absent from YAML
    - Add getter `getTagSync()`
    - Update `equals()`, `hashCode()`, `toString()`
    - _Requirements: 1.1_

  - [ ] 1.3 Add `tagSync` validation to `ConfigValidator`
    - When `tagSync.enabled=true` and `tagServiceName` is blank → add error `"tagSync.tagServiceName is required when tagSync.enabled is true"`
    - When `tagSync.enabled=true` and `tagSyncIntervalMs < 0` → add error `"tagSync.tagSyncIntervalMs must be >= 0"`
    - _Requirements: 1.3, 1.4_

  - [ ] 1.4 Create `TagSyncResult` data model
    - Create `src/main/java/com/amazonaws/policyconverters/model/TagSyncResult.java`
    - Fields: `success`, `durationMs`, `tagsCreated`, `tagsDeleted`, `attachmentsAdded`, `attachmentsRemoved`, `failed`, `errorMessage`
    - Static factory: `success(long durationMs, int tagsCreated, int tagsDeleted, int attachmentsAdded, int attachmentsRemoved, int failed)`
    - Static factory: `failure(long durationMs, Exception error)`
    - Getters, `equals()`, `hashCode()`, `toString()`
    - _Requirements: 6.1, 6.4_

  - [ ] 1.5 Write unit tests for TagSyncConfig and ConfigValidator
    - Create `src/test/java/com/amazonaws/policyconverters/config/TagSyncConfigTest.java`
    - Test: enabled=false requires no tagServiceName; enabled=true + blank name → validation error; negative interval → validation error; zero interval is valid; YAML round-trip with all fields
    - _Requirements: 1.1, 1.3, 1.4_

- [ ] 2. Extend SyncCheckpoint and CheckpointStore for tag version tracking
  - [ ] 2.1 Add `lastKnownTagVersion` and `lastKnownRangerTagNames` to `SyncCheckpoint`
    - Add `@JsonProperty("lastKnownTagVersion") private final Long lastKnownTagVersion` (nullable — null means no tag version persisted)
    - Add `@JsonProperty("lastKnownRangerTagNames") private final Set<String> lastKnownRangerTagNames` (empty set if absent)
    - Update constructor, getters, `equals()`, `hashCode()`, `toString()`
    - Verify backward compatibility: existing checkpoint files without these fields deserialize without error (Jackson treats missing fields as null/empty)
    - _Requirements: 5.2, 5.4, 5.5_

  - [ ] 2.2 Add `saveTagState()` method to `CheckpointStore`
    - `void saveTagState(long tagVersion, Set<String> managedTagNames)` — reads current checkpoint, updates only the tag fields, writes atomically
    - _Requirements: 5.2, 5.5_

  - [ ] 2.3 Write unit tests for checkpoint extension
    - Test: existing checkpoint without tag fields deserializes with `lastKnownTagVersion=null`, `lastKnownRangerTagNames=empty`
    - Test: `saveTagState()` preserves existing non-tag checkpoint fields unchanged
    - Test: checkpoint round-trip with all tag fields populated
    - _Requirements: 5.2, 5.3, 5.4, 5.5_

  - [ ] 2.4 Write property test: Checkpoint round-trip consistency (Property 6)
    - `// Feature: tag-metadata-sync, Property 6: Checkpoint round-trip consistency`
    - Create `src/test/java/com/amazonaws/policyconverters/sync/TagCheckpointRoundTripPropertyTest.java`
    - Generate random `SyncCheckpoint` with random tag version (Long) and random tag name sets using jqwik
    - Verify serialize → deserialize produces equivalent object
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5**

- [ ] 3. Checkpoint — Ensure all tests pass
  - Ensure all tests pass before proceeding.

- [ ] 4. Implement RangerTagService
  - [ ] 4.1 Create `RangerTagService` class
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/service/RangerTagService.java`
    - Constructor: `RangerTagService(String tagServiceName, RangerConnectionConfig rangerConfig)`
    - Initialize `RangerAdminRESTClient` with `init(tagServiceName, "ranger-lf-sync", "", new Configuration())`; configure Ranger Admin URL and credentials in `Configuration` object using Ranger property keys
    - Implement `ServiceTags getLatestTags()`: call `adminClient.getServiceTagsIfUpdated(lastKnownTagVersion, lastActivationTime)`; on null response return `lastKnownTags`; on `isDelta=true` merge delta into `lastKnownTags`; on `op=REPLACE` replace `lastKnownTags`; update `lastKnownTagVersion`; on exception log ERROR and return `lastKnownTags`
    - Implement `long getLastKnownTagVersion()`
    - Implement `Map<String, Set<LFResource>> getResourcesForTag()`: derive from `lastKnownTags` using `serviceResources` + `resourceToTagIds` join table
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [ ] 4.2 Write unit tests for `RangerTagService`
    - Create `src/test/java/com/amazonaws/policyconverters/ranger/service/RangerTagServiceTest.java`
    - Mock `RangerAdminRESTClient` using Mockito
    - Test: first call uses version 0; response updates `lastKnownTagVersion` to returned `tagVersion`
    - Test: delta merge — subsequent delta correctly merges additions without replacing unchanged entries
    - Test: REPLACE op replaces entire in-memory state
    - Test: exception returns last known state unchanged (does not update version)
    - Test: null response (no change) returns `lastKnownTags`
    - Test: `getResourcesForTag()` builds correct tag-name → LFResource mapping from ServiceTags join table
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

- [ ] 5. Extend LakeFormationClient with LF-Tag API methods
  - [ ] 5.1 Add tag definition methods to `LakeFormationClient`
    - `void createLFTag(String catalogId, String tagKey, List<String> tagValues)` — calls `LakeFormationClient.createLFTag()`; catch `AlreadyExistsException` → log INFO; other exceptions → propagate
    - `void deleteLFTag(String catalogId, String tagKey)` — calls `LakeFormationClient.deleteLFTag()`; catch `EntityNotFoundException` → log INFO; other exceptions → propagate
    - `List<String> listLFTagKeys(String catalogId)` — calls `LakeFormationClient.listLFTags()`, paginate all pages, return tag key names
    - _Requirements: 3.1, 3.2, 3.5, 3.6, 3.7_

  - [ ] 5.2 Add resource tag attachment methods to `LakeFormationClient`
    - `Map<String, String> getResourceLFTags(LFResource resource, String catalogId)` — calls `LakeFormationClient.getResourceLFTags()`; returns map of tagKey → tagValue; on failure propagate exception (caller decides abort behavior)
    - `void addLFTagsToResource(LFResource resource, Map<String, String> tags, String catalogId)` — calls `LakeFormationClient.addLFTagsToResource()`; reuses `buildResource(LFResource)` for the `Resource` SDK type; on failure propagate
    - `void removeLFTagsFromResource(LFResource resource, List<String> tagKeys, String catalogId)` — calls `LakeFormationClient.removeLFTagsFromResource()`; reuses `buildResource(LFResource)`; on failure propagate
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.7_

  - [ ] 5.3 Write unit tests for new LakeFormationClient methods
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/LakeFormationClientTagTest.java`
    - Mock AWS SDK `software.amazon.awssdk.services.lakeformation.LakeFormationClient`
    - Test: `createLFTag` calls SDK with correct params; `AlreadyExistsException` does not throw
    - Test: `deleteLFTag` calls SDK with correct params; `EntityNotFoundException` does not throw
    - Test: `listLFTagKeys` paginates and returns all keys
    - Test: `getResourceLFTags` returns correct tag map; exception propagates
    - Test: `addLFTagsToResource` reuses `buildResource()` for database/table/column shapes
    - Test: `removeLFTagsFromResource` reuses `buildResource()` for all shapes
    - _Requirements: 3.5, 3.6, 4.3, 4.4, 4.7_

- [ ] 6. Implement TagMetadataSyncer
  - [ ] 6.1 Create `TagMetadataSyncer` class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/TagMetadataSyncer.java`
    - Constructor: `TagMetadataSyncer(LakeFormationClient lfClient, String catalogId)`
    - Implement `TagSyncResult sync(ServiceTags desired, Set<String> lfManagedTags)` per the algorithm in the design doc:
      1. Build `desiredTagNames` from `tagDefinitions`
      2. Fetch `actualTagNames` via `listLFTagKeys()`
      3. Compute `toCreate` and `toDelete` sets
      4. Apply tag definition creates/deletes (record failures, continue)
      5. Build `desiredAttachments` from `serviceResources` + `resourceToTagIds` join
      6. Build `actualAttachments` via `getResourceLFTags()` per resource — on failure abort entire cycle (return `TagSyncResult.failure()`, no LF calls made)
      7. Apply attachment adds/removes (record failures, continue)
      8. Return `TagSyncResult.success(...)` with counts
    - Before deleting a tag, check if it has resource attachments remaining; if so, skip delete and log INFO
    - _Requirements: 3.1–3.7, 4.1–4.7, 8.2, 8.3, 8.4_

  - [ ] 6.2 Write property test: Reconciliation idempotency (Property 1)
    - `// Feature: tag-metadata-sync, Property 1: Reconciliation idempotency`
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/TagReconciliationIdempotencyPropertyTest.java`
    - Generate random `ServiceTags` as desired state; set LF actual state to match desired exactly
    - Call `sync()` and verify all counts are zero and no LF API calls made
    - **Validates: Requirements 3.1, 3.2, 4.3, 4.4**

  - [ ] 6.3 Write property test: No-touch invariant (Property 2)
    - `// Feature: tag-metadata-sync, Property 2: No-touch invariant for externally managed tags`
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/NoTouchInvariantPropertyTest.java`
    - Generate random external tag keys (not in lfManagedTags); include them in actual LF state
    - Call `sync()` and verify no `DeleteLFTag` or `RemoveLFTagsFromResource` called for external keys
    - **Validates: Requirements 3.4, 4.5**

  - [ ] 6.4 Write property test: Desired superset → creates only (Property 3)
    - `// Feature: tag-metadata-sync, Property 3: Desired superset produces only create/attach operations`
    - Generate actual LF state as a subset of desired Ranger state
    - Call `sync()` and verify `tagsDeleted=0`, `attachmentsRemoved=0`, and `tagsCreated + attachmentsAdded > 0`
    - **Validates: Requirements 3.1, 4.3**

  - [ ] 6.5 Write property test: Actual superset → deletes only (Property 4)
    - `// Feature: tag-metadata-sync, Property 4: Actual superset (of owned tags) produces only delete/detach operations`
    - Generate desired state as subset of actual LF state; all extra actual tags in `lfManagedTags`
    - Call `sync()` and verify `tagsCreated=0`, `attachmentsAdded=0`
    - **Validates: Requirements 3.2, 4.4**

  - [ ] 6.6 Write property test: Tag rename produces delete+create (Property 8)
    - `// Feature: tag-metadata-sync, Property 8: Rename produces DeleteLFTag(old) + CreateLFTag(new)`
    - Generate a pair (oldName in lfManagedTags + actual, newName in desired only)
    - Call `sync()` and verify `DeleteLFTag` called with oldName, `CreateLFTag` called with newName
    - **Validates: Requirements 3.3**

  - [ ] 6.7 Write property test: Failure isolation (Property 7)
    - `// Feature: tag-metadata-sync, Property 7: GetResourceLFTags failure aborts cleanly`
    - Mock `getResourceLFTags` to throw; call `sync()`
    - Verify `TagSyncResult.success=false`; verify zero calls to `CreateLFTag`, `DeleteLFTag`, `AddLFTagsToResource`, `RemoveLFTagsFromResource`
    - **Validates: Requirements 8.2**

  - [ ] 6.8 Write unit tests for TagMetadataSyncer
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/TagMetadataSyncerTest.java`
    - Test: new tag in Ranger, absent in LF → `CreateLFTag` called
    - Test: tag in `lfManagedTags` + actual, absent in desired → `DeleteLFTag` called
    - Test: tag in actual but NOT in `lfManagedTags` → `DeleteLFTag` NOT called
    - Test: new resource-tag pair in desired, absent in actual → `AddLFTagsToResource` called
    - Test: resource-tag pair in actual (owned) and absent in desired → `RemoveLFTagsFromResource` called
    - Test: resource-tag pair in actual (NOT owned) → `RemoveLFTagsFromResource` NOT called
    - Test: `createLFTag` fails with `AlreadyExistsException` → recorded as non-failure, continues
    - Test: `deleteLFTag` fails with `EntityNotFoundException` → recorded as non-failure, continues
    - Test: `addLFTagsToResource` fails → recorded in `failed` count, continues
    - Test: `listLFTagKeys` fails → returns `TagSyncResult.failure()`, zero LF calls made
    - Test: tag with remaining attachments → delete skipped, logged as INFO
    - _Requirements: 3.1–3.7, 4.1–4.7, 8.2, 8.3, 8.4_

- [ ] 7. Checkpoint — Ensure all tests pass
  - Ensure all tests pass before proceeding.

- [ ] 8. Wire TagMetadataSyncer into SyncService and ConversionServerMain
  - [ ] 8.1 Add `executeTagMetadataSync()` to `SyncService`
    - Add fields: `RangerTagService rangerTagService` (nullable), `TagMetadataSyncer tagMetadataSyncer` (nullable), `long lastTagSyncMs = 0`
    - Implement `TagSyncResult executeTagMetadataSync()` per design: check enabled, check interval elapsed, call `rangerTagService.getLatestTags()`, call `tagMetadataSyncer.sync()`, call `checkpointStore.saveTagState()`, update `lastTagSyncMs`
    - Call `executeTagMetadataSync()` from `executeSyncCycle()` after policy sync completes; pass result to `metricsEmitter.recordTagSync()` and `logTagSyncResult()`
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [ ] 8.2 Wire `RangerTagService` and `TagMetadataSyncer` into `ConversionServerMain`
    - When `syncConfig.getTagSync().isEnabled()`: construct `RangerTagService(tagServiceName, rangerConfig)`; construct `TagMetadataSyncer(lfClient, awsConfig.getCatalogId())`; inject into `SyncService`
    - Log configured tag service name and effective sync interval at INFO level
    - When `tagSync.enabled=false`: log DEBUG and skip construction
    - _Requirements: 1.2, 1.5, 7.1_

  - [ ] 8.3 Write unit tests for SyncService tag sync integration
    - Create `src/test/java/com/amazonaws/policyconverters/sync/SyncServiceTagSyncTest.java`
    - Test: `tagSync.enabled=false` → `executeTagMetadataSync()` returns null immediately, no `rangerTagService` called
    - Test: `tagSyncIntervalMs` not yet elapsed → sync skipped this cycle
    - Test: `getLatestTags()` returns null → `TagSyncResult.failure()` returned, checkpoint NOT updated
    - Test: successful sync → `checkpointStore.saveTagState()` called with new version and tag names
    - Test: tag sync failure does NOT affect policy sync result for same cycle
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [ ] 8.4 Write property test: Tag version incremental correctness (Property 5)
    - `// Feature: tag-metadata-sync, Property 5: Tag version incremental correctness`
    - Create `src/test/java/com/amazonaws/policyconverters/ranger/service/TagVersionIncrementalPropertyTest.java`
    - Generate sequence of `ServiceTags` responses with increasing `tagVersion`; feed through `RangerTagService`; verify each response's version becomes the next `lastKnownTagVersion`
    - **Validates: Requirements 2.6, 5.1**

- [ ] 9. Add CloudWatch metrics for tag sync
  - [ ] 9.1 Add `recordTagSync(TagSyncResult)` to `MetricsEmitter`
    - Emit `TagSyncSuccess` (count=1) or `TagSyncFailure` (count=1)
    - Emit `TagSyncDuration` (milliseconds)
    - Emit `TagsCreated`, `TagsDeleted`, `TagAttachmentsAdded`, `TagAttachmentsRemoved` (counts, always emitted)
    - Emit `TagSyncPartialFailure` (count=1) when `success=true` but `failed > 0`
    - All with `ServiceName=conversion-server` dimension
    - _Requirements: 6.4, 6.5_

  - [ ] 9.2 Write unit tests for MetricsEmitter tag sync metrics
    - Extend `src/test/java/com/amazonaws/policyconverters/reporting/MetricsEmitterTest.java`
    - Test: success result emits `TagSyncSuccess`, `TagSyncDuration`, all count metrics
    - Test: failure result emits `TagSyncFailure`, `TagSyncDuration`, does NOT emit `TagSyncSuccess`
    - Test: partial failure (success=true, failed>0) emits both `TagSyncSuccess` and `TagSyncPartialFailure`
    - _Requirements: 6.4, 6.5_

- [ ] 10. Update configuration files and documentation
  - [ ] 10.1 Add `tagSync` section to configuration YAML files
    - Add `tagSync` section to `conf/server-config.yaml` with `enabled: false` and documentation comments
    - Add same to `deploy/server-config-deploy.yaml` and `src/main/deploy/server-config-deploy.yaml`
    - Add same to `integration-test/docker/server-config-it.yaml`
    - _Requirements: 1.1_

  - [ ] 10.2 Update README.md
    - Add `Tag Metadata Sync` section describing the `tagSync` configuration, the tag mapping convention (name → `key=true`), and limitations (no Atlas, no externally managed tag modification, Phase 2 TBAC not yet implemented)
    - Add `TagSyncSuccess`, `TagSyncFailure`, etc. to the CloudWatch metrics table
    - _Requirements: 1.1, 1.2_

- [ ] 11. Final checkpoint — Ensure all tests pass
  - Run `mvn test` and confirm all unit and property tests pass.
  - Verify no existing tests are broken by the new fields in `SyncConfig`, `SyncCheckpoint`, or `LakeFormationClient`.

## Notes

- All new classes are Java 17
- `RangerAdminRESTClient` is already on classpath in `ranger-plugins-common-2.4.0.jar` — no new Maven dependencies
- The `buildResource(LFResource)` method in `LakeFormationClient` is reused by the new tag attachment methods — do not duplicate this logic
- Phase 2 (tag policy sync with hybrid TBAC/named-resource path) depends on `RangerTagService.getResourcesForTag()` — the interface is defined here even though Phase 2 is a separate spec
- Integration tests for tag sync against a live Ranger tag service are deferred (requires tag service configuration in Docker Compose test stack)
- Property tests validate universal invariants; unit tests validate specific examples and edge cases
