# Requirements Document

## Introduction

This document specifies the requirements for a Java-based utility that bridges Apache Ranger access control policies to AWS Lake Formation permissions. The utility consists of three core components: a bulk policy extractor that pulls existing Ranger policies via REST API, a policy converter that transforms Ranger policy structures into Lake Formation grant/revoke operations, and a RangerPlugin-based synchronization service that receives real-time policy updates from Ranger Admin and commits them to Lake Formation. The utility also includes a custom Lake Formation service definition installable into Ranger Admin, modeled after Privacera's approach. The project targets Java 8 (JDK 1.8+) and uses Maven for dependency management and building.

## Glossary

- **Ranger_Admin**: The Apache Ranger administration server that manages policies, service definitions, and plugin registrations.
- **Ranger_Policy**: A JSON-structured access control rule in Apache Ranger that defines permissions on resources for users, groups, and roles.
- **Ranger_Service_Definition**: A JSON descriptor registered with Ranger_Admin that defines a new service type, its resources, access types, and configuration properties.
- **Ranger_Plugin**: A Java component extending RangerBasePlugin that periodically retrieves policies from Ranger_Admin and enforces authorization decisions.
- **Resource_Lookup_Service**: A Java class extending RangerBaseService that enables Ranger_Admin to browse and validate resources for a service type.
- **Lake_Formation**: AWS Lake Formation, a service that manages fine-grained permissions on AWS Glue Data Catalog resources using a grant/revoke model.
- **LF_Permission**: A Lake Formation permission entry that grants or revokes a specific action (SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, CREATE_DATABASE, CREATE_TABLE, DATA_LOCATION_ACCESS) on a Data Catalog resource to a principal.
- **LF_Data_Filter**: A Lake Formation row-level security construct that restricts row access on a table using a filter expression.
- **LF_Tag**: A Lake Formation tag (LF-Tag) used for tag-based access control within Lake Formation, structurally different from Ranger tags.
- **Policy_Converter**: The component that transforms Ranger_Policy objects into LF_Permission grant/revoke operations.
- **Bulk_Extractor**: The component that retrieves all Ranger policies from Ranger_Admin via the REST API export endpoint.
- **Sync_Service**: The RangerPlugin-based component that receives incremental policy updates from Ranger_Admin and applies them to Lake_Formation.
- **Unsupported_Policy**: A Ranger_Policy that uses features with no equivalent in Lake_Formation (e.g., data masking, deny policies, time-based conditions).
- **Policy_Gap_Report**: A structured report documenting Unsupported_Policy instances encountered during conversion, including the policy identifier, the unsupported feature, and a recommended action.
- **Glue_Data_Catalog**: The AWS Glue Data Catalog that stores metadata for databases, tables, and columns managed by Lake_Formation.
- **Service_Definition_Installer**: The component that registers the custom Lake Formation service definition with Ranger_Admin either via REST API or file-based installation.

## Requirements

### Requirement 1: Bulk Policy Extraction

**User Story:** As a data platform engineer, I want to extract all existing Apache Ranger policies in bulk, so that I can migrate them to AWS Lake Formation.

#### Acceptance Criteria

1. WHEN the Bulk_Extractor is invoked with valid Ranger_Admin connection parameters (URL, credentials), THE Bulk_Extractor SHALL retrieve all policies from Ranger_Admin using the /service/plugins/policies/exportJson REST endpoint and return them as a collection of Ranger_Policy objects.
2. WHEN the Bulk_Extractor retrieves policies, THE Bulk_Extractor SHALL support filtering by service name so that only policies for specified Ranger services are extracted.
3. WHEN the Bulk_Extractor retrieves policies, THE Bulk_Extractor SHALL preserve the complete policy structure including resource definitions, policy items, deny policy items, row filter policy items, data mask policy items, validity schedules, and policy conditions.
4. IF the Ranger_Admin REST endpoint returns an authentication error, THEN THE Bulk_Extractor SHALL report the error with the HTTP status code and a descriptive message without terminating the process.
5. IF the Ranger_Admin REST endpoint is unreachable or returns a server error, THEN THE Bulk_Extractor SHALL retry the request up to a configurable number of times with exponential backoff before reporting failure.
6. WHEN the Bulk_Extractor completes extraction, THE Bulk_Extractor SHALL log the total number of policies extracted, grouped by service type.

### Requirement 2: Policy Conversion from Ranger to Lake Formation

**User Story:** As a data platform engineer, I want to convert Apache Ranger policies into AWS Lake Formation permissions, so that I can enforce equivalent access controls in Lake Formation.

#### Acceptance Criteria

1. WHEN the Policy_Converter receives a Ranger_Policy with resource-based access policies on databases, tables, or columns, THE Policy_Converter SHALL produce corresponding LF_Permission grant operations mapping Ranger access types to Lake Formation permissions (SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, CREATE_DATABASE, CREATE_TABLE, DATA_LOCATION_ACCESS).
2. WHEN the Policy_Converter receives a Ranger_Policy with row-level filter policy items, THE Policy_Converter SHALL produce corresponding LF_Data_Filter definitions that enforce equivalent row-level restrictions in Lake_Formation.
3. WHEN the Policy_Converter receives a Ranger_Policy that grants permissions to users, groups, or roles, THE Policy_Converter SHALL map those principals to the corresponding AWS IAM principals (IAM users, IAM roles, or SAML/federated identities) using a configurable principal mapping.
4. WHEN the Policy_Converter receives a Ranger_Policy containing an Unsupported_Policy feature, THE Policy_Converter SHALL skip the unsupported portion, convert all supported portions of the policy, and add an entry to the Policy_Gap_Report.
5. WHEN the Policy_Converter processes a Ranger_Policy with multiple policy items (allow, deny, allow-exceptions, deny-exceptions), THE Policy_Converter SHALL convert only the allow policy items into LF_Permission grants and record deny items and exception items in the Policy_Gap_Report.
6. THE Policy_Converter SHALL produce a deterministic output such that converting the same Ranger_Policy twice yields identical LF_Permission operations.
7. WHEN the Policy_Converter encounters a Ranger_Policy with wildcard resource patterns (e.g., "db_*" or "*"), THE Policy_Converter SHALL expand wildcards against the Glue_Data_Catalog to produce explicit LF_Permission entries for each matching resource, since Lake_Formation does not support wildcard grants.

### Requirement 3: Policy Gap Documentation and Reporting

**User Story:** As a data platform engineer, I want to know which Ranger policies cannot be fully represented in Lake Formation, so that I can plan compensating controls or accept the risk.

#### Acceptance Criteria

1. WHEN the Policy_Converter encounters a data masking policy item (RangerDataMaskPolicyItem), THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID, resource path, masking type, and a note that Lake_Formation does not support native data masking.
2. WHEN the Policy_Converter encounters a tag-based policy, THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID, tag name, and a note that Ranger tags are structurally different from LF-Tags and require manual mapping.
3. WHEN the Policy_Converter encounters a deny policy item or deny-exception item, THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID, affected principals, and a note that Lake_Formation uses a grant-only model.
4. WHEN the Policy_Converter encounters a validity schedule (time-bound policy), THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID, schedule details, and a note that Lake_Formation does not support temporal policy constraints.
5. WHEN the Policy_Converter encounters a custom policy condition (IP-based, geo-based, or other custom conditions), THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID, condition type, and a note that Lake_Formation does not support conditional policies.
6. WHEN the Policy_Converter encounters a security zone reference, THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID, zone name, and a note that Lake_Formation has no equivalent concept.
7. WHEN the Policy_Converter encounters delegated admin permissions in a Ranger_Policy, THE Policy_Converter SHALL record it in the Policy_Gap_Report with the policy ID and a note that Lake_Formation uses a different admin delegation model.
8. THE Policy_Gap_Report SHALL be serializable to JSON format and include a summary section with counts of each unsupported feature type encountered.
9. WHEN the Policy_Gap_Report is serialized to JSON and then deserialized, THE Policy_Gap_Report SHALL produce an equivalent object (round-trip consistency).

### Requirement 4: RangerPlugin-Based Real-Time Synchronization

**User Story:** As a data platform engineer, I want a RangerPlugin that receives policy updates from Ranger Admin in real time and applies them to Lake Formation, so that Lake Formation permissions stay in sync with Ranger policies without manual intervention.

#### Acceptance Criteria

1. WHEN the Sync_Service starts, THE Sync_Service SHALL register with Ranger_Admin as a RangerPlugin instance using the custom Lake Formation service definition and begin receiving policy updates via the RangerBasePlugin policy refresh mechanism.
2. WHILE the Sync_Service is running, THE Sync_Service SHALL periodically receive updated policies from Ranger_Admin and apply the corresponding LF_Permission grant and revoke operations to Lake_Formation.
3. WHEN the Sync_Service receives a policy update, THE Sync_Service SHALL compute the difference between the previous policy state and the new policy state and apply only the incremental changes (new grants, revoked permissions) to Lake_Formation.
4. IF the Sync_Service encounters a ConcurrentModificationException from the Lake Formation API during a permission update, THEN THE Sync_Service SHALL retry the operation with exponential backoff up to a configurable maximum number of retries. Note: Throttling retries are handled internally by the AWS SDK v2 retry policy and do not require explicit implementation.
5. WHEN the Sync_Service applies permission changes to Lake_Formation, THE Sync_Service SHALL log each grant and revoke operation with the policy ID, resource, principal, and permission type for audit purposes.
6. IF the Sync_Service loses connectivity to Ranger_Admin, THEN THE Sync_Service SHALL continue operating with the last known policy set and resume synchronization when connectivity is restored.
7. WHEN the Sync_Service processes a policy containing Unsupported_Policy features, THE Sync_Service SHALL convert supported portions and add unsupported portions to the Policy_Gap_Report, consistent with Policy_Converter behavior.

### Requirement 5: Custom Lake Formation Service Definition for Ranger Admin

**User Story:** As a data platform engineer, I want a custom Lake Formation service definition installed in Ranger Admin, so that I can manage Lake Formation permissions through the Ranger Admin UI and policy framework.

#### Acceptance Criteria

1. THE Service_Definition_Installer SHALL provide a JSON service definition file that defines a "lakeformation" service type in Ranger_Admin with resource types for database, table, and column matching the Glue_Data_Catalog hierarchy.
2. THE Service_Definition_Installer SHALL define access types in the service definition that correspond to Lake Formation permissions: SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, CREATE_DATABASE, CREATE_TABLE, and DATA_LOCATION_ACCESS.
3. THE Service_Definition_Installer SHALL define configuration properties in the service definition for AWS region, AWS credentials (access key, secret key, or IAM role ARN), and Glue Data Catalog ID.
4. THE Service_Definition_Installer SHALL support registering the service definition with Ranger_Admin via the /service/plugins/definitions REST endpoint.
5. THE Service_Definition_Installer SHALL support file-based installation by placing the service definition JSON in the ranger-admin/ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation/ directory.
6. THE Resource_Lookup_Service SHALL extend RangerBaseService and implement resource lookup by querying the Glue_Data_Catalog to list databases, tables, and columns for resource browsing in the Ranger_Admin UI.
7. WHEN the Resource_Lookup_Service queries the Glue_Data_Catalog, THE Resource_Lookup_Service SHALL use the AWS credentials configured in the service definition properties.

### Requirement 6: Principal Mapping

**User Story:** As a data platform engineer, I want to configure how Ranger users, groups, and roles map to AWS IAM principals, so that permissions are granted to the correct identities in Lake Formation.

#### Acceptance Criteria

1. THE Policy_Converter SHALL accept a configurable principal mapping that maps Ranger user names to AWS IAM principal ARNs (IAM users, IAM roles, or SAML federated identity ARNs).
2. THE Policy_Converter SHALL accept a configurable principal mapping that maps Ranger group names to AWS IAM role ARNs or IAM group paths.
3. IF the Policy_Converter encounters a Ranger principal (user, group, or role) with no configured mapping, THEN THE Policy_Converter SHALL log a warning with the unmapped principal name and skip the permission entry for that principal.
4. THE principal mapping configuration SHALL be loadable from a JSON or properties file.
5. WHEN the principal mapping is loaded from a file and then serialized back, THE principal mapping SHALL produce an equivalent configuration (round-trip consistency).

### Requirement 7: Build Toolchain and Java Version

**User Story:** As a developer, I want the project to use the correct Java version and build tools, so that the utility is compatible with Apache Ranger and follows the project's build conventions.

#### Acceptance Criteria

1. THE project SHALL target Java 8 (JDK 1.8+) to maintain compatibility with Apache Ranger versions 2.6, 2.7, and 2.8.
2. THE project SHALL use Maven for dependency management, declaring Apache Ranger dependencies from the org.apache.ranger group on Maven Central.
3. THE project SHALL use Maven as the build system for compiling, testing, and packaging the utility.
4. THE project SHALL produce a deployable artifact (JAR or set of JARs) that can be installed into the Ranger Admin plugin directory at ranger-admin/ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation/.
5. THE project SHALL declare AWS SDK dependencies for Lake Formation, Glue, IAM, and STS APIs.

### Requirement 8: Error Handling and Resilience

**User Story:** As a data platform engineer, I want the utility to handle errors gracefully, so that transient failures do not cause data loss or inconsistent permission states.

#### Acceptance Criteria

1. IF the Policy_Converter encounters an invalid or malformed Ranger_Policy (missing required fields, unrecognized resource types), THEN THE Policy_Converter SHALL log the error with the policy ID and skip the policy without terminating the conversion batch.
2. IF the Bulk_Extractor or Sync_Service encounters a network timeout, THEN THE Bulk_Extractor or Sync_Service SHALL retry with configurable exponential backoff and a maximum retry count.
3. WHEN the Sync_Service applies a batch of permission changes to Lake_Formation, THE Sync_Service SHALL apply changes atomically per policy: either all LF_Permission operations for a single Ranger_Policy succeed, or none are applied and the failure is logged.
4. IF the Sync_Service fails to apply a permission change after exhausting retries, THEN THE Sync_Service SHALL record the failed operation in a dead-letter log with the policy ID, operation details, and error message for manual remediation.
5. WHEN any component logs an error, THE component SHALL include a timestamp, component name, severity level, and sufficient context (policy ID, resource path, principal) to diagnose the issue.

### Requirement 9: Configuration Management

**User Story:** As a data platform engineer, I want to configure the utility through external configuration files, so that I can adapt it to different environments without code changes.

#### Acceptance Criteria

1. THE utility SHALL load configuration from an external YAML or properties file specifying Ranger_Admin connection parameters (URL, username, password or Kerberos keytab), AWS credentials or IAM role configuration, target AWS region, and Glue Data Catalog ID.
2. THE utility SHALL support overriding configuration values via environment variables.
3. THE utility SHALL validate all required configuration parameters at startup and report missing or invalid parameters with descriptive error messages before attempting any operations.
4. WHEN configuration is loaded, THE utility SHALL mask sensitive values (passwords, secret keys) in log output.
