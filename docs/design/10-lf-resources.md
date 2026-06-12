# LF Resource Model

## `lakeformation/LFResource.java`

**Purpose:** Immutable value object representing a single LakeFormation resource target. Used as a map key throughout the diff engine — must have correct `equals`/`hashCode`.

### Fields

| Field | Type | Notes |
|---|---|---|
| `catalogId` | `String` | Glue catalog ID (= AWS account ID) |
| `databaseName` | `String` | |
| `tableName` | `String` | null for database-level; `"*"` for TableWildcard fetched entries |
| `columnNames` | `Set<String>` | immutable copy; null = table-level |
| `rowFilterExpression` | `String` | for row-level security grants; null = no filter |
| `dataLocationPath` | `String` | `s3://...` path for DATA_LOCATION resources |
| `allTables` | `boolean` | true = `TableWildcard` grant (covers all tables in database) |

### Resource level interpretation

| Fields present | Resource level |
|---|---|
| `dataLocationPath` set | DATA_LOCATION |
| `allTables=true` | DATABASE (TableWildcard) |
| `tableName=null` | DATABASE |
| `tableName` set, `columnNames=null` | TABLE |
| `tableName` set, `columnNames` non-null | TABLE_WITH_COLUMNS |

### Factory method

`static allTablesResource(String catalogId, String databaseName)` — creates `allTables=true`, `tableName=null`.

---

## `lakeformation/LFPermission.java`

Enum of all LF permission actions. JSON-serializable via `@JsonValue`/`@JsonCreator` (case-insensitive).

**Values:** `SELECT`, `INSERT`, `DELETE`, `DESCRIBE`, `ALTER`, `DROP`, `CREATE_DATABASE`, `CREATE_TABLE`, `DATA_LOCATION_ACCESS`, `ALL`.

---

## `lakeformation/LFPermissionOperation.java`

**Purpose:** Immutable value object for a single LF GRANT or REVOKE operation. Used in both the forward sync diff and the reverse-sync drift computation.

### Fields

| Field | Type | Notes |
|---|---|---|
| `operationType` | `OperationType` | `GRANT` or `REVOKE` |
| `sourcePolicyId` | `String` | format `"serviceType:policyId"`; null for fetched/reconciled ops |
| `principalArn` | `String` | IAM ARN |
| `resource` | `LFResource` | |
| `permissions` | `Set<LFPermission>` | immutable `EnumSet` |
| `grantable` | `boolean` | true = `permissionsWithGrantOption`; set when Ranger `delegateAdmin=true` |

`equals`/`hashCode` include ALL fields including `operationType` and `sourcePolicyId` (for operation-level dedup). **Note:** `PermissionKey` in `SyncService` and `DriftDetector` excludes `operationType` and `sourcePolicyId` for identity comparison — these are separate inner classes, not this class's `equals`.

---

## `lakeformation/PermissionFilter.java`

**Purpose:** Config object for scoping reverse-sync permission retrieval and excluding specific principals or resources from drift computation.

### Fields

| Field | Type | Notes |
|---|---|---|
| `principalArn` | `String` | optional; used to filter `ListPermissions` request |
| `resourceType` | `String` | optional resource type filter |
| `excludedPrincipals` | `Set<String>` | immutable; exact ARN match |
| `excludedResourcePatterns` | `Set<String>` | immutable; glob patterns (`*` = any chars) |

### Key method

`shouldExclude(LFPermissionOperation op) → boolean` — true if op's principal is in `excludedPrincipals` OR resource path matches any `excludedResourcePatterns`.

`static buildResourcePath(LFResource) → String` — formats as `"db/table/col1,col2"` or `"s3://..."`.

`static matchesGlob(String text, String pattern) → boolean` — converts `*` to `.*`, escapes regex metacharacters, then calls `text.matches()`.

---

## `lakeformation/AwsContext.java`

Final value object holding `region`, `accountId`, and `catalogId`. Passed to all adapters and ARN-building utilities.

---

## `lakeformation/TagMetadataSyncer.java`

**Purpose:** Reconciles Ranger tag definitions and resource-tag mappings into LF-Tags and resource tag attachments using a 3-way diff. Only manages tags in `lfManagedTags` — never touches externally-created tags.

### `sync(ServiceTags desired, Set<String> lfManagedTags) → TagSyncResult`

9-step cycle:
1. Extract desired tag names from `desired.tagDefinitions`
2. Fetch actual LF-Tag keys via `lfClient.listLFTagKeys()`
3. Compute `toCreate` = desired - actual; `toDelete` = (managed ∩ actual) - desired
4. Create new tags via `lfClient.createLFTag(catalogId, tagName, ["true"])`
5. Build desired resource→tag attachments from `ServiceTags`
6. Fetch actual resource-level tags from LF
7. Add missing attachments via `lfClient.addLFTagsToResource()`
8. Remove stale attachments (only for managed tags) via `lfClient.removeLFTagsFromResource()`
9. Deferred deletes: only delete a tag key if no resource still carries it

**Tag value:** all Ranger tags represented as `"true"` LF-Tag values (`TAG_VALUE = "true"` constant).

**`mapToLFResource(RangerServiceResource)`** (static): extracts db/table/column from `resourceElements`; requires `database` to be non-null.
