# Requirements Document

## Introduction

This feature introduces Cedar as an intermediate policy representation in the Ranger-to-LakeFormation sync pipeline. The current pipeline converts Ranger policies directly to LakeFormation permission operations. The new pipeline inserts a Cedar policy layer between them:

```
Ranger Policies → RangerToCedarConverter → Cedar Policies → CedarToLFConverter → LakeFormationClient
```

Cedar's partial evaluation capability is used to materialize effective permissions per principal, properly handling deny/except semantics (which the current system drops). The Cedar intermediate representation also enables future support for multiple policy sources and targets beyond Ranger and LakeFormation.

The architecture is designed to support multiple policy sources through a pluggable source adapter pattern. Different Ranger service types (LakeFormation, Trino, Presto, Hive, etc.) have different access type vocabularies and resource hierarchies, so each source requires its own adapter to map into the generic Cedar schema. For this initial implementation, only the Ranger LakeFormation service definition adapter will be built, but the interfaces will support adding other Ranger service types and non-Ranger policy sources in the future.

## Glossary

- **Cedar_Policy_Set**: An ordered collection of Cedar `permit` and `forbid` policy statements representing the authorization rules for a set of resources and principals.
- **SourcePolicyAdapter**: An interface that defines how a specific policy source (e.g., Ranger LakeFormation, Ranger Trino, Ranger Hive) maps its access types and resource hierarchy into Cedar actions and `DataCatalog::` entity types.
- **RangerToCedarConverter**: The component that translates Apache Ranger policy objects into Cedar policy statements, delegating service-type-specific mapping to a SourcePolicyAdapter.
- **CedarToLFConverter**: The component that uses Cedar partial evaluation to materialize effective permissions and converts them into LakeFormation permission operations.
- **Cedar_Schema**: A Cedar schema definition that describes the entity types, actions, and resource hierarchy for a generic data catalog domain (Database, Table, Column) using the `DataCatalog::` namespace, decoupled from any specific target system.
- **Partial_Evaluator**: The Cedar engine component that evaluates a policy set against a specific principal and enumerates the effective grants, resolving permit/forbid interactions.
- **Gap_Reporter**: The existing component that records unsupported Ranger policy features that cannot be represented in Cedar or LakeFormation.
- **Cedar_Policy_Store**: An in-memory collection of Cedar policy statements produced by the RangerToCedarConverter, serving as input to the CedarToLFConverter.
- **Policy_Sync_Pipeline**: The end-to-end flow from Ranger policy ingestion through Cedar conversion, partial evaluation, and LakeFormation grant/revoke application.
- **Cedar_Java_SDK**: The cedar-java library (v4.2.3+, JDK 17+) providing policy parsing, validation, schema support, and partial evaluation via JNI (Rust FFI).

## Requirements

### Requirement 1: Cedar Schema Definition

**User Story:** As a developer, I want a Cedar schema that models a generic data catalog resource hierarchy using target-agnostic entity types, so that Cedar policies are decoupled from any specific target system and can be reused across multiple policy targets.

#### Acceptance Criteria

1. THE Cedar_Schema SHALL define entity types for `DataCatalog::Catalog`, `DataCatalog::Database`, `DataCatalog::Table`, and `DataCatalog::Column` with a parent-child containment hierarchy (Catalog contains Databases, Database contains Tables, Table contains Columns).
2. THE Cedar_Schema SHALL define action types corresponding to common data catalog permissions: `Action::"SELECT"`, `Action::"INSERT"`, `Action::"UPDATE"`, `Action::"DELETE"`, `Action::"DESCRIBE"`, `Action::"ALTER"`, `Action::"DROP"`, `Action::"CREATE_DATABASE"`, `Action::"CREATE_TABLE"`, and `Action::"DATA_LOCATION_ACCESS"`.
3. THE Cedar_Schema SHALL be extensible to include additional action types from other policy sources (e.g., `Action::"GRANT"`, `Action::"REVOKE"` from Trino) without requiring changes to existing target converters.
4. THE Cedar_Schema SHALL define a principal entity type `DataCatalog::Principal` representing an identity (e.g., an IAM ARN or other identity provider identifier).
5. WHEN a Cedar policy statement references an entity type or action not defined in the Cedar_Schema, THE Cedar_Java_SDK validation SHALL return a schema validation error.
6. THE Cedar_Schema SHALL define a `DataCatalog::DataLocation` entity type for storage location resources (e.g., S3 paths).
7. WHEN a target converter (e.g., CedarToLFConverter) encounters a Cedar policy with an action that the target system does not support, THE target converter SHALL skip the policy and record a gap entry with GapType `UNSUPPORTED_ACTION` in the Gap_Reporter, rather than failing.

### Requirement 2: Cedar Entity Identifier Conventions

**User Story:** As a developer, I want Cedar entity identifiers to use AWS ARNs for AWS-sourced resources and a structured URN scheme for non-AWS resources, so that resource origin is self-describing and target converters can reliably parse and map identifiers.

#### Acceptance Criteria

1. WHEN the source policy originates from an AWS service (e.g., Ranger LakeFormation service definition), THE SourcePolicyAdapter SHALL produce Cedar entity identifiers using AWS Glue Data Catalog ARNs for resources (e.g., `DataCatalog::Database::"arn:aws:glue:us-east-1:123456789012:database/analytics_db"`).
2. WHEN the source policy originates from an AWS service, THE SourcePolicyAdapter SHALL produce Cedar entity identifiers using AWS Glue ARN format for table resources (e.g., `DataCatalog::Table::"arn:aws:glue:us-east-1:123456789012:table/analytics_db/orders"`).
3. WHEN the source policy originates from an AWS service, THE SourcePolicyAdapter SHALL produce Cedar entity identifiers using AWS Glue ARN format for column resources (e.g., `DataCatalog::Column::"arn:aws:glue:us-east-1:123456789012:column/analytics_db/orders/email"`).
4. WHEN the source policy originates from an AWS service, THE SourcePolicyAdapter SHALL produce Cedar entity identifiers using S3 ARNs for data location resources (e.g., `DataCatalog::DataLocation::"arn:aws:s3:::my-bucket/data/path"`).
5. WHEN the source policy originates from an AWS service, THE SourcePolicyAdapter SHALL produce Cedar principal identifiers using IAM ARNs (e.g., `DataCatalog::Principal::"arn:aws:iam::123456789012:role/AnalystRole"`).
6. WHEN the source policy originates from a non-AWS system (future), THE SourcePolicyAdapter SHALL produce Cedar entity identifiers using a URN scheme that encodes the source system and resource path (e.g., `DataCatalog::Database::"urn:databricks:unity:workspace-123:catalog/my_catalog/schema/analytics"`).
7. THE CedarToLFConverter SHALL parse AWS ARN-formatted entity identifiers to extract region, account ID, database name, table name, and column name for constructing LakeFormation SDK resource objects.
8. WHEN the CedarToLFConverter encounters a non-ARN entity identifier (e.g., a URN from a non-AWS source), THE CedarToLFConverter SHALL attempt to resolve it using a configurable resource mapping, and record a gap entry with GapType `UNMAPPED_RESOURCE` if no mapping exists.

### Requirement 3: Pluggable Source Policy Adapter

**User Story:** As a developer, I want a pluggable adapter interface for mapping different policy sources into Cedar, so that the system can support multiple Ranger service types (Trino, Hive, Presto, etc.) and non-Ranger sources in the future without modifying the core conversion logic.

#### Acceptance Criteria

1. THE system SHALL define a SourcePolicyAdapter interface that encapsulates service-type-specific mapping of access types to Cedar actions and resource definitions to `DataCatalog::` entity types.
2. THE RangerToCedarConverter SHALL delegate access type mapping and resource hierarchy mapping to the SourcePolicyAdapter for the incoming policy's service type.
3. THE system SHALL provide a concrete SourcePolicyAdapter implementation for the Ranger LakeFormation service definition that maps LakeFormation access types (select, update, create, drop, alter, all, datalocation, etc.) to Cedar actions using the same mapping rules as the existing AccessTypeMapper.
4. WHEN the RangerToCedarConverter receives a policy with a service type for which no SourcePolicyAdapter is registered, THE RangerToCedarConverter SHALL skip the policy and record a gap entry with GapType `UNSUPPORTED_SERVICE_TYPE` in the Gap_Reporter.
5. THE SourcePolicyAdapter interface SHALL be designed to support non-Ranger policy sources in the future (e.g., LakeFormation ListPermissions API, OPA policies) without requiring changes to the core Cedar conversion or target converter logic.

### Requirement 4: Ranger-to-Cedar Conversion (Allow Policies)

**User Story:** As a developer, I want Ranger allow policies converted to Cedar permit statements, so that the Cedar intermediate layer faithfully represents Ranger access grants.

#### Acceptance Criteria

1. WHEN the RangerToCedarConverter receives a Ranger access policy with policyType=0 and allow policy items, THE RangerToCedarConverter SHALL produce one Cedar `permit` statement per (principal, resource, action) combination.
2. WHEN a Ranger policy item grants access to multiple users, groups, or roles, THE RangerToCedarConverter SHALL produce separate Cedar `permit` statements for each resolved IAM principal ARN.
3. WHEN a Ranger policy specifies database-level resources, THE RangerToCedarConverter SHALL produce Cedar `permit` statements with the resource scoped to `DataCatalog::Database`.
4. WHEN a Ranger policy specifies table-level resources, THE RangerToCedarConverter SHALL produce Cedar `permit` statements with the resource scoped to `DataCatalog::Table` and the parent set to the containing `DataCatalog::Database`.
5. WHEN a Ranger policy specifies column-level resources, THE RangerToCedarConverter SHALL produce Cedar `permit` statements with the resource scoped to `DataCatalog::Column` and the parent chain set to the containing Table and Database.
6. WHEN a Ranger policy contains wildcard patterns in resource names, THE RangerToCedarConverter SHALL expand wildcards via the CatalogResolver before generating Cedar statements.
7. THE RangerToCedarConverter SHALL map Ranger access types to Cedar actions by delegating to the registered SourcePolicyAdapter for the policy's service type.

### Requirement 5: Ranger-to-Cedar Conversion (Deny Policies)

**User Story:** As a developer, I want Ranger deny policies converted to Cedar forbid statements, so that deny semantics are properly represented and resolved through partial evaluation.

#### Acceptance Criteria

1. WHEN the RangerToCedarConverter receives a Ranger policy with deny policy items, THE RangerToCedarConverter SHALL produce Cedar `forbid` statements for each (principal, resource, action) combination in the deny items.
2. WHEN a Ranger policy contains deny exception items, THE RangerToCedarConverter SHALL produce Cedar `permit` statements annotated with a deny-exception marker for each (principal, resource, action) combination in the deny exception items.
3. WHEN both allow and deny items exist in the same Ranger policy, THE RangerToCedarConverter SHALL produce both `permit` and `forbid` Cedar statements and rely on Cedar partial evaluation to resolve the effective permissions.
4. THE RangerToCedarConverter SHALL preserve the Ranger policy ID as an annotation on each generated Cedar statement for traceability.

### Requirement 6: Ranger-to-Cedar Conversion (Row Filter Policies)

**User Story:** As a developer, I want Ranger row filter policies converted to Cedar permit statements with conditions, so that row-level security is preserved through the Cedar layer.

#### Acceptance Criteria

1. WHEN the RangerToCedarConverter receives a Ranger policy with row filter policy items, THE RangerToCedarConverter SHALL produce Cedar `permit` statements with a `rowFilter` attribute containing the filter expression.
2. WHEN a row filter policy item has an empty or null filter expression, THE RangerToCedarConverter SHALL skip the item and log a warning.
3. THE CedarToLFConverter SHALL extract the `rowFilter` attribute from Cedar permit statements and set the corresponding `rowFilterExpression` on the generated LFResource.

### Requirement 7: Gap Handling in Cedar Conversion

**User Story:** As a developer, I want unsupported Ranger features properly reported as gaps during Cedar conversion, so that operators know which policies could not be fully represented.

#### Acceptance Criteria

1. WHEN the RangerToCedarConverter encounters a Ranger policy with policyType=1 (data masking), THE RangerToCedarConverter SHALL skip the policy and record a gap entry with GapType `DATA_MASKING` in the Gap_Reporter.
2. WHEN the RangerToCedarConverter encounters a Ranger policy with runtime context conditions (IP address restrictions, time-of-day constraints), THE RangerToCedarConverter SHALL record a gap entry with GapType `CUSTOM_CONDITION` in the Gap_Reporter and exclude the condition from the Cedar statement.
3. WHEN the RangerToCedarConverter encounters a Ranger policy with validity schedules, THE RangerToCedarConverter SHALL record a gap entry with GapType `VALIDITY_SCHEDULE` in the Gap_Reporter.
4. WHEN the RangerToCedarConverter encounters a tag-based Ranger policy, THE RangerToCedarConverter SHALL record a gap entry with GapType `TAG_BASED_POLICY` in the Gap_Reporter and skip the policy.

### Requirement 8: Cedar Schema Validation

**User Story:** As a developer, I want all generated Cedar policies validated against the Cedar schema, so that invalid conversions are caught before partial evaluation.

#### Acceptance Criteria

1. WHEN the RangerToCedarConverter produces a Cedar_Policy_Set, THE RangerToCedarConverter SHALL validate the policy set against the Cedar_Schema using the Cedar_Java_SDK validator.
2. IF a Cedar policy statement fails schema validation, THEN THE RangerToCedarConverter SHALL log the validation error, skip the invalid statement, and record a gap entry with details of the validation failure.
3. THE RangerToCedarConverter SHALL validate the Cedar_Policy_Set before passing the policy set to the CedarToLFConverter.

### Requirement 9: Cedar Partial Evaluation to LakeFormation Permissions

**User Story:** As a developer, I want Cedar partial evaluation to materialize effective permissions per principal, so that deny policies are correctly subtracted from the grant set before applying to LakeFormation.

#### Acceptance Criteria

1. WHEN the CedarToLFConverter receives a validated Cedar_Policy_Set, THE Partial_Evaluator SHALL evaluate the policy set for each unique principal to determine the effective set of permitted (resource, action) pairs.
2. WHEN a Cedar `forbid` statement applies to a principal and resource, THE Partial_Evaluator SHALL remove the corresponding (resource, action) pair from the effective grant set for the principal.
3. WHEN a Cedar `permit` statement with a deny-exception marker applies to a principal and resource that is also covered by a `forbid` statement, THE Partial_Evaluator SHALL restore the (resource, action) pair to the effective grant set.
4. THE CedarToLFConverter SHALL convert each effective (principal, resource, action) pair from partial evaluation into an LFPermissionOperation with OperationType GRANT.
5. THE CedarToLFConverter SHALL preserve the source Ranger policy ID from Cedar annotations on the generated LFPermissionOperation objects.
6. WHEN partial evaluation produces an empty effective grant set for a principal, THE CedarToLFConverter SHALL produce zero LFPermissionOperation objects for the principal.

### Requirement 10: Cedar Policy Pretty-Printing

**User Story:** As a developer, I want Cedar policies serialized to human-readable Cedar syntax, so that operators can inspect and debug the intermediate representation.

#### Acceptance Criteria

1. THE Cedar_Policy_Store SHALL format Cedar_Policy_Set objects into valid Cedar policy syntax strings.
2. WHEN a Cedar_Policy_Set is formatted to a string and then parsed back, THE Cedar_Java_SDK SHALL produce a semantically equivalent Cedar_Policy_Set (round-trip property).
3. THE Cedar_Policy_Store SHALL include Ranger policy ID annotations in the formatted output for traceability.

### Requirement 11: Pipeline Integration

**User Story:** As a developer, I want the Cedar abstraction layer integrated into the existing SyncService pipeline, so that the new conversion path replaces the direct Ranger-to-LF conversion.

#### Acceptance Criteria

1. WHEN the SyncService receives a policy update, THE Policy_Sync_Pipeline SHALL invoke the RangerToCedarConverter to produce a Cedar_Policy_Set, then invoke the CedarToLFConverter to produce LFPermissionOperation objects.
2. THE Policy_Sync_Pipeline SHALL preserve the existing diff-based sync mechanism, computing diffs on the LFPermissionOperation output from the CedarToLFConverter.
3. THE Policy_Sync_Pipeline SHALL pass the same PrincipalMapper and CatalogResolver instances to the RangerToCedarConverter that the existing PolicyConverter uses.
4. WHEN the Cedar_Java_SDK is unavailable or fails to initialize, THE Policy_Sync_Pipeline SHALL log an error and prevent the SyncService from starting.
5. THE Policy_Sync_Pipeline SHALL aggregate gap entries from both the RangerToCedarConverter and the CedarToLFConverter into the existing Gap_Reporter.

### Requirement 12: JDK and Dependency Configuration

**User Story:** As a developer, I want the project configured with the cedar-java SDK dependency and JDK 17+ compilation, so that Cedar functionality is available at build and runtime.

#### Acceptance Criteria

1. THE Policy_Sync_Pipeline SHALL declare a Maven dependency on `cedar-java` version 4.2.3 or later in the project pom.xml.
2. THE Policy_Sync_Pipeline SHALL configure the Maven compiler plugin for JDK 17 source and target compatibility.
3. WHEN the cedar-java native library fails to load at runtime (JNI initialization failure), THE Policy_Sync_Pipeline SHALL throw a descriptive error indicating the platform and JDK version requirements.
