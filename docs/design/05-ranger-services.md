# Ranger Services

## `ranger/service/BaseRangerService.java`

**Purpose:** Abstract base for all Ranger service integrations. Composes a `RangerBasePlugin` (does not extend it). Provides uniform lifecycle and fault-tolerant policy retrieval.

### Key fields

| Field | Type | Notes |
|---|---|---|
| `plugin` | `RangerBasePlugin` | composed, not inherited |
| `latestPolicies` | `volatile ServicePolicies` | last received snapshot |
| `lastKnownGoodPolicies` | `volatile List<RangerPolicy>` | fallback; initialized to empty list |

### Key methods

| Method | Notes |
|---|---|
| `init()` | calls `plugin.init()`, registers with Ranger Admin |
| `getLatestPolicies()` | updates `lastKnownGoodPolicies` on non-null result |
| `getLastKnownGoodPolicies()` | always non-null; empty list if never fetched |
| `abstract createAdapter(AwsContext) → SourcePolicyAdapter` | implemented by subclasses |
| `abstract getServiceDefinitionResourcePath() → String` | classpath path to bundled service def JSON |

---

## Service type registry

| Class | serviceType | Adapter | Service definition classpath |
|---|---|---|---|
| `LakeFormationRangerService` | `"lakeformation"` | `RangerServiceAdapter` | `/ranger-servicedef-lakeformation.json` |
| `HiveRangerService` | `"hive"` | `HiveServiceAdapter` | `/ranger-servicedef-hive.json` |
| `EmrfsRangerService` | `"amazon-emr-emrfs"` | `EmrfsServiceAdapter` | `/ranger-servicedef-amazon-emr-emrfs.json` |
| `EmrSparkRangerService` | `"amazon-emr-spark"` | `EmrSparkServiceAdapter` | `/ranger-servicedef-amazon-emr-spark.json` |
| `PrestoRangerService` | `"presto"` | `PrestoServiceAdapter(gdcCatalogName)` | `/ranger-servicedef-presto.json` |
| `TrinoRangerService` | `"trino"` | `TrinoServiceAdapter(gdcCatalogName)` | `/ranger-servicedef-trino.json` |

Dispatched from `ConversionServerMain.createRangerService(RangerServiceConfig)`.

---

## `ranger/RangerPlugin.java`

**Purpose:** Extends `RangerBasePlugin` for the `"lakeformation"` service definition. Push-based policy updates via `PolicyUpdateListener`. Used only in single-plugin (legacy) mode.

**Key fields:** `volatile ServicePolicies latestPolicies`, `volatile PolicyUpdateListener policyUpdateListener`.

**Key methods:**
- `setRangerAdminUrl(String url)` — must be called before `init()`; overrides `ranger.plugin.lakeformation.policy.rest.url`
- `setPolicyUpdateListener(PolicyUpdateListener)` — implemented by `SyncService`
- Override `setPolicies(ServicePolicies)` — stores snapshot, fires listener; listener exceptions logged, plugin continues

---

## Source policy adapters

### `ranger/RangerServiceAdapter.java` — `"lakeformation"`

| Ranger access type | Cedar action | Notes |
|---|---|---|
| `select`, `read` | `SELECT` | |
| `insert`, `update`, `write` | `INSERT` | |
| `delete` | `DELETE` | |
| `describe` | `DESCRIBE` | |
| `alter` | `ALTER` | |
| `drop` | `DROP` | |
| `create_database`, `create` | `CREATE_DATABASE` / `CREATE_TABLE` | depends on resource level |
| `create_table` | `CREATE_TABLE` | |
| `all` | `ALL` | |
| `datalocation`, `data_location_access` | `DATA_LOCATION_ACCESS` | |

**Resource-level action filtering:**
- column: only `{SELECT, ALL}`
- table: `{SELECT, INSERT, DELETE, DESCRIBE, ALTER, DROP, ALL}`
- database: `{CREATE_TABLE, CREATE_DATABASE, ALTER, DROP, DESCRIBE, ALL}`

**ARN builders:**
- `buildDatabaseArn(db)` → `arn:aws:glue:{region}:{account}:database/{db}`
- `buildTableArn(db, table)` → `arn:aws:glue:{region}:{account}:table/{db}/{table}`
- `buildColumnArn(db, table, col)` → `arn:aws:glue:{region}:{account}:column/{db}/{table}/{col}`
- `buildDataLocationArn(s3Path)` → `arn:aws:s3:::{path}` (strips `s3://` prefix)

---

### `ranger/HiveServiceAdapter.java` — `"hive"`

Same action mapping as `RangerServiceAdapter` for table/db/column/url resources. Explicitly skips `"index"` and `"lock"` with a warning. `url`/`datalocation` access types → `DATA_LOCATION_ACCESS`. Same Glue ARN structure.

---

### `ranger/EmrSparkServiceAdapter.java` — `"amazon-emr-spark"`

Maps catalog actions (`select`, `update`, `alter`, `create`, `drop`, `read`, `write`, `all`) and `url` resources (always → `DATA_LOCATION_ACCESS`). Glue ARN for catalog resources; `arn:aws:s3:::` for `url`. Same resource-level hierarchy as Hive (database/table/column/url).

---

### `ranger/EmrfsServiceAdapter.java` — `"amazon-emr-emrfs"`

| Ranger access type | S3 action |
|---|---|
| `GetObject` | `s3:GetObject` |
| `PutObject` | `s3:PutObject` |
| `ListObjects` | `s3:ListObjects` |
| `DeleteObject` | `s3:DeleteObject` |

**Resource level:** `sthreeresource`. Entity type: `S3::Object`. ARN: `arn:aws:s3:::bucket/path` (appends `/*` if `isRecursive=true`).

**Unique:** `buildEntityRefFromValues` takes a `List<String>` of values (not positional args) because EMRFS resources can have multiple values per policy item.

---

### `ranger/PrestoServiceAdapter.java` — `"presto"`

| Ranger access type | Cedar action |
|---|---|
| `select` | `SELECT` |
| `insert` | `INSERT` |
| `delete` | `DELETE` |
| `create` | `CREATE_TABLE` |
| `drop` | `DROP` |
| `alter` | `ALTER` |
| `use`, `show` | `DESCRIBE` |
| `grant`, `revoke` | silently skipped |

**Resource levels:** `schema` (maps to `database` in Glue ARNs), `table`, `column`.

**`shouldProcessPolicy(policy)`:** checks `catalog` resource value == `gdcCatalogName`; policies without a `catalog` resource are always processed.

---

### `ranger/TrinoServiceAdapter.java` — `"trino"`

Identical to `PrestoServiceAdapter` in every detail. Separate class, same logic, same resource levels and action mappings.

---

## `ranger/AccessTypeMapper.java`

Static utility used by `PolicyConverter` (the direct LF path). Maps Ranger access type strings to `Set<LFPermission>` enums. `"all"` → `EnumSet.of(LFPermission.ALL)`. Unknown types: logs error, emits `MetricsEmitter.recordUnmappedAccessType`.

---

## `ranger/service/RangerTagService.java`

**Purpose:** Fetches Ranger tag definitions and resource-tag mappings via `RangerAdminRESTClient`. Supports incremental delta fetches.

| Method | Notes |
|---|---|
| `getLatestTags()` | calls `adminClient.getServiceTagsIfUpdated(lastKnownTagVersion, 0L)`; handles delta via `mergeDelta()` |
| `getResourcesForTag()` | builds `Map<tagName, Set<LFResource>>` inverse index |

**Admin client setup:** sets `ranger.plugin.<tagServiceName>.policy.rest.url` and auth props in Hadoop config; calls `client.init()`.

---

## `ranger/service/ResourceLookupService.java`

**Purpose:** Extends `RangerBaseService` for Ranger Admin UI resource browsing (database/table/column lookup against Glue).

Routes `lookupResource(ResourceLookupContext)` by `resourceName` to:
- `database` → `catalogResolver.expandDatabases(pattern)`
- `table` → `expandTables(selectedDb, pattern)`
- `column` → `expandColumns(db, table, pattern)`

Credential resolution: `roleArn` set → STS AssumeRole; static key+secret; default chain.

---

## `ranger/service/ServiceDefinitionInstaller.java`

Registers the LakeFormation service definition with Ranger Admin.

**Two install modes:**
- `installViaRest(config, json)`: POST to `{rangerAdminUrl}/service/plugins/definitions`, Basic auth
- `installViaFile(rangerAdminHome, json)`: writes to `{rangerAdminHome}/ews/webapp/WEB-INF/classes/ranger-plugins/lakeformation/...`
