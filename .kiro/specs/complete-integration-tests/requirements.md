# Requirements Document

## Introduction

This feature completes the end-to-end integration test coverage for the Ranger Lake Formation Sync Plugin by addressing gaps identified after the initial containerized integration test spec (`containerized-integration-tests`, tasks 1–9). The existing spec established the Docker Compose infrastructure, startup orchestration, base test class (`ContainerizedPipelineIT`), and containerized tests for all core grant/revoke scenarios. This new spec focuses on three high-impact areas:

1. **Data location deletion timeout fix** — The `ContainerizedDataLocationIT.testDataLocationDeletionRevoke` test times out because the Ranger SDK `PolicyRefresher` exhibits unpredictable backoff when `policyDeltas=null`, causing delays far exceeding the configured 5s poll interval. This is task 10 from the existing spec, carried forward here with a concrete investigation and fix plan.

2. **Containerized wildcard tests** — The `WildcardPolicyIT` exists only as an in-process test (extending `DryRunPipelineIT`). The `WildcardRefreshScheduler` and `SyncService.executeWildcardRefresh()` are untested through the containerized server. The IT config currently has `wildcardRefreshIntervalSeconds: 0` (disabled).

3. **Checkpoint and restart resilience tests** — The `CheckpointStore` persists Cedar policy state for restart recovery, but no integration tests verify that the containerized server resumes correctly after a restart, or that checkpoint files are written and loaded through the real pipeline.

Error handling/resilience tests, multi-service mode tests, and reverse-sync tests are noted as deferred — they require significant infrastructure changes (mock Ranger Admin, multi-service Docker Compose, etc.) and are lower priority than the three areas above.

## Glossary

- **Conversion_Server_Container**: The Docker container running the conversion server as part of the integration test Docker Compose stack, writing dry-run JSON output to a shared volume.
- **Dry_Run_Output**: A JSON file written by `DryRunLakeFormationClient` containing a timestamp, sequence number, and list of `LFPermissionOperation` objects (grants and revokes).
- **Dry_Run_Output_Volume**: The bind-mount shared between the Conversion_Server_Container and the host (`integration-test/docker/dry-run-output`).
- **ContainerizedPipelineIT**: The abstract JUnit 5 base class for containerized integration tests, providing policy management helpers and poll-based `waitForDryRunOutput()`.
- **Policy_REST_Client**: The `RangerPolicyRestClient` test helper that creates, updates, and deletes Ranger policies via the Ranger Admin REST API.
- **PolicyRefresher**: The Ranger SDK thread (`org.apache.ranger.plugin.util.PolicyRefresher`) inside the Conversion_Server_Container that polls Ranger Admin for policy updates at `policyRefreshIntervalMs` intervals.
- **PolicyRefresher_Backoff**: The behavior where `PolicyRefresher` increases its poll interval when Ranger Admin returns `policyDeltas=null`, causing delays beyond the configured `policyRefreshIntervalMs`.
- **ServerLifecycle**: The server run-loop class that executes sync cycles at the configured interval, coordinating with `WildcardRefreshScheduler` via a shared `ReentrantLock`.
- **WildcardRefreshScheduler**: The scheduled executor that periodically triggers `SyncService.executeWildcardRefresh()` to re-expand glob-containing policies against the Glue catalog.
- **CheckpointStore**: The component that persists Cedar policy text and Ranger policy version to a JSON file for restart recovery, using atomic write-via-rename.
- **SyncCheckpoint**: The JSON-serializable checkpoint object containing `policyVersion`, `timestamp`, `cedarPolicyText`, and optionally `serviceVersions`.
- **Startup_Orchestrator**: The `run-integration-tests.sh` script that manages the ordered startup sequence for the Docker Compose stack.
- **IT_Config**: The `integration-test/docker/server-config-it.yaml` file used by the Conversion_Server_Container.

## Requirements

### Requirement 1: Diagnose and Fix Data Location Deletion Timeout

**User Story:** As a developer, I want the `ContainerizedDataLocationIT.testDataLocationDeletionRevoke` test to pass reliably within a reasonable timeout, so that data location revoke scenarios have stable end-to-end coverage.

#### Acceptance Criteria

1. WHEN the `testDataLocationDeletionRevoke` test deletes a data location policy and waits for the Conversion_Server_Container to produce a REVOKE, THE test SHALL complete within 120 seconds.
2. THE investigation SHALL examine the `PolicyRefresher` backoff logic to determine why `policyDeltas=null` responses cause delays exceeding the configured `policyRefreshIntervalMs`.
3. WHEN the root cause is identified, THE fix SHALL ensure that policy deletions are detected by the Conversion_Server_Container within a bounded time proportional to the configured `policyRefreshIntervalMs` (no more than 3 refresh intervals plus 10 seconds of margin).
4. IF the `PolicyRefresher_Backoff` cannot be controlled or overridden, THEN THE fix SHALL implement an alternative sync mechanism (such as using the `ServerLifecycle` main loop as the sole sync trigger) that bypasses the `PolicyRefresher` thread for policy fetching.
5. THE fix SHALL preserve the existing behavior of all other containerized integration tests (tasks 1–9 from the `containerized-integration-tests` spec).
6. IF the fix involves restarting the Conversion_Server_Container between test classes, THEN THE Startup_Orchestrator or test infrastructure SHALL automate the restart without manual intervention.

### Requirement 2: Containerized Wildcard Database Grant Test

**User Story:** As a developer, I want a containerized integration test that creates a wildcard database grant policy (database pattern `*`) and verifies the Conversion_Server_Container produces correct dry-run output, so that wildcard database patterns are covered end-to-end.

#### Acceptance Criteria

1. WHEN a Ranger policy granting ALTER on database `*` (wildcard) to user `data_admin` is created via the Policy_REST_Client, THE test SHALL wait for the Conversion_Server_Container to process the policy and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain at least one GRANT operation with `permissions` containing `ALTER`.
3. THE Dry_Run_Output GRANT operation SHALL have a `principalArn` corresponding to the mapped IAM ARN for user `data_admin`.
4. THE test SHALL verify that the wildcard database pattern is handled without errors by the containerized pipeline (no exceptions in Conversion_Server_Container logs).

### Requirement 3: Containerized Wildcard Table Grant Test

**User Story:** As a developer, I want a containerized integration test that creates a wildcard table grant policy (table pattern `*` within a specific database) and verifies the Conversion_Server_Container produces correct dry-run output.

#### Acceptance Criteria

1. WHEN a Ranger policy granting SELECT on table `*` (wildcard) in database `analytics` to user `analyst` is created via the Policy_REST_Client, THE test SHALL wait for the Conversion_Server_Container to process the policy and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain at least one GRANT operation with `databaseName` equal to `analytics` and `permissions` containing `SELECT`.
3. THE Dry_Run_Output GRANT operation SHALL have a `principalArn` corresponding to the mapped IAM ARN for user `analyst`.

### Requirement 4: Containerized Wildcard Refresh Scheduler Test

**User Story:** As a developer, I want a containerized integration test that verifies the `WildcardRefreshScheduler` detects new resources matching an existing wildcard policy and produces incremental GRANT operations, so that the periodic wildcard re-expansion is covered end-to-end.

#### Acceptance Criteria

1. THE IT_Config SHALL be updated (or a variant created) to set `wildcardRefreshIntervalSeconds` to a positive value (30 seconds or less) for wildcard refresh tests.
2. WHEN a Ranger policy granting SELECT on table `*` in database `analytics` to user `analyst` is created and the initial sync produces Dry_Run_Output, THE test SHALL clear the Dry_Run_Output and wait for the `WildcardRefreshScheduler` to trigger a refresh cycle.
3. WHEN the `WildcardRefreshScheduler` completes a refresh cycle, THE Conversion_Server_Container SHALL produce Dry_Run_Output reflecting the re-expanded wildcard policy.
4. IF the wildcard refresh produces no changes (same resources as before), THEN THE Conversion_Server_Container SHALL produce Dry_Run_Output with an empty operations list or no new output file.

### Requirement 5: Checkpoint Persistence Through Containerized Server

**User Story:** As a developer, I want a containerized integration test that verifies the `CheckpointStore` writes a valid checkpoint file after a sync cycle, so that checkpoint persistence is covered end-to-end.

#### Acceptance Criteria

1. THE IT_Config SHALL configure a `checkpointPath` that maps to a file accessible from the host via the Dry_Run_Output_Volume or a separate bind-mount.
2. WHEN the Conversion_Server_Container completes a sync cycle that produces GRANT operations, THE CheckpointStore SHALL write a checkpoint file containing a valid `SyncCheckpoint` JSON object.
3. THE checkpoint file SHALL contain a non-empty `cedarPolicyText` field representing the current Cedar policy state.
4. THE checkpoint file SHALL contain a `policyVersion` field with a value greater than zero.
5. THE checkpoint file SHALL contain a `timestamp` field with a valid ISO-8601 timestamp.

### Requirement 6: Server Restart Resilience Through Containerized Server

**User Story:** As a developer, I want a containerized integration test that verifies the Conversion_Server_Container resumes correctly after a restart, using the persisted checkpoint to avoid re-granting already-applied permissions, so that restart resilience is covered end-to-end.

#### Acceptance Criteria

1. GIVEN the Conversion_Server_Container has synced a policy and written a checkpoint, WHEN the Conversion_Server_Container is restarted (stopped and started via Docker Compose), THE Conversion_Server_Container SHALL load the persisted checkpoint on startup.
2. WHEN the restarted Conversion_Server_Container completes its first sync cycle, THE Dry_Run_Output SHALL NOT contain duplicate GRANT operations for permissions that were already applied before the restart.
3. WHEN a new policy is created after the restart, THE Conversion_Server_Container SHALL produce Dry_Run_Output containing GRANT operations only for the new policy's permissions.
4. IF the checkpoint file is corrupted or missing, THEN THE Conversion_Server_Container SHALL start from empty state and perform a full bulk sync, producing GRANT operations for all existing policies.

### Requirement 7: Deferred Test Areas

**User Story:** As a developer, I want the following test areas documented as deferred, so that the team tracks them for future implementation without blocking the current spec.

#### Acceptance Criteria

1. THE requirements document SHALL note that error handling and resilience tests (Ranger Admin connection failures, malformed policies, `onConnectivityLost`/`onConnectivityRestored` behavior) are deferred to a future phase.
2. THE requirements document SHALL note that multi-service mode integration tests (hive, presto, trino adapters through `executeSyncCycle()`) are deferred to a future phase, pending multi-service Docker Compose infrastructure.
3. THE requirements document SHALL note that reverse-sync integration tests (drift detection and correction) remain deferred, consistent with the existing `containerized-integration-tests` spec.
4. THE requirements document SHALL note that dead-letter log integration tests (verifying failed operations are logged to the dead-letter file through the containerized server) are deferred to a future phase.
