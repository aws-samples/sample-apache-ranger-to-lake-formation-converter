# Requirements Document

## Introduction

This feature adds a dry-run mode to the Ranger Lake Formation Sync Plugin and builds integration tests that exercise the full Ranger → Cedar → LF conversion pipeline against a live Docker Ranger instance. In dry-run mode, the LakeFormationClient is replaced with a file-writing implementation that serializes LF grant/revoke operations to JSON instead of calling the real AWS Lake Formation API. Integration tests programmatically create, update, and delete policies in Ranger via its REST API, run the sync service in dry-run mode, and validate the resulting JSON output files to verify correct end-to-end translation.

## Glossary

- **Sync_Service**: The `SyncService` class that orchestrates diff computation between Ranger policy snapshots and applies the resulting LF permission operations.
- **LakeFormation_Client**: The `LakeFormationClient` class that applies grant/revoke operations to AWS Lake Formation. The interception point for dry-run mode.
- **Dry_Run_Client**: A new implementation that replaces `LakeFormation_Client` and writes LF permission operations to a JSON output file instead of calling AWS APIs.
- **Dry_Run_Output**: A JSON file containing the list of `LFPermissionOperation` objects (grants and revokes) that the Sync_Service would have applied to Lake Formation.
- **Ranger_Admin**: The Apache Ranger Admin server running in Docker, accessible via REST API for policy management.
- **Policy_REST_Client**: A test helper that creates, updates, and deletes Ranger policies via the Ranger Admin REST API.
- **Integration_Test**: A JUnit 5 test class (suffixed `IT.java`) that runs against the Docker Ranger stack under the `integration-test` Maven profile.
- **Cedar_Pipeline**: The full conversion chain: Ranger policies → `RangerToCedarConverter` → `CedarPolicySet` → `CedarToLFConverter` → `LFPermissionOperation` list.
- **Output_Directory**: A temporary directory where Dry_Run_Client writes JSON output files during test execution.

## Requirements

### Requirement 1: Dry-Run LakeFormation Client

**User Story:** As a developer, I want a dry-run implementation of the LakeFormation client, so that I can capture LF operations to a file without calling AWS APIs.

#### Acceptance Criteria

1. THE Dry_Run_Client SHALL implement the same `applyBatch` method signature as LakeFormation_Client.
2. WHEN `applyBatch` is called, THE Dry_Run_Client SHALL serialize the list of `LFPermissionOperation` objects to a JSON file in the configured Output_Directory.
3. THE Dry_Run_Client SHALL write each batch invocation to a separate JSON file with a monotonically increasing sequence number in the filename (e.g., `dry-run-001.json`, `dry-run-002.json`).
4. THE Dry_Run_Client SHALL return a `BatchResult` indicating all operations succeeded with zero failures and zero rollbacks.
5. WHEN the Output_Directory does not exist, THE Dry_Run_Client SHALL create the directory before writing.
6. THE Dry_Run_Client SHALL use Jackson `ObjectMapper` for JSON serialization to ensure consistency with the existing `LFPermissionOperation` Jackson annotations.

### Requirement 2: Dry-Run Output File Format

**User Story:** As a developer, I want a well-defined JSON output format for dry-run files, so that integration tests can reliably parse and validate the captured operations.

#### Acceptance Criteria

1. THE Dry_Run_Output SHALL contain a JSON object with the following top-level fields: `timestamp` (ISO-8601 string), `sequenceNumber` (integer), and `operations` (array of LFPermissionOperation objects).
2. THE Dry_Run_Output SHALL serialize each `LFPermissionOperation` with fields: `operationType`, `sourcePolicyId`, `principalArn`, `resource`, `permissions`, and `grantable`.
3. THE Dry_Run_Output SHALL serialize each `LFResource` with fields: `catalogId`, `databaseName`, `tableName`, `columnNames`, `rowFilterExpression`, and `dataLocationPath`, omitting null fields.
4. FOR ALL valid LFPermissionOperation lists, serializing to Dry_Run_Output and then deserializing SHALL produce an equivalent list of operations (round-trip property).

### Requirement 3: Dry-Run Mode Configuration

**User Story:** As a developer, I want to enable dry-run mode via configuration, so that the sync service can be started in dry-run mode without code changes.

#### Acceptance Criteria

1. WHEN the `DRY_RUN_ENABLED` environment variable is set to `true`, THE Sync_Service SHALL use Dry_Run_Client instead of LakeFormation_Client.
2. WHEN dry-run mode is enabled, THE Sync_Service SHALL read the `DRY_RUN_OUTPUT_DIR` environment variable to determine the Output_Directory.
3. IF `DRY_RUN_ENABLED` is `true` and `DRY_RUN_OUTPUT_DIR` is not set, THEN THE Sync_Service SHALL default the Output_Directory to `./dry-run-output`.
4. WHEN dry-run mode is enabled, THE Sync_Service SHALL log a message at INFO level indicating that dry-run mode is active and the configured Output_Directory path.

### Requirement 4: Ranger Policy REST Client for Tests

**User Story:** As a test author, I want a helper client that manages Ranger policies via REST API, so that integration tests can programmatically set up and tear down test policies.

#### Acceptance Criteria

1. THE Policy_REST_Client SHALL create a Ranger policy by sending a POST request to the Ranger Admin REST API (`/service/public/v2/api/policy`).
2. THE Policy_REST_Client SHALL update an existing Ranger policy by sending a PUT request to the Ranger Admin REST API (`/service/public/v2/api/policy/{id}`).
3. THE Policy_REST_Client SHALL delete a Ranger policy by sending a DELETE request to the Ranger Admin REST API (`/service/public/v2/api/policy/{id}`).
4. THE Policy_REST_Client SHALL authenticate with Ranger Admin using HTTP Basic authentication with configurable credentials.
5. IF a policy REST operation returns an HTTP status code outside the 200-299 range, THEN THE Policy_REST_Client SHALL throw an exception containing the HTTP status code and response body.
6. THE Policy_REST_Client SHALL accept the Ranger Admin URL as a constructor parameter, defaulting to `http://localhost:6080`.

### Requirement 5: Ranger Service Instance Setup for Tests

**User Story:** As a test author, I want the integration test setup to ensure a Lake Formation service instance exists in Ranger Admin, so that policies can be created against it.

#### Acceptance Criteria

1. WHEN the integration test suite starts, THE Integration_Test setup SHALL verify that the `lakeformation` service definition exists in Ranger Admin.
2. WHEN the `lakeformation` service definition exists, THE Integration_Test setup SHALL create a service instance named `lakeformation` if one does not already exist.
3. IF the service instance already exists, THEN THE Integration_Test setup SHALL reuse the existing instance without error.
4. THE Integration_Test setup SHALL configure the service instance with test-appropriate values for AWS region, catalog ID, and other required properties.

### Requirement 6: Integration Test — Database Grant Policy

**User Story:** As a developer, I want an integration test that creates a database-level grant policy in Ranger and verifies the dry-run output contains the correct LF grant operations.

#### Acceptance Criteria

1. WHEN a Ranger policy granting SELECT on database `test_db` to user `analyst` is created, THE Integration_Test SHALL run the Cedar_Pipeline and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain exactly one GRANT operation with `databaseName` equal to `test_db`.
3. THE Dry_Run_Output GRANT operation SHALL have `permissions` containing `SELECT`.
4. THE Dry_Run_Output GRANT operation SHALL have a `principalArn` corresponding to the mapped IAM ARN for user `analyst`.

### Requirement 7: Integration Test — Table Grant Policy

**User Story:** As a developer, I want an integration test that creates a table-level grant policy in Ranger and verifies the dry-run output contains the correct LF grant operations.

#### Acceptance Criteria

1. WHEN a Ranger policy granting SELECT and INSERT on table `test_db.events` to user `etl_user` is created, THE Integration_Test SHALL run the Cedar_Pipeline and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain exactly one GRANT operation with `databaseName` equal to `test_db` and `tableName` equal to `events`.
3. THE Dry_Run_Output GRANT operation SHALL have `permissions` containing both `SELECT` and `INSERT`.

### Requirement 8: Integration Test — Policy Update Produces Diff

**User Story:** As a developer, I want an integration test that updates an existing policy and verifies the dry-run output reflects the incremental diff (new grants and revocations).

#### Acceptance Criteria

1. WHEN a Ranger policy is updated to add INSERT permission to an existing SELECT-only grant, THE Integration_Test SHALL run the Cedar_Pipeline and produce Dry_Run_Output reflecting the diff.
2. THE Dry_Run_Output SHALL contain a GRANT operation for the newly added permission.
3. WHEN a Ranger policy is updated to remove a user from the policy, THE Integration_Test SHALL produce Dry_Run_Output containing a REVOKE operation for that user.

### Requirement 9: Integration Test — Policy Deletion Produces Revocations

**User Story:** As a developer, I want an integration test that deletes a policy and verifies the dry-run output contains revoke operations for all previously granted permissions.

#### Acceptance Criteria

1. WHEN a Ranger policy is deleted after being synced, THE Integration_Test SHALL run the Cedar_Pipeline and produce Dry_Run_Output.
2. THE Dry_Run_Output SHALL contain REVOKE operations for all permissions that were previously granted by the deleted policy.
3. THE Dry_Run_Output REVOKE operations SHALL reference the same resource and principal as the original GRANT operations.

### Requirement 10: Integration Test Cleanup

**User Story:** As a test author, I want integration tests to clean up created policies after each test, so that tests are isolated and repeatable.

#### Acceptance Criteria

1. WHEN an integration test completes (pass or fail), THE Integration_Test SHALL delete all Ranger policies created during that test via the Policy_REST_Client.
2. WHEN an integration test completes, THE Integration_Test SHALL delete all files in the Output_Directory created during that test.
3. IF policy cleanup fails, THEN THE Integration_Test SHALL log a warning and continue without failing the test.
