# Implementation Plan: Cedar Policy Abstraction Layer

## Overview

Replace the direct Ranger-to-LakeFormation `PolicyConverter` with a two-stage Cedar pipeline: `RangerToCedarConverter` → `CedarToLFConverter`. This introduces Cedar as an intermediate policy representation, enabling proper deny/except semantics via partial evaluation, a pluggable source adapter pattern, and a target-agnostic `DataCatalog::` Cedar schema. All existing infrastructure (SyncService diff/apply, LakeFormationClient, PrincipalMapper, CatalogResolver, GapReporter, DeadLetterLogger) is preserved.

## Tasks

- [x] 1. Reorganize package hierarchy into three-way split
  - [x] 1.1 Move Ranger-specific source files to `com.amazonaws.policyconverters.ranger.*`
    - Move `service/` (LakeFormationResourceLookupService, ServiceDefinitionInstaller, ServiceDefinitionInstallException) → `com.amazonaws.policyconverters.ranger.service`
    - Move `sync/` (SyncService, LakeFormationPlugin) → `com.amazonaws.policyconverters.ranger.sync`
    - Move `ServiceDefInstallerMain.java`, `SyncServiceMain.java` → `com.amazonaws.policyconverters.ranger`
    - Move `converter/` (PolicyConverter, AccessTypeMapper, ConversionResult) → `com.amazonaws.policyconverters.ranger.converter`
    - Move `mapper/` (PrincipalMapper) → `com.amazonaws.policyconverters.ranger.mapper`
    - Move `catalog/` (CatalogResolver) → `com.amazonaws.policyconverters.ranger.catalog`
    - Move `config/` (ConfigLoader, ConfigValidator) → `com.amazonaws.policyconverters.ranger.config`
  - [x] 1.2 Move LakeFormation-specific target files to `com.amazonaws.policyconverters.lakeformation.*`
    - Move `client/` (LakeFormationClient, BatchResult, DeadLetterLogger, LakeFormationClientException) → `com.amazonaws.policyconverters.lakeformation.client`
    - Move `model/` (LFPermission, LFPermissionOperation, LFResource, AwsConfig, SyncConfig, RetryConfig, RangerConnectionConfig, PrincipalMappingConfig, GapEntry, GapReport, LFPermissionOperation) → `com.amazonaws.policyconverters.lakeformation.model`
    - Move `reporter/` (GapReporter) → `com.amazonaws.policyconverters.lakeformation.reporter`
    - Move `logging/` (StructuredErrorLogger) → `com.amazonaws.policyconverters.lakeformation.logging`
  - [x] 1.3 Move all test files to match the new package structure
    - Update all `package` declarations and `import` statements in test files
  - [x] 1.4 Move all integration test files to match the new package structure
    - Update all `package` declarations and `import` statements in integration test files
  - [x] 1.5 Update `pom.xml` and any configuration files that reference the old package paths
  - [x] 1.6 Verify the build compiles and all existing tests pass after the reorganization

- [x] 2. Update build configuration and add Cedar SDK dependency
  - [x] 2.1 Update `pom.xml` to add `cedar-java` 4.2.3+ dependency and change compiler source/target from 1.8 to 17
    - Add `<dependency>` for `com.cedarpolicy:cedar-java:4.2.3`
    - Update `<maven.compiler.source>` and `<maven.compiler.target>` to `17`
    - Update `maven-compiler-plugin` configuration `<source>` and `<target>` to `17`
    - Update integration-test profile compiler settings to `17`
    - _Requirements: 12.1, 12.2_

- [x] 3. Create Cedar schema and schema provider
  - [x] 3.1 Create the `datacatalog.cedarschema` file in `src/main/resources/cedar/`
    - Define `DataCatalog` namespace with entity types: `Principal`, `Catalog`, `Database in [Catalog]`, `Table in [Database]`, `Column in [Table]`, `DataLocation`
    - Define actions: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `DESCRIBE`, `ALTER`, `DROP`, `CREATE_DATABASE`, `CREATE_TABLE`, `DATA_LOCATION_ACCESS` with correct `appliesTo` constraints
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6_

  - [x] 3.2 Create `CedarSchemaProvider` class in `com.amazonaws.policyconverters.cedar`
    - Implement `loadSchema()` to load `datacatalog.cedarschema` from classpath
    - Implement `validate(CedarPolicySet)` returning `List<ValidationError>`
    - _Requirements: 1.5, 8.1_

  - [x] 3.3 Write unit tests for `CedarSchemaProvider`
    - Verify schema loads successfully and defines expected entity types and actions
    - Verify validation rejects statements with unknown entity types or actions
    - _Requirements: 1.1, 1.2, 1.4, 1.5, 1.6_

- [x] 4. Create Cedar value objects and wrapper types
  - [x] 4.1 Create `CedarEntityRef` value object in `com.amazonaws.policyconverters.cedar`
    - Fields: `entityType` (e.g., `DataCatalog::Database`), `entityId` (e.g., ARN)
    - Constructor, getters, equals, hashCode
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.2 Create `AwsContext` value object in `com.amazonaws.policyconverters.lakeformation.cedar`
    - Fields: `region`, `accountId`, `catalogId`
    - Constructor, getters
    - _Requirements: 2.1_

  - [x] 4.3 Create `GlueResourceRef` value object in `com.amazonaws.policyconverters.lakeformation.cedar`
    - Fields: `region`, `accountId`, `databaseName`, `tableName` (nullable), `columnName` (nullable)
    - Constructor, getters, equals, hashCode
    - _Requirements: 2.7_

  - [x] 4.4 Create `S3ResourceRef` value object in `com.amazonaws.policyconverters.lakeformation.cedar`
    - Fields: `bucket`, `path`
    - Constructor, getters
    - _Requirements: 2.4_

  - [x] 4.5 Create `CedarPolicySet` wrapper class in `com.amazonaws.policyconverters.cedar`
    - Implement `getPrincipals()`, `toCedarString()`, `fromCedarString()`, `getPermitCount()`, `getForbidCount()`, `getSourcePolicyIds()`
    - Wrap the cedar-java SDK `PolicySet` type
    - _Requirements: 10.1, 10.2, 10.3_

- [x] 5. Extend GapType enum and create ArnParser utility
  - [x] 5.1 Add new `GapType` values to `GapEntry.GapType` enum
    - Add: `UNSUPPORTED_SERVICE_TYPE`, `UNSUPPORTED_ACTION`, `UNMAPPED_RESOURCE`, `SCHEMA_VALIDATION_FAILURE`
    - _Requirements: 1.7, 2.8, 3.4, 8.2_

  - [x] 5.2 Create `ArnParser` utility class in `com.amazonaws.policyconverters.lakeformation.cedar`
    - Implement `parseDatabaseArn(String)` → `GlueResourceRef`
    - Implement `parseTableArn(String)` → `GlueResourceRef`
    - Implement `parseColumnArn(String)` → `GlueResourceRef`
    - Implement `parseS3Arn(String)` → `S3ResourceRef`
    - Implement `isArn(String)` → boolean
    - _Requirements: 2.7_

  - [x] 5.3 Write unit tests for `ArnParser`
    - Test parsing valid database, table, column, and S3 ARNs
    - Test `isArn()` with valid ARNs and non-ARN strings
    - Test malformed ARN handling
    - _Requirements: 2.7_

  - [x] 5.4 Write property test for ARN round-trip (Property 1)
    - **Property 1: ARN Construction Round-Trip**
    - For any valid (region, accountId, databaseName, tableName, columnName), constructing an ARN and parsing it back yields the original components
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.7**

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Create SourcePolicyAdapter interface and RangerLFServiceAdapter
  - [x] 7.1 Create `SourcePolicyAdapter` interface in `com.amazonaws.policyconverters.cedar`
    - Define methods: `getServiceType()`, `mapAccessTypeToCedarActions(String)`, `buildEntityRef(RangerPolicy, String)`, `buildPrincipalRef(String)`, `getAwsContext()`
    - _Requirements: 3.1, 3.2, 3.5_

  - [x] 7.2 Create `RangerLFServiceAdapter` implementing `SourcePolicyAdapter` in `com.amazonaws.policyconverters.ranger.cedar`
    - Service type: `"lakeformation"`
    - Map LF access types to Cedar actions using same rules as `AccessTypeMapper`
    - Build Glue ARN-formatted entity identifiers for database, table, column, data location
    - Build IAM ARN-formatted principal identifiers
    - _Requirements: 3.3, 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 7.3 Write unit tests for `RangerLFServiceAdapter`
    - Test access type mapping matches `AccessTypeMapper` behavior
    - Test entity ref construction for each resource level
    - Test principal ref construction
    - _Requirements: 3.3, 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 7.4 Write property test for resource level → Cedar entity type (Property 5)
    - **Property 5: Resource Level Determines Cedar Entity Type**
    - Database-only → `DataCatalog::Database`, table → `DataCatalog::Table`, column → `DataCatalog::Column`, data location → `DataCatalog::DataLocation`
    - **Validates: Requirements 4.3, 4.4, 4.5**

  - [x] 7.5 Write property test for unregistered service type (Property 3)
    - **Property 3: Unregistered Service Type Produces Gap**
    - Any policy with unregistered service type → zero Cedar statements + `UNSUPPORTED_SERVICE_TYPE` gap
    - **Validates: Requirements 3.4**

- [x] 8. Implement RangerToCedarConverter
  - [x] 8.1 Create `RangerToCedarConverter` class in `com.amazonaws.policyconverters.ranger.cedar`
    - Constructor takes adapter registry (`Map<String, SourcePolicyAdapter>`), `PrincipalMapper`, `CatalogResolver`, `GapReporter`, `CedarSchemaProvider`
    - Implement `convert(List<RangerPolicy>)` → `CedarPolicySet`
    - For each policy: look up adapter, skip unsupported types (data masking, tag-based) → gap, expand wildcards, produce permit/forbid/deny-exception statements, annotate with policy ID
    - Validate full PolicySet against schema, exclude invalid statements → gap
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 7.1, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3_

  - [x] 8.2 Write unit tests for `RangerToCedarConverter`
    - Test allow policy → permit statements
    - Test deny policy → forbid statements
    - Test deny exception → annotated permit statements
    - Test row filter → permit with rowFilter attribute
    - Test data masking policy → gap + skip
    - Test tag-based policy → gap + skip
    - Test custom conditions → gap
    - Test validity schedules → gap
    - Test empty policy list → empty PolicySet
    - _Requirements: 4.1, 5.1, 5.2, 6.1, 7.1, 7.2, 7.3, 7.4_

  - [x] 8.3 Write property test for allow policy permit count (Property 4)
    - **Property 4: Allow Policy Permit Count**
    - P principals × R resources × A actions = exact permit count
    - **Validates: Requirements 4.1, 4.2**

  - [x] 8.4 Write property test for wildcard expansion (Property 6)
    - **Property 6: Wildcard Expansion Produces Only Concrete Names**
    - After conversion, no `*` or `?` in ARN path segments
    - **Validates: Requirements 4.6**

  - [x] 8.5 Write property test for deny/deny-exception statement generation (Property 7)
    - **Property 7: Deny and Deny-Exception Statement Generation**
    - D_resolved forbid statements + E_resolved deny-exception permit statements
    - **Validates: Requirements 5.1, 5.2, 5.3**

  - [x] 8.6 Write property test for policy ID annotation preservation (Property 8)
    - **Property 8: Policy ID Annotation Preservation**
    - Every Cedar statement carries the original Ranger policy ID annotation
    - **Validates: Requirements 5.4**

  - [x] 8.7 Write property test for unsupported features gap entries (Property 10)
    - **Property 10: Unsupported Features Produce Correct Gap Entries**
    - Data masking, tag-based, custom conditions, validity schedules → correct GapType entries
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4**

  - [x] 8.8 Write property test for schema validation exclusion (Property 11)
    - **Property 11: Schema Validation Excludes Invalid Statements**
    - Invalid statements excluded + `SCHEMA_VALIDATION_FAILURE` gap entries
    - **Validates: Requirements 1.5, 8.1, 8.2**

- [x] 9. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement CedarToLFConverter
  - [x] 10.1 Create `CedarToLFConverter` class in `com.amazonaws.policyconverters.lakeformation.cedar`
    - Constructor takes `CedarSchemaProvider`, `GapReporter`, `ArnParser`
    - Implement `convert(CedarPolicySet)` → `List<LFPermissionOperation>`
    - For each unique principal: run Cedar partial evaluation, collect effective (resource, action) grants
    - Parse ARN entity identifiers → LFResource fields, map Cedar actions → LFPermission
    - Extract rowFilter attribute → `LFResource.rowFilterExpression`
    - Create `LFPermissionOperation` with `OperationType.GRANT` and source policy ID from annotation
    - Skip unsupported actions → `UNSUPPORTED_ACTION` gap
    - Skip non-ARN identifiers without mapping → `UNMAPPED_RESOURCE` gap
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 1.7, 2.7, 2.8, 6.3_

  - [x] 10.2 Write unit tests for `CedarToLFConverter`
    - Test permit-only PolicySet → GRANT operations
    - Test permit + forbid → deny removes grant
    - Test permit + forbid + deny-exception → grant restored
    - Test row filter extraction
    - Test unsupported action → gap
    - Test non-ARN identifier → gap
    - Test empty effective grants → zero operations
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 1.7, 2.8, 6.3_

  - [x] 10.3 Write property test for non-ARN identifiers (Property 2)
    - **Property 2: Non-ARN Identifiers Produce UNMAPPED_RESOURCE Gap**
    - Any non-ARN entity identifier → skip + `UNMAPPED_RESOURCE` gap
    - **Validates: Requirements 2.8**

  - [x] 10.4 Write property test for forbid removes grants (Property 12)
    - **Property 12: Forbid Removes Effective Grants**
    - permit(P,A,R) + forbid(P,A,R) → zero operations for (P,A,R)
    - **Validates: Requirements 9.2**

  - [x] 10.5 Write property test for deny-exception restores grants (Property 13)
    - **Property 13: Deny-Exception Restores Grants**
    - permit + forbid + deny-exception for same (P,A,R) → GRANT operation
    - **Validates: Requirements 9.3**

  - [x] 10.6 Write property test for effective grants produce GRANT with policy ID (Property 14)
    - **Property 14: Effective Grants Produce GRANT Operations with Policy ID**
    - Each effective grant → `LFPermissionOperation` with `OperationType.GRANT` and matching `sourcePolicyId`
    - **Validates: Requirements 9.4, 9.5**

  - [x] 10.7 Write property test for row filter round-trip (Property 9)
    - **Property 9: Row Filter Attribute Round-Trip**
    - Ranger row filter → Cedar permit with rowFilter → LFPermissionOperation with matching `rowFilterExpression`
    - **Validates: Requirements 6.1, 6.3**

  - [x] 10.8 Write property test for unsupported target action (Property 16)
    - **Property 16: Unsupported Target Action Produces Gap**
    - Cedar action not in LF supported set → skip + `UNSUPPORTED_ACTION` gap
    - **Validates: Requirements 1.7**

- [x] 11. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Implement CedarPolicySet round-trip and pipeline gap aggregation
  - [x] 12.1 Write property test for CedarPolicySet format/parse round-trip (Property 15)
    - **Property 15: Cedar PolicySet Format/Parse Round-Trip**
    - `toCedarString()` → `fromCedarString()` produces semantically equivalent PolicySet
    - **Validates: Requirements 10.2, 10.3**

  - [x] 12.2 Write property test for gap aggregation (Property 17)
    - **Property 17: Gap Aggregation from Both Converters**
    - N gaps from RangerToCedarConverter + M gaps from CedarToLFConverter → at least N+M total in GapReporter
    - **Validates: Requirements 11.5**

- [x] 13. Integrate Cedar pipeline into SyncService
  - [x] 13.1 Update `SyncService` to replace `PolicyConverter` with `RangerToCedarConverter` + `CedarToLFConverter`
    - Change constructor to accept `RangerToCedarConverter` and `CedarToLFConverter` instead of `PolicyConverter`
    - Update `onPoliciesUpdated()`: call `rangerToCedarConverter.convert(policies)` → `cedarToLFConverter.convert(policySet)` → existing diff/apply
    - Preserve existing `computeDiff`, `LakeFormationClient.applyBatch`, dead-letter logging, audit logging
    - Pass same `PrincipalMapper` and `CatalogResolver` to `RangerToCedarConverter`
    - Aggregate gap entries from both converters into the same `GapReporter`
    - _Requirements: 11.1, 11.2, 11.3, 11.5_

  - [x] 13.2 Update `SyncServiceMain` to wire Cedar components and add fail-fast initialization
    - Instantiate `CedarSchemaProvider`, `RangerLFServiceAdapter`, `RangerToCedarConverter`, `CedarToLFConverter`
    - Register `RangerLFServiceAdapter` in adapter registry with key `"lakeformation"`
    - Add try/catch for `UnsatisfiedLinkError` / `ExceptionInInitializerError` on cedar-java native library load → log descriptive error with platform/JDK info → `System.exit(1)`
    - _Requirements: 11.4, 12.3_

  - [x] 13.3 Write unit tests for updated `SyncService` wiring
    - Test end-to-end flow: Ranger policies → RangerToCedarConverter → CedarToLFConverter → diff → apply
    - Test Cedar SDK initialization failure → service does not start
    - Test gap entries from both converters appear in GapReporter
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

- [x] 14. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (Properties 1–17)
- Unit tests validate specific examples and edge cases
- The cedar-java SDK v4.2.3+ requires JDK 17+ and JNI/Rust FFI native library
- jqwik (already in pom.xml) is used for property-based testing with `@Property(tries = 100)` minimum
