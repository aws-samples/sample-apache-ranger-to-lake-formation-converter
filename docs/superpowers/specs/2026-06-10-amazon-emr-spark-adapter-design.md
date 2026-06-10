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

Six targeted changes:

1. **`determineResourceLevel`** — add `url` detection as the first check, before `datalocation`. The `url` resource is standalone (not in the db/table/column hierarchy); it takes priority over all catalog keys:
   ```java
   if (hasResource(resources, "url")) return "url";
   // then existing: datalocation, column, table, database
   ```

2. **`expandResources`** — add `url` early-return branch immediately after the `datalocation` branch (lines 436–443), and before the `dbPatterns` guard (line 445). This is required because url-only policies have no `database` resource; if the url branch is placed after the `dbPatterns` guard, the policy is silently dropped:
   ```java
   if ("url".equals(resourceLevel)) {
       List<String> urls = getResourceValues(resources, "url");
       for (String url : urls) {
           if (isWildcard(url)) {
               gapReporter.recordGap(new GapEntry(
                       policyId, null, GapType.WILDCARD_PATTERN,
                       url,
                       "URL pattern '" + url + "' cannot be expanded. ARN is a placeholder.",
                       "Register specific S3 paths in Lake Formation as data locations."
               ));
           }
           CedarEntityRef ref = buildEntityRef(adapter, "url", null, null, null, url);
           combinations.add(new ResourceCombination(ref, resourceLevel));
       }
       return combinations;
   }
   ```

3. **`buildEntityRef`** — add `instanceof EmrSparkServiceAdapter` routing immediately before the `instanceof HiveServiceAdapter` check (ordering matters: Hive's `buildEntityRefFromValues` throws on `"url"` resourceLevel, so the EMR Spark branch must be reached first):
   ```java
   if (adapter instanceof EmrSparkServiceAdapter) {
       return ((EmrSparkServiceAdapter) adapter).buildEntityRefFromValues(
               resourceLevel, database, table, column, dataLocation);
   }
   ```
   Also add a `"url"` case to the fallback `switch` (reached when neither EMR Spark nor Hive nor LakeFormation) as a safety net:
   ```java
   case "url":
       entityType = "DataCatalog::DataLocation";
       entityId = dataLocation != null ? dataLocation.replaceFirst("^s3://", "arn:aws:s3:::") : dataLocation;
       break;
   ```
   Note: the `dataLocation` parameter carries the url value (reusing the existing parameter slot — `url` is semantically a data location).

4. **`ResourceCombination`** — add `resourceLevel` field so it travels with each expanded ref. This is the mechanism for threading resource level into action mapping without adding a new parameter to `generateStatements`:
   ```java
   static class ResourceCombination {
       final CedarEntityRef entityRef;
       final String resourceLevel;

       ResourceCombination(CedarEntityRef entityRef, String resourceLevel) {
           this.entityRef = entityRef;
           this.resourceLevel = resourceLevel;
       }
   }
   ```
   All existing `new ResourceCombination(ref)` call sites must be updated to `new ResourceCombination(ref, resourceLevel)`, passing the level already computed at the call site.

5. **`extractCedarActions`** and **`generateStatements`** — move action extraction inside the resource combination loop so each combination uses its own resource level. This requires two coordinated changes:

   a. Change `extractCedarActions` signature to accept `resourceLevel` and call the resource-level-aware overload:
   ```java
   private Set<String> extractCedarActions(RangerPolicyItem item, SourcePolicyAdapter adapter, String resourceLevel) {
       // ...
       Set<String> mapped = adapter.mapAccessTypeToCedarActions(access.getType(), resourceLevel);
       // ...
   }
   ```

   b. In `generateStatements`, **remove** the pre-loop `extractCedarActions(item, adapter)` call and its `isEmpty()` early-return guard. Move action extraction **inside** the `for (ResourceCombination rc : resourceCombinations)` loop, binding the result to a local `cedarActions` variable used immediately in `for (String action : cedarActions)`:
   ```java
   for (ResourceCombination rc : resourceCombinations) {
       Set<String> cedarActions = extractCedarActions(item, adapter, rc.resourceLevel);
       if (cedarActions.isEmpty()) continue;
       for (String action : cedarActions) {
           // ... build statement ...
       }
   }
   ```
   This ensures url-resource combinations get `DATA_LOCATION_ACCESS` and catalog-resource combinations get the catalog actions — driven by the per-combination resource level, not a pre-computed single value.

6. **`buildResourcePath`** — add `url` key so gap messages for url-only policies show the resource path instead of `<no resources>`:
   ```java
   appendResourcePart(sb, resources, "url");
   ```

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

- **`promoteResourceLevel` unchanged**: `url` resources are standalone (not part of the db→table→column hierarchy) so promotion logic doesn't apply. `promoteResourceLevel` only acts on `"column"` and `"table"` resourceLevels; any other input (including `"url"`) is returned unchanged. This is already correct without modification.

- **No Glue catalog expansion for url resources**: S3 paths are not registered in the Glue catalog. Wildcard expansion is impossible; wildcards are passed through with a `WILDCARD_PATTERN` gap.

- **`create` maps to `CREATE_TABLE` only (no `CREATE_DATABASE`)**: The `amazon-emr-spark` service definition has a single `create` access type (no separate `create_database`). Mapping it to `CREATE_TABLE` is consistent with how `HiveServiceAdapter` handles the same access type. There is no `CREATE_DATABASE` mapping because the service definition does not define a database-create operation distinct from table-create.
