# Implementation Plan: Containerized Integration Tests

## Overview

Add the conversion server as a Docker container to the existing Ranger integration test stack, replacing in-process pipeline wiring with true end-to-end containerized testing. Infrastructure first (Docker Compose, config, scripts), then the base test class, then concrete test classes in batches with checkpoints.

## Tasks

- [x] 1. Add conversion-server service to Docker Compose and create IT config
  - [x] 1.1 Add `conversion-server` service to `integration-test/docker/docker-compose.yml`
    - Add service with `build: context: ../.. dockerfile: Dockerfile`
    - Set `depends_on: ranger-admin: condition: service_healthy`
    - Set environment variables `DRY_RUN_ENABLED=true` and `DRY_RUN_OUTPUT_DIR=/app/dry-run-output`
    - Add bind-mount volume `./dry-run-output:/app/dry-run-output`
    - Join `rangernw` network
    - Add health check: `pgrep -f 'java.*app.jar' || exit 1` with interval 10s, timeout 5s, retries 12, start_period 15s
    - Override entrypoint to `["java", "-jar", "app.jar", "/app/config-it.yaml"]`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 1.2 Create `integration-test/docker/server-config-it.yaml`
    - Set `rangerConfig.rangerAdminUrl` to `http://ranger-admin:6080` with credentials `admin`/`rangerR0cks!`
    - Set `policyRefreshIntervalMs` to `5000`
    - Define `principalMapping.userMappings` for `analyst`, `etl_user`, `data_admin`, `viewer` mapped to IAM ARNs with account `123456789012`
    - Define `principalMapping.groupMappings` for `data_engineers`
    - Define `principalMapping.roleMappings` for `admin_role`
    - Set `awsConfig.region` to `us-east-1` and `awsConfig.catalogId` to `123456789012`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 2. Create startup scripts and modify run-integration-tests.sh
  - [x] 2.1 Create `integration-test/scripts/create-service-instance.sh`
    - Accept `--ranger-url` argument (default `http://localhost:6080`)
    - Use `admin:rangerR0cks!` basic auth
    - Check if `lakeformation` service instance already exists via GET
    - If not, POST to `/service/public/v2/api/service` with name `lakeformation`, type `lakeformation`, configs for region and catalog ID
    - Handle HTTP 409 (already exists) as success
    - Exit non-zero on unexpected failures with diagnostic output
    - Make script executable
    - _Requirements: 3.4, 4.1_

  - [x] 2.2 Modify `integration-test/scripts/run-integration-tests.sh` for two-phase startup
    - Add `docker compose down -v` at the start for clean state (before starting anything)
    - Add `docker compose build conversion-server` step to build the image
    - Change `start-ranger.sh` invocation to start only Ranger stack services (`ranger-db`, `ranger-solr`, `ranger-admin`) — not `conversion-server`
    - After servicedef install, call `create-service-instance.sh`
    - After service instance creation, start `conversion-server` via `docker compose -f ... up -d conversion-server`
    - Poll for conversion-server health (process running) with timeout
    - Clean up `dry-run-output` directory on teardown
    - Ensure `docker compose down -v` in cleanup handler
    - Preserve existing `--skip-teardown` flag behavior
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 5.1, 5.4, 17.1, 17.2, 17.3_

- [x] 3. Checkpoint — Verify infrastructure
  - Ensure Docker Compose config is valid (`docker compose config`)
  - Ensure scripts are executable and syntactically correct
  - Ask the user to manually verify the stack starts correctly with `./integration-test/scripts/run-integration-tests.sh --skip-teardown` if desired
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement ContainerizedPipelineIT base class
  - [x] 4.1 Create `src/integration-test/java/com/amazonaws/policyconverters/ranger/it/ContainerizedPipelineIT.java`
    - Define constants: `TEST_ACCOUNT_ID`, `DEFAULT_DRY_RUN_PATH` (`integration-test/docker/dry-run-output`), `DEFAULT_SYNC_TIMEOUT_MS` (30000), `POLL_INTERVAL_MS` (1000)
    - Read `dry.run.output.path` and `ranger.admin.url` from system properties with defaults
    - Initialize `RangerPolicyRestClient`, `ObjectMapper`, `createdPolicyIds` list in `@BeforeEach`
    - Clear dry-run output directory in `@BeforeEach`
    - Implement `@AfterEach` to delete tracked policies (log warnings, don't fail) and clear output
    - Implement `createAndTrackPolicy(String policyJson)` returning policy ID
    - Implement `updatePolicy(int policyId, String policyJson)`
    - Implement `deletePolicyAndUntrack(int policyId)`
    - Implement `waitForDryRunOutput(long timeoutMs)` with poll-based approach: record existing files/timestamps before call, poll every `POLL_INTERVAL_MS` for new/updated files, parse and return `DryRunOutput` list, throw `AssertionError` on timeout
    - Implement `readDryRunOutputs()` to parse all dry-run JSON files in the output directory
    - Implement `clearDryRunOutputs()` to delete all files in the output directory
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

- [x] 5. Implement first batch of containerized test classes (resource-level grants)
  - [x] 5.1 Create `ContainerizedDatabaseGrantIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test: create ALTER grant on `test_db` for `analyst`, call `waitForDryRunOutput()`, assert GRANT with `databaseName=test_db`, `permissions` containing ALTER, `principalArn` for analyst
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 5.2 Create `ContainerizedTableGrantIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test: create SELECT+DROP grant on `test_db.events` for `etl_user`, call `waitForDryRunOutput()`, assert GRANT with `databaseName=test_db`, `tableName=events`, `permissions` containing SELECT and DROP
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 5.3 Create `ContainerizedColumnGrantIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test: create SELECT grant on `test_db.events.user_id` for `analyst`, call `waitForDryRunOutput()`, assert GRANT with `columnNames` containing `user_id`, `permissions` containing SELECT
    - _Requirements: 9.1, 9.2, 9.3_

  - [x] 5.4 Create `ContainerizedDataLocationIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test grant: create `data_location_access` on `my-bucket/data/warehouse` for `data_admin`, assert GRANT with `dataLocationPath` containing `my-bucket` and `DATA_LOCATION_ACCESS`
    - Test revoke: delete the policy, call `waitForDryRunOutput()`, assert REVOKE with `DATA_LOCATION_ACCESS`
    - _Requirements: 10.1, 10.2_

- [x] 6. Checkpoint — Verify base class and resource-level tests compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement second batch of containerized test classes (policy lifecycle)
  - [x] 7.1 Create `ContainerizedPolicyUpdateDiffIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test add permission: create SELECT-only policy, sync, clear output, update to add DROP, assert GRANT for DROP
    - Test remove user: create policy with two users, sync, clear output, update to remove one user, assert REVOKE for removed user
    - _Requirements: 11.1, 11.2, 11.3_

  - [x] 7.2 Create `ContainerizedPolicyDeletionIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test: create policy, sync, clear output, delete policy, assert REVOKE for same resource and principal
    - _Requirements: 12.1, 12.2_

  - [x] 7.3 Create `ContainerizedMultiPrincipalIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test user mapping: policy with user `analyst`, assert `principalArn` matches user mapping
    - Test group mapping: policy with group `data_engineers`, assert `principalArn` matches group mapping
    - Test role mapping: policy with role `admin_role`, assert `principalArn` matches role mapping
    - Test unmapped principal: policy with unmapped user, assert no GRANT for that principal
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

  - [x] 7.4 Create `ContainerizedDisabledPolicyIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test disabled policy: create with `isEnabled=false`, assert no GRANT
    - Test disable active: create enabled policy, sync, clear output, update to `isEnabled=false`, assert REVOKE
    - _Requirements: 14.1, 14.2, 14.3_

- [x] 8. Implement third batch of containerized test classes (multi-policy scenarios)
  - [x] 8.1 Create `ContainerizedOverlappingColumnIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test: create Policy A (columns d,e,f) and Policy B (columns e,f,g), sync, assert GRANTs for d,e,f,g
    - Disable Policy B, assert REVOKE only for column g, no REVOKE for d,e,f
    - _Requirements: 15.1, 15.2, 15.3, 15.4_

  - [x] 8.2 Create `ContainerizedMultiPolicyIT.java`
    - Extend `ContainerizedPipelineIT`
    - Test: create two independent policies, sync, assert GRANTs for both
    - Delete one policy, assert REVOKE only for deleted policy's resources, no changes for retained policy
    - _Requirements: 16.1, 16.2_

- [x] 9. Final checkpoint — Ensure all tests compile and integration test profile is correct
  - Verify all 10 containerized test classes compile under `mvn compile -Pintegration-test`
  - Verify `ContainerizedPipelineIT` and all subclasses are picked up by `maven-failsafe-plugin` (`**/*IT.java` pattern)
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. Fix `ContainerizedDataLocationIT.testDataLocationDeletionRevoke` timeout failure
  - The test times out (>60s) waiting for the containerized server to produce a REVOKE after a data location policy deletion
  - The Ranger SDK `PolicyRefresher` thread exhibits unpredictable backoff behavior when `policyDeltas=null` is returned by Ranger Admin, causing delays far exceeding the configured 5s `pollIntervalMs`
  - The server logs confirm the REVOKE is eventually produced, but the delay is inconsistent and can exceed 60 seconds — especially later in the test suite after many rapid policy create/delete cycles
  - Investigation needed:
    - Examine `org.apache.ranger.plugin.util.PolicyRefresher` backoff logic when `policyDeltas=null`
    - Determine if Ranger Admin's delta API returns `null` deltas after policy deletions (forcing a full refresh with longer interval)
    - Consider whether the `ServerLifecycle` main loop (which uses the YAML `policyRefreshIntervalMs`) can be used as the sole sync mechanism instead of relying on the Ranger SDK `PolicyRefresher` thread
    - Alternatively, consider resetting the conversion-server container between test classes to avoid accumulated backoff state
  - _Requirements: 10.2_

## Notes

- No property-based tests — this feature is infrastructure and integration test scaffolding with no pure functions to test with random inputs
- The existing `DryRunPipelineIT` and its subclasses remain unchanged; the new `ContainerizedPipelineIT` hierarchy runs alongside them
- All test classes use `waitForDryRunOutput()` (poll-based) instead of `triggerSync()` (synchronous) to account for the async containerized server
- The `server-config-it.yaml` is bind-mounted into the container, keeping the production Dockerfile unchanged
- Reverse-sync integration tests (Req 18) are deferred to a future phase
