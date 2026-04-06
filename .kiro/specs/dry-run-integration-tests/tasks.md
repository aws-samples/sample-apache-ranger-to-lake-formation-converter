# Implementation Plan: Dry-Run Integration Tests

## Overview

Implement a dry-run mode for the Ranger–Lake Formation sync pipeline and a suite of integration tests that exercise the full Ranger → Cedar → LF conversion chain against a live Docker Ranger instance. The dry-run client serializes LF operations to JSON files instead of calling AWS APIs. Integration tests create/update/delete policies in Ranger via REST, trigger the pipeline, and assert on the JSON output.

## Tasks

- [x] 1. Implement DryRunOutput POJO and DryRunLakeFormationClient
  - [x] 1.1 Create `DryRunOutput` POJO in `src/main/java/com/amazonaws/policyconverters/lakeformation/client/DryRunOutput.java`
    - Jackson-annotated class with `timestamp` (ISO-8601 String), `sequenceNumber` (int), and `operations` (List<LFPermissionOperation>)
    - Include `@JsonCreator` constructor and getters for deserialization support
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 1.2 Create `DryRunLakeFormationClient` in `src/main/java/com/amazonaws/policyconverters/lakeformation/client/DryRunLakeFormationClient.java`
    - Extend `LakeFormationClient`, pass `null` SDK client and dummy `RetryConfig` to parent constructor
    - Accept `Path outputDirectory` and `ObjectMapper` in constructor
    - Maintain `AtomicInteger` sequence counter for monotonic filenames (`dry-run-001.json`, `dry-run-002.json`, …)
    - Override `applyBatch()` to serialize operations as `DryRunOutput` JSON to a file in `outputDirectory`
    - Create `outputDirectory` if it doesn't exist
    - Return `BatchResult` with all operations succeeded, zero failures, zero rollbacks
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 1.3 Write property test `DryRunOutputRoundTripPropertyTest` in `src/test/java/com/amazonaws/policyconverters/lakeformation/client/DryRunOutputRoundTripPropertyTest.java`
    - **Property 1: Dry-run output round-trip serialization**
    - Use jqwik to generate arbitrary `LFPermissionOperation` lists; serialize via `DryRunLakeFormationClient.applyBatch()`, deserialize the JSON file, and assert the operations list equals the original
    - **Validates: Requirements 1.2, 2.1, 2.2, 2.3, 2.4**

  - [x] 1.4 Write property test `DryRunBatchResultPropertyTest` in `src/test/java/com/amazonaws/policyconverters/lakeformation/client/DryRunBatchResultPropertyTest.java`
    - **Property 2: Dry-run BatchResult always-success invariant**
    - Use jqwik to generate arbitrary `LFPermissionOperation` lists; call `applyBatch()` and assert `BatchResult` has zero failures, zero rollbacks, and applied count equals input size
    - **Validates: Requirements 1.4**

  - [x] 1.5 Write property test `DryRunSequencePropertyTest` in `src/test/java/com/amazonaws/policyconverters/lakeformation/client/DryRunSequencePropertyTest.java`
    - **Property 3: Monotonic sequence numbering**
    - Use jqwik to generate a random number of `applyBatch()` calls (1–20); assert the output directory contains exactly N files with consecutive sequence numbers 1..N
    - **Validates: Requirements 1.3**

- [x] 2. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Wire dry-run mode into SyncServiceMain
  - [x] 3.1 Modify `SyncServiceMain.startSyncService()` to check `DRY_RUN_ENABLED` env var
    - When `"true"` (case-insensitive), read `DRY_RUN_OUTPUT_DIR` (default `./dry-run-output`)
    - Log INFO message indicating dry-run mode is active with the output directory path
    - Instantiate `DryRunLakeFormationClient` instead of the real `LakeFormationClient`
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 3.2 Write unit test for dry-run wiring in `src/test/java/com/amazonaws/policyconverters/ranger/SyncServiceMainDryRunTest.java`
    - Verify that when `DRY_RUN_ENABLED=true`, the constructed client is `DryRunLakeFormationClient`
    - Verify default output directory is `./dry-run-output` when `DRY_RUN_OUTPUT_DIR` is not set
    - _Requirements: 3.1, 3.2, 3.3_

- [x] 4. Implement RangerPolicyRestClient test helper
  - [x] 4.1 Create `RangerPolicyRestClient` in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/RangerPolicyRestClient.java`
    - Constructor accepts `rangerAdminUrl` (default `http://localhost:6080`), `username`, `password`
    - Implement `createPolicy(String policyJson)` → POST to `/service/public/v2/api/policy`, return created policy ID
    - Implement `updatePolicy(int policyId, String policyJson)` → PUT to `/service/public/v2/api/policy/{id}`
    - Implement `deletePolicy(int policyId)` → DELETE to `/service/public/v2/api/policy/{id}`
    - Use `HttpURLConnection` with Basic auth (consistent with existing `ServiceDefInstallIT`)
    - Throw `RuntimeException` with HTTP status code and response body on non-2xx responses
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 5. Implement DryRunPipelineIT base class and service instance setup
  - [x] 5.1 Create `DryRunPipelineIT` abstract base class in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/DryRunPipelineIT.java`
    - `@BeforeAll`: Verify `lakeformation` service definition exists; create `lakeformation` service instance if not present (reuse if exists)
    - `@BeforeEach`: Create temp output directory, initialize `RangerPolicyRestClient`, reset policy tracking list
    - `@AfterEach`: Delete all created policies (log warning on failure), delete output files (log warning on failure)
    - Provide helper methods to trigger a sync cycle in dry-run mode and read/parse `DryRunOutput` from output files
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 10.1, 10.2, 10.3_

- [x] 6. Implement integration test scenarios
  - [x] 6.1 Create `DatabaseGrantPolicyIT` in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/DatabaseGrantPolicyIT.java`
    - Extend `DryRunPipelineIT`
    - Create a Ranger policy granting SELECT on database `test_db` to user `analyst`
    - Trigger sync cycle, read dry-run output
    - Assert exactly one GRANT operation with `databaseName=test_db`, `permissions` containing SELECT, and correct `principalArn`
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 6.2 Create `TableGrantPolicyIT` in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/TableGrantPolicyIT.java`
    - Extend `DryRunPipelineIT`
    - Create a Ranger policy granting SELECT and INSERT on table `test_db.events` to user `etl_user`
    - Trigger sync cycle, read dry-run output
    - Assert exactly one GRANT operation with `databaseName=test_db`, `tableName=events`, `permissions` containing SELECT and INSERT
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 6.3 Create `PolicyUpdateDiffIT` in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/PolicyUpdateDiffIT.java`
    - Extend `DryRunPipelineIT`
    - Create a SELECT-only policy, sync, then update to add INSERT; sync again and assert GRANT for INSERT in diff output
    - Update policy to remove a user, sync and assert REVOKE for that user
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 6.4 Create `PolicyDeletionRevokeIT` in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/PolicyDeletionRevokeIT.java`
    - Extend `DryRunPipelineIT`
    - Create a policy, sync, then delete the policy; sync again and assert REVOKE operations for all previously granted permissions
    - Assert REVOKE operations reference the same resource and principal as the original GRANTs
    - _Requirements: 9.1, 9.2, 9.3_

- [x] 7. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Write deletion-revocation symmetry property test
  - [x] 8.1 Write property test `DeletionRevocationSymmetryPropertyTest` in `src/test/java/com/amazonaws/policyconverters/ranger/sync/DeletionRevocationSymmetryPropertyTest.java`
    - **Property 4: Deletion-revocation symmetry**
    - Use jqwik to generate arbitrary GRANT operation lists as the "previous" snapshot; compute diff with empty "current" snapshot via `SyncService.computeDiff()`; assert REVOKE operations match the original GRANTs on principal and resource
    - **Validates: Requirements 9.3**

- [x] 9. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties using jqwik (already in project dependencies)
- Integration tests require the Docker Ranger stack and run under `mvn verify -Pintegration-test`
- All code is Java 17, consistent with the existing project
