# Requirements Document

## Introduction

The system currently supports a single Apache Ranger service type (LakeFormation) with a hardcoded `RangerPlugin`, `RangerServiceAdapter`, and service definition. This feature introduces multi-service Ranger plugin support, enabling the Conversion Server to simultaneously manage policies from multiple Ranger service types (Apache Hive, Presto, Trino) alongside the existing LakeFormation service. Each service type has its own Ranger service definition, access type mappings, and resource hierarchy, but all contribute Cedar policies to a shared Cedar policy repository that drives Lake Formation permissions. The sync service must be configurable to select which Ranger services to enable, their Ranger service instance names, and their service definitions.

## Glossary

- **Conversion_Server**: The main application (`ConversionServerMain`) that runs the sync loop, converting Ranger policies to Cedar policies and applying them as Lake Formation permissions.
- **Ranger_Admin**: The Apache Ranger Admin server that manages policies and service definitions.
- **Ranger_Plugin**: A `RangerBasePlugin` instance that registers with Ranger Admin for a specific service type and receives policy updates.
- **Service_Type**: A Ranger service type identifier (e.g., "lakeformation", "hive", "presto", "trino") that determines the service definition, resource hierarchy, and access type mappings.
- **Service_Definition**: A JSON document registered with Ranger Admin that describes a service type's resources, access types, and configuration parameters.
- **Service_Instance_Name**: The name of a specific Ranger service instance within Ranger Admin (e.g., "my_hive_service"), used by the plugin to fetch policies for that instance.
- **SourcePolicyAdapter**: The interface that maps a service type's access types and resource hierarchy into Cedar actions and entity references.
- **Adapter_Registry**: The `Map<String, SourcePolicyAdapter>` in `RangerToCedarConverter` that dispatches conversion logic to the correct adapter based on service type.
- **Cedar_Policy_Repository**: The shared `CedarPolicySet` that aggregates Cedar policies from all configured Ranger service types before applying them to Lake Formation.
- **Base_Ranger_Service**: An abstract base class that extracts common Ranger plugin lifecycle, configuration, and adapter registration logic shared across all service types.
- **Service_Configuration**: A YAML configuration block that defines which Ranger service types are enabled, their service instance names, and service-definition file paths.
- **Sync_Service**: The `SyncService` component that orchestrates policy fetching, conversion, diffing, and application to Lake Formation.
- **GDC_Catalog_Name**: The name of the Presto or Trino catalog that maps to the AWS Glue Data Catalog. Policies targeting any other catalog are ignored during conversion. Configured per service instance in the YAML configuration.

## Requirements

### Requirement 1: Abstract Base Ranger Service

**User Story:** As a developer, I want a base abstraction for Ranger service integrations, so that common plugin lifecycle, configuration loading, and adapter registration logic is shared and not duplicated across service types.

#### Acceptance Criteria

1. THE Base_Ranger_Service SHALL define an abstract class that encapsulates Ranger_Plugin initialization, policy fetching, and SourcePolicyAdapter registration for a given Service_Type.
2. THE Base_Ranger_Service SHALL require subclasses to provide the Service_Type identifier, the Service_Instance_Name, and the path to the Service_Definition JSON file.
3. THE Base_Ranger_Service SHALL provide a concrete `init()` method that initializes the Ranger_Plugin with the subclass-provided Service_Type and Service_Instance_Name.
4. THE Base_Ranger_Service SHALL provide a concrete `getLatestPolicies()` method that returns the latest `ServicePolicies` from the underlying Ranger_Plugin.
5. THE Base_Ranger_Service SHALL provide an abstract method for subclasses to supply their SourcePolicyAdapter implementation.

### Requirement 2: LakeFormation Service Implementation

**User Story:** As a developer, I want the existing LakeFormation integration refactored to extend the Base_Ranger_Service, so that it follows the same pattern as new service types without breaking existing functionality.

#### Acceptance Criteria

1. THE LakeFormation service implementation SHALL extend Base_Ranger_Service and provide "lakeformation" as the Service_Type.
2. THE LakeFormation service implementation SHALL reuse the existing `RangerServiceAdapter` as its SourcePolicyAdapter.
3. THE LakeFormation service implementation SHALL reuse the existing `ranger-servicedef-lakeformation.json` as its Service_Definition.
4. WHEN the LakeFormation service is configured, THE Conversion_Server SHALL produce identical Cedar policies and Lake Formation permissions as the current single-service implementation.

### Requirement 3: Hive Service Implementation

**User Story:** As a developer, I want an Apache Hive Ranger service integration, so that Hive policies in Ranger Admin are converted to Cedar policies and applied as Lake Formation permissions.

#### Acceptance Criteria

1. THE Hive service implementation SHALL extend Base_Ranger_Service and provide "hive" as the Service_Type.
2. THE Hive service implementation SHALL provide a SourcePolicyAdapter that maps Hive access types (select, update, create, drop, alter, index, lock, all, read, write) to Cedar actions compatible with the DataCatalog schema.
3. THE Hive service implementation SHALL map Hive resource hierarchy (database, table, column) to DataCatalog Cedar entity references using the same ARN format as the LakeFormation adapter.
4. THE Hive service implementation SHALL include a Hive-specific Service_Definition JSON file that defines the Hive resource hierarchy and access types for Ranger Admin.
5. IF a Hive access type has no valid mapping to a Cedar action, THEN THE Hive SourcePolicyAdapter SHALL return an empty set and log the unmapped access type.

### Requirement 4: Presto Service Implementation

**User Story:** As a developer, I want a Presto Ranger service integration, so that Presto policies targeting the Glue Data Catalog are converted to Cedar policies and applied as Lake Formation permissions, while policies for non-GDC catalogs are ignored.

#### Acceptance Criteria

1. THE Presto service implementation SHALL extend Base_Ranger_Service and provide "presto" as the Service_Type.
2. THE Presto service implementation SHALL provide a SourcePolicyAdapter that maps Presto access types (select, insert, delete, create, drop, alter, use, show, grant, revoke) to Cedar actions compatible with the DataCatalog schema.
3. THE Presto service implementation SHALL map Presto resource hierarchy (catalog, schema, table, column) to DataCatalog Cedar entity references, treating Presto "schema" as the equivalent of "database" in the DataCatalog model.
4. THE Presto service implementation SHALL include a Presto-specific Service_Definition JSON file that defines the Presto resource hierarchy and access types for Ranger Admin.
5. IF a Presto access type has no valid mapping to a Cedar action, THEN THE Presto SourcePolicyAdapter SHALL return an empty set and log the unmapped access type.
6. THE Presto service configuration SHALL require a `gdcCatalogName` property that identifies which Presto catalog maps to the Glue Data Catalog.
7. WHEN converting Presto policies, THE Presto SourcePolicyAdapter SHALL only process policy resources whose catalog value matches the configured GDC_Catalog_Name.
8. IF a Presto policy targets a catalog that does not match the configured GDC_Catalog_Name, THEN THE Presto SourcePolicyAdapter SHALL skip that policy and log a debug message indicating the catalog was ignored.

### Requirement 5: Trino Service Implementation

**User Story:** As a developer, I want a Trino Ranger service integration, so that Trino policies targeting the Glue Data Catalog are converted to Cedar policies and applied as Lake Formation permissions, while policies for non-GDC catalogs are ignored.

#### Acceptance Criteria

1. THE Trino service implementation SHALL extend Base_Ranger_Service and provide "trino" as the Service_Type.
2. THE Trino service implementation SHALL provide a SourcePolicyAdapter that maps Trino access types (select, insert, delete, create, drop, alter, use, show, grant, revoke) to Cedar actions compatible with the DataCatalog schema.
3. THE Trino service implementation SHALL map Trino resource hierarchy (catalog, schema, table, column) to DataCatalog Cedar entity references, treating Trino "schema" as the equivalent of "database" in the DataCatalog model.
4. THE Trino service implementation SHALL include a Trino-specific Service_Definition JSON file that defines the Trino resource hierarchy and access types for Ranger Admin.
5. IF a Trino access type has no valid mapping to a Cedar action, THEN THE Trino SourcePolicyAdapter SHALL return an empty set and log the unmapped access type.
6. THE Trino service configuration SHALL require a `gdcCatalogName` property that identifies which Trino catalog maps to the Glue Data Catalog.
7. WHEN converting Trino policies, THE Trino SourcePolicyAdapter SHALL only process policy resources whose catalog value matches the configured GDC_Catalog_Name.
8. IF a Trino policy targets a catalog that does not match the configured GDC_Catalog_Name, THEN THE Trino SourcePolicyAdapter SHALL skip that policy and log a debug message indicating the catalog was ignored.

### Requirement 6: Multi-Service Configuration

**User Story:** As an operator, I want to configure which Ranger service types are enabled and their service instance names, so that I can control which policy sources contribute to the Cedar policy repository without code changes.

#### Acceptance Criteria

1. THE SyncConfig SHALL support a `rangerServices` list in the YAML configuration, where each entry specifies a Service_Type, a Service_Instance_Name, an optional path to a custom Service_Definition JSON file, and an optional `gdcCatalogName` for catalog-aware service types.
2. WHEN the `rangerServices` list is omitted or empty, THE Conversion_Server SHALL default to enabling only the LakeFormation service with the existing behavior for backward compatibility.
3. WHEN the `rangerServices` list contains one or more entries, THE Conversion_Server SHALL initialize a Ranger_Plugin and SourcePolicyAdapter for each configured service.
4. THE ConfigValidator SHALL reject configurations where the `rangerServices` list contains duplicate Service_Type and Service_Instance_Name combinations.
5. THE ConfigValidator SHALL reject configurations where a `rangerServices` entry specifies an unknown Service_Type.
6. THE ConfigValidator SHALL reject configurations where a `rangerServices` entry is missing a required Service_Instance_Name.
7. THE ConfigValidator SHALL reject configurations where a Presto or Trino `rangerServices` entry is missing the required `gdcCatalogName` property.

### Requirement 7: Multi-Plugin Sync Orchestration

**User Story:** As an operator, I want the sync service to fetch and merge policies from all configured Ranger plugins in each sync cycle, so that all service types contribute to a unified Cedar policy set.

#### Acceptance Criteria

1. WHEN a sync cycle executes, THE Sync_Service SHALL fetch the latest policies from each configured Ranger_Plugin.
2. THE Sync_Service SHALL pass each service's policies through the RangerToCedarConverter, which dispatches to the correct SourcePolicyAdapter via the Adapter_Registry.
3. THE Sync_Service SHALL merge the Cedar policies from all configured services into a single Cedar_Policy_Repository before computing the diff and applying changes to Lake Formation.
4. THE Sync_Service SHALL NOT proceed with the first sync cycle (diff and apply to Lake Formation) until every configured Ranger_Plugin has completed at least one successful policy fetch, even if the fetched policy set is empty.
5. IF a Ranger_Plugin fails to fetch policies during a subsequent sync cycle (after the initial successful fetch), THEN THE Sync_Service SHALL log the error and use that plugin's last successfully fetched policies for the current cycle's Cedar policy merge, rather than omitting the service's policies.
6. THE Sync_Service SHALL retain the last successfully fetched policies for each Ranger_Plugin in memory, so that transient Ranger Admin failures do not cause unintended policy revocations on Lake Formation.
7. THE Sync_Service SHALL include the Service_Type in all log messages and metrics related to policy fetching and conversion, so that operators can identify which service produced an error or gap.

### Requirement 8: Multi-Service Definition Installation

**User Story:** As an operator, I want the service definition installer to register all configured service definitions with Ranger Admin, so that each service type is available for policy management.

#### Acceptance Criteria

1. WHEN the ServiceDefInstallerMain runs in REST mode, THE ServiceDefInstallerMain SHALL install the Service_Definition for each Service_Type listed in the `rangerServices` configuration.
2. WHEN the ServiceDefInstallerMain runs in file mode, THE ServiceDefInstallerMain SHALL copy each configured Service_Definition to the appropriate Ranger Admin plugin directory.
3. IF a Service_Definition installation fails for one service, THEN THE ServiceDefInstallerMain SHALL log the error and continue installing the remaining service definitions.
4. THE ServiceDefInstallerMain SHALL load the bundled Service_Definition for a Service_Type when no custom path is specified in the configuration.

### Requirement 9: Cedar Policy Namespace Isolation

**User Story:** As a developer, I want Cedar policies from different Ranger service types to be distinguishable within the shared Cedar policy repository, so that policy diffs and auditing can trace policies back to their source service.

#### Acceptance Criteria

1. THE RangerToCedarConverter SHALL include the Service_Type identifier (not the Service_Instance_Name) in the Cedar policy ID for each generated policy statement (e.g., "hive_policy_42_item_0" instead of "policy_42_item_0").
2. WHEN computing the policy diff, THE Sync_Service SHALL treat policies from different Service_Types as independent, so that adding a new service does not trigger revocations of policies from other services.
3. THE Sync_Service SHALL preserve the per-service policy version tracking, so that each Ranger_Plugin's policy version is tracked independently.

### Requirement 10: Multi-Service Checkpoint Persistence

**User Story:** As an operator, I want the checkpoint store to track per-service policy versions, so that after a restart the system can detect which services have new policies since the last checkpoint.

#### Acceptance Criteria

1. THE CheckpointStore SHALL persist a map of Service_Type to Ranger policy version (instead of a single policy version) alongside the merged Cedar policy text.
2. WHEN restoring from a checkpoint on startup, THE Sync_Service SHALL use the per-service policy version map to determine whether each Ranger_Plugin has new policies since the last persisted state.
3. THE CheckpointStore SHALL remain backward-compatible with existing single-version checkpoint files by treating a legacy checkpoint as having a single "lakeformation" entry in the version map.

### Requirement 11: Multi-Service Wildcard Refresh

**User Story:** As an operator, I want wildcard pattern expansion against the Glue Data Catalog to apply to policies from all configured Ranger service types, so that wildcard patterns in Hive, Presto, and Trino policies are resolved the same way as LakeFormation policies.

#### Acceptance Criteria

1. WHEN the WildcardRefreshScheduler triggers a refresh cycle, THE Sync_Service SHALL re-evaluate wildcard patterns from all configured Ranger service types against the Glue Data Catalog.
2. THE WildcardRefreshScheduler SHALL include the Service_Type in log messages when reporting wildcard expansion results or errors.

### Requirement 12: Multi-Service Audit Logging

**User Story:** As an operator, I want audit log entries to include the originating Ranger service type, so that I can trace each grant or revoke operation back to the service that produced it.

#### Acceptance Criteria

1. THE Sync_Service SHALL include the Service_Type in each audit log entry for grant and revoke operations.
2. THE audit log entry format SHALL include a field identifying the source Service_Type alongside the existing policy ID, resource, principal, and permission fields.
