# Amazon EMR Spark Adapter Design

## Goal

Add support for `amazon-emr-spark` Ranger service policies so they are converted to Cedar instead of being skipped with `UNSUPPORTED_SERVICE_TYPE`. The adapter handles the `database → table → column` Glue catalog hierarchy identically to Hive, and maps the novel `url` resource type (S3 paths) to `DataCatalog::DataLocation` / `DATA_LOCATION_ACCESS` grants.

---

## Background

The customer export (`Ranger_Policies_20260602_001709.json`) contains 37,729 policies from an `amazon-emr-spark` Ranger service. These are currently skipped entirely because no adapter is registered for the `amazon-emr-spark` service type.

The `amazon-emr-spark` Ranger service definition (`conf/ranger-servicedef-amazon-emr-spark.json`) has:

- **Catalog resources** (db → table → column hierarchy): `database`, `table`, `column` — identical structure to Hive
- **Filesystem resource** (standalone): `url` — S3 paths such as `s3://bucket/prefix`, supports wildcards and recursive flag
- **Access types**: `select`, `update`, `alter`, `read`, `write`, `create`, `drop` (no `index` or `lock`)

---

## Approach

Direct implementation — `EmrSparkServiceAdapter` as an independent flat class, following the same pattern as `HiveServiceAdapter` and `EmrfsServiceAdapter`. No inheritance from `HiveServiceAdapter`; no shared abstract base class extracted.

Rationale: the existing adapters are all flat independent implementations. Introducing inheritance would couple two independently-evolving service definitions and require touching working code with no immediate benefit.

---

## Architecture

### New Files

#### `EmrSparkServiceAdapter.java`

`SourcePolicyAdapter` implementation for `amazon-emr-spark`.

**Catalog action mapping** (used for `database`, `table`, `column` resources):

| Ranger access type | Cedar action |
|--------------------|-------------|
| `select` | `SELECT` |
| `update` | `INSERT` |
| `alter` | `ALTER` |
| `create` | `CREATE_TABLE` |
| `drop` | `DROP` |
| `read` | `SELECT` |
| `write` | `INSERT` |

No unmapped types (EMR Spark has no `index` or `lock`).

**URL action mapping** (used for `url` resources):

All access types → `DATA_LOCATION_ACCESS`. LF registered-location grants use a single permission regardless of the Spark operation that triggered access.

**`buildEntityRefFromValues` resource levels:**

| Level | Entity type | Entity ID (ARN) |
|-------|-------------|-----------------|
| `database` | `DataCatalog::Database` | `arn:aws:glue:{region}:{account}:database/{db}` |
| `table` | `DataCatalog::Table` | `arn:aws:glue:{region}:{account}:table/{db}/{table}` |
| `column` | `DataCatalog::Column` | `arn:aws:glue:{region}:{account}:column/{db}/{table}/{col}` |
| `url` | `DataCatalog::DataLocation` | `arn:aws:s3:::{bucket}/{prefix}` (strip `s3://`) |

**URL ARN construction**: strip the `s3://` scheme and prepend `arn:aws:s3:::`:
- `s3://my-bucket/data/` → `arn:aws:s3:::my-bucket/data/`

**`MetricsEmitter`** wired via `setMetricsEmitter(MetricsEmitter)`, following Hive pattern.

#### `EmrSparkRangerService.java`

`BaseRangerService` subclass:
- `super("amazon-emr-spark", instanceName)`
- `createAdapter(awsContext)` → `new EmrSparkServiceAdapter(awsContext)`
- `getServiceDefinitionResourcePath()` → `"/ranger-servicedef-amazon-emr-spark.json"`

---

### Modified Files

#### `SourcePolicyAdapter.java` (interface)

Add one default method that accepts `resourceLevel` so adapters can vary their action mapping by resource tier:

```java
default Set<String> mapAccessTypeToCedarActions(String accessType, String resourceLevel) {
    return mapAccessTypeToCedarActions(accessType);
}
```

`EmrSparkServiceAdapter` overrides this to return `DATA_LOCATION_ACCESS` for `resourceLevel = "url"` and delegates to the catalog mapping otherwise. All existing adapters inherit the default (zero behavior change).

#### `RangerToCedarConverter.java`

Four targeted changes:

1. **`determineResourceLevel`** — add `url` detection before the database fallback:
   ```java
   if (hasResource(resources, "url")) return "url";
   ```

2. **`expandResources`** — add `url` branch between `datalocation` and `database`:
   - Iterate url values directly (no Glue catalog expansion needed)
   - For each value, call `buildEntityRef(adapter, "url", null, null, null, urlValue)`
   - If a url value contains a wildcard (`*` or `?`), record a `WILDCARD_PATTERN` gap (same pattern as table/column wildcards) and pass through as-is

3. **`buildEntityRef`** — add `instanceof EmrSparkServiceAdapter` routing:
   ```java
   if (adapter instanceof EmrSparkServiceAdapter) {
       return ((EmrSparkServiceAdapter) adapter).buildEntityRefFromValues(
               resourceLevel, database, table, column, dataLocation);
   }
   ```
   Note: the `dataLocation` parameter carries the url value (reusing the existing parameter slot — `url` is semantically a data location).

4. **`extractCedarActions`** — call the resource-level-aware overload:
   ```java
   Set<String> mapped = adapter.mapAccessTypeToCedarActions(access.getType(), resourceLevel);
   ```
   `resourceLevel` is already available in `convertSinglePolicy`; it must be threaded through to `generateStatements` and then to `extractCedarActions`.

#### `ConversionServerMain.java`

Two changes:

1. **Factory** — add `amazon-emr-spark` case:
   ```java
   case "amazon-emr-spark":
       return new EmrSparkRangerService(instanceName);
   ```

2. **MetricsEmitter wiring** — add EMR Spark branch alongside the existing Hive/Presto/Trino branches:
   ```java
   else if (adapter instanceof EmrSparkServiceAdapter) {
       ((EmrSparkServiceAdapter) adapter).setMetricsEmitter(metricsEmitter);
   }
   ```

---

## Data Flow

### Catalog policy (database/table/column)

```
RangerPolicy{service="spark-prod", resources={database="db1", table="tbl1", column="*"}}
  → determineResourceLevel → "column"
  → promoteResourceLevel   → "table"  (col=* promoted)
  → expandResources("table") → EmrSparkServiceAdapter.buildEntityRefFromValues("table", "db1", "tbl1", null, null)
  → CedarEntityRef("DataCatalog::Table", "arn:aws:glue:us-east-1:123:table/db1/tbl1")
  → extractCedarActions("select", "table") → {"SELECT"}
  → permit(... action == "SELECT" ... resource == DataCatalog::Table::"arn:...")
```

### URL policy

```
RangerPolicy{service="spark-prod", resources={url="s3://bucket/data/"}}
  → determineResourceLevel → "url"
  → expandResources("url") → EmrSparkServiceAdapter.buildEntityRefFromValues("url", null, null, null, "s3://bucket/data/")
  → CedarEntityRef("DataCatalog::DataLocation", "arn:aws:s3:::bucket/data/")
  → extractCedarActions("read", "url") → {"DATA_LOCATION_ACCESS"}
  → permit(... action == "DATA_LOCATION_ACCESS" ... resource == DataCatalog::DataLocation::"arn:...")
```

### Wildcard URL policy

```
RangerPolicy{resources={url="s3://bucket/*"}}
  → expandResources("url") → records WILDCARD_PATTERN gap
  → passes "s3://bucket/*" through as-is → ARN: "arn:aws:s3:::bucket/*"
```

---

## Gap Handling

| Scenario | Gap type | Details |
|----------|----------|---------|
| Wildcard url value (e.g. `s3://bucket/*`) | `WILDCARD_PATTERN` | Cannot expand S3 path wildcards; ARN is a placeholder |

No new gap types needed. `UNSUPPORTED_ACTION` already exists and fires if `mapAccessTypeToCedarActions` returns empty — not expected for EMR Spark (all 7 access types are mapped).

---

## Testing

### `EmrSparkServiceAdapterTest.java` (new)

- Action mapping: each of the 7 access types for catalog resources
- Action mapping: all access types for url resources → `DATA_LOCATION_ACCESS`
- `buildEntityRefFromValues` for `database`, `table`, `column`, `url` levels
- URL ARN construction: `s3://bucket/prefix` → `arn:aws:s3:::bucket/prefix`
- Null/empty access type handled gracefully

### `RangerToCedarConverterTest.java` (extended)

- EMR Spark table grant converts to `DataCatalog::Table` Cedar statement
- EMR Spark url grant converts to `DataCatalog::DataLocation` + `DATA_LOCATION_ACCESS`
- Wildcard url records `WILDCARD_PATTERN` gap and passes ARN through
- `col=*` promotion works for EMR Spark (reuses existing promotion logic)

### `ConversionServerMainTest.java` (extended, if exists)

- `createRangerService("amazon-emr-spark", ...)` returns `EmrSparkRangerService`

---

## Constraints and Decisions

- **`url` resource level name**: the string `"url"` is used as the `resourceLevel` throughout the converter. It is distinct from `"datalocation"` (used by the LakeFormation service adapter's dedicated datalocation resource). Both ultimately produce `DataCatalog::DataLocation` entity refs, but via different code paths and different adapters.

- **`dataLocation` parameter reuse**: `buildEntityRefFromValues` already accepts a `dataLocation` parameter (unused by Hive). The url value is passed in that slot — semantically correct since a Spark URL is a data location.

- **`promoteResourceLevel` unchanged**: `url` resources are standalone (not part of the db→table→column hierarchy) so promotion logic doesn't apply. Because `determineResourceLevel` returns `"url"` only when the `url` key is present (it is a standalone resource, not in the hierarchy), `promoteResourceLevel` will not match `"column"` or `"table"` for url policies and returns `"url"` unchanged.

- **No Glue catalog expansion for url resources**: S3 paths are not registered in the Glue catalog. Wildcard expansion is impossible; wildcards are passed through with a `WILDCARD_PATTERN` gap.
