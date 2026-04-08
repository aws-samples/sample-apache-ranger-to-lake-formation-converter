# Requirements Document

## Introduction

This feature containerizes the conversion server and adds it to the Docker Compose integration test stack alongside the existing Ranger services. Instead of the current in-process pipeline wiring (where `DryRunPipelineIT` manually constructs the converter chain and calls `syncService.onPoliciesUpdated()` directly), the conversion server runs as a Docker container that registers with Ranger Admin as a real plugin, receives policy updates via the Ranger policy refresh mechanism, and writes dry-run JSON output to a shared volume. Integration tests create/update/delete policies in Ranger via REST, wait for the containerized server to pick up changes, and validate the dry-run JSON files from the host. This provides true end-to-end coverage of the plugin registration, policy refresh, and conversion pipeline running as a deployed service.

## Glossary

- **Conversion_Server**: The Java application (`SyncServiceMain`) that connects to Ranger Admin via the `LakeFormationPlugin`, receives policy updates, converts them through the Ranger → Cedar → LF pipeline, and applies LF operations via `LakeFormationClient` (or `DryRunLakeFormationClient` in dry-run mode).
- **Conversion_Server_Image**: The Docker image built from the project `Dockerfile` containing the Conversion_Server JAR and its runtime dependencies including the Cedar native library.
- **Conversion_Server_Container**: The Docker container running the Conversion_Server_Image as part of the integration test Docker Compose stack.
- **Ranger_Stack**: The existing Docker Compose services: `ranger-db` (PostgreSQL), `ranger-solr` (Solr), and `ranger-admin` (Ranger Admin).
- **Docker_Compose_File**: The `integration-test/docker/docker-compose.yml` file defining all services for the integration test environment.
- **Dry_Run_Output_Volume**: A Docker volume or bind-mount shared between the Conversion_Server_Container and the host, where dry-run JSON files are written by the Conversion_Server and read by integration tests.
- **Dry_Run_Output**: A JSON file written by `DryRunLakeFormationClient` containing a timestamp, sequence number, and list of `LFPermissionOperation` objects (grants and revokes).
- **Service_Definition**: The `ranger-servicedef-lakeformation.json` file that defines the `lakeformation` service type in Ranger Admin.
- **Service_Instance**: A Ranger service of type `lakeformation` that the Conversion_Server_Container registers with to receive policy updates.
- **Startup_Orchestrator**: The script or process that manages the ordered startup sequence: Ranger_Stack → service definition installation → service instance creation → Conversion_Server_Container start.
- **Integration_Test**: A JUnit 5 test class (suffixed `IT.java`) that runs against the containerized stack under the `integration-test` Maven profile.
- **Policy_REST_Client**: The existing `RangerPolicyRestClient` test helper that creates, updates, and deletes Ranger policies via the Ranger Admin REST API.
- **Policy_Refresh_Interval**: The interval at which the `LakeFormationPlugin` polls Ranger Admin for policy updates, configured via `policyRefreshIntervalMs` in `server-config.yaml`.
- **Principal_Mapping**: The configuration mapping Ranger users, groups, and roles to AWS IAM principal ARNs, defined in the Conversion_Server's `server-config.yaml`.

## Requirements

### Requirement 1: Conversion Server Docker Compose Service

**User Story:** As a developer, I want the conversion server added to the Docker Compose stack as a service, so that integration tests exercise the full deployed pipeline including plugin registration and policy refresh.

#### Acceptance Criteria

1. THE Docker_Compose_File SHALL define a `conversion-server` service that runs the Conversion_Server_Image.
2. THE `conversion-server` service SHALL depend on `ranger-admin` with condition `service_healthy`, so that the Conversion_Server_Container starts only after Ranger Admin is healthy.
3. THE `conversion-server` service SHALL set the environment variable `DRY_RUN_ENABLED` to `true`.
4. THE `conversion-server` service SHALL set the environment variable `DRY_RUN_OUTPUT_DIR` to a path inside the container that is mapped to the Dry_Run_Output_Volume.
5. THE `conversion-server` service SHALL join the existing `rangernw` Docker network so that the Conversion_Server_Container can reach Ranger Admin by hostname.
6. THE `conversion-server` service SHALL configure a health check that verifies the Conversion_Server process is running.
7. THE Docker_Compose_File SHALL define the Dry_Run_Output_Volume as a bind-mount to a host directory so that integration tests can read dry-run JSON files from the host filesystem.

### Requirement 2: Conversion Server Integration Test Configuration

**User Story:** As a developer, I want a dedicated server configuration file for the integration test environment, so that the conversion server connects to the Dockerized Ranger Admin with test-appropriate settings.

#### Acceptance Criteria

1. THE Conversion_Server_Container SHALL use a `server-config.yaml` that sets `rangerConfig.rangerAdminUrl` to `http://ranger-admin:6080` (the Docker network hostname).
2. THE server configuration SHALL set `policyRefreshIntervalMs` to a value of 5000 milliseconds or less, so that policy changes are picked up promptly during tests.
3. THE server configuration SHALL define Principal_Mapping entries for test users (`analyst`, `etl_user`, `data_admin`, `viewer`) mapped to IAM ARN patterns using a test account ID.
4. THE server configuration SHALL define Principal_Mapping entries for at least one test group mapped to an IAM role ARN.
5. THE server configuration SHALL define Principal_Mapping entries for at least one test role mapped to an IAM role ARN.
6. THE server configuration SHALL set `awsConfig.region` and `awsConfig.catalogId` to test values consistent with the existing integration tests (`us-east-1` and `123456789012`).

### Requirement 3: Fresh Ranger State on Each Run

**User Story:** As a developer, I want Ranger to start completely clean each time the integration test stack is brought up, so that tests are deterministic and not affected by leftover state.

#### Acceptance Criteria

1. WHEN the Startup_Orchestrator brings up the stack, THE Ranger_Stack SHALL start with no pre-existing service definitions or policies.
2. THE Startup_Orchestrator SHALL execute `docker compose down -v` before starting the stack, removing all containers, networks, and volumes from previous runs.
3. WHEN the Ranger_Stack is healthy, THE Startup_Orchestrator SHALL install the Service_Definition into Ranger Admin before starting the Conversion_Server_Container.
4. WHEN the Service_Definition is installed, THE Startup_Orchestrator SHALL create a Service_Instance named `lakeformation` in Ranger Admin before starting the Conversion_Server_Container.
5. IF the Conversion_Server_Container starts before the Service_Definition and Service_Instance exist, THEN THE Conversion_Server_Container SHALL fail to register with Ranger Admin and the Startup_Orchestrator SHALL detect this failure.

### Requirement 4: Startup Sequence Orchestration

**User Story:** As a developer, I want the startup sequence to be automated and ordered, so that all dependencies are satisfied before the conversion server begins receiving policies.

#### Acceptance Criteria

1. THE Startup_Orchestrator SHALL execute the following steps in order: (a) start Ranger_Stack, (b) wait for Ranger Admin health check, (c) install Service_Definition, (d) create Service_Instance, (e) start Conversion_Server_Container.
2. WHEN the Conversion_Server_Container starts, THE Startup_Orchestrator SHALL wait for the Conversion_Server health check to pass before reporting readiness.
3. IF any step in the startup sequence fails, THEN THE Startup_Orchestrator SHALL print diagnostic logs and exit with a non-zero exit code.
4. THE Startup_Orchestrator SHALL accept a `--skip-teardown` flag to keep the stack running after tests for debugging purposes.

### Requirement 5: Conversion Server Image Build

**User Story:** As a developer, I want the conversion server Docker image built automatically before integration tests run, so that the latest code changes are always tested.

#### Acceptance Criteria

1. WHEN the integration test lifecycle starts, THE Startup_Orchestrator SHALL build the Conversion_Server_Image from the project `Dockerfile` before starting the Docker Compose stack.
2. THE Conversion_Server_Image SHALL include the Cedar native library (cedar-java JNI) required for the Ranger → Cedar conversion pipeline.
3. THE Conversion_Server_Image SHALL use `eclipse-temurin:17-jre-alpine` as the runtime base image, consistent with the existing `Dockerfile`.
4. IF the Docker image build fails, THEN THE Startup_Orchestrator SHALL exit with a non-zero exit code and print the build error output.

### Requirement 6: Containerized Integration Test Base Class

**User Story:** As a test author, I want a base class for containerized integration tests that reads dry-run output from the shared volume and provides helpers for policy management and sync waiting, so that individual test classes are concise.

#### Acceptance Criteria

1. THE Integration_Test base class SHALL read dry-run JSON output files from the Dry_Run_Output_Volume host path instead of an in-process temporary directory.
2. THE Integration_Test base class SHALL provide a method that waits for the Conversion_Server_Container to pick up policy changes by polling the Dry_Run_Output_Volume for new or updated files, with a configurable timeout.
3. THE Integration_Test base class SHALL provide a method to clear all dry-run output files between sync cycles within the same test.
4. THE Integration_Test base class SHALL delete all Ranger policies created during a test in the `@AfterEach` teardown, logging warnings on failure without failing the test.
5. THE Integration_Test base class SHALL use the Policy_REST_Client to interact with Ranger Admin at the URL specified by the `ranger.admin.url` system property.
6. WHEN the Dry_Run_Output_Volume path is not configured, THE Integration_Test base class SHALL default to a path relative to the project root (e.g., `integration-test/dry-run-output`).

### Requirement 7: Integration Test — Database Grant via Containerized Server

**User Story:** As a developer, I want an integration test that creates a database-level grant policy in Ranger and verifies the containerized conversion server produces the correct dry-run LF grant operations.

#### Acceptance Criteria

1. WHEN a Ranger policy granting ALTER on database `test_db` to user `analyst` is created via the Policy_REST_Client, THE Integration_Test SHALL wait for the Conversion_Server_Container to process the policy and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain a GRANT operation with `databaseName` equal to `test_db`.
3. THE Dry_Run_Output GRANT operation SHALL have `permissions` containing `ALTER`.
4. THE Dry_Run_Output GRANT operation SHALL have a `principalArn` corresponding to the mapped IAM ARN for user `analyst`.

### Requirement 8: Integration Test — Table Grant via Containerized Server

**User Story:** As a developer, I want an integration test that creates a table-level grant policy and verifies the containerized server produces correct multi-permission LF grant operations.

#### Acceptance Criteria

1. WHEN a Ranger policy granting SELECT and DROP on table `test_db.events` to user `etl_user` is created, THE Integration_Test SHALL wait for the Conversion_Server_Container to process the policy and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain GRANT operations with `databaseName` equal to `test_db` and `tableName` equal to `events`.
3. THE Dry_Run_Output GRANT operations SHALL have `permissions` containing both `SELECT` and `DROP`.

### Requirement 9: Integration Test — Column Grant via Containerized Server

**User Story:** As a developer, I want an integration test that creates a column-level grant policy and verifies the containerized server produces correct column-scoped LF grant operations.

#### Acceptance Criteria

1. WHEN a Ranger policy granting SELECT on column `test_db.events.user_id` to user `analyst` is created, THE Integration_Test SHALL wait for the Conversion_Server_Container to process the policy and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain a GRANT operation with `databaseName` equal to `test_db`, `tableName` equal to `events`, and `columnNames` containing `user_id`.
3. THE Dry_Run_Output GRANT operation SHALL have `permissions` containing `SELECT`.

### Requirement 10: Integration Test — Data Location Grant and Revoke

**User Story:** As a developer, I want integration tests that verify data location grant and revoke scenarios through the containerized server, so that S3 path-based permissions are covered.

#### Acceptance Criteria

1. WHEN a Ranger policy granting `data_location_access` on S3 path `my-bucket/data/warehouse` to user `data_admin` is created, THE Integration_Test SHALL wait for the Conversion_Server_Container to produce Dry_Run_Output containing a GRANT with `dataLocationPath` containing `my-bucket` and `permissions` containing `DATA_LOCATION_ACCESS`.
2. WHEN the data location policy is deleted and the Conversion_Server_Container processes the change, THE Dry_Run_Output SHALL contain a REVOKE operation with `permissions` containing `DATA_LOCATION_ACCESS` for the same principal.

### Requirement 11: Integration Test — Policy Update Diff via Containerized Server

**User Story:** As a developer, I want an integration test that updates an existing policy and verifies the containerized server produces the correct incremental diff in dry-run output.

#### Acceptance Criteria

1. WHEN a Ranger policy is updated to add DROP permission to an existing SELECT-only grant, THE Integration_Test SHALL wait for the Conversion_Server_Container to process the update and produce Dry_Run_Output reflecting the diff.
2. THE Dry_Run_Output SHALL contain a GRANT operation for the newly added DROP permission.
3. WHEN a Ranger policy is updated to remove a user, THE Integration_Test SHALL produce Dry_Run_Output containing a REVOKE operation for that user's previously granted permissions.

### Requirement 12: Integration Test — Policy Deletion Revocations via Containerized Server

**User Story:** As a developer, I want an integration test that deletes a policy and verifies the containerized server produces revoke operations for all previously granted permissions.

#### Acceptance Criteria

1. WHEN a Ranger policy is deleted after being synced by the Conversion_Server_Container, THE Integration_Test SHALL wait for the next sync cycle and verify Dry_Run_Output contains REVOKE operations.
2. THE Dry_Run_Output REVOKE operations SHALL reference the same resource and principal as the original GRANT operations.

### Requirement 13: Integration Test — Multiple Principal Types

**User Story:** As a developer, I want integration tests that verify policies with users, groups, and roles are correctly mapped to IAM ARNs by the containerized server.

#### Acceptance Criteria

1. WHEN a Ranger policy grants access to a user (`analyst`), THE Dry_Run_Output GRANT operation SHALL have a `principalArn` matching the user's IAM ARN from the Principal_Mapping.
2. WHEN a Ranger policy grants access to a group (`data_engineers`), THE Dry_Run_Output GRANT operation SHALL have a `principalArn` matching the group's IAM role ARN from the Principal_Mapping.
3. WHEN a Ranger policy grants access to a role (`admin_role`), THE Dry_Run_Output GRANT operation SHALL have a `principalArn` matching the role's IAM role ARN from the Principal_Mapping.
4. WHEN a Ranger policy references an unmapped principal, THE Dry_Run_Output SHALL contain no GRANT operations for that principal.

### Requirement 14: Integration Test — Disabled Policy Handling

**User Story:** As a developer, I want an integration test that verifies the containerized server handles disabled policies correctly without producing grant operations, and that disabling a previously active policy revokes all its permissions.

#### Acceptance Criteria

1. WHEN a Ranger policy with `isEnabled` set to `false` is created, THE Conversion_Server_Container SHALL process the policy without error.
2. THE Dry_Run_Output SHALL contain no GRANT operations for resources and principals referenced only by the disabled policy.
3. WHEN a previously active Ranger policy that produced GRANT operations is updated to set `isEnabled` to `false`, THE Conversion_Server_Container SHALL produce Dry_Run_Output containing REVOKE operations for all permissions that were previously granted by that policy.

### Requirement 15: Integration Test — Overlapping Column Policies with Disable

**User Story:** As a developer, I want an integration test that verifies disabling one of two overlapping column-level policies only revokes permissions unique to the disabled policy, preserving permissions still covered by the remaining active policy.

#### Acceptance Criteria

1. GIVEN Policy A grants SELECT on columns `d`, `e`, `f` of table `test_db.events` to user `analyst`, AND Policy B grants SELECT on columns `e`, `f`, `g` of the same table to the same user, WHEN both policies are synced by the Conversion_Server_Container, THE Dry_Run_Output SHALL contain GRANT operations covering columns `d`, `e`, `f`, and `g`.
2. WHEN Policy B is updated to set `isEnabled` to `false`, THE Conversion_Server_Container SHALL produce Dry_Run_Output containing a REVOKE operation only for column `g` (the column unique to the disabled policy).
3. THE Dry_Run_Output SHALL NOT contain REVOKE operations for columns `e` or `f`, because those columns are still granted by the active Policy A.
4. THE Dry_Run_Output SHALL NOT contain REVOKE operations for column `d`, because it was never part of the disabled Policy B.

### Requirement 16: Integration Test — Multi-Policy Interaction

**User Story:** As a developer, I want an integration test that verifies correct behavior when multiple independent policies exist and one is selectively deleted.

#### Acceptance Criteria

1. WHEN two independent Ranger policies are created and synced, THE Dry_Run_Output SHALL contain GRANT operations for both policies' resources.
2. WHEN one policy is deleted and the Conversion_Server_Container processes the change, THE Dry_Run_Output SHALL contain REVOKE operations only for the deleted policy's resources, with no REVOKE or new GRANT for the retained policy.

### Requirement 17: Teardown and Cleanup

**User Story:** As a developer, I want the integration test teardown to remove all Docker containers, volumes, and dry-run output files, so that each test run starts from a clean state.

#### Acceptance Criteria

1. WHEN the integration test suite completes (pass or fail), THE Startup_Orchestrator SHALL execute `docker compose down -v` to remove all containers, networks, and volumes.
2. THE Startup_Orchestrator SHALL delete all files in the Dry_Run_Output_Volume host directory after tests complete.
3. THE Startup_Orchestrator SHALL exit with exit code 0 for cleanup operations even when containers are already stopped or do not exist.

### Requirement 18: Reverse-Sync Integration Tests (Deferred)


**User Story:** As a developer, I want reverse-sync integration tests noted as a future work item, so that the team tracks this requirement without implementing it now.

#### Acceptance Criteria

1. THE requirements document SHALL note that reverse-sync integration tests (verifying drift detection and correction through the containerized server) are deferred to a future phase.
2. THE deferred scope SHALL include: fetching actual LF permissions, computing drift against Cedar-authoritative state, and applying corrections via the containerized server in dry-run mode.
