# Implementation Plan: LakeFormation Cedar Reverse Sync

## Overview

Implement the reverse-sync feature that retrieves actual LakeFormation permissions via `ListPermissions`, computes drift against the Cedar-authoritative desired state, and applies corrective GRANT/REVOKE operations. The implementation builds incrementally: model classes first, then the fetcher, then the drift detector, then the orchestrator, and finally wiring and configuration. All code is Java 17, tested with JUnit 5 and jqwik 1.7.4.

## Tasks

- [x] 1. Create model classes for reverse-sync
  - [x] 1.1 Create `PermissionFilter` model class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/model/PermissionFilter.java`
    - Fields: `principalArn` (String, nullable), `resourceType` (String, nullable), `excludedPrincipals` (Set\<String\>), `excludedResourcePatterns` (Set\<String\>)
    - Implement `shouldExclude(LFPermissionOperation op)` method that returns true if the operation's principal is in `excludedPrincipals` or the resource matches any `excludedResourcePatterns`
    - Include Jackson annotations for YAML deserialization
    - Include `equals`, `hashCode`, `toString`
    - _Requirements: 1.7, 3.7_

  - [x] 1.2 Create `DriftReport` model class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/model/DriftReport.java`
    - Fields: `missingGrants` (int), `extraPermissions` (int), `inSyncCount` (int), `skippedPermissions` (List\<LFPermissionOperation\>), `failedOperations` (List\<FailedOperation\>)
    - Create inner class `FailedOperation` with fields: `operation` (LFPermissionOperation), `error` (String)
    - Implement `getTotalDrift()` returning `missingGrants + extraPermissions`
    - Include Jackson annotations for JSON serialization
    - _Requirements: 3.5, 7.4_

  - [x] 1.3 Create `DriftResult` model class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/model/DriftResult.java`
    - Fields: `report` (DriftReport), `correctiveOperations` (List\<LFPermissionOperation\>)
    - `correctiveOperations` is empty when `reportOnly=true`
    - _Requirements: 3.5, 3.6_

  - [x] 1.4 Create `ReverseSyncConfig` model class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/model/ReverseSyncConfig.java`
    - Fields: `catalogId` (String), `reportOnly` (boolean), `dryRun` (boolean), `filter` (PermissionFilter), `periodicIntervalMs` (long, 0 = on-demand)
    - Include Jackson annotations for YAML deserialization from `server-config.yaml`
    - _Requirements: 5.1, 5.3_

  - [x] 1.5 Create `ReverseSyncResult` model class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/model/ReverseSyncResult.java`
    - Fields: `driftReport` (DriftReport), `successfulGrants` (int), `successfulRevokes` (int), `failedOperations` (int), `durationMs` (long)
    - _Requirements: 4.6_

  - [x] 1.6 Create `ReverseSyncDryRunOutput` class extending `DryRunOutput`
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/client/ReverseSyncDryRunOutput.java`
    - Extend existing `DryRunOutput` with additional `driftSummary` (DriftReport) field
    - Include Jackson `@JsonCreator` and `@JsonProperty` annotations
    - _Requirements: 6.2, 6.3_

- [x] 2. Checkpoint - Ensure all model classes compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Implement `LFPermissionFetcher`
  - [x] 3.1 Create `LFPermissionFetcher` class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/client/LFPermissionFetcher.java`
    - Constructor takes `software.amazon.awssdk.services.lakeformation.LakeFormationClient`, `RetryConfig`, `LakeFormationClient.Sleeper`
    - Implement `fetchPermissions(String catalogId, PermissionFilter filter)` that calls `ListPermissions` API, paginates through all pages using `NextToken`, applies the filter, and returns `List<LFPermissionOperation>`
    - Implement retry with exponential backoff for transient errors (throttling, 5xx) using `RetryConfig`
    - Throw `LakeFormationClientException` immediately (no retry) for `AccessDeniedException`
    - Throw `LakeFormationClientException` after exhausting retries for transient errors
    - _Requirements: 1.1, 1.2, 1.5, 1.6, 1.7, 7.5_

  - [x] 3.2 Implement `normalizeEntry(PrincipalResourcePermissions entry)` method
    - Convert a single SDK `PrincipalResourcePermissions` into zero or more `LFPermissionOperation` objects
    - Set `operationType` to `GRANT` for all returned operations
    - Set `sourcePolicyId` to `null` for all returned operations
    - Handle `PermissionsWithGrantOption` by creating a separate operation with `grantable=true`
    - Skip entries with unrecognized resource types (CatalogResource, LFTagPolicy) and log warning
    - Skip entries where all permissions are unrecognized and log warning
    - _Requirements: 1.3, 1.4, 2.3, 2.4, 7.1, 7.2_

  - [x] 3.3 Implement `reverseMapResource(Resource sdkResource)` method
    - Convert SDK `DatabaseResource` → `LFResource` with catalogId, databaseName
    - Convert SDK `TableResource` → `LFResource` with catalogId, databaseName, tableName
    - Convert SDK `TableResource` with `TableWildcard` → `LFResource` with tableName="*"
    - Convert SDK `TableWithColumnsResource` → `LFResource` with catalogId, databaseName, tableName, columnNames
    - Convert SDK `TableWithColumnsResource` with `ColumnWildcard` → `LFResource` with columnNames=null (table-level)
    - Convert SDK `DataLocationResource` → `LFResource` with dataLocationPath=resourceArn
    - Return null for `CatalogResource` and `LFTagPolicyResource`
    - _Requirements: 2.1, 2.5, 2.6_

  - [x] 3.4 Implement `reverseMapPermissions(List<Permission> sdkPermissions)` method
    - Convert SDK `Permission` enum values to `LFPermission` enum using case-insensitive matching
    - Skip unrecognized permission values and log warning
    - Return the resulting `Set<LFPermission>`
    - _Requirements: 2.2_

  - [x] 3.5 Write unit tests for `LFPermissionFetcher`
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/client/LFPermissionFetcherTest.java`
    - Mock the AWS SDK `LakeFormationClient` to return canned `ListPermissionsResponse` objects
    - Test single-page response with database, table, column, and data-location resources
    - Test multi-page pagination (3 pages with NextToken chaining)
    - Test `AccessDeniedException` throws `LakeFormationClientException` immediately
    - Test transient error retry and eventual success
    - Test transient error retry exhaustion throws `LakeFormationClientException`
    - Test `CatalogResource` entries are skipped with warning
    - Test `LFTagPolicyResource` entries are skipped with warning
    - Test `ColumnWildcard` maps to table-level resource
    - Test `TableWildcard` maps to wildcard table indicator
    - Test unrecognized permission values are skipped
    - Test entry with all unrecognized permissions is skipped entirely
    - Test `PermissionsWithGrantOption` creates separate grantable operation
    - Test `sourcePolicyId` is null for all returned operations
    - Test filter excludes matching principals
    - _Requirements: 1.1–1.7, 2.1–2.6, 7.1, 7.2, 7.5_

  - [x] 3.6 Write property test: LFResource round-trip (Property 1)
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/client/LFPermissionFetcherPropertyTest.java`
    - `@Property(tries = 100)`: For any valid `LFResource` (database, table, column, data-location), `buildResource()` → `reverseMapResource()` produces an equal `LFResource`
    - Use jqwik `@Provide` to generate random `LFResource` instances across all resource types
    - **Property 1: LFResource round-trip**
    - **Validates: Requirements 8.1, 1.3, 2.1**

  - [x] 3.7 Write property test: LFPermission round-trip (Property 2)
    - Add to `LFPermissionFetcherPropertyTest.java`
    - `@Property(tries = 100)`: For any valid non-empty set of `LFPermission` values, `toLfPermissions()` → `reverseMapPermissions()` produces an equal set
    - **Property 2: LFPermission round-trip**
    - **Validates: Requirements 8.2, 2.2**

  - [x] 3.8 Write property test: Fetched operations have null sourcePolicyId (Property 12)
    - Add to `LFPermissionFetcherPropertyTest.java`
    - `@Property(tries = 100)`: For any `LFPermissionOperation` produced by `normalizeEntry()`, `sourcePolicyId` is null
    - Generate random `PrincipalResourcePermissions` SDK entries and verify all normalized operations have null `sourcePolicyId`
    - **Property 12: Fetched operations have null sourcePolicyId**
    - **Validates: Requirements 2.4**

  - [x] 3.9 Write property test: Pagination completeness (Property 13)
    - Add to `LFPermissionFetcherPropertyTest.java`
    - `@Property(tries = 100)`: For any number of pages N (1–10), mock `ListPermissions` to return N pages with NextToken chaining, verify `fetchPermissions()` returns the concatenation of all entries from all pages
    - **Property 13: Pagination completeness**
    - **Validates: Requirements 1.2**

- [x] 4. Checkpoint - Ensure LFPermissionFetcher tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement `DriftDetector`
  - [x] 5.1 Create `DriftDetector` class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/sync/DriftDetector.java`
    - Implement `computeDrift(List<LFPermissionOperation> desired, List<LFPermissionOperation> actual, PermissionFilter filter, boolean reportOnly)` method
    - Reuse the `PermissionKey` identity pattern from `SyncService` (principalArn, resource, permissions, grantable) for comparison
    - Produce GRANT corrective operations for permissions in desired but not in actual
    - Produce REVOKE corrective operations for permissions in actual but not in desired
    - Apply `PermissionFilter.shouldExclude()` to exclude matching permissions from both desired and actual before comparison
    - Populate `DriftReport` with `missingGrants`, `extraPermissions`, `inSyncCount`, and `skippedPermissions`
    - When `reportOnly=true`, return `DriftResult` with empty `correctiveOperations` but fully populated `DriftReport`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x] 5.2 Write unit tests for `DriftDetector`
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/sync/DriftDetectorTest.java`
    - Test: identical desired and actual → zero drift, zero corrective ops
    - Test: desired has permissions not in actual → GRANT corrective ops
    - Test: actual has permissions not in desired → REVOKE corrective ops
    - Test: mixed drift (some missing, some extra, some in-sync)
    - Test: empty desired list → all actual become REVOKE ops
    - Test: empty actual list → all desired become GRANT ops
    - Test: both empty → zero drift
    - Test: report-only mode → DriftReport populated, correctiveOperations empty
    - Test: exclusion filter removes matching permissions from drift
    - Test: DriftReport counts are correct (missingGrants + extraPermissions + inSyncCount = union size)
    - _Requirements: 3.1–3.7_

  - [x] 5.3 Write property test: Drift correctness (Property 3)
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/sync/DriftDetectorPropertyTest.java`
    - `@Property(tries = 100)`: For any two lists of `LFPermissionOperation`, verify GRANT ops cover all desired-not-in-actual, REVOKE ops cover all actual-not-in-desired, no ops for in-sync, and `missingGrants + extraPermissions + inSyncCount` equals union size
    - **Property 3: Drift correctness**
    - **Validates: Requirements 3.2, 3.3, 3.4, 3.5**

  - [x] 5.4 Write property test: Identity / zero-drift (Property 4)
    - Add to `DriftDetectorPropertyTest.java`
    - `@Property(tries = 100)`: For any valid list, drift(desired=X, actual=X) → zero corrective ops, missingGrants=0, extraPermissions=0
    - **Property 4: Identity / zero-drift**
    - **Validates: Requirements 8.3**

  - [x] 5.5 Write property test: Convergence (Property 5)
    - Add to `DriftDetectorPropertyTest.java`
    - `@Property(tries = 100)`: For any desired and actual, applying corrective ops to actual produces a PermissionKey set equal to desired's PermissionKey set
    - **Property 5: Convergence**
    - **Validates: Requirements 8.4**

  - [x] 5.6 Write property test: Report-only mode (Property 6)
    - Add to `DriftDetectorPropertyTest.java`
    - `@Property(tries = 100)`: For any desired and actual, `computeDrift(reportOnly=true)` → empty correctiveOperations, DriftReport still correctly populated
    - **Property 6: Report-only mode produces no corrective operations**
    - **Validates: Requirements 3.6**

  - [x] 5.7 Write property test: Exclusion filter (Property 7)
    - Add to `DriftDetectorPropertyTest.java`
    - `@Property(tries = 100)`: For any desired, actual, and PermissionFilter with excluded principals, excluded permissions do not appear in corrective ops or contribute to missingGrants/extraPermissions counts
    - **Property 7: Exclusion filter removes matching permissions from drift**
    - **Validates: Requirements 3.7, 1.7**

- [x] 6. Checkpoint - Ensure DriftDetector tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement `ReverseSyncService` orchestrator
  - [x] 7.1 Create `ReverseSyncService` class
    - Create `src/main/java/com/amazonaws/policyconverters/lakeformation/sync/ReverseSyncService.java`
    - Constructor takes `LFPermissionFetcher`, `DriftDetector`, `LakeFormationClient`, `CedarToLFConverter`, `DeadLetterLogger`
    - Include `AtomicBoolean running` concurrency guard
    - Implement `execute(ReverseSyncConfig config, CedarPolicySet cedarPolicySet)` method:
      1. CAS on `running` → reject with `IllegalStateException` if already running
      2. Guard: if `cedarPolicySet` is null or empty → skip cycle, log error, return zero-op result
      3. Convert Cedar to desired state via `cedarToLFConverter.convert(cedarPolicySet)`
      4. Fetch actual state via `fetcher.fetchPermissions(config.getCatalogId(), config.getFilter())`
      5. Compute drift via `driftDetector.computeDrift(desired, actual, config.getFilter(), config.isReportOnly())`
      6. If `reportOnly` → return result with drift report, no corrections applied
      7. If `dryRun` → serialize corrective ops via `DryRunLakeFormationClient`, include drift summary
      8. Otherwise → order corrective ops (REVOKEs first, then GRANTs), apply each individually with continue-on-failure, log failures to `DeadLetterLogger`
      9. Build and return `ReverseSyncResult` with counts and duration
      10. Always reset `running` to false in finally block
    - Emit structured log entries at start and end of each cycle
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 5.1, 5.2, 5.4, 5.5, 5.6, 6.1, 6.4, 7.3, 7.4_

  - [x] 7.2 Write unit tests for `ReverseSyncService`
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/sync/ReverseSyncServiceTest.java`
    - Mock `LFPermissionFetcher`, `DriftDetector`, `LakeFormationClient`, `CedarToLFConverter`, `DeadLetterLogger`
    - Test: happy path orchestration flow (fetch → drift → apply → result)
    - Test: REVOKE operations applied before GRANT operations
    - Test: report-only mode skips apply phase
    - Test: dry-run mode uses `DryRunLakeFormationClient`
    - Test: empty Cedar policy set → skip cycle, zero-op result, no revocations
    - Test: null Cedar policy set → skip cycle, zero-op result
    - Test: concurrent execution rejected with `IllegalStateException`
    - Test: corrective operation failure → logged to DeadLetterLogger, remaining ops continue
    - Test: multiple failures → all recorded in result, all remaining ops attempted
    - Test: structured log entries emitted at start and end of cycle
    - Test: `running` flag reset even when exception occurs
    - Test: result contains correct counts (successfulGrants, successfulRevokes, failedOperations, durationMs)
    - _Requirements: 4.1–4.6, 5.1, 5.2, 5.4, 5.5, 5.6, 6.1, 6.4, 7.3, 7.4_

  - [x] 7.3 Write property test: REVOKE-before-GRANT ordering (Property 8)
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/sync/ReverseSyncServicePropertyTest.java`
    - `@Property(tries = 100)`: For any list of corrective operations, when `ReverseSyncService` orders them for application, all REVOKE operations appear before all GRANT operations
    - **Property 8: REVOKE-before-GRANT ordering**
    - **Validates: Requirements 4.1**

  - [x] 7.4 Write property test: Continue-on-failure (Property 9)
    - Add to `ReverseSyncServicePropertyTest.java`
    - `@Property(tries = 100)`: For any list of corrective operations where some fail, all remaining operations are still attempted, and the result accurately reports successful and failed counts
    - Mock `LakeFormationClient` to fail on specific operations
    - **Property 9: Continue-on-failure**
    - **Validates: Requirements 4.3, 4.4, 7.3**

  - [x] 7.5 Write property test: Empty Cedar safety guard (Property 10)
    - Add to `ReverseSyncServicePropertyTest.java`
    - `@Property(tries = 100)`: For any empty or null `CedarPolicySet`, `execute()` produces zero corrective operations
    - **Property 10: Empty Cedar safety guard**
    - **Validates: Requirements 5.5**

  - [x] 7.6 Write property test: Concurrency guard (Property 11)
    - Add to `ReverseSyncServicePropertyTest.java`
    - `@Property(tries = 100)`: For any two concurrent invocations, exactly one succeeds and the other is rejected with `IllegalStateException`
    - Use `CountDownLatch` to synchronize concurrent threads
    - **Property 11: Concurrency guard**
    - **Validates: Requirements 5.4**

- [x] 8. Checkpoint - Ensure ReverseSyncService tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement dry-run serialization round-trip test
  - [x] 9.1 Write property test: Dry-run reverse-sync round-trip (Property 14)
    - Create `src/test/java/com/amazonaws/policyconverters/lakeformation/client/ReverseSyncDryRunOutputRoundTripPropertyTest.java`
    - `@Property(tries = 100)`: For any `ReverseSyncDryRunOutput` object, serializing to JSON via Jackson and deserializing back produces an equivalent object including the `driftSummary` field
    - Generate random `DriftReport` and `LFPermissionOperation` lists
    - **Property 14: Dry-run reverse-sync round-trip**
    - **Validates: Requirements 6.1, 6.2, 6.3**

- [x] 10. Wire reverse-sync into configuration and entry point
  - [x] 10.1 Extend `server-config.yaml` with `reverseSync` section
    - Add `reverseSync` configuration block to `conf/server-config.yaml` with fields: `enabled`, `reportOnly`, `dryRun`, `periodicIntervalMs`, `catalogId`, `exclusionFilter` (with `excludedPrincipals` and `excludedResourcePatterns`)
    - All fields have sensible defaults (enabled=false, reportOnly=false, dryRun=false, periodicIntervalMs=0)
    - _Requirements: 5.1, 5.3_

  - [x] 10.2 Add `ReverseSyncConfig` deserialization support to config loader
    - Update the server config loader to parse the `reverseSync` YAML section into `ReverseSyncConfig`
    - Default `catalogId` to `awsConfig.catalogId` if not explicitly set
    - Map `exclusionFilter` to `PermissionFilter`
    - _Requirements: 5.1_

  - [x] 10.3 Wire `ReverseSyncService` into the application entry point
    - Instantiate `LFPermissionFetcher` with the AWS SDK LakeFormation client and `RetryConfig`
    - Instantiate `DriftDetector`
    - Instantiate `ReverseSyncService` with fetcher, detector, existing `LakeFormationClient` (or `DryRunLakeFormationClient`), `CedarToLFConverter`, and `DeadLetterLogger`
    - When `reverseSync.enabled=true`, trigger `execute()` after each forward-sync cycle (or on periodic schedule if `periodicIntervalMs > 0`)
    - _Requirements: 5.1, 5.2, 5.3, 6.4_

  - [x] 10.4 Write unit tests for reverse-sync configuration loading
    - Test YAML deserialization of `reverseSync` section into `ReverseSyncConfig`
    - Test default values when `reverseSync` section is absent
    - Test `catalogId` defaults to `awsConfig.catalogId`
    - Test `exclusionFilter` maps correctly to `PermissionFilter`
    - _Requirements: 5.1_

- [x] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document (Properties 1–14)
- Unit tests validate specific examples, edge cases, and error conditions
- The `sync` package (`com.amazonaws.policyconverters.lakeformation.sync`) is new and will contain `DriftDetector` and `ReverseSyncService`
- The `LFPermissionFetcher` and `ReverseSyncDryRunOutput` go in the existing `client` package alongside `LakeFormationClient`
- New model classes go in the existing `model` package
