# Implementation Plan: Complete Integration Tests

## Overview

Address three high-impact gaps in the containerized integration test suite: fix the PolicyRefresher-induced timeout in data location deletion tests, add containerized wildcard tests, and add checkpoint/restart resilience tests. All changes are in Java, targeting the existing Maven `integration-test` profile.

## Tasks

- [x] 1. PolicyRefresher bypass — modify ConversionServerMain to fetch policies via REST API
  - [x] 1.1 Add `fetchPoliciesFromRangerAdmin()` static method to `ConversionServerMain.java`
    - Add a static method that does GET `/service/public/v2/api/service/{serviceName}/policy` using `HttpURLConnection` with Basic auth
    - Parse the JSON response into `List<RangerPolicy>`, wrap in a `ServicePolicies` envelope with `policyVersion = System.currentTimeMillis()`
    - Accept `rangerAdminUrl`, `username`, `password` parameters
    - Handle errors (non-200 status, IOException) by logging and returning null
    - _Requirements: 1.2, 1.4_

  - [x] 1.2 Modify `createSyncCycleExecutor()` to use `fetchPoliciesFromRangerAdmin()` instead of `plugin.getLatestPolicies()`
    - Change the method signature to accept `rangerAdminUrl`, `username`, `password` parameters
    - Replace `plugin.getLatestPolicies()` call with `fetchPoliciesFromRangerAdmin(rangerAdminUrl, username, password)`
    - Keep the reverse-sync logic unchanged
    - _Requirements: 1.3, 1.4, 1.5_

  - [x] 1.3 Update `startServer()` to pass Ranger Admin credentials to `createSyncCycleExecutor()`
    - Extract `rangerAdminUrl`, `username`, `password` from `SyncConfig.getRangerConfig()` (or equivalent)
    - Pass them to the updated `createSyncCycleExecutor()` call
    - _Requirements: 1.4_

  - [x] 1.4 Update `ContainerizedDataLocationIT.testDataLocationDeletionRevoke` timeout to 25s
    - Change the `waitForDryRunOutput()` call in the revoke step to use `waitForDryRunOutput(25_000)` (3 × 5s + 10s margin)
    - _Requirements: 1.1, 1.3_

- [x] 2. Checkpoint — Verify PolicyRefresher bypass compiles and existing tests are unaffected
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. IT config updates and base class modifications
  - [x] 3.1 Add `wildcardRefreshIntervalSeconds: 15` and `checkpointPath` to `server-config-it.yaml`
    - Change `wildcardRefreshIntervalSeconds` from `0` to `15`
    - Add `checkpointPath: /app/dry-run-output/sync-checkpoint.json`
    - _Requirements: 4.1, 5.1_

  - [x] 3.2 Update `clearDryRunOutputs()` in `ContainerizedPipelineIT` to only delete `dry-run-*.json` files
    - Change the file filter from deleting all files to only deleting files matching `dry-run-*.json` pattern
    - This preserves the checkpoint file (`sync-checkpoint.json`) across test cleanup
    - _Requirements: 5.1, 5.2_

  - [x] 3.3 Add `readCheckpointFile()` helper method to `ContainerizedPipelineIT`
    - Read and parse `sync-checkpoint.json` from the dry-run output directory
    - Return a `Map<String, Object>` (or a dedicated type) with checkpoint fields
    - _Requirements: 5.2, 5.3, 5.4, 5.5_

  - [x] 3.4 Add `waitForContainerHealth(long timeoutMs)` helper method to `ContainerizedPipelineIT`
    - Poll the conversion-server container health via `docker compose -f <path> ps conversion-server`
    - Check for "running" state and healthy process
    - Throw `AssertionError` on timeout with diagnostic message
    - _Requirements: 6.1_

  - [x] 3.5 Add `getComposeFilePath()` helper method to `ContainerizedPipelineIT`
    - Return the Docker Compose file path (`integration-test/docker/docker-compose.yml`)
    - _Requirements: 6.1_

  - [x] 3.6 Add `restartConversionServer()` helper method to `ContainerizedPipelineIT`
    - Use `ProcessBuilder` to run `docker compose -f <path> restart conversion-server`
    - Wait for the process to complete, throw on non-zero exit code
    - Call `waitForContainerHealth()` after restart
    - _Requirements: 6.1, 6.2_

- [x] 4. Checkpoint — Verify base class changes compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement ContainerizedWildcardIT test class
  - [x] 5.1 Create `ContainerizedWildcardIT.java` extending `ContainerizedPipelineIT`
    - Add `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` annotation
    - Place in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/`
    - _Requirements: 2.1, 3.1_

  - [x] 5.2 Implement `testWildcardDatabaseGrant()` test method
    - Create a Ranger policy granting ALTER on database `*` to user `data_admin`
    - Call `waitForDryRunOutput()` and assert GRANT with ALTER permission
    - Assert `principalArn` matches `arn:aws:iam::123456789012:role/data_admin`
    - Handle the case where Glue failure causes empty expansion (no GRANTs) as acceptable
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 5.3 Implement `testWildcardTableGrant()` test method
    - Create a Ranger policy granting SELECT on table `*` in database `analytics` to user `analyst`
    - Call `waitForDryRunOutput()` and assert GRANT with SELECT permission
    - Assert `databaseName` equals `analytics` and `principalArn` matches analyst ARN
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 5.4 Implement `testWildcardRefreshScheduler()` test method
    - Create a wildcard table policy, wait for initial sync via `waitForDryRunOutput()`
    - Clear dry-run output
    - Wait up to 45s for the `WildcardRefreshScheduler` to trigger a refresh cycle and produce output
    - Assert that output is produced (empty operations or re-expanded grants are both acceptable)
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 6. Implement ContainerizedCheckpointIT test class
  - [x] 6.1 Create `ContainerizedCheckpointIT.java` extending `ContainerizedPipelineIT`
    - Add `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` with `@Order` annotations
    - Place in `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/`
    - _Requirements: 5.1, 6.1_

  - [x] 6.2 Implement `testCheckpointPersistence()` test method
    - Create a policy, wait for sync via `waitForDryRunOutput()`
    - Read checkpoint file using `readCheckpointFile()`
    - Assert checkpoint is valid JSON with non-empty `cedarPolicyText`, `policyVersion > 0`, valid ISO-8601 `timestamp`
    - _Requirements: 5.2, 5.3, 5.4, 5.5_

  - [x] 6.3 Implement `testRestartResilience()` test method
    - Create policy A, wait for GRANT, verify checkpoint exists
    - Call `restartConversionServer()` to restart the container
    - Clear dry-run output, wait for next sync cycle
    - Assert no duplicate GRANT operations for policy A
    - Create policy B, wait for GRANT, assert GRANT only for policy B
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 6.4 Implement `testCorruptedCheckpointRecovery()` test method
    - Create a policy, wait for sync, verify checkpoint exists
    - Corrupt the checkpoint file by overwriting with invalid content (via host filesystem)
    - Call `restartConversionServer()` to restart the container
    - Wait for sync and assert full bulk sync produces GRANTs for all existing policies
    - _Requirements: 6.4_

- [x] 7. Final checkpoint — Verify all new and modified files compile and tests are correct
  - Verify all files compile under `mvn compile -Pintegration-test`
  - Verify existing containerized tests still pass with the PolicyRefresher bypass change
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Run full integration test suite and verify all tests pass
  - Run `./integration-test/scripts/run-integration-tests.sh` to execute the full Docker Compose lifecycle (provision → test → teardown)
  - Verify all existing containerized tests (database grant, table grant, column grant, data location, policy update diff, policy deletion, multi-principal, disabled policy, overlapping column, multi-policy) still pass
  - Verify `ContainerizedDataLocationIT.testDataLocationDeletionRevoke` completes within 25s with the PolicyRefresher bypass fix
  - Verify `ContainerizedWildcardIT` tests pass (wildcard database grant, wildcard table grant, wildcard refresh scheduler)
  - Verify `ContainerizedCheckpointIT` tests pass (checkpoint persistence, restart resilience, corrupted checkpoint recovery)
  - If any test fails, diagnose the failure, fix the issue, and re-run until all tests pass
  - _Requirements: 1.1, 1.5, 2.1, 3.1, 4.1, 5.2, 6.1, 6.4_

## Notes

- No property-based tests — all requirements describe integration test scenarios exercising external services, file I/O, and container lifecycle management
- The implementation language is Java, matching the existing codebase
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation between major phases
- The `clearDryRunOutputs()` change (task 3.2) is critical — without it, checkpoint tests will fail because the checkpoint file gets deleted between tests
