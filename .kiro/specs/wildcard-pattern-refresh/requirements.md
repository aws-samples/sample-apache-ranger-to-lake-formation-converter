# Requirements Document

## Introduction

When Ranger policies use glob wildcard patterns (e.g., `table_*`, `database_*`, `events_?`) to match a subset of resources by name, the system currently expands these patterns against the AWS Glue Data Catalog only at the time of policy conversion. If new tables or databases are added to the Glue catalog after the initial expansion, those new resources are not covered until Ranger pushes a new policy update. This causes permissions to become stale for glob-pattern policies.

The bare `*` wildcard case (meaning ALL tables) is already handled via Lake Formation's `TableWildcard`, which automatically covers future tables. This feature addresses the remaining glob patterns that match a subset of resources by name pattern.

The solution introduces a periodic catalog re-scan that re-evaluates all active policies containing glob patterns against the current Glue catalog. When expansion produces different results compared to the previous expansion, the system computes a delta and applies the necessary grant/revoke operations to Lake Formation.

## Glossary

- **Conversion_Server**: The main application process (`ConversionServerMain`) that runs the sync loop, converting Ranger policies to Lake Formation permissions.
- **SyncService**: The orchestrator that receives Ranger policy updates, converts them through the Cedar pipeline, computes diffs, and applies delta operations to Lake Formation.
- **CatalogResolver**: The component that queries the AWS Glue Data Catalog to expand wildcard patterns into explicit database, table, and column names.
- **PolicyConverter**: The component that converts individual Ranger policies into Lake Formation permission operations, delegating wildcard expansion to the CatalogResolver.
- **ServerLifecycle**: The run-loop controller that executes sync cycles at a configured interval and coordinates graceful shutdown.
- **SyncCycleExecutor**: The functional interface representing a single sync cycle execution, used by ServerLifecycle.
- **Glob_Pattern**: A Ranger wildcard pattern that matches a subset of resources by name (e.g., `table_*`, `db_?`), excluding the bare `*` which already maps to Lake Formation's TableWildcard.
- **Wildcard_Refresh_Cycle**: A periodic task that re-evaluates all active policies containing glob patterns against the current Glue Data Catalog and applies any resulting permission deltas.
- **Delta_Operations**: The set of new grants and revocations computed by diffing the previous expansion results against the current expansion results.
- **LakeFormationClient**: The wrapper around the AWS Lake Formation SDK that applies batch grant/revoke operations with retry logic.

## Requirements

### Requirement 1: Periodic Wildcard Refresh Scheduling

**User Story:** As an administrator, I want the system to periodically re-evaluate glob wildcard patterns against the Glue catalog, so that new resources matching existing patterns receive the correct Lake Formation permissions without waiting for a Ranger policy push.

#### Acceptance Criteria

1. THE Conversion_Server SHALL support a `wildcardRefreshIntervalSeconds` configuration property in `server-config.yaml` that controls the interval between Wildcard_Refresh_Cycles.
2. WHEN `wildcardRefreshIntervalSeconds` is set to a positive integer, THE Conversion_Server SHALL execute a Wildcard_Refresh_Cycle at the configured interval independently of the normal Ranger policy sync cycle.
3. WHEN `wildcardRefreshIntervalSeconds` is set to `0` or is absent from the configuration, THE Conversion_Server SHALL disable periodic wildcard refresh.
4. WHEN the Conversion_Server starts with a valid `wildcardRefreshIntervalSeconds` value, THE Conversion_Server SHALL log the configured interval at INFO level.
5. IF `wildcardRefreshIntervalSeconds` is set to a negative value, THEN THE Conversion_Server SHALL reject the configuration and log an error at startup.

### Requirement 2: Glob Pattern Detection

**User Story:** As a developer, I want the system to identify which active policies contain glob patterns, so that only relevant policies are re-evaluated during a wildcard refresh cycle.

#### Acceptance Criteria

1. THE SyncService SHALL identify policies containing Glob_Patterns by detecting `*` or `?` characters in database, table, or column resource values, excluding the bare `*` pattern that already maps to Lake Formation's TableWildcard.
2. WHEN a Wildcard_Refresh_Cycle is triggered, THE SyncService SHALL re-evaluate only the policies that contain at least one Glob_Pattern in their resource definitions.
3. THE SyncService SHALL use the `lastKnownPolicies` snapshot as the source of policies for re-evaluation during a Wildcard_Refresh_Cycle.

### Requirement 3: Catalog Re-Expansion and Delta Computation

**User Story:** As a developer, I want the system to re-expand glob patterns against the current Glue catalog and compute the difference from the previous expansion, so that only the necessary permission changes are applied.

#### Acceptance Criteria

1. WHEN a Wildcard_Refresh_Cycle executes, THE SyncService SHALL re-run the conversion pipeline on policies containing Glob_Patterns by querying the CatalogResolver with the current Glue Data Catalog state.
2. WHEN re-expansion produces a set of LF permission operations different from the `previousOperations` snapshot, THE SyncService SHALL compute Delta_Operations using the existing `computeDiff` mechanism.
3. WHEN Delta_Operations contain new grants, THE LakeFormationClient SHALL apply the grants using the existing batch grant mechanism.
4. WHEN Delta_Operations contain revocations (resources that previously matched a Glob_Pattern but no longer exist in the catalog), THE LakeFormationClient SHALL apply the revocations using the existing batch revoke mechanism.
5. WHEN re-expansion produces the same set of LF permission operations as the `previousOperations` snapshot, THE SyncService SHALL log that no changes were detected and skip applying operations.
6. WHEN a Wildcard_Refresh_Cycle completes, THE SyncService SHALL update the `previousOperations` snapshot to reflect the re-expanded state.

### Requirement 4: Interaction with Normal Sync Cycles

**User Story:** As a developer, I want the wildcard refresh to coexist safely with the normal Ranger policy sync cycle, so that concurrent updates do not cause inconsistent permission states.

#### Acceptance Criteria

1. THE Conversion_Server SHALL ensure that a Wildcard_Refresh_Cycle and a normal Ranger policy sync cycle do not execute concurrently.
2. WHEN a normal Ranger policy sync cycle is in progress and a Wildcard_Refresh_Cycle is due, THE Conversion_Server SHALL defer the Wildcard_Refresh_Cycle until the sync cycle completes.
3. WHEN a Wildcard_Refresh_Cycle is in progress and a normal Ranger policy sync cycle is triggered, THE Conversion_Server SHALL defer the sync cycle until the Wildcard_Refresh_Cycle completes.
4. WHEN a normal Ranger policy sync cycle completes and updates `previousOperations`, THE SyncService SHALL use the updated `previousOperations` as the baseline for the next Wildcard_Refresh_Cycle.

### Requirement 5: Error Handling and Resilience

**User Story:** As an administrator, I want the wildcard refresh to handle errors gracefully, so that transient failures do not disrupt the normal sync process or leave permissions in an inconsistent state.

#### Acceptance Criteria

1. IF the CatalogResolver fails to query the Glue Data Catalog during a Wildcard_Refresh_Cycle, THEN THE SyncService SHALL log the error, skip the current refresh cycle, and retain the existing `previousOperations` snapshot unchanged.
2. IF the LakeFormationClient encounters partial failures when applying Delta_Operations from a Wildcard_Refresh_Cycle, THEN THE SyncService SHALL log the failed operations to the dead-letter log using the existing DeadLetterLogger.
3. IF a Wildcard_Refresh_Cycle throws an unexpected exception, THEN THE Conversion_Server SHALL log the error and continue operating normally, scheduling the next Wildcard_Refresh_Cycle at the configured interval.
4. WHILE the Conversion_Server has lost connectivity to Ranger Admin, THE Conversion_Server SHALL continue executing Wildcard_Refresh_Cycles using the `lastKnownPolicies` snapshot.

### Requirement 6: Observability

**User Story:** As an administrator, I want visibility into wildcard refresh activity, so that I can monitor whether new resources are being picked up and diagnose issues.

#### Acceptance Criteria

1. WHEN a Wildcard_Refresh_Cycle starts, THE SyncService SHALL log the number of policies being re-evaluated and the trigger reason at INFO level.
2. WHEN a Wildcard_Refresh_Cycle completes, THE SyncService SHALL log the number of new grants, revocations, and unchanged operations at INFO level.
3. WHEN Delta_Operations are applied during a Wildcard_Refresh_Cycle, THE SyncService SHALL emit audit log entries for each grant and revoke operation using the existing audit logging mechanism.
4. THE Conversion_Server SHALL emit a CloudWatch metric for each Wildcard_Refresh_Cycle recording the cycle duration, number of delta operations applied, and success or failure status.

### Requirement 7: Checkpoint Persistence for Wildcard Refresh

**User Story:** As an administrator, I want the wildcard refresh state to survive server restarts, so that the system does not re-apply all permissions from scratch after a restart.

#### Acceptance Criteria

1. WHEN a Wildcard_Refresh_Cycle updates the `previousOperations` snapshot, THE SyncService SHALL persist the updated Cedar policy text to the checkpoint store using the existing CheckpointStore mechanism.
2. WHEN the Conversion_Server restarts and restores from a checkpoint, THE SyncService SHALL use the restored `previousOperations` as the baseline for the next Wildcard_Refresh_Cycle.
