# Implementation Plan: Multi-Ranger Plugin Support

## Overview

This plan implements multi-service Ranger plugin support by building foundational layers first (config model, base class), then concrete service implementations and adapters, then orchestration changes (SyncService, CheckpointStore, converter), and finally cross-cutting concerns (audit, metrics, installer, wildcard refresh). Property-based tests validate each correctness property from the design document.

## Tasks

- [x] 1. Configuration model and validation
  - [x] 1.1 Create `RangerServiceConfig` data model
    - Create `src/main/java/com/amazonaws/policyconverters/config/RangerServiceConfig.java` with fields: `serviceType`, `serviceInstanceName`, `serviceDefPath` (nullable), `gdcCatalogName` (nullable)
    - Use `@JsonCreator` / `@JsonProperty` annotations for Jackson deserialization
    - Include `equals`, `hashCode`, `toString`
    - _Requirements: 6.1_

  - [x] 1.2 Extend `SyncConfig` with `rangerServices` list
    - Add `List<RangerServiceConfig> rangerServices` field to `SyncConfig`
    - Add `@JsonProperty("rangerServices")` to the `@JsonCreator` constructor
    - Add getter `getRangerServices()`
    - Update `equals`, `hashCode`, `toString` to include the new field
    - Maintain backward compatibility: existing constructors still work when `rangerServices` is null
    - _Requirements: 6.1, 6.2_

  - [x] 1.3 Extend `ConfigValidator` for multi-service validation
    - Add `validateRangerServices(List<RangerServiceConfig>, List<String>)` method
    - Validate: reject duplicate `serviceType + serviceInstanceName` pairs
    - Validate: reject unknown `serviceType` (allowed: lakeformation, hive, presto, trino)
    - Validate: reject entries with missing `serviceInstanceName`
    - Validate: reject presto/trino entries missing `gdcCatalogName`
    - Call the new validation from `validate(SyncConfig)` only when `rangerServices` is non-null and non-empty
    - _Requirements: 6.4, 6.5, 6.6, 6.7_

  - [x] 1.4 Write property test: Configuration Round-Trip (Property 5)
    - **Property 5: Configuration Round-Trip**
    - Generate random `SyncConfig` with `rangerServices` lists, serialize to YAML, deserialize, assert equality
    - **Validates: Requirements 6.1**

  - [x] 1.5 Write property test: Configuration Validation Rejects Invalid Configs (Property 6)
    - **Property 6: Configuration Validation Rejects Invalid Configs**
    - Generate invalid configs (duplicates, unknown types, missing fields, missing gdcCatalogName), verify non-empty error list from `ConfigValidator`
    - **Validates: Requirements 6.4, 6.5, 6.6, 6.7**

- [x] 2. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Base Ranger service abstraction
  - [x] 3.1 Create `BaseRangerService` abstract class
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/service/BaseRangerService.java`
    - Fields: `serviceType` (String), `serviceInstanceName` (String), `plugin` (RangerBasePlugin, composed), `latestPolicies` (volatile ServicePolicies), `lastKnownGoodPolicies` (volatile List<RangerPolicy>)
    - Constructor takes `serviceType` and `serviceInstanceName`, creates `new RangerBasePlugin(serviceType, serviceInstanceName)`
    - Concrete methods: `init()`, `getLatestPolicies()` (updates lastKnownGoodPolicies on success), `getLastKnownGoodPolicies()`, `getServiceType()`, `getServiceInstanceName()`
    - Abstract methods: `createAdapter(AwsContext)`, `getServiceDefinitionResourcePath()`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 3.2 Write unit tests for `BaseRangerService` subclass contracts
    - Verify serviceType, instanceName, adapter creation, and service definition path for each concrete subclass
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 4. Add `shouldProcessPolicy` default method to `SourcePolicyAdapter`
  - Add `default boolean shouldProcessPolicy(RangerPolicy policy) { return true; }` to `SourcePolicyAdapter` interface
  - _Requirements: 4.7, 5.7_

- [x] 5. Concrete service implementations and adapters
  - [x] 5.1 Create `LakeFormationRangerService`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/service/LakeFormationRangerService.java`
    - Extends `BaseRangerService` with serviceType `"lakeformation"`
    - `createAdapter()` returns `new RangerServiceAdapter(awsContext)`
    - `getServiceDefinitionResourcePath()` returns `"/ranger-servicedef-lakeformation.json"`
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 5.2 Create `HiveServiceAdapter`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapter.java`
    - Implements `SourcePolicyAdapter` with serviceType `"hive"`
    - Access type mapping: select→SELECT, update→INSERT, create→CREATE_TABLE, drop→DROP, alter→ALTER, read→SELECT, write→INSERT, all→SUPER, index→∅ (logged), lock→∅ (logged)
    - `buildEntityRef()` maps database/table/column to Glue ARNs (same format as `RangerServiceAdapter`)
    - `buildEntityRefFromValues()` for expanded resource combinations
    - _Requirements: 3.2, 3.3, 3.5_

  - [x] 5.3 Create `HiveRangerService`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/service/HiveRangerService.java`
    - Extends `BaseRangerService` with serviceType `"hive"`
    - `createAdapter()` returns `new HiveServiceAdapter(awsContext)`
    - `getServiceDefinitionResourcePath()` returns `"/ranger-servicedef-hive.json"`
    - _Requirements: 3.1, 3.4_

  - [x] 5.4 Create `PrestoServiceAdapter`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/PrestoServiceAdapter.java`
    - Implements `SourcePolicyAdapter` with serviceType `"presto"`
    - Constructor takes `AwsContext` and `gdcCatalogName`
    - Access type mapping: select→SELECT, insert→INSERT, delete→DELETE, create→CREATE_TABLE, drop→DROP, alter→ALTER, use→DESCRIBE, show→DESCRIBE, grant→∅ (logged), revoke→∅ (logged)
    - `shouldProcessPolicy()` checks policy catalog resource matches `gdcCatalogName`; returns false with DEBUG log if mismatch
    - `buildEntityRef()` maps catalog/schema/table/column, treating "schema" as "database" in Glue ARNs
    - `buildEntityRefFromValues()` for expanded resource combinations
    - _Requirements: 4.2, 4.3, 4.5, 4.6, 4.7, 4.8_

  - [x] 5.5 Create `PrestoRangerService`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/service/PrestoRangerService.java`
    - Extends `BaseRangerService` with serviceType `"presto"`, accepts `gdcCatalogName`
    - `createAdapter()` returns `new PrestoServiceAdapter(awsContext, gdcCatalogName)`
    - `getServiceDefinitionResourcePath()` returns `"/ranger-servicedef-presto.json"`
    - _Requirements: 4.1, 4.4_

  - [x] 5.6 Create `TrinoServiceAdapter`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/TrinoServiceAdapter.java`
    - Implements `SourcePolicyAdapter` with serviceType `"trino"`
    - Same access type mapping and GDC catalog filtering as `PrestoServiceAdapter`
    - _Requirements: 5.2, 5.3, 5.5, 5.6, 5.7, 5.8_

  - [x] 5.7 Create `TrinoRangerService`
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/service/TrinoRangerService.java`
    - Extends `BaseRangerService` with serviceType `"trino"`, accepts `gdcCatalogName`
    - `createAdapter()` returns `new TrinoServiceAdapter(awsContext, gdcCatalogName)`
    - `getServiceDefinitionResourcePath()` returns `"/ranger-servicedef-trino.json"`
    - _Requirements: 5.1, 5.4_

  - [x] 5.8 Write property test: Access Type Mapping Validity (Property 2)
    - **Property 2: Access Type Mapping Validity**
    - For each adapter (Hive, Presto, Trino), generate access types from known mapping, verify non-empty valid Cedar actions. Generate unknown strings, verify empty set.
    - **Validates: Requirements 3.2, 3.5, 4.2, 4.5, 5.2, 5.5**

  - [x] 5.9 Write property test: Resource ARN Format Consistency (Property 3)
    - **Property 3: Resource ARN Format Consistency**
    - Generate random database/table/column names, verify all adapters produce ARNs matching `arn:aws:glue:{region}:{account}:{type}/{path}` pattern
    - **Validates: Requirements 3.3, 4.3, 5.3**

  - [x] 5.10 Write property test: GDC Catalog Filtering (Property 4)
    - **Property 4: GDC Catalog Filtering**
    - Generate random Presto/Trino policies with random catalog values, verify `shouldProcessPolicy()` returns true iff catalog matches `gdcCatalogName`
    - **Validates: Requirements 4.7, 4.8, 5.7, 5.8**

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Service definition JSON files
  - [x] 7.1 Create `ranger-servicedef-hive.json`
    - Create `conf/ranger-servicedef-hive.json` with Hive resource hierarchy (database, table, column) and access types (select, update, create, drop, alter, index, lock, all, read, write)
    - _Requirements: 3.4_

  - [x] 7.2 Create `ranger-servicedef-presto.json`
    - Create `conf/ranger-servicedef-presto.json` with Presto resource hierarchy (catalog, schema, table, column) and access types (select, insert, delete, create, drop, alter, use, show, grant, revoke)
    - _Requirements: 4.4_

  - [x] 7.3 Create `ranger-servicedef-trino.json`
    - Create `conf/ranger-servicedef-trino.json` with Trino resource hierarchy (catalog, schema, table, column) and access types (same as Presto)
    - _Requirements: 5.4_

  - [x] 7.4 Update `pom.xml` build resources to include new service definition files
    - Add `ranger-servicedef-hive.json`, `ranger-servicedef-presto.json`, `ranger-servicedef-trino.json` to the `<resources>` and `<testResources>` sections
    - _Requirements: 3.4, 4.4, 5.4_

- [x] 8. CheckpointStore and SyncCheckpoint multi-service extensions
  - [x] 8.1 Extend `SyncCheckpoint` with per-service version map
    - Add `Map<String, Long> serviceVersions` field to `SyncCheckpoint`
    - Update `@JsonCreator` constructor to accept `serviceVersions` parameter
    - Backward compatibility: when `serviceVersions` is null, derive `Map.of("lakeformation", policyVersion)`
    - Add getter `getServiceVersions()`
    - _Requirements: 10.1, 10.2, 10.3_

  - [x] 8.2 Extend `CheckpointStore.save()` to accept per-service version map
    - Add overloaded `save(Map<String, Long> serviceVersions, String cedarPolicyText)` method
    - Construct `SyncCheckpoint` with the version map
    - Keep existing `save(long, String)` for backward compatibility
    - _Requirements: 10.1_

  - [x] 8.3 Write property test: Checkpoint Round-Trip with Per-Service Versions (Property 10)
    - **Property 10: Checkpoint Round-Trip with Per-Service Versions**
    - Generate random service version maps and Cedar text, save/load via `CheckpointStore`, assert equality
    - **Validates: Requirements 10.1, 10.2, 9.3**

  - [x] 8.4 Write property test: Legacy Checkpoint Backward Compatibility (Property 11)
    - **Property 11: Legacy Checkpoint Backward Compatibility**
    - Generate legacy checkpoint JSON (single `policyVersion`, no `serviceVersions`), deserialize, verify `getServiceVersions()` returns `{"lakeformation": policyVersion}`
    - **Validates: Requirements 10.3**

- [x] 9. Cedar namespace isolation in `RangerToCedarConverter`
  - [x] 9.1 Prefix `@source` annotations with service type
    - Modify `RangerToCedarConverter.generateStatements()` to emit `@source("serviceType:policyId")` instead of `@source("policyId")`
    - Derive service type from the adapter's `getServiceType()` method
    - _Requirements: 9.1, 9.2_

  - [x] 9.2 Update `RangerToCedarConverter` to call `shouldProcessPolicy()`
    - In `convertSinglePolicy()`, after looking up the adapter, call `adapter.shouldProcessPolicy(policy)` and skip with DEBUG log if false
    - _Requirements: 4.7, 4.8, 5.7, 5.8_

  - [x] 9.3 Write property test: Service Namespace Isolation (Property 9)
    - **Property 9: Service Namespace Isolation**
    - Generate baseline Cedar policies from service A, add service B policies, compute diff, verify no revocations of A's policies
    - **Validates: Requirements 9.1, 9.2**

- [x] 10. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Multi-plugin SyncService orchestration
  - [x] 11.1 Refactor `SyncService` to hold `List<BaseRangerService>`
    - Add `List<BaseRangerService> rangerServices` field alongside existing `RangerPlugin plugin` (keep for backward compat)
    - Add constructor overload accepting `List<BaseRangerService>`
    - Add `Set<String> initializedServices` for first-sync gate tracking
    - Add `Map<String, Long> serviceVersions` for per-service version tracking
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 11.2 Implement multi-service policy fetching in `onPoliciesUpdated` / new `executeSyncCycle`
    - Iterate over all `BaseRangerService` instances, call `getLatestPolicies()` on each
    - On success: update `initializedServices`, merge policies into single list
    - On failure: use `getLastKnownGoodPolicies()` for that service (fault tolerance)
    - _Requirements: 7.1, 7.5, 7.6_

  - [x] 11.3 Implement first-sync gate
    - Track which services have completed at least one successful fetch in `initializedServices`
    - Defer diff-and-apply until `initializedServices.size() == rangerServices.size()`
    - Log warning when waiting for services to initialize
    - _Requirements: 7.4_

  - [x] 11.4 Update checkpoint persistence to use per-service version map
    - After each sync cycle, call `checkpointStore.save(serviceVersions, cedarPolicyText)`
    - On startup, restore `serviceVersions` from checkpoint's `getServiceVersions()`
    - _Requirements: 10.1, 10.2_

  - [x] 11.5 Write property test: Merged Cedar Set Completeness (Property 7)
    - **Property 7: Merged Cedar Set Completeness**
    - Generate policies from N service types, verify merged Cedar set contains contributions from all types
    - **Validates: Requirements 7.2, 7.3**

  - [x] 11.6 Write property test: Last-Known-Good Fault Tolerance (Property 8)
    - **Property 8: Last-Known-Good Fault Tolerance**
    - Generate fetch sequences with intermittent failures, verify last-known-good policies are used
    - **Validates: Requirements 7.5, 7.6**

- [x] 12. Audit logging with service type
  - [x] 12.1 Extend `logAuditEntry()` to include service type
    - Parse service type from `@source("serviceType:policyId")` annotation prefix on each operation's source policy ID
    - Include `serviceType=` field in the AUDIT log format
    - _Requirements: 12.1, 12.2_

  - [x] 12.2 Write property test: Audit Entry Service Type Inclusion (Property 12)
    - **Property 12: Audit Entry Service Type Inclusion**
    - Generate operations with service-type-prefixed source IDs, verify audit entries contain the service type
    - **Validates: Requirements 12.1, 12.2**

- [x] 13. MetricsEmitter `ServiceType` dimension
  - [x] 13.1 Add `ServiceType` dimension to relevant metrics
    - Extend `recordSuccess()` and `recordFailure()` to accept an optional `serviceType` parameter
    - Add `ServiceType` dimension to `PluginFetchFailure`, `PoliciesProcessed`, and per-service metrics
    - _Requirements: 7.7_

- [x] 14. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 15. ServiceDefInstallerMain multi-service iteration
  - [x] 15.1 Extend `ServiceDefInstallerMain` to iterate over configured services
    - In REST mode: load config, iterate `rangerServices` list, install each service definition
    - Load bundled service def when no custom `serviceDefPath` is specified
    - Log errors for individual failures but continue with remaining services
    - When `rangerServices` is null/empty, fall back to existing single LakeFormation behavior
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 15.2 Write unit tests for `ServiceDefInstallerMain` multi-service iteration
    - Test with multiple service configs, verify all are installed
    - Test partial failure: one service fails, others succeed
    - Test backward compat: no `rangerServices` → installs LakeFormation only
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [x] 16. ConversionServerMain wiring for multi-service
  - [x] 16.1 Update `ConversionServerMain.startServer()` to build multi-service pipeline
    - When `syncConfig.getRangerServices()` is non-null and non-empty: create `BaseRangerService` instances for each entry, build adapter registry from all services, pass `List<BaseRangerService>` to `SyncService`
    - When `rangerServices` is null/empty: fall back to existing single `RangerPlugin` wiring for backward compatibility
    - Initialize all plugins, register all adapters in the adapter registry
    - _Requirements: 6.2, 6.3, 7.1_

  - [x] 16.2 Write property test: Backward Compatibility (Property 1)
    - **Property 1: Backward Compatibility**
    - Generate random LakeFormation Ranger policies, convert through multi-service pipeline (LakeFormation only) and original single-service pipeline, assert identical Cedar output
    - **Validates: Requirements 2.4**

- [x] 17. WildcardRefreshScheduler multi-service support
  - [x] 17.1 Update wildcard refresh to use merged policies from all services
    - `executeWildcardRefresh()` already operates on `lastKnownPolicies`; ensure this field is populated from the merged policy list of all services
    - Include service type in log messages during wildcard expansion
    - _Requirements: 11.1, 11.2_

- [x] 18. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate the 12 correctness properties from the design document using jqwik
- The implementation language is Java 17 (matching the existing codebase)
- All property tests should use `@Property` with minimum 100 iterations and be tagged with the feature name
