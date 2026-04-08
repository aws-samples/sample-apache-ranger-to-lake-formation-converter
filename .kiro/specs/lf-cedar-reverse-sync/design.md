# Design Document: LakeFormation Cedar Reverse Sync

## Overview

The reverse-sync feature adds a reconciliation path to the existing Ranger → Cedar → LakeFormation pipeline. While the forward pipeline converts Cedar-authoritative policies into LakeFormation GRANT/REVOKE operations, the reverse-sync retrieves the *actual* state of LakeFormation permissions via the `ListPermissions` API, normalizes them into the same `LFPermissionOperation` model, computes a diff against the Cedar-derived desired state, and applies corrective operations to bring LakeFormation back into alignment.

This addresses drift caused by out-of-band changes (console edits, other automation, partial apply failures). The feature reuses the existing `SyncService.computeDiff` PermissionKey-based comparison, `LakeFormationClient.applyBatch` with retry/rollback semantics, `DryRunLakeFormationClient` for dry-run mode, and `DeadLetterLogger` for failed operations.

### Key Design Decisions

1. **Reuse `computeDiff` logic**: The existing `SyncService.PermissionKey` identity (principalArn, resource, permissions, grantable) is the correct comparison key for reverse-sync drift detection. We reuse this rather than duplicating.
2. **New `LFPermissionFetcher` class**: Encapsulates `ListPermissions` pagination, SDK-to-model normalization, and filtering. Keeps the `LakeFormationClient` focused on write operations.
3. **New `ReverseSyncService` orchestrator**: Separate from `SyncService` to avoid coupling forward-sync lifecycle with reverse-sync. Shares the same `LakeFormationClient` and `DeadLetterLogger` instances.
4. **REVOKE-before-GRANT ordering**: Corrective operations apply REVOKEs first to avoid transient over-permissioning windows.
5. **Continue-on-failure semantics**: Unlike the forward-sync `applyBatch` which rolls back per-policy, reverse-sync corrective operations use continue-on-failure since they have no policy grouping.

## Architecture

```
                          Reverse Sync Pipeline
                    ┌─────────────────────────────────────┐
                    │  ReverseSyncService (orchestrator)   │
                    │                                      │
  Cedar-derived     │  ┌──────────────────┐                │
  desired state ───►│  │ LFPermissionFetcher│               │
  (from forward     │  │  ListPermissions  │               │
   pipeline)        │  │  + pagination     │               │
                    │  │  + normalization  │               │    LF API
                    │  └────────┬─────────┘               │
                    │           │ List<LFPermissionOperation>
                    │           ▼                          │
                    │  ┌──────────────────┐               │
                    │  │  DriftDetector    │               │
                    │  │  computeDrift()   │               │
                    │  │  (reuses          │               │
                    │  │   PermissionKey)  │               │
                    │  └────────┬─────────┘               │
                    │           │ DriftResult              │
                    │           ▼                          │
                    │  ┌──────────────────┐               │
                    │  │ LakeFormationClient│──────────────┼──► GRANT/REVOKE
                    │  │  or DryRun client │              │
                    │  └──────────────────┘               │
                    └─────────────────────────────────────┘
```

### Integration with Existing Pipeline

The reverse-sync operates alongside the forward-sync pipeline:

```
Forward:  Ranger → Cedar → CedarToLFConverter → SyncService.computeDiff → applyBatch
                                    │
                                    ▼
                          Cedar-derived desired state
                                    │
Reverse:  LF ListPermissions → LFPermissionFetcher → DriftDetector.computeDrift → applyBatch
```

Both pipelines share:
- `LakeFormationClient` (or `DryRunLakeFormationClient`)
- `DeadLetterLogger`
- `RetryConfig`
- Model classes (`LFPermissionOperation`, `LFResource`, `LFPermission`)

The `ReverseSyncService` obtains the Cedar-derived desired state by calling `CedarToLFConverter.convert()` on the current `CedarPolicySet`, the same conversion the forward pipeline uses.

## Components and Interfaces

### 1. LFPermissionFetcher

**Package:** `com.amazonaws.policyconverters.lakeformation.client`

Responsible for calling `ListPermissions`, paginating, and normalizing SDK responses into `LFPermissionOperation` objects.

```java
public class LFPermissionFetcher {

    private final software.amazon.awssdk.services.lakeformation.LakeFormationClient lfClient;
    private final RetryConfig retryConfig;
    private final LakeFormationClient.Sleeper sleeper;

    /**
     * Fetch all LakeFormation permissions for the given catalog.
     * Paginates through all pages using NextToken.
     * Retries transient errors with exponential backoff.
     *
     * @param catalogId the Glue Data Catalog ID
     * @param filter    optional filter for scoped retrieval (may be null)
     * @return list of normalized LFPermissionOperation objects (all with OperationType.GRANT)
     * @throws LakeFormationClientException if retrieval fails after retries
     */
    public List<LFPermissionOperation> fetchPermissions(String catalogId, PermissionFilter filter)
            throws LakeFormationClientException;

    /**
     * Normalize a single PrincipalResourcePermissions SDK entry into
     * zero or more LFPermissionOperation objects.
     * Returns empty list if the entry has an unrecognized resource type or permission.
     */
    List<LFPermissionOperation> normalizeEntry(PrincipalResourcePermissions entry);

    /**
     * Convert an SDK Resource to an LFResource.
     * This is the reverse of LakeFormationClient.buildResource().
     * Returns null for unsupported resource types (CatalogResource, LFTagPolicy).
     */
    LFResource reverseMapResource(Resource sdkResource);

    /**
     * Convert SDK Permission enums to LFPermission enums.
     * Skips unrecognized values and logs a warning.
     */
    Set<LFPermission> reverseMapPermissions(List<Permission> sdkPermissions);
}
```

**Design rationale:** Placed in the `client` package alongside `LakeFormationClient` since it wraps the same SDK client. Uses the same `RetryConfig` and `Sleeper` abstractions for testability.

### 2. PermissionFilter

**Package:** `com.amazonaws.policyconverters.lakeformation.model`

Configurable filter for scoped permission retrieval and drift exclusion.

```java
public class PermissionFilter {

    private final String principalArn;        // filter by specific principal (nullable)
    private final String resourceType;        // filter by resource type (nullable)
    private final Set<String> excludedPrincipals;  // principals to exclude from drift
    private final Set<String> excludedResourcePatterns; // resource patterns to exclude

    public boolean shouldExclude(LFPermissionOperation op);
}
```

### 3. DriftDetector

**Package:** `com.amazonaws.policyconverters.lakeformation.sync`

Computes the diff between Cedar-derived desired state and LF actual state.

```java
public class DriftDetector {

    /**
     * Compute drift between desired and actual LakeFormation permissions.
     *
     * @param desired   Cedar-derived desired operations (all GRANTs)
     * @param actual    LF-retrieved actual operations (all GRANTs)
     * @param filter    exclusion filter (may be null)
     * @param reportOnly if true, compute report without producing corrective ops
     * @return DriftResult containing report and optional corrective operations
     */
    public DriftResult computeDrift(
            List<LFPermissionOperation> desired,
            List<LFPermissionOperation> actual,
            PermissionFilter filter,
            boolean reportOnly);
}
```

**Design rationale:** Extracted from `SyncService.computeDiff` to allow reuse. Internally uses the same `PermissionKey` comparison. The `reportOnly` flag supports requirement 3.6.

### 4. DriftResult

**Package:** `com.amazonaws.policyconverters.lakeformation.model`

```java
public class DriftResult {

    private final DriftReport report;
    private final List<LFPermissionOperation> correctiveOperations; // empty if reportOnly

    public DriftReport getReport();
    public List<LFPermissionOperation> getCorrectiveOperations();
}
```

### 5. DriftReport

**Package:** `com.amazonaws.policyconverters.lakeformation.model`

```java
public class DriftReport {

    private final int missingGrants;      // in desired but not in actual
    private final int extraPermissions;   // in actual but not in desired
    private final int inSyncCount;        // in both
    private final List<LFPermissionOperation> skippedPermissions;  // unrecognized/excluded
    private final List<FailedOperation> failedOperations;          // populated post-apply

    public int getTotalDrift();  // missingGrants + extraPermissions
}
```

### 6. ReverseSyncService

**Package:** `com.amazonaws.policyconverters.lakeformation.sync`

Orchestrates the full reverse-sync cycle.

```java
public class ReverseSyncService {

    private final LFPermissionFetcher fetcher;
    private final DriftDetector driftDetector;
    private final LakeFormationClient lakeFormationClient;
    private final CedarToLFConverter cedarToLFConverter;
    private final DeadLetterLogger deadLetterLogger;
    private final AtomicBoolean running;  // concurrency guard

    /**
     * Execute a single reverse-sync cycle.
     *
     * @param config the reverse sync configuration
     * @param cedarPolicySet the current Cedar-authoritative policy set
     * @return ReverseSyncResult summarizing the cycle
     * @throws LakeFormationClientException if LF retrieval fails
     * @throws IllegalStateException if a sync cycle is already running
     */
    public ReverseSyncResult execute(ReverseSyncConfig config, CedarPolicySet cedarPolicySet)
            throws LakeFormationClientException;
}
```

### 7. ReverseSyncConfig

**Package:** `com.amazonaws.policyconverters.lakeformation.model`

```java
public class ReverseSyncConfig {

    private final String catalogId;
    private final boolean reportOnly;
    private final boolean dryRun;
    private final PermissionFilter filter;
    private final long periodicIntervalMs;  // 0 = on-demand only
}
```

### 8. ReverseSyncResult

**Package:** `com.amazonaws.policyconverters.lakeformation.model`

```java
public class ReverseSyncResult {

    private final DriftReport driftReport;
    private final int successfulGrants;
    private final int successfulRevokes;
    private final int failedOperations;
    private final long durationMs;
}
```

### 9. Extended DryRunOutput

The existing `DryRunOutput` is extended for reverse-sync to include a drift summary:

```java
public class ReverseSyncDryRunOutput extends DryRunOutput {

    private final DriftReport driftSummary;

    @JsonCreator
    public ReverseSyncDryRunOutput(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("sequenceNumber") int sequenceNumber,
            @JsonProperty("operations") List<LFPermissionOperation> operations,
            @JsonProperty("driftSummary") DriftReport driftSummary);
}
```

## Data Models

### PermissionKey (existing, reused)

The identity tuple used for diff comparison. Already defined in `SyncService`:

| Field | Type | Description |
|-------|------|-------------|
| principalArn | String | IAM principal ARN |
| resource | LFResource | The LF resource target |
| permissions | Set\<LFPermission\> | Permission set |
| grantable | boolean | Whether grant-with-grant-option |

### SDK-to-Model Mapping (reverse of `buildResource`)

| SDK Resource Type | LFResource Fields |
|-------------------|-------------------|
| `DatabaseResource` | catalogId, databaseName, tableName=null, columnNames=null |
| `TableResource` | catalogId, databaseName, tableName, columnNames=null |
| `TableResource` with `TableWildcard` | catalogId, databaseName, tableName="*" (wildcard indicator) |
| `TableWithColumnsResource` | catalogId, databaseName, tableName, columnNames=Set |
| `TableWithColumnsResource` with `ColumnWildcard` | catalogId, databaseName, tableName, columnNames=null (table-level) |
| `DataLocationResource` | dataLocationPath=resourceArn, all others null |
| `CatalogResource` | **Skipped** — not supported by LFResource model |
| `LFTagPolicyResource` | **Skipped** — not supported by LFResource model |

### Corrective Operation Ordering

When applying corrective operations, the order is:

1. All REVOKE operations (extra permissions removed first)
2. All GRANT operations (missing permissions added second)

Within each group, operations are unordered. This prevents transient over-permissioning.

### Configuration Extension

New fields added to `server-config.yaml`:

```yaml
reverseSync:
  enabled: false                    # enable reverse-sync feature
  reportOnly: false                 # compute drift without applying corrections
  dryRun: false                     # serialize corrections to JSON instead of applying
  periodicIntervalMs: 0             # 0 = on-demand only, >0 = periodic schedule
  catalogId: "123456789012"         # defaults to awsConfig.catalogId if not set
  exclusionFilter:
    excludedPrincipals:             # principals managed outside Cedar
      - "arn:aws:iam::123456789012:role/LFAdmin"
    excludedResourcePatterns:       # resource patterns to exclude
      - "system_db/*"
```



## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: LFResource round-trip

*For any* valid `LFResource` (database, table, column, or data location), converting it to an AWS SDK `Resource` via `LakeFormationClient.buildResource()` and then converting back via `LFPermissionFetcher.reverseMapResource()` shall produce an `LFResource` equal to the original.

**Validates: Requirements 8.1, 1.3, 2.1**

### Property 2: LFPermission round-trip

*For any* valid non-empty set of `LFPermission` values, converting to AWS SDK `Permission` enums via `LakeFormationClient.toLfPermissions()` and then converting back via `LFPermissionFetcher.reverseMapPermissions()` shall produce a set equal to the original.

**Validates: Requirements 8.2, 2.2**

### Property 3: Drift correctness

*For any* two lists of `LFPermissionOperation` objects (desired and actual), `DriftDetector.computeDrift()` shall produce:
- A GRANT corrective operation for every permission in desired but not in actual (by PermissionKey)
- A REVOKE corrective operation for every permission in actual but not in desired (by PermissionKey)
- No corrective operation for permissions present in both
- A `DriftReport` where `missingGrants + extraPermissions + inSyncCount` equals the size of the union of desired and actual PermissionKey sets

**Validates: Requirements 3.2, 3.3, 3.4, 3.5**

### Property 4: Identity / zero-drift

*For any* valid list of `LFPermissionOperation` objects, computing drift where the desired state and actual state are identical shall produce zero corrective operations and a `DriftReport` with `missingGrants=0` and `extraPermissions=0`.

**Validates: Requirements 8.3**

### Property 5: Convergence

*For any* two lists of `LFPermissionOperation` objects (desired and actual), computing drift and then applying the corrective operations to the actual state shall produce a state whose PermissionKey set equals the desired state's PermissionKey set.

**Validates: Requirements 8.4**

### Property 6: Report-only mode produces no corrective operations

*For any* desired and actual permission lists, when `DriftDetector.computeDrift()` is called with `reportOnly=true`, the returned `DriftResult` shall have an empty `correctiveOperations` list while the `DriftReport` is still correctly populated.

**Validates: Requirements 3.6**

### Property 7: Exclusion filter removes matching permissions from drift

*For any* desired and actual permission lists and any `PermissionFilter` with excluded principals, permissions matching the exclusion filter shall not appear in the corrective operations or contribute to the `missingGrants`/`extraPermissions` counts.

**Validates: Requirements 3.7, 1.7**

### Property 8: REVOKE-before-GRANT ordering

*For any* list of corrective operations produced by `DriftDetector.computeDrift()`, when the `ReverseSyncService` orders them for application, all REVOKE operations shall appear before all GRANT operations in the ordered list.

**Validates: Requirements 4.1**

### Property 9: Continue-on-failure

*For any* list of corrective operations where some operations fail, the `ReverseSyncService` shall attempt all remaining operations (not abort the batch), and the `ReverseSyncResult` shall accurately report the count of successful and failed operations.

**Validates: Requirements 4.3, 4.4, 7.3**

### Property 10: Empty Cedar safety guard

*For any* empty or null `CedarPolicySet`, the `ReverseSyncService.execute()` shall skip the sync cycle and produce zero corrective operations, preventing mass revocation of all LakeFormation permissions.

**Validates: Requirements 5.5**

### Property 11: Concurrency guard

*For any* two concurrent invocations of `ReverseSyncService.execute()`, exactly one shall succeed and the other shall be rejected with an `IllegalStateException`.

**Validates: Requirements 5.4**

### Property 12: Fetched operations have null sourcePolicyId

*For any* `LFPermissionOperation` produced by `LFPermissionFetcher.normalizeEntry()`, the `sourcePolicyId` field shall be null.

**Validates: Requirements 2.4**

### Property 13: Pagination completeness

*For any* number of pages N returned by the `ListPermissions` API (each with a NextToken except the last), `LFPermissionFetcher.fetchPermissions()` shall return the concatenation of all entries from all N pages.

**Validates: Requirements 1.2**

### Property 14: Dry-run reverse-sync round-trip

*For any* `ReverseSyncDryRunOutput` object, serializing to JSON and deserializing back shall produce an equivalent object, including the `driftSummary` field.

**Validates: Requirements 6.1, 6.2, 6.3**

## Error Handling

| Scenario | Component | Behavior |
|----------|-----------|----------|
| `ListPermissions` returns transient error (throttling, 5xx) | `LFPermissionFetcher` | Retry with exponential backoff per `RetryConfig`. After exhausting retries, throw `LakeFormationClientException`. |
| `ListPermissions` returns `AccessDeniedException` | `LFPermissionFetcher` | Throw `LakeFormationClientException` immediately (no retry) with message indicating insufficient `lakeformation:ListPermissions` IAM permission. |
| Unrecognized resource type (CatalogResource, LFTagPolicy) | `LFPermissionFetcher.normalizeEntry()` | Skip entry, log warning with raw resource details, increment `skippedPermissions` in `DriftReport`. |
| Unrecognized permission value | `LFPermissionFetcher.reverseMapPermissions()` | Skip the permission value, log warning. If all permissions in an entry are unrecognized, skip the entire entry. |
| `ColumnWildcard` in `TableWithColumnsResource` | `LFPermissionFetcher.reverseMapResource()` | Map to table-level `LFResource` (columnNames=null), matching existing wildcard convention. |
| `TableWildcard` in `TableResource` | `LFPermissionFetcher.reverseMapResource()` | Map to `LFResource` with tableName="*" as wildcard indicator. |
| Empty or null `CedarPolicySet` | `ReverseSyncService.execute()` | Skip sync cycle, log error, return result with zero operations. Do NOT revoke all LF permissions. |
| Corrective GRANT/REVOKE fails after retries | `ReverseSyncService` | Log to `DeadLetterLogger`, record in `DriftReport.failedOperations`, continue with remaining operations. |
| Concurrent sync request while cycle is running | `ReverseSyncService.execute()` | Reject with `IllegalStateException`, log warning. Uses `AtomicBoolean` CAS for lock-free guard. |
| Dry-run output directory doesn't exist | `DryRunLakeFormationClient` | Created automatically (existing behavior). |
| Dry-run JSON write fails | `DryRunLakeFormationClient` | `RuntimeException` propagated to `ReverseSyncService`. |

## Testing Strategy

### Dual Testing Approach

The reverse-sync feature uses both unit tests and property-based tests for comprehensive coverage:

- **Unit tests** (JUnit 5): Verify specific examples, edge cases, error conditions, and integration points.
- **Property-based tests** (jqwik 1.7.4): Verify universal correctness properties across randomized inputs.

### Property-Based Testing Configuration

- **Library:** jqwik 1.7.4 (already in `pom.xml`)
- **Minimum iterations:** 100 per property test (`@Property(tries = 100)`)
- **Tag format:** Each test method includes a comment: `Feature: lf-cedar-reverse-sync, Property {N}: {title}`
- **Each correctness property is implemented by a single `@Property`-annotated test method**

### Test Classes

| Test Class | Type | Properties/Tests Covered |
|------------|------|--------------------------|
| `LFPermissionFetcherTest` | Unit | Pagination, error handling, AccessDeniedException, unrecognized resources/permissions |
| `LFPermissionFetcherPropertyTest` | Property | Property 1 (LFResource round-trip), Property 2 (LFPermission round-trip), Property 12 (null sourcePolicyId), Property 13 (pagination completeness) |
| `DriftDetectorTest` | Unit | Specific drift scenarios, empty inputs, single-element diffs |
| `DriftDetectorPropertyTest` | Property | Property 3 (drift correctness), Property 4 (identity/zero-drift), Property 5 (convergence), Property 6 (report-only), Property 7 (exclusion filter) |
| `ReverseSyncServiceTest` | Unit | Orchestration flow, error scenarios, empty Cedar guard, dry-run wiring |
| `ReverseSyncServicePropertyTest` | Property | Property 8 (REVOKE-before-GRANT ordering), Property 9 (continue-on-failure), Property 10 (empty Cedar safety), Property 11 (concurrency guard) |
| `ReverseSyncDryRunOutputRoundTripPropertyTest` | Property | Property 14 (dry-run round-trip serialization) |

### Generators (Arbitraries)

The property tests reuse and extend the generator patterns from the existing `SyncServicePropertyTest`:

- **`LFResource` generator:** Produces database-level, table-level, column-level, and data-location resources with random catalog IDs, database names, table names, and column sets.
- **`LFPermission` set generator:** Produces non-empty subsets of `LFPermission.values()`.
- **`LFPermissionOperation` generator:** Combines the above with random principal ARNs and grantable flags. For fetched operations, forces `sourcePolicyId=null` and `operationType=GRANT`.
- **`PermissionFilter` generator:** Produces filters with random excluded principals and resource patterns.
- **Snapshot pair generator:** Produces pairs of desired/actual operation lists for drift testing.

### Unit Test Focus Areas

- `LFPermissionFetcher`: Mock the AWS SDK `LakeFormationClient` to return canned `ListPermissionsResponse` objects with various page counts, resource types, and error conditions.
- `DriftDetector`: Test specific drift scenarios (all missing, all extra, mixed, empty inputs).
- `ReverseSyncService`: Mock `LFPermissionFetcher`, `DriftDetector`, and `LakeFormationClient` to verify orchestration flow, REVOKE-before-GRANT ordering, and error handling.
- Edge cases: `CatalogResource` entries, `LFTagPolicy` entries, `ColumnWildcard`, `TableWildcard`, `AccessDeniedException`, empty Cedar policy set.
