# LakeFormation Client Layer

## `lakeformation/LakeFormationClient.java`

**Purpose:** Wrapper around the AWS LF SDK. Handles single-operation and batch grant/revoke with retry, consolidation, and conflict resolution.

### Constants

- `MAX_BATCH_SIZE = 20` — hard LF API batch limit

### Key methods

| Method | Notes |
|---|---|
| `grantPermission(op)` | Single-entry grant via `executeWithRetry`; sets `permissionsWithGrantOption` when `op.isGrantable()` |
| `revokePermission(op)` | Single-entry revoke; silently ignores "No permissions revoked" response |
| `applyBatch(ops, deadLetterLogger) → BatchResult` | Main batch path; consolidate → conflict resolve → chunk ≤20 → revokes first then grants |
| `createLFTag`, `deleteLFTag`, `listLFTagKeys` | LF-Tag key management; paginated with `maxResults=1000` |
| `getResourceLFTags`, `addLFTagsToResource`, `removeLFTagsFromResource` | LF-Tag resource attachment management |

### `applyBatch` detailed flow

1. **`consolidateOperations`**: merge column names and permission sets for operations sharing the same `ConsolidationKey` = `(opType, sourcePolicyId, principalArn, catalogId, db, table, dataLocationPath, rowFilterExpression, grantable)`
2. **`resolveTableColumnConflicts`**: within the batch, if the same principal has both a TABLE and TABLE_WITH_COLUMNS grant for the same resource, the TWC grant wins (TABLE grant is dropped)
3. Split into revokes / grants
4. Chunk each list into groups of ≤20
5. `executeBatchRevoke` then `executeBatchGrant` for each chunk
6. Revokes always run before grants

### `executeBatchGrant` conflict resolution

If a batch grant returns "Permissions modification is invalid":
- Detects cross-cycle TABLE_WITH_COLUMNS conflict: revokes existing TWC via `ColumnWildcard`, then retries the grant recursively

### `executeBatchRevoke` idempotency

"No permissions revoked" and "Permissions modification is invalid" are treated as success (idempotent revokes — the permission simply wasn't there).

### `executeWithRetry`

Retries only on `ConcurrentModificationException` with exponential backoff from `RetryConfig`. All other exceptions are wrapped in `LakeFormationClientException` and thrown immediately. SDK-level throttling retries are handled by the AWS SDK's built-in retry policy.

### `buildResource(LFResource) → Resource`

Routing logic:
- `dataLocationPath` set → `DataLocationResource` (converts `s3://` → `arn:aws:s3:::`)
- `isAllTables=true` → `TableWildcard`
- `tableName=null` → `DatabaseResource`
- `columnNames` present → `TableWithColumnsResource`
- Otherwise → `TableResource`

---

## `lakeformation/LFPermissionFetcher.java`

**Purpose:** Fetches live LF permissions via `ListPermissions`, paginates through all pages, normalizes SDK responses to `LFPermissionOperation` objects.

### Key methods

| Method | Notes |
|---|---|
| `fetchPermissions(catalogId, filter) → List<LFPermissionOperation>` | Paginates using `nextToken`; applies `filter.shouldExclude()` after normalization |
| `fetchPermissionsByResourceType(catalogId, resourceType) → List<LFPermissionOperation>` | Same pagination loop with `.resourceType(resourceType)` filter added |
| `normalizeEntry(PrincipalResourcePermissions) → List<LFPermissionOperation>` | Produces up to two ops: one for regular perms (`grantable=false`), one for `permissionsWithGrantOption` (`grantable=true`) |
| `reverseMapResource(Resource) → LFResource` | Reverse of `LakeFormationClient.buildResource()`; returns null for `CatalogResource` and `LFTagPolicyResource` |
| `reverseMapPermissions(List<Permission>) → Set<LFPermission>` | Skips unrecognized values with warning |

### `reverseMapResource` handling

| SDK resource type | LFResource result |
|---|---|
| `database` | `(catalogId, name, null, null, null)` |
| `table` (normal) | `(catalogId, databaseName, name, null, null)` |
| `table` (TableWildcard) | `(catalogId, databaseName, "*", null, null)` |
| `tableWithColumns` (ColumnWildcard) | `(catalogId, databaseName, name, null, null)` — treated as table-level |
| `tableWithColumns` (explicit columns) | `(catalogId, databaseName, name, columnNames, null)` |
| `dataLocation` | `(null, null, null, null, null, resourceArn)` |
| `catalog`, `lfTagPolicy` | null (skipped) |

### Retry / error handling

No retry logic of its own — relies on AWS SDK built-in retry policy. `AccessDeniedException` and `LakeFormationException` are wrapped in `LakeFormationClientException`.

### Usage for reconciliation (Bug #1)

`fetchPermissions(catalogId, null)` performs an unfiltered scan of all permissions. This is the existing call used by `ReverseSyncService`. The scan can take 30+ minutes for large catalogs (300K+ permissions). The `TableLister` interface (Bug #1 fix) enables partitioned fetching per-resource to parallelize this.

---

## `lakeformation/DryRunLakeFormationClient.java`

**Purpose:** Test/dry-run subclass of `LakeFormationClient`. Writes JSON files instead of calling AWS. Initialized with `super(null, new RetryConfig())`.

### Key fields

| Field | Notes |
|---|---|
| `outputDirectory` | `Path` for output files |
| `sequenceCounter` | `AtomicInteger` for `dry-run-NNN.json` file names |
| `tagSequenceCounter` | `AtomicInteger` for `tag-sync-NNN.json` file names |
| `tagDefinitions` | `ConcurrentHashMap<String, List<String>>` in-memory LF-Tag key store |
| `resourceTagAttachments` | `ConcurrentHashMap<String, Map<String,String>>` in-memory attachment store |

### Overridden methods

- `applyBatch` → writes `DryRunOutput` (timestamp, sequence, operations) to `dry-run-NNN.json`; returns success `BatchResult` with all ops counted as applied
- `createLFTag`, `deleteLFTag`, `listLFTagKeys` → in-memory + writes `tag-sync-NNN.json`
- `getResourceLFTags`, `addLFTagsToResource`, `removeLFTagsFromResource` → in-memory + writes `tag-sync-NNN.json`

---

## `lakeformation/LakeFormationClientException.java`

Checked exception wrapper for all LF API failures after retry exhaustion or non-retryable errors. Constructors: `(String message)` and `(String message, Throwable cause)`.
