# Requirements Document

## Introduction

This feature adds a reverse-sync capability to the Ranger LakeFormation Sync Plugin. The existing pipeline flows Ranger → Cedar → LakeFormation. The reverse-sync feature retrieves the current state of LakeFormation permissions via the `LakeFormation:ListPermissions` API, converts those permissions into a normalized representation comparable to Cedar-derived `LFPermissionOperation` objects, computes a diff against the Cedar-authoritative policy set, and applies corrective GRANT/REVOKE operations to bring LakeFormation back into alignment with Cedar.

This is essential for detecting and correcting drift caused by out-of-band changes to LakeFormation permissions (e.g., manual console edits, other automation tools, or failed partial applies).

## Glossary

- **Reverse_Sync_Service**: The orchestrator component that coordinates retrieval of LakeFormation permissions, diff computation against Cedar, and application of corrective operations.
- **LF_Permission_Fetcher**: The component responsible for calling the `LakeFormation:ListPermissions` API and converting the SDK response into a list of `LFPermissionOperation` objects.
- **Drift_Detector**: The component that computes the diff between Cedar-derived (desired) permissions and LakeFormation-actual permissions, producing a list of corrective GRANT and REVOKE operations.
- **Cedar_Policy_Set**: The set of Cedar policy statements representing the authoritative desired state, as produced by the existing conversion pipeline.
- **LF_Actual_Permissions**: The set of permissions currently active in LakeFormation, as retrieved via `ListPermissions`.
- **Corrective_Operations**: The list of GRANT and REVOKE operations needed to reconcile LF_Actual_Permissions with the Cedar-derived desired state.
- **Drift_Report**: A structured summary of detected differences between desired and actual LakeFormation permissions.
- **LFPermissionOperation**: The existing model class representing a single GRANT or REVOKE operation with principal, resource, permissions, and grantable flag.
- **LFResource**: The existing model class representing a LakeFormation resource target (database, table, column, data location).
- **LFPermission**: The existing enum representing LakeFormation permission types (SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, CREATE_DATABASE, CREATE_TABLE, DATA_LOCATION_ACCESS).
- **PermissionKey**: The identity tuple (principalArn, resource, permissions, grantable) used for diff comparison, as defined in the existing SyncService.
- **Catalog_Id**: The AWS account ID that identifies the Glue Data Catalog.
- **Dead_Letter_Logger**: The existing component that logs failed operations for later investigation.

## Requirements

### Requirement 1: Retrieve LakeFormation Permissions

**User Story:** As a sync operator, I want to retrieve all current LakeFormation permissions for a given catalog, so that the system can compare them against the Cedar-authoritative state.

#### Acceptance Criteria

1. WHEN the Reverse_Sync_Service initiates a sync cycle, THE LF_Permission_Fetcher SHALL call the `LakeFormation:ListPermissions` API for the configured Catalog_Id.
2. WHEN the `ListPermissions` response contains multiple pages, THE LF_Permission_Fetcher SHALL paginate through all pages using the NextToken until all permissions are retrieved.
3. THE LF_Permission_Fetcher SHALL convert each `PrincipalResourcePermissions` entry from the SDK response into an `LFPermissionOperation` with OperationType GRANT, preserving the principal ARN, resource (database, table, column, or data location), permission set, and grantable flag.
4. WHEN a `PrincipalResourcePermissions` entry contains `PermissionsWithGrantOption`, THE LF_Permission_Fetcher SHALL create a separate `LFPermissionOperation` with `grantable=true` for the grant-option permissions.
5. IF the `ListPermissions` API call fails with a transient error (throttling or service unavailable), THEN THE LF_Permission_Fetcher SHALL retry with exponential backoff using the existing RetryConfig parameters.
6. IF the `ListPermissions` API call fails after exhausting retries, THEN THE LF_Permission_Fetcher SHALL throw a `LakeFormationClientException` with a descriptive error message.
7. THE LF_Permission_Fetcher SHALL support filtering by principal ARN, resource type, or both, to allow scoped retrieval of permissions.

### Requirement 2: Normalize LakeFormation Permissions for Comparison

**User Story:** As a sync operator, I want LakeFormation permissions normalized into the same format as Cedar-derived operations, so that the diff algorithm can compare them accurately.

#### Acceptance Criteria

1. THE LF_Permission_Fetcher SHALL map each LakeFormation SDK `Resource` (DatabaseResource, TableResource, TableWithColumnsResource, DataLocationResource) to the existing `LFResource` model using the same field mapping as `LakeFormationClient.buildResource` in reverse.
2. THE LF_Permission_Fetcher SHALL map each LakeFormation SDK `Permission` enum value to the existing `LFPermission` enum using case-insensitive matching.
3. WHEN a LakeFormation permission entry references a resource type not supported by the existing `LFResource` model (e.g., CatalogResource, LFTagPolicy), THE LF_Permission_Fetcher SHALL skip the entry and log a warning.
4. THE LF_Permission_Fetcher SHALL set the `sourcePolicyId` field to null for all LakeFormation-retrieved operations, since LakeFormation permissions have no source policy concept.
5. WHEN a `TableWithColumnsResource` uses `ColumnWildcard` instead of explicit column names, THE LF_Permission_Fetcher SHALL represent the resource as a table-level `LFResource` with no column names (matching the existing wildcard convention).
6. WHEN a `TableResource` uses `TableWildcard` (all tables in a database), THE LF_Permission_Fetcher SHALL represent the resource as a database-level `LFResource` with a wildcard table indicator.

### Requirement 3: Compute Drift Between Cedar and LakeFormation

**User Story:** As a sync operator, I want to detect differences between the Cedar-authoritative permissions and the actual LakeFormation permissions, so that corrective operations can be generated.

#### Acceptance Criteria

1. THE Drift_Detector SHALL compare Cedar-derived desired operations against LF_Actual_Permissions using the same PermissionKey identity (principalArn, resource, permissions, grantable) as the existing `SyncService.computeDiff`.
2. WHEN a Cedar-derived permission exists in the desired state but not in LF_Actual_Permissions, THE Drift_Detector SHALL produce a GRANT corrective operation for the missing permission.
3. WHEN a permission exists in LF_Actual_Permissions but not in the Cedar-derived desired state, THE Drift_Detector SHALL produce a REVOKE corrective operation for the extra permission.
4. WHEN a permission exists in both the desired state and LF_Actual_Permissions with identical PermissionKey, THE Drift_Detector SHALL treat the permission as in-sync and produce no corrective operation.
5. THE Drift_Detector SHALL produce a Drift_Report containing the count of missing grants, extra permissions, and in-sync permissions.
6. WHEN the Drift_Detector is configured in report-only mode, THE Drift_Detector SHALL compute and return the Drift_Report without producing any Corrective_Operations.
7. THE Drift_Detector SHALL exclude permissions from the diff that match a configurable exclusion filter (e.g., specific principals or resource patterns managed outside Cedar).

### Requirement 4: Apply Corrective Operations to LakeFormation

**User Story:** As a sync operator, I want the system to apply corrective GRANT and REVOKE operations to LakeFormation, so that LakeFormation permissions match the Cedar-authoritative state.

#### Acceptance Criteria

1. WHEN the Drift_Detector produces Corrective_Operations, THE Reverse_Sync_Service SHALL apply REVOKE operations before GRANT operations to avoid transient over-permissioning.
2. THE Reverse_Sync_Service SHALL apply Corrective_Operations using the existing `LakeFormationClient.applyBatch` method with the same retry and rollback semantics.
3. IF a corrective REVOKE operation fails after exhausting retries, THEN THE Reverse_Sync_Service SHALL log the failure to the Dead_Letter_Logger and continue processing remaining operations.
4. IF a corrective GRANT operation fails after exhausting retries, THEN THE Reverse_Sync_Service SHALL log the failure to the Dead_Letter_Logger and continue processing remaining operations.
5. THE Reverse_Sync_Service SHALL log an audit entry for each corrective operation applied, including the operation type, principal, resource, permissions, and the drift reason (missing grant or extra permission).
6. WHEN all Corrective_Operations have been applied, THE Reverse_Sync_Service SHALL return a summary result containing the count of successful grants, successful revokes, failed operations, and the total drift detected.

### Requirement 5: Reverse Sync Orchestration and Configuration

**User Story:** As a sync operator, I want to configure and trigger reverse-sync cycles, so that I can control when and how LakeFormation drift is corrected.

#### Acceptance Criteria

1. THE Reverse_Sync_Service SHALL accept a configuration specifying the Catalog_Id, the Cedar_Policy_Set source, the report-only flag, and the exclusion filter.
2. WHEN triggered, THE Reverse_Sync_Service SHALL execute the full cycle: fetch LF permissions, compute drift, and apply corrections (unless in report-only mode).
3. THE Reverse_Sync_Service SHALL support being triggered on-demand (single execution) and on a configurable periodic schedule.
4. WHILE the Reverse_Sync_Service is executing a sync cycle, THE Reverse_Sync_Service SHALL reject concurrent sync requests and log a warning.
5. IF the Cedar_Policy_Set source is empty or unavailable, THEN THE Reverse_Sync_Service SHALL skip the sync cycle and log an error rather than revoking all LakeFormation permissions.
6. THE Reverse_Sync_Service SHALL emit structured log entries at the start and end of each sync cycle, including the cycle duration, drift summary, and any errors encountered.

### Requirement 6: Dry-Run Mode for Reverse Sync

**User Story:** As a sync operator, I want to run the reverse sync in dry-run mode, so that I can review what changes would be made before applying them.

#### Acceptance Criteria

1. WHEN dry-run mode is enabled, THE Reverse_Sync_Service SHALL compute the full Drift_Report and Corrective_Operations without applying any changes to LakeFormation.
2. WHEN dry-run mode is enabled, THE Reverse_Sync_Service SHALL serialize the Corrective_Operations to a JSON file using the existing `DryRunOutput` format with a monotonically increasing sequence number.
3. THE dry-run output SHALL include a `driftSummary` field containing the count of missing grants, extra permissions, and in-sync permissions.
4. WHEN dry-run mode is enabled, THE Reverse_Sync_Service SHALL use the existing `DryRunLakeFormationClient` for operation serialization.

### Requirement 7: Error Handling and Resilience

**User Story:** As a sync operator, I want the reverse sync to handle errors gracefully, so that partial failures do not leave LakeFormation in an inconsistent state.

#### Acceptance Criteria

1. IF the LF_Permission_Fetcher encounters a permission entry with an unrecognized resource type, THEN THE LF_Permission_Fetcher SHALL skip the entry, log a warning with the raw resource details, and continue processing remaining entries.
2. IF the LF_Permission_Fetcher encounters a permission entry with an unrecognized permission value, THEN THE LF_Permission_Fetcher SHALL skip the entry, log a warning, and continue processing remaining entries.
3. WHEN a corrective operation fails, THE Reverse_Sync_Service SHALL continue applying remaining corrective operations rather than aborting the entire batch.
4. THE Reverse_Sync_Service SHALL record all skipped permissions and failed operations in the Drift_Report for post-cycle review.
5. IF the `ListPermissions` API returns an `AccessDeniedException`, THEN THE LF_Permission_Fetcher SHALL throw a `LakeFormationClientException` with a message indicating insufficient IAM permissions for the `lakeformation:ListPermissions` action.

### Requirement 8: LakeFormation Permission Fetcher to LFPermissionOperation Round-Trip Consistency

**User Story:** As a developer, I want to verify that converting LakeFormation SDK responses to LFPermissionOperation and back to LakeFormation SDK requests produces equivalent operations, so that the normalization layer is correct.

#### Acceptance Criteria

1. FOR ALL valid LFPermissionOperation objects, building an LF SDK Resource via `LakeFormationClient.buildResource` and then converting back via the LF_Permission_Fetcher normalization SHALL produce an equivalent LFResource (round-trip property).
2. FOR ALL valid LFPermission sets, converting to LF SDK Permission enums via `LakeFormationClient.toLfPermissions` and then converting back via the LF_Permission_Fetcher normalization SHALL produce an equivalent LFPermission set (round-trip property).
3. FOR ALL valid LFPermissionOperation lists representing a desired state, computing drift against an identical LF_Actual_Permissions list SHALL produce zero Corrective_Operations (identity property).
4. FOR ALL valid LFPermissionOperation lists, computing drift and then applying the Corrective_Operations to the actual state SHALL produce a state equal to the desired state (convergence property).
