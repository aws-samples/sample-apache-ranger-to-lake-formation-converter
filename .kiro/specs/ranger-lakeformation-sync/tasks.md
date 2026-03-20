# Implementation Plan: Ranger-LakeFormation Sync Utility

## Overview

This plan implements a Java 8 utility that bridges Apache Ranger policies to AWS Lake Formation permissions. The implementation proceeds bottom-up: data models and configuration first, then core conversion logic, then the sync service (which handles both initial bulk sync and incremental updates), and finally the service definition installer. Testing (jqwik property tests and JUnit 5 unit tests) is woven into each step. Build uses Maven for both dependency management and building.

## Tasks

- [x] 1. Set up project structure, build configuration, and core data models
  - [x] 1.1 Create Maven pom.xml with Java 8 target, Maven Central repositories, and dependencies (Apache Ranger SDK, AWS SDK for Lake Formation/Glue/IAM/STS, JUnit 5, jqwik, Mockito, Jackson for JSON)
    - Configure `<maven.compiler.source>1.8</maven.compiler.source>` and `<maven.compiler.target>1.8</maven.compiler.target>`
    - Declare `org.apache.ranger` dependencies, `software.amazon.awssdk` dependencies
    - Declare test dependencies: `org.junit.jupiter`, `net.jqwik`, `org.mockito`
    - Configure `maven-jar-plugin` and `maven-assembly-plugin` for packaging
    - _Requirements: 7.1, 7.2, 7.3, 7.5_

  - [x] 1.2 Create package structure and core data model classes
    - Create `LFPermissionOperation` (with `OperationType` enum, `sourcePolicyId`, `principalArn`, `LFResource`, `Set<LFPermission>`, `grantable`)
    - Create `LFResource` (`catalogId`, `databaseName`, `tableName`, `columnNames`, `rowFilterExpression`)
    - Create `LFPermission` enum (SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, CREATE_DATABASE, CREATE_TABLE, DATA_LOCATION_ACCESS)
    - Create `GapEntry` (with `GapType` enum, `policyId`, `policyName`, `gapType`, `resourcePath`, `details`, `recommendation`)
    - Create `GapReport` (`entries`, `summary` map, `generatedAt`)
    - Create `RetryConfig` (`maxRetries`, `initialBackoffMs`, `backoffMultiplier`, `maxBackoffMs`)
    - _Requirements: 2.1, 3.8_

  - [x] 1.3 Create configuration model classes
    - Create `RangerConnectionConfig` (`rangerAdminUrl`, `username`, `password`, `kerberosKeytab`, `kerberosPrincipal`, `maxRetries`, `retryBackoffMs`)
    - Create `AwsConfig` (`region`, `catalogId`, `accessKey`, `secretKey`, `roleArn`)
    - Create `PrincipalMappingConfig` (`userMappings`, `groupMappings`, `roleMappings`)
    - Create `SyncConfig` (composing `RangerConnectionConfig`, `AwsConfig`, `PrincipalMappingConfig`, plus sync-specific fields)
    - _Requirements: 9.1, 6.4_


- [x] 2. Implement configuration loading, validation, and principal mapping
  - [x] 2.1 Implement configuration loader with YAML/properties file support and environment variable overrides
    - Load from external YAML or properties file
    - Override values from environment variables when present
    - Mask sensitive values (passwords, secret keys) in log output
    - _Requirements: 9.1, 9.2, 9.4_

  - [x] 2.2 Implement configuration validation at startup
    - Validate all required parameters are present and well-formed
    - Report all missing/invalid parameters with descriptive error messages before any operations
    - _Requirements: 9.3_

  - [x] 2.3 Write property tests for configuration handling
    - **Property 21: Environment variable override** — *For any* config key with both file and env var values, the env var value wins
    - **Validates: Requirements 9.2**
    - **Property 22: Configuration validation completeness** — *For any* config with N missing required params, exactly N errors are reported
    - **Validates: Requirements 9.3**
    - **Property 23: Sensitive value masking in logs** — *For any* config with sensitive fields, log output contains no raw sensitive values
    - **Validates: Requirements 9.4**

  - [x] 2.4 Implement PrincipalMapper with JSON/properties file loading
    - Load principal mappings from JSON or properties file
    - Implement `resolveUser`, `resolveGroup`, `resolveRole` returning `Optional<String>`
    - Log warning and return empty for unmapped principals
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 2.5 Write property tests for PrincipalMapper
    - **Property 16: Principal mapping round-trip** — *For any* valid PrincipalMappingConfig, serialize to JSON and deserialize back produces equivalent config
    - **Validates: Requirements 6.5**
    - **Property 15: Unmapped principal skipping** — *For any* policy with unmapped principals, no LF operations are produced for those principals and warnings are logged
    - **Validates: Requirements 6.3**

- [x] 3. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement GapReporter and policy conversion core
  - [x] 4.1 Implement GapReporter with JSON serialization/deserialization
    - Implement `recordGap`, `getReport`, `toJson`, `fromJson`
    - Compute summary counts per GapType
    - _Requirements: 3.8, 3.9_

  - [x] 4.2 Write property tests for GapReporter
    - **Property 11: Gap report JSON round-trip** — *For any* valid GapReport, serialize to JSON and deserialize back produces equivalent object
    - **Validates: Requirements 3.8, 3.9**
    - **Property 12: Gap report summary accuracy** — *For any* GapReport, summary count per GapType equals actual count of entries with that type
    - **Validates: Requirements 3.8**

  - [x] 4.3 Implement CatalogResolver for wildcard expansion against Glue Data Catalog
    - Implement `expandDatabases`, `expandTables`, `expandColumns` using AWS Glue SDK
    - Handle catalog resolution failures gracefully (log and skip)
    - _Requirements: 2.7_

  - [x] 4.4 Implement Ranger access type to LF permission mapping
    - Create mapping table: select→SELECT, update→INSERT, create→CREATE_TABLE, drop→DROP, alter→ALTER, read→SELECT, write→INSERT, all→expanded set
    - _Requirements: 2.1_

  - [x] 4.5 Implement PolicyConverter core conversion logic
    - Convert resource-based access policies (database, table, column) to LF_Permission grants
    - Convert row-level filter policy items to LF_Data_Filter definitions
    - Use PrincipalMapper to resolve IAM ARNs for each principal
    - Use CatalogResolver to expand wildcard resource patterns
    - Convert only allow policy items; record deny items and exceptions in GapReporter
    - Detect and record all unsupported features: data masking, tag-based policies, deny/deny-exceptions, validity schedules, custom conditions, security zones, delegated admin
    - Skip malformed policies with logging
    - Implement `convertBatch` wrapping single-policy conversion
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 8.1_


  - [x] 4.6 Write property tests for PolicyConverter
    - **Property 5: Access type mapping correctness** — *For any* valid resource-based Ranger policy with known access types, the converter produces LF operations with exactly the corresponding LF permissions
    - **Validates: Requirements 2.1**
    - **Property 6: Row filter conversion preserves filter expressions** — *For any* Ranger policy with row filter items, the converter produces LF_Data_Filter definitions with matching filter expressions
    - **Validates: Requirements 2.2**
    - **Property 7: Principal mapping resolution** — *For any* principal with a configured mapping, the converter produces LF operations with the correct IAM ARN
    - **Validates: Requirements 2.3, 6.1, 6.2**
    - **Property 8: Unsupported feature detection and gap reporting** — *For any* policy with unsupported features, the converter produces LF operations only for supported portions and a GapEntry for each unsupported feature
    - **Validates: Requirements 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**
    - **Property 9: Conversion determinism** — *For any* Ranger policy, converting twice with the same dependencies produces identical LF operation lists
    - **Validates: Requirements 2.6**
    - **Property 10: Wildcard expansion completeness** — *For any* wildcard pattern and catalog state, the converter produces one LF_Permission per matching resource
    - **Validates: Requirements 2.7**
    - **Property 17: Malformed policy resilience** — *For any* batch containing malformed policies, valid policies are converted and malformed ones are skipped with logged errors
    - **Validates: Requirements 8.1**

  - [x] 4.7 Write unit tests for PolicyConverter edge cases
    - Test specific known Ranger policy → expected LF operations conversion
    - Test "all" access type expansion
    - Test policy with only deny items (should produce zero LF operations, all in gap report)
    - Test policy with mixed allow and deny items
    - _Requirements: 2.1, 2.4, 2.5_

- [x] 5. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Implement LakeFormationClient with retry and atomic batch application
  - [x] 6.1 Implement LakeFormationClient grant/revoke operations
    - Wrap AWS Lake Formation SDK `grantPermissions` and `revokePermissions` calls
    - Implement retry with exponential backoff for ConcurrentModificationException and throttling
    - _Requirements: 4.4, 4.5_

  - [ ] 6.2 Implement atomic per-policy batch application with rollback
    - Group operations by source policy ID
    - Apply sequentially; on failure after retries, reverse previously applied operations for that policy
    - Write failed operations to dead-letter log (JSON lines format)
    - _Requirements: 8.3, 8.4_

  - [ ] 6.3 Write property tests for LakeFormationClient
    - **Property 18: Atomic per-policy application** — *For any* batch grouped by policy, if any operation for a policy fails, all operations for that policy are rolled back while other policies are unaffected
    - **Validates: Requirements 8.3**
    - **Property 19: Dead-letter log completeness** — *For any* operation that fails after exhausting retries, the dead-letter log contains an entry with policy ID, operation details, and error message
    - **Validates: Requirements 8.4**

  - [ ] 6.4 Write unit tests for LakeFormationClient retry behavior
    - Test ConcurrentModificationException triggers retry with backoff
    - Test throttling triggers backoff and retry
    - Test successful rollback on partial batch failure
    - _Requirements: 4.4, 4.5, 8.3_


- [ ] 7. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 8. Implement SyncService (RangerPlugin-based real-time sync)
  - [ ] 8.1 Implement LakeFormationPlugin extending RangerBasePlugin
    - Register with Ranger Admin using the custom "lakeformation" service definition
    - Override `setPolicies` to receive policy updates
    - _Requirements: 4.1_

  - [ ] 8.2 Implement policy diff computation in SyncService
    - Maintain previous policy snapshot (empty on first startup for implicit bulk sync)
    - Compute delta: new grants, revoked permissions, unchanged (no-op)
    - Pass delta through PolicyConverter and apply via LakeFormationClient
    - _Requirements: 4.2, 4.3_

  - [ ] 8.3 Implement SyncService resilience and audit logging
    - Log each grant/revoke operation with policy ID, resource, principal, permission type
    - Continue with last known policy set on Ranger Admin connectivity loss; resume on reconnect
    - Route unsupported features through GapReporter consistent with PolicyConverter
    - _Requirements: 4.6, 4.7, 4.8_

  - [ ] 8.4 Write property tests for SyncService diff logic
    - **Property 13: Policy diff correctness** — *For any* two policy snapshots, the diff produces GRANT for new permissions, REVOKE for removed permissions, and no-op for unchanged
    - **Validates: Requirements 4.3**
    - **Property 14: Audit log completeness** — *For any* set of applied LF operations, the audit log contains one entry per operation with policy ID, resource, principal, and permission type
    - **Validates: Requirements 4.6**

  - [ ] 8.5 Write unit tests for SyncService
    - Test plugin registration with Ranger Admin
    - Test connectivity loss handling (continues with last known policies)
    - Test unsupported features in sync mode are routed to GapReporter
    - _Requirements: 4.1, 4.7, 4.8_

- [ ] 9. Implement Service Definition and Resource Lookup
  - [ ] 9.1 Create the Lake Formation service definition JSON file
    - Define "lakeformation" service type with database, table, column resource hierarchy
    - Define access types matching LF permissions (SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, CREATE_DATABASE, CREATE_TABLE, DATA_LOCATION_ACCESS)
    - Define configuration properties for AWS region, catalog ID, credentials
    - _Requirements: 5.1, 5.2, 5.3_

  - [ ] 9.2 Implement ServiceDefinitionInstaller
    - REST-based installation via POST to `/service/plugins/definitions`
    - File-based installation by copying to `ranger-admin/ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation/`
    - _Requirements: 5.4, 5.5_

  - [ ] 9.3 Implement LakeFormationResourceLookupService extending RangerBaseService
    - Implement `validateConfig` to verify AWS credentials
    - Implement `lookupResource` to query Glue Data Catalog for databases, tables, columns
    - Use AWS credentials from service definition configuration properties
    - _Requirements: 5.6, 5.7_

  - [ ] 9.4 Write property test for resource lookup
    - **Property 24: Resource lookup returns matching catalog entries** — *For any* catalog state and lookup context, the service returns exactly the matching entries
    - **Validates: Requirements 5.6**

  - [ ] 9.5 Write unit tests for service definition and installer
    - Validate service definition JSON structure (correct resource types, access types, configs)
    - Test REST-based installation call
    - Test file-based installation writes to correct directory
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 10. Implement structured error logging across all components
  - [ ] 10.1 Implement consistent error logging format
    - Every error log entry includes timestamp, component name, severity level, and context (policy ID, resource path, principal)
    - _Requirements: 8.5_

  - [ ] 10.2 Write property test for error log structure
    - **Property 20: Error log structure** — *For any* error logged by any component, the entry contains timestamp, component name, severity, and applicable context fields
    - **Validates: Requirements 8.5**

- [ ] 11. Wire components together and create entry points
  - [ ] 11.1 Create main entry point for sync service mode
    - Load configuration, instantiate SyncService with all dependencies
    - Start plugin and begin receiving policy updates
    - On first startup with empty previous snapshot, effectively performs a bulk sync
    - _Requirements: 4.1, 4.2, 7.4_

  - [ ] 11.2 Create main entry point for service definition installation
    - Load configuration, instantiate ServiceDefinitionInstaller
    - Support both REST and file-based installation via CLI flag
    - _Requirements: 5.4, 5.5, 7.4_

- [ ] 12. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik with minimum 100 iterations per property
- Unit tests use JUnit 5 with Mockito for mocking AWS and Ranger APIs
- Checkpoints ensure incremental validation at key integration points
