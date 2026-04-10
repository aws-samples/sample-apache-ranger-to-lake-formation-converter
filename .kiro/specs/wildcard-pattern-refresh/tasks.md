# Implementation Plan: Wildcard Pattern Refresh

## Overview

Implement periodic re-evaluation of glob wildcard patterns in Ranger policies against the Glue Data Catalog. The implementation follows a bottom-up approach: foundational utilities first, then data models, configuration, core logic, scheduling, wiring, metrics, and finally tests. All components are Java 17, using the existing Maven project structure with jqwik for property tests and JUnit 5 for unit tests.

## Tasks

- [x] 1. Create GlobPatternDetector utility and WildcardRefreshResult data model
  - [x] 1.1 Create `GlobPatternDetector` utility class
    - Create `src/main/java/com/amazonaws/policyconverters/ranger/GlobPatternDetector.java`
    - Implement `isGlobPattern(String value)`: returns true if value contains `*` or `?` but is not exactly `"*"`
    - Implement `hasGlobPatterns(RangerPolicy policy)`: checks all resource values (database, table, column) in the policy
    - Implement `filterGlobPolicies(List<RangerPolicy> policies)`: returns only policies where `hasGlobPatterns` is true
    - _Requirements: 2.1, 2.2_

  - [x] 1.2 Create `WildcardRefreshResult` data model
    - Create `src/main/java/com/amazonaws/policyconverters/model/WildcardRefreshResult.java`
    - Fields: `success`, `durationMs`, `policiesEvaluated`, `newGrants`, `revocations`, `unchanged`, `error`
    - Static factory methods: `success(long, int, int, int, int)` and `failure(long, Exception)`
    - _Requirements: 6.2, 6.4_

  - [x] 1.3 Write property test: Glob pattern detection is correct for all resource strings
    - **Property 2: Glob pattern detection is correct for all resource strings**
    - Create `src/test/java/com/amazonaws/policyconverters/ranger/GlobPatternDetectorPropertyTest.java`
    - Generate random strings (with/without `*`/`?`, bare `*`, empty, unicode) using jqwik
    - Verify `isGlobPattern()` returns true iff string contains `*` or `?` and is not exactly `"*"`
    - **Validates: Requirements 2.1**

  - [x] 1.4 Write property test: Wildcard policy filter returns exactly glob-containing policies
    - **Property 3: Wildcard policy filter returns exactly glob-containing policies**
    - Create or extend `GlobPatternDetectorPropertyTest.java`
    - Generate random lists of `RangerPolicy` objects with varying resource patterns using jqwik
    - Verify `filterGlobPolicies()` returns exactly those policies where `hasGlobPatterns()` is true
    - **Validates: Requirements 2.2**

  - [x] 1.5 Write unit tests for GlobPatternDetector
    - Create `src/test/java/com/amazonaws/policyconverters/ranger/GlobPatternDetectorTest.java`
    - Test `isGlobPattern`: `"table_*"` → true, `"db_?"` → true, `"*"` → false, `"exact_name"` → false, `""` → false, `null` → false
    - Test `hasGlobPatterns`: policy with glob in database, table, column resources; policy with no globs; policy with bare `*` only
    - Test `filterGlobPolicies`: mixed list returns only glob-containing policies
    - _Requirements: 2.1, 2.2_

- [x] 2. Extend SyncConfig and ConfigValidator for wildcard refresh interval
  - [x] 2.1 Add `wildcardRefreshIntervalSeconds` field to `SyncConfig`
    - Add `private final int wildcardRefreshIntervalSeconds` field with default `0`
    - Add `@JsonProperty("wildcardRefreshIntervalSeconds")` to the `@JsonCreator` constructor
    - Add getter `getWildcardRefreshIntervalSeconds()`
    - Update `equals()`, `hashCode()`, and `toString()` to include the new field
    - _Requirements: 1.1, 1.3_

  - [x] 2.2 Add validation in `ConfigValidator` for negative values
    - In `ConfigValidator.validate()`, add check: if `wildcardRefreshIntervalSeconds < 0`, add error `"Invalid parameter: wildcardRefreshIntervalSeconds must be >= 0"`
    - _Requirements: 1.5_

  - [x] 2.3 Write property test: Configuration validation classifies all integers correctly
    - **Property 1: Configuration validation classifies all integers correctly**
    - Create `src/test/java/com/amazonaws/policyconverters/config/SyncConfigWildcardPropertyTest.java`
    - Generate random integers using jqwik; build `SyncConfig` with each value
    - Verify: positive values stored as-is, zero treated as disabled (stored as 0), negative values produce validation error
    - **Validates: Requirements 1.1, 1.3, 1.5**

  - [x] 2.4 Write unit tests for SyncConfig and ConfigValidator wildcard changes
    - Extend `src/test/java/com/amazonaws/policyconverters/config/ConfigValidatorTest.java`
    - Test: absent/null defaults to 0, zero stores as 0, positive value stores correctly, negative value produces validation error
    - Test: YAML deserialization with `wildcardRefreshIntervalSeconds: 300` parses correctly
    - _Requirements: 1.1, 1.3, 1.5_

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement `SyncService.executeWildcardRefresh()` method
  - [x] 4.1 Add `executeWildcardRefresh()` method to `SyncService`
    - Filter `lastKnownPolicies` using `GlobPatternDetector.filterGlobPolicies()`
    - Log number of glob policies being re-evaluated at INFO level
    - Re-convert glob policies through `rangerToCedarConverter.convert()` and `cedarToLFConverter.convert()`
    - Build merged operation set: non-glob ops from `previousOperations` (by source policy ID) + re-expanded glob ops
    - Call `computeDiff(previousOperations, mergedOperations)`
    - If delta is non-empty, call `lakeFormationClient.applyBatch(delta, deadLetterLogger)` and log audit entries
    - If delta is empty, log "no changes detected" at INFO level
    - Update `previousOperations`, `lastCedarPolicyText`, and persist checkpoint
    - Return `WildcardRefreshResult` with counts
    - Wrap entire method in try-catch: on CatalogResolver/Glue failure, log error, return `WildcardRefreshResult.failure()`, leave `previousOperations` unchanged
    - _Requirements: 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 5.1, 5.2, 6.1, 6.2, 6.3, 7.1, 7.2_

  - [x] 4.2 Write property test: Diff computation correctly partitions operations
    - **Property 4: Diff computation correctly partitions operations into grants, revocations, and unchanged**
    - Create `src/test/java/com/amazonaws/policyconverters/sync/SyncServiceDiffPropertyTest.java`
    - Generate random pairs of `List<LFPermissionOperation>` using jqwik
    - Verify: new grants = in current but not previous, revocations = in previous but not current, unchanged = intersection size, union invariant holds
    - **Validates: Requirements 3.2**

  - [x] 4.3 Write unit tests for `executeWildcardRefresh()`
    - Create `src/test/java/com/amazonaws/policyconverters/sync/SyncServiceWildcardRefreshTest.java`
    - Test: no glob policies → returns success with 0 evaluated, no applyBatch call
    - Test: new table appears in Glue → new grant in delta, applyBatch called
    - Test: table removed from Glue → revocation in delta, applyBatch called
    - Test: identical re-expansion → no delta, applyBatch not called, "no changes" logged
    - Test: CatalogResolver throws → `previousOperations` unchanged, failure result returned
    - Test: partial LF failure → dead-letter entries written, `previousOperations` still updated
    - Test: checkpoint persisted after successful refresh
    - Test: uses `lastKnownPolicies` as source (connectivity loss scenario)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 5.1, 5.2, 5.4, 7.1_

- [x] 5. Implement lock coordination in ServerLifecycle and WildcardRefreshScheduler
  - [x] 5.1 Add `ReentrantLock` coordination to `ServerLifecycle`
    - Add `private final ReentrantLock cycleLock` field, accepted via constructor parameter
    - Provide a backward-compatible constructor that creates its own `ReentrantLock` internally
    - Add getter `getCycleLock()` for sharing with `WildcardRefreshScheduler`
    - Wrap `executeCycle()` body in `cycleLock.lock()` / `finally { cycleLock.unlock() }`
    - _Requirements: 4.1, 4.2, 4.3_

  - [x] 5.2 Create `WildcardRefreshScheduler` class
    - Create `src/main/java/com/amazonaws/policyconverters/app/WildcardRefreshScheduler.java`
    - Fields: `SyncService`, `MetricsEmitter`, `ReentrantLock cycleLock`, `ScheduledExecutorService scheduler`
    - `start(int intervalSeconds)`: if interval <= 0, no-op; otherwise schedule at fixed rate using `Executors.newSingleThreadScheduledExecutor()`
    - Each scheduled task: acquire `cycleLock`, call `syncService.executeWildcardRefresh()`, emit metrics via `metricsEmitter.recordWildcardRefresh()`, release lock in finally block
    - Wrap task body in try-catch to prevent `ScheduledExecutorService` task cancellation on exception
    - `shutdown(int timeoutSeconds)`: call `scheduler.shutdown()`, then `awaitTermination(timeoutSeconds, SECONDS)`
    - _Requirements: 1.2, 4.1, 4.2, 4.3, 5.3, 6.4_

  - [x] 5.3 Write unit tests for lock coordination and scheduler
    - Create `src/test/java/com/amazonaws/policyconverters/app/WildcardRefreshSchedulerTest.java`
    - Test: `start(0)` is a no-op, no scheduler created
    - Test: `start(5)` schedules at 5-second interval, `executeWildcardRefresh()` is called
    - Test: lock is acquired before and released after refresh execution
    - Test: exception in `executeWildcardRefresh()` does not cancel future scheduled tasks
    - Test: `shutdown()` stops the scheduler and waits for in-flight cycle
    - Test: sync cycle and refresh cycle are mutually exclusive (use `CountDownLatch` coordination)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.3_

- [x] 6. Extend MetricsEmitter and wire ConversionServerMain
  - [x] 6.1 Add `recordWildcardRefresh(WildcardRefreshResult)` to `MetricsEmitter`
    - Emit `WildcardRefreshSuccess` or `WildcardRefreshFailure` (count=1)
    - Emit `WildcardRefreshDuration` (milliseconds)
    - Emit `WildcardRefreshDeltaOperations` (count of newGrants + revocations)
    - All metrics include `ServiceName=conversion-server` dimension
    - _Requirements: 6.4_

  - [x] 6.2 Wire wildcard refresh into `ConversionServerMain.startServer()`
    - Read `wildcardRefreshIntervalSeconds` from `SyncConfig`
    - Create `ReentrantLock` and pass to `ServerLifecycle` constructor
    - Create `WildcardRefreshScheduler` with `SyncService`, `MetricsEmitter`, and the shared lock
    - If interval > 0, log at INFO level and call `scheduler.start(interval)`
    - Register `scheduler.shutdown()` in the SIGTERM shutdown hook (before closing AWS clients)
    - _Requirements: 1.2, 1.4, 4.1_

  - [x] 6.3 Write unit tests for MetricsEmitter wildcard refresh metrics
    - Extend `src/test/java/com/amazonaws/policyconverters/reporting/MetricsEmitterTest.java`
    - Test: success result emits `WildcardRefreshSuccess`, `WildcardRefreshDuration`, `WildcardRefreshDeltaOperations`
    - Test: failure result emits `WildcardRefreshFailure`
    - _Requirements: 6.4_

- [x] 7. Update configuration files
  - [x] 7.1 Add `wildcardRefreshIntervalSeconds` to configuration YAML files
    - Add `wildcardRefreshIntervalSeconds: 0` (disabled by default) to `conf/server-config.yaml` with documentation comment
    - Add same to `deploy/server-config-deploy.yaml` and `src/main/deploy/server-config-deploy.yaml`
    - Add same to `integration-test/docker/server-config-it.yaml`
    - _Requirements: 1.1, 1.3_

- [x] 8. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The implementation reuses the existing sync pipeline (`RangerToCedarConverter` → `CedarToLFConverter` → `computeDiff` → `applyBatch`) — no new diff or application logic is needed
