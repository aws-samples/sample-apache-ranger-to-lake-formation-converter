# Amazon EMR Spark Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `amazon-emr-spark` `SourcePolicyAdapter` so the converter processes EMR Spark policies (db/table/column → Glue ARNs, url → DataLocation ARNs) instead of skipping them with `UNSUPPORTED_SERVICE_TYPE`.

**Architecture:** Two new files (`EmrSparkServiceAdapter`, `EmrSparkRangerService`) follow the flat-adapter pattern of `HiveServiceAdapter`/`HiveRangerService`. Six targeted changes to `RangerToCedarConverter` add `url` resource support and thread `resourceLevel` into action mapping. `SourcePolicyAdapter` interface gets a new default overload. `ConversionServerMain` registers the new service type.

**Tech Stack:** Java 11+, Apache Ranger plugin model, Cedar policy language, AWS Glue ARN format, Maven (`mvn test`).

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java` | **Create** | Adapter: action mapping, ARN construction for db/table/column/url |
| `src/main/java/com/amazonaws/policyconverters/ranger/service/EmrSparkRangerService.java` | **Create** | `BaseRangerService` subclass wiring EMR Spark into multi-service pipeline |
| `src/test/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapterTest.java` | **Create** | Unit tests for the adapter |
| `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java` | **Modify** | Add `mapAccessTypeToCedarActions(String, String)` default overload |
| `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java` | **Modify** | 6 changes: url level detection, url branch in expandResources, buildEntityRef routing, ResourceCombination resourceLevel field, extractCedarActions, buildResourcePath |
| `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java` | **Modify** | Add EMR Spark integration tests |
| `src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java` | **Modify** | Factory case + MetricsEmitter wiring |
| `src/test/java/com/amazonaws/policyconverters/app/ConversionServerMainTest.java` | **Modify** | Add `createRangerService` test for `amazon-emr-spark` |

---

## Task 1: `EmrSparkServiceAdapter` — adapter unit (TDD)

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java`
- Create: `src/test/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapterTest.java`

### Background for implementer

`SourcePolicyAdapter` is the interface at `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java`. Look at `HiveServiceAdapter` at `src/main/java/com/amazonaws/policyconverters/ranger/HiveServiceAdapter.java` — `EmrSparkServiceAdapter` has the same structure with different action mappings and an extra `url` resource level.

`AwsContext` holds region + accountId used for building ARNs. `CedarEntityRef` is a simple value holder: `new CedarEntityRef(entityType, entityId)`.

EMR Spark access types: `select`, `update`, `alter`, `read`, `write`, `create`, `drop`. No `index`/`lock`.

Action mapping for **catalog** resources (database/table/column):
- `select` → `SELECT`, `update` → `INSERT`, `alter` → `ALTER`, `create` → `CREATE_TABLE`, `drop` → `DROP`, `read` → `SELECT`, `write` → `INSERT`

Action mapping for **url** resources: any access type → `DATA_LOCATION_ACCESS`

ARN formats:
- database: `arn:aws:glue:{region}:{account}:database/{db}`
- table: `arn:aws:glue:{region}:{account}:table/{db}/{table}`
- column: `arn:aws:glue:{region}:{account}:column/{db}/{table}/{col}`
- url: strip `s3://` prefix, prepend `arn:aws:s3:::` → `s3://bucket/path` → `arn:aws:s3:::bucket/path`

The `mapAccessTypeToCedarActions(String, String)` overload (resource-level-aware) is not yet on the interface — Task 2 adds it. For now, implement a package-private helper `mapCatalogAccessType(String)` that the single-arg interface method delegates to. Task 2 will add the overriding two-arg method.

- [ ] **Step 1: Write the adapter test file**

```java
package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EmrSparkServiceAdapterTest {

    private static final AwsContext CTX = new AwsContext("us-east-1", "123456789012", "123456789012");
    private EmrSparkServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new EmrSparkServiceAdapter(CTX);
    }

    // ---- getServiceType ----

    @Test
    void serviceType_isAmazonEmrSpark() {
        assertEquals("amazon-emr-spark", adapter.getServiceType());
    }

    // ---- mapAccessTypeToCedarActions (catalog, single-arg) ----

    @Test
    void select_mapsToCatalogSelect() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("select"));
    }

    @Test
    void update_mapsToInsert() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("update"));
    }

    @Test
    void alter_mapsToAlter() {
        assertEquals(Set.of("ALTER"), adapter.mapAccessTypeToCedarActions("alter"));
    }

    @Test
    void create_mapsToCreateTable() {
        assertEquals(Set.of("CREATE_TABLE"), adapter.mapAccessTypeToCedarActions("create"));
    }

    @Test
    void drop_mapsToDrop() {
        assertEquals(Set.of("DROP"), adapter.mapAccessTypeToCedarActions("drop"));
    }

    @Test
    void read_mapsToSelect() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("read"));
    }

    @Test
    void write_mapsToInsert() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("write"));
    }

    @Test
    void null_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions(null).isEmpty());
    }

    @Test
    void empty_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions("").isEmpty());
    }

    @Test
    void unknown_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions("lock").isEmpty());
    }

    // ---- buildEntityRefFromValues ----

    @Test
    void buildEntityRef_database_producesGlueDatabaseArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("database", "mydb", null, null, null);
        assertEquals("DataCatalog::Database", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:database/mydb", ref.getEntityId());
    }

    @Test
    void buildEntityRef_table_producesGlueTableArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("table", "mydb", "mytable", null, null);
        assertEquals("DataCatalog::Table", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:table/mydb/mytable", ref.getEntityId());
    }

    @Test
    void buildEntityRef_column_producesGlueColumnArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("column", "mydb", "mytable", "mycol", null);
        assertEquals("DataCatalog::Column", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:column/mydb/mytable/mycol", ref.getEntityId());
    }

    @Test
    void buildEntityRef_url_stripsS3SchemeAndProducesS3Arn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("url", null, null, null, "s3://my-bucket/data/");
        assertEquals("DataCatalog::DataLocation", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/data/", ref.getEntityId());
    }

    @Test
    void buildEntityRef_url_withoutS3Scheme_prefixesArn() {
        // Some policies may omit the s3:// prefix; strip replaceFirst is a no-op, result still gets arn: prefix
        CedarEntityRef ref = adapter.buildEntityRefFromValues("url", null, null, null, "my-bucket/path");
        assertEquals("DataCatalog::DataLocation", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/path", ref.getEntityId());
    }

    @Test
    void buildEntityRef_url_withWildcard_preservesWildcard() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("url", null, null, null, "s3://bucket/*");
        assertEquals("arn:aws:s3:::bucket/*", ref.getEntityId());
    }

    @Test
    void buildEntityRef_unknownLevel_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                adapter.buildEntityRefFromValues("unknown", "db", null, null, null));
    }

    // ---- buildPrincipalRef ----

    @Test
    void buildPrincipalRef_returnsInputUnchanged() {
        assertEquals("arn:aws:iam::123:user/bob", adapter.buildPrincipalRef("arn:aws:iam::123:user/bob"));
    }

    // ---- getAwsContext ----

    @Test
    void getAwsContext_returnsNonEmpty() {
        assertTrue(adapter.getAwsContext().isPresent());
        assertEquals("us-east-1", adapter.getAwsContext().get().getRegion());
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (class not found)**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=EmrSparkServiceAdapterTest -q 2>&1 | tail -10
```

Expected: compilation failure — `EmrSparkServiceAdapter` does not exist yet.

- [ ] **Step 3: Implement `EmrSparkServiceAdapter`**

Create `src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java`:

```java
package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EmrSparkServiceAdapter implements SourcePolicyAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(EmrSparkServiceAdapter.class);

    private static final String SERVICE_TYPE = "amazon-emr-spark";

    private static final Map<String, Set<String>> CATALOG_ACTION_MAPPING;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("select", Collections.singleton("SELECT"));
        m.put("update", Collections.singleton("INSERT"));
        m.put("alter",  Collections.singleton("ALTER"));
        m.put("create", Collections.singleton("CREATE_TABLE"));
        m.put("drop",   Collections.singleton("DROP"));
        m.put("read",   Collections.singleton("SELECT"));
        m.put("write",  Collections.singleton("INSERT"));
        CATALOG_ACTION_MAPPING = Collections.unmodifiableMap(m);
    }

    private static final Set<String> URL_ACTIONS = Collections.singleton("DATA_LOCATION_ACCESS");

    private final AwsContext awsContext;
    private volatile MetricsEmitter metricsEmitter;

    public EmrSparkServiceAdapter(AwsContext awsContext) {
        this(awsContext, null);
    }

    public EmrSparkServiceAdapter(AwsContext awsContext, MetricsEmitter metricsEmitter) {
        this.awsContext = awsContext;
        this.metricsEmitter = metricsEmitter;
    }

    public void setMetricsEmitter(MetricsEmitter metricsEmitter) {
        this.metricsEmitter = metricsEmitter;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public Set<String> mapAccessTypeToCedarActions(String sourceAccessType) {
        if (sourceAccessType == null || sourceAccessType.trim().isEmpty()) {
            LOG.error("Null or empty access type provided");
            return Collections.emptySet();
        }
        String normalized = sourceAccessType.trim().toLowerCase();
        Set<String> result = CATALOG_ACTION_MAPPING.get(normalized);
        if (result == null) {
            LOG.error("Unknown EMR Spark access type: '{}' — this access type will be skipped, "
                    + "affected policy items may lose permissions", sourceAccessType);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedAccessType(sourceAccessType);
            }
            return Collections.emptySet();
        }
        return result;
    }

    @Override
    public CedarEntityRef buildEntityRef(RangerPolicy policy, String resourceLevel) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("Policy has no resources");
        }
        switch (resourceLevel) {
            case "database": {
                String db = getFirstResourceValue(resources, "database");
                return new CedarEntityRef("DataCatalog::Database", buildDatabaseArn(db));
            }
            case "table": {
                String db = getFirstResourceValue(resources, "database");
                String table = getFirstResourceValue(resources, "table");
                return new CedarEntityRef("DataCatalog::Table", buildTableArn(db, table));
            }
            case "column": {
                String db = getFirstResourceValue(resources, "database");
                String table = getFirstResourceValue(resources, "table");
                String col = getFirstResourceValue(resources, "column");
                return new CedarEntityRef("DataCatalog::Column", buildColumnArn(db, table, col));
            }
            case "url": {
                String url = getFirstResourceValue(resources, "url");
                return new CedarEntityRef("DataCatalog::DataLocation", buildUrlArn(url));
            }
            default:
                throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
        }
    }

    @Override
    public String buildPrincipalRef(String resolvedPrincipalId) {
        return resolvedPrincipalId;
    }

    @Override
    public Optional<AwsContext> getAwsContext() {
        return Optional.of(awsContext);
    }

    public CedarEntityRef buildEntityRefFromValues(String resourceLevel,
                                                   String database,
                                                   String table,
                                                   String column,
                                                   String dataLocation) {
        switch (resourceLevel) {
            case "database":
                return new CedarEntityRef("DataCatalog::Database", buildDatabaseArn(database));
            case "table":
                return new CedarEntityRef("DataCatalog::Table", buildTableArn(database, table));
            case "column":
                return new CedarEntityRef("DataCatalog::Column", buildColumnArn(database, table, column));
            case "url":
                return new CedarEntityRef("DataCatalog::DataLocation", buildUrlArn(dataLocation));
            default:
                throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
        }
    }

    // ---- url action mapping (used in Task 2 two-arg overload) ----

    Set<String> mapUrlAccessType(String sourceAccessType) {
        if (sourceAccessType == null || sourceAccessType.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return URL_ACTIONS;
    }

    // ---- ARN builders ----

    private String buildDatabaseArn(String database) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":database/" + database;
    }

    private String buildTableArn(String database, String table) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":table/" + database + "/" + table;
    }

    private String buildColumnArn(String database, String table, String column) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":column/" + database + "/" + table + "/" + column;
    }

    private String buildUrlArn(String url) {
        String path = url != null ? url.replaceFirst("^s3://", "") : "";
        return "arn:aws:s3:::" + path;
    }

    private static String getFirstResourceValue(Map<String, RangerPolicyResource> resources, String key) {
        RangerPolicyResource res = resources.get(key);
        if (res == null) {
            throw new IllegalArgumentException("Resource key '" + key + "' not found in policy resources");
        }
        List<String> values = res.getValues();
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Resource key '" + key + "' has no values");
        }
        return values.get(0);
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=EmrSparkServiceAdapterTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java \
        src/test/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapterTest.java
git commit -m "feat: add EmrSparkServiceAdapter for amazon-emr-spark service type"
```

---

## Task 2: Add `mapAccessTypeToCedarActions(String, String)` to `SourcePolicyAdapter`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java` (add override)

### Background for implementer

`SourcePolicyAdapter` is at `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java`. We need a two-arg overload so `EmrSparkServiceAdapter` can return `DATA_LOCATION_ACCESS` for `url` resources and catalog actions for catalog resources — all other adapters inherit the default which delegates to the existing single-arg method.

The TDD sequence here is: (1) write the failing tests, (2) add the interface default (makes tests compile but url-level tests fail because the default delegates to single-arg which returns `SELECT`), (3) add the override to make url-level tests pass.

- [ ] **Step 1: Write the failing tests first**

Add the following tests to `src/test/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapterTest.java`:

```java
// ---- mapAccessTypeToCedarActions (two-arg, url resource level) ----

@Test
void twoArg_urlLevel_select_mapsToDataLocationAccess() {
    assertEquals(Set.of("DATA_LOCATION_ACCESS"),
            adapter.mapAccessTypeToCedarActions("select", "url"));
}

@Test
void twoArg_urlLevel_read_mapsToDataLocationAccess() {
    assertEquals(Set.of("DATA_LOCATION_ACCESS"),
            adapter.mapAccessTypeToCedarActions("read", "url"));
}

@Test
void twoArg_urlLevel_write_mapsToDataLocationAccess() {
    assertEquals(Set.of("DATA_LOCATION_ACCESS"),
            adapter.mapAccessTypeToCedarActions("write", "url"));
}

@Test
void twoArg_tableLevel_select_mapsToCatalogSelect() {
    assertEquals(Set.of("SELECT"),
            adapter.mapAccessTypeToCedarActions("select", "table"));
}

@Test
void twoArg_databaseLevel_alter_mapsToAlter() {
    assertEquals(Set.of("ALTER"),
            adapter.mapAccessTypeToCedarActions("alter", "database"));
}
```

- [ ] **Step 2: Run tests to confirm they fail to compile** (method doesn't exist yet)

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=EmrSparkServiceAdapterTest -q 2>&1 | tail -10
```

Expected: compilation error — `mapAccessTypeToCedarActions(String, String)` does not exist.

- [ ] **Step 3: Add the default overload to the interface**

In `src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java`, add after the `mapAccessTypeToCedarActions(String)` method:

```java
/**
 * Map a source access type to Cedar actions, with awareness of the resource level.
 * The default delegates to the single-arg overload; adapters that vary behaviour
 * by resource level (e.g., EMR Spark url vs catalog) override this method.
 */
default Set<String> mapAccessTypeToCedarActions(String sourceAccessType, String resourceLevel) {
    return mapAccessTypeToCedarActions(sourceAccessType);
}
```

- [ ] **Step 4: Run tests to confirm url-level tests fail (wrong value)**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=EmrSparkServiceAdapterTest -q 2>&1 | tail -10
```

Expected: tests compile, but `twoArg_urlLevel_*` tests fail — the default delegates to single-arg which returns `{"SELECT"}` for `"select"`, not `{"DATA_LOCATION_ACCESS"}`.

- [ ] **Step 5: Add the override to `EmrSparkServiceAdapter`**

In `src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java`, add the `@Override` method after the single-arg `mapAccessTypeToCedarActions`:

```java
@Override
public Set<String> mapAccessTypeToCedarActions(String sourceAccessType, String resourceLevel) {
    if ("url".equals(resourceLevel)) {
        return mapUrlAccessType(sourceAccessType);
    }
    return mapAccessTypeToCedarActions(sourceAccessType);
}
```

- [ ] **Step 6: Run tests — all should pass**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=EmrSparkServiceAdapterTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/cedar/SourcePolicyAdapter.java \
        src/main/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapter.java \
        src/test/java/com/amazonaws/policyconverters/ranger/EmrSparkServiceAdapterTest.java
git commit -m "feat: add resource-level-aware mapAccessTypeToCedarActions overload to SourcePolicyAdapter"
```

---

## Task 3: `RangerToCedarConverter` — url support and `ResourceCombination` threading

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java`

### Background for implementer

This task makes six changes to `RangerToCedarConverter`. Read the full file at `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java` before starting.

Key current code locations:
- `determineResourceLevel`: lines ~379–390. Currently checks `datalocation` → `column` → `table` → else `database`.
- `expandResources`: lines ~428–487. Has early returns for `datalocation` (lines 436–443) then `dbPatterns` guard (line 445).
- `buildEntityRef`: lines ~489–527. Has `instanceof RangerServiceAdapter`, `instanceof HiveServiceAdapter` branches, then a fallback switch.
- `ResourceCombination`: lines ~772–778. Inner static class, one field: `final CedarEntityRef entityRef`.
- `generateStatements`: lines ~272–336. Pre-loop call to `extractCedarActions` at line ~287, `isEmpty()` guard at ~288.
- `extractCedarActions`: lines ~338–350. Currently 2 args: `(RangerPolicyItem item, SourcePolicyAdapter adapter)`.
- `buildResourcePath`: lines ~743–753. Calls `appendResourcePart` for `database`, `table`, `column`.

**Implement changes in this order to keep the build green at each commit.**

### Change A: `determineResourceLevel` — add `url` as first check

- [ ] **Step 1: Add `url` detection to `determineResourceLevel`**

In `determineResourceLevel`, add `url` as the **first** check (before `datalocation`):

```java
private String determineResourceLevel(Map<String, RangerPolicyResource> resources) {
    if (hasResource(resources, "url")) {
        return "url";
    }
    if (hasResource(resources, "datalocation")) {
        return "datalocation";
    }
    if (hasResource(resources, "column")) {
        return "column";
    }
    if (hasResource(resources, "table")) {
        return "table";
    }
    return "database";
}
```

- [ ] **Step 2: Run existing tests to confirm no regressions**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=RangerToCedarConverterTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. (No EMR Spark test yet, but no existing test should break.)

### Change B: `expandResources` — add `url` early-return branch

- [ ] **Step 3: Add `url` branch to `expandResources`**

In `expandResources`, add this block immediately after the `datalocation` early return (after the closing `}` of the `if ("datalocation".equals(resourceLevel))` block, and before `List<String> dbPatterns = getResourceValues(resources, "database")`):

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

Note: `new ResourceCombination(ref, resourceLevel)` uses the two-arg constructor — you will add this constructor in Change D. For now, this will produce a compilation error; that is fine because Change D is in the same task and the code will compile after all changes in this task are applied before committing.

### Change C: `buildEntityRef` — add `EmrSparkServiceAdapter` routing and `url` fallback

- [ ] **Step 4: Add EMR Spark instanceof branch and url fallback case**

In `buildEntityRef`, add the `EmrSparkServiceAdapter` branch **before** the `HiveServiceAdapter` branch:

```java
if (adapter instanceof EmrSparkServiceAdapter) {
    return ((EmrSparkServiceAdapter) adapter).buildEntityRefFromValues(
            resourceLevel, database, table, column, dataLocation);
}
```

Also add a `"url"` case to the fallback `switch` (before the `default` throw):

```java
case "url":
    entityType = "DataCatalog::DataLocation";
    entityId = dataLocation != null ? dataLocation.replaceFirst("^s3://", "arn:aws:s3:::") : "";
    break;
```

Note: The fallback `switch` currently has `database`, `table`, `column`, `datalocation` cases. Add `url` after `datalocation`.

### Change D: `ResourceCombination` — add `resourceLevel` field

- [ ] **Step 5: Update the `ResourceCombination` inner class**

Replace the existing `ResourceCombination` class:

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

Then update **all existing** `new ResourceCombination(ref)` call sites in `expandResources` to pass `resourceLevel` as the second argument. The existing call sites are:
- database branch: `new ResourceCombination(ref)` → `new ResourceCombination(ref, resourceLevel)` (resourceLevel is `"database"` at that point)
- table branch: same → `new ResourceCombination(ref, resourceLevel)` (resourceLevel is `"table"`)
- column branch: same → `new ResourceCombination(ref, resourceLevel)` (resourceLevel is `"column"`)
- datalocation branch: same → `new ResourceCombination(ref, resourceLevel)` (resourceLevel is `"datalocation"`)

### Change E: `extractCedarActions` — add `resourceLevel` parameter and restructure `generateStatements`

- [ ] **Step 6: Update `extractCedarActions` signature**

Change `extractCedarActions` to accept `resourceLevel` and use the two-arg overload:

```java
private Set<String> extractCedarActions(RangerPolicyItem item, SourcePolicyAdapter adapter, String resourceLevel) {
    if (item.getAccesses() == null || item.getAccesses().isEmpty()) {
        return Collections.emptySet();
    }
    java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
    for (RangerPolicyItemAccess access : item.getAccesses()) {
        if (access.getIsAllowed() == null || access.getIsAllowed()) {
            Set<String> mapped = adapter.mapAccessTypeToCedarActions(access.getType(), resourceLevel);
            actions.addAll(mapped);
        }
    }
    return actions;
}
```

- [ ] **Step 7: Restructure `generateStatements` to move action extraction inside the resource loop**

In `generateStatements`, **remove** the pre-loop `extractCedarActions` call and its `isEmpty()` guard:

```java
// DELETE these two lines:
Set<String> cedarActions = extractCedarActions(item, adapter);
if (cedarActions.isEmpty()) {
    return statements;
}
```

Then, inside the `for (ResourceCombination rc : resourceCombinations)` loop, add the per-combination action extraction **before** the `for (String action : cedarActions)` inner loop:

```java
for (ResourceCombination rc : resourceCombinations) {
    Set<String> cedarActions = extractCedarActions(item, adapter, rc.resourceLevel);
    if (cedarActions.isEmpty()) continue;
    for (String action : cedarActions) {
        // ... existing statement building code (unchanged) ...
    }
}
```

The existing statement building code inside the inner loop is unchanged — only the `cedarActions` variable binding moves.

### Change F: `buildResourcePath` — include `url` key

- [ ] **Step 8: Add `url` to `buildResourcePath`**

In `buildResourcePath`, add `appendResourcePart(sb, resources, "url");` after the `column` line:

```java
private String buildResourcePath(RangerPolicy policy) {
    Map<String, RangerPolicyResource> resources = policy.getResources();
    if (resources == null || resources.isEmpty()) {
        return "<no resources>";
    }
    StringBuilder sb = new StringBuilder();
    appendResourcePart(sb, resources, "database");
    appendResourcePart(sb, resources, "table");
    appendResourcePart(sb, resources, "column");
    appendResourcePart(sb, resources, "url");
    return sb.length() > 0 ? sb.toString() : "<no resources>";
}
```

- [ ] **Step 9: Run existing converter tests to confirm no regressions**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=RangerToCedarConverterTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`. All existing tests should still pass.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java
git commit -m "feat: add url resource level support to RangerToCedarConverter"
```

---

## Task 4: `RangerToCedarConverterTest` — EMR Spark integration tests

**Files:**
- Modify: `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java`

### Background for implementer

Read the existing test file at `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java`. Focus on the `setUp()` method — it creates a `hiveConverter` using `HiveServiceAdapter` and `PassthroughPrincipalMapper`. You will add an `emrSparkConverter` using the same pattern with `EmrSparkServiceAdapter`.

The tests need to verify:
1. A catalog policy (table-level) produces a `DataCatalog::Table` Cedar statement with `SELECT`
2. A url policy produces a `DataCatalog::DataLocation` Cedar statement with `DATA_LOCATION_ACCESS`
3. A wildcard url records a `WILDCARD_PATTERN` gap

Cedar schema validation will reject `DATA_LOCATION_ACCESS` on `DataCatalog::DataLocation` if the schema doesn't include it — check by running the test; if validation fails, the test should assert it at least recorded a non-empty Cedar output or a specific gap type. Based on existing code (`datalocation` resources already produce `DataCatalog::DataLocation` + `DATA_LOCATION_ACCESS` for the LakeFormation adapter), the schema supports this combination.

- [ ] **Step 1: Add imports and `emrSparkConverter` field**

In `RangerToCedarConverterTest.java`, add the import:
```java
import com.amazonaws.policyconverters.ranger.EmrSparkServiceAdapter;
```

Add field declaration alongside `hiveConverter`:
```java
private RangerToCedarConverter emrSparkConverter;
```

In `setUp()`, add after the `hiveConverter` setup block:
```java
AwsContext emrCtx = new AwsContext("us-east-1", "123456789012", "123456789012");
EmrSparkServiceAdapter emrSparkAdapter = new EmrSparkServiceAdapter(emrCtx);
Map<String, SourcePolicyAdapter> emrRegistry = new HashMap<>();
emrRegistry.put("amazon-emr-spark", emrSparkAdapter);
emrSparkConverter = new RangerToCedarConverter(emrRegistry, new PassthroughPrincipalMapper(),
        new PassthroughCatalogResolver(), gapReporter, schemaProvider);
```

Note: `gapReporter` and `schemaProvider` are already defined in `setUp()`.

- [ ] **Step 2: Write failing tests for EMR Spark catalog policy**

Add these tests to the test class:

```java
// ---- EMR Spark catalog policies ----

@Test
void emrSpark_tablePolicy_producesDataCatalogTableStatement() {
    RangerPolicy policy = buildEmrSparkTablePolicy("mydb", "mytable", "select");

    CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::Table::"), "Must reference DataCatalog::Table entity");
    assertTrue(cedar.contains("arn:aws:glue:us-east-1:123456789012:table/mydb/mytable"),
            "Must contain Glue table ARN");
    assertTrue(cedar.contains("\"SELECT\""), "Must contain SELECT action");
    assertTrue(cedar.contains("permit("), "Must be a permit statement");
}

@Test
void emrSpark_columnWildcard_promotesToTableLevel() {
    RangerPolicy policy = buildEmrSparkPolicy("mydb", "mytable", "*", "select");

    CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::Table::"),
            "col=* should promote to table-level grant");
    assertFalse(cedar.contains("DataCatalog::Column::"),
            "Should not produce column-level grant when col=*");
}

// ---- EMR Spark url policies ----

@Test
void emrSpark_urlPolicy_producesDataLocationStatement() {
    RangerPolicy policy = buildEmrSparkUrlPolicy("s3://my-bucket/data/", "read");

    CedarPolicySet result = emrSparkConverter.convert(Collections.singletonList(policy));

    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::DataLocation::"),
            "URL policy must reference DataCatalog::DataLocation entity");
    assertTrue(cedar.contains("arn:aws:s3:::my-bucket/data/"),
            "Must contain S3 ARN with s3:// stripped");
    assertTrue(cedar.contains("\"DATA_LOCATION_ACCESS\""),
            "URL policy must use DATA_LOCATION_ACCESS action");
}

@Test
void emrSpark_wildcardUrl_recordsWildcardPatternGap() {
    // Use a fresh GapReporter so prior gaps don't interfere with the count check
    GapReporter freshGapReporter = new GapReporter();
    CedarSchemaProvider schemaProvider = new CedarSchemaProvider();
    Map<String, SourcePolicyAdapter> emrRegistry = new HashMap<>();
    emrRegistry.put("amazon-emr-spark",
            new EmrSparkServiceAdapter(new AwsContext("us-east-1", "123456789012", "123456789012")));
    RangerToCedarConverter freshConverter = new RangerToCedarConverter(
            emrRegistry, new PassthroughPrincipalMapper(),
            new PassthroughCatalogResolver(), freshGapReporter, schemaProvider);

    RangerPolicy policy = buildEmrSparkUrlPolicy("s3://bucket/*", "read");
    freshConverter.convert(Collections.singletonList(policy));

    boolean hasWildcardGap = freshGapReporter.getReport().getEntries().stream()
            .anyMatch(e -> e.getGapType() == GapType.WILDCARD_PATTERN);
    assertTrue(hasWildcardGap, "Wildcard URL should record WILDCARD_PATTERN gap");
}
```

- [ ] **Step 3: Add helper methods**

Add these builders at the bottom of the test class (alongside existing helpers like `buildHivePolicy`):

```java
private RangerPolicy buildEmrSparkPolicy(String db, String table, String col, String accessType) {
    RangerPolicy policy = new RangerPolicy();
    policy.setId(1001L);
    policy.setName("test-emr-spark-policy");
    policy.setService("amazon-emr-spark");
    policy.setIsEnabled(true);
    Map<String, RangerPolicyResource> resources = new HashMap<>();
    resources.put("database", resource(db));
    resources.put("table", resource(table));
    resources.put("column", resource(col));
    policy.setResources(resources);
    RangerPolicyItem item = new RangerPolicyItem();
    item.setUsers(List.of("alice"));
    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
    access.setType(accessType);
    access.setIsAllowed(true);
    item.setAccesses(List.of(access));
    policy.setPolicyItems(List.of(item));
    return policy;
}

private RangerPolicy buildEmrSparkTablePolicy(String db, String table, String accessType) {
    RangerPolicy policy = new RangerPolicy();
    policy.setId(1002L);
    policy.setName("test-emr-spark-table-policy");
    policy.setService("amazon-emr-spark");
    policy.setIsEnabled(true);
    Map<String, RangerPolicyResource> resources = new HashMap<>();
    resources.put("database", resource(db));
    resources.put("table", resource(table));
    policy.setResources(resources);
    RangerPolicyItem item = new RangerPolicyItem();
    item.setUsers(List.of("alice"));
    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
    access.setType(accessType);
    access.setIsAllowed(true);
    item.setAccesses(List.of(access));
    policy.setPolicyItems(List.of(item));
    return policy;
}

private RangerPolicy buildEmrSparkUrlPolicy(String url, String accessType) {
    RangerPolicy policy = new RangerPolicy();
    policy.setId(1003L);
    policy.setName("test-emr-spark-url-policy");
    policy.setService("amazon-emr-spark");
    policy.setIsEnabled(true);
    Map<String, RangerPolicyResource> resources = new HashMap<>();
    RangerPolicyResource urlRes = new RangerPolicyResource();
    urlRes.setValues(List.of(url));
    resources.put("url", urlRes);
    policy.setResources(resources);
    RangerPolicyItem item = new RangerPolicyItem();
    item.setUsers(List.of("alice"));
    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
    access.setType(accessType);
    access.setIsAllowed(true);
    item.setAccesses(List.of(access));
    policy.setPolicyItems(List.of(item));
    return policy;
}
```

Note: `GapReporter.clear()` may not exist — check the `GapReporter` class. If it doesn't have a `clear()` method, reset the gap reporter by assigning `gapReporter = new GapReporter()` before the test and re-creating `emrSparkConverter` with it, or check that the wildcard gap test accounts for previously recorded gaps by filtering on WILDCARD_PATTERN type (as shown).

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=RangerToCedarConverterTest -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java
git commit -m "test: add EMR Spark integration tests to RangerToCedarConverterTest"
```

---

## Task 5: `EmrSparkRangerService` + factory registration

**Files:**
- Create: `src/main/java/com/amazonaws/policyconverters/ranger/service/EmrSparkRangerService.java`
- Modify: `src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java`
- Modify: `src/test/java/com/amazonaws/policyconverters/app/ConversionServerMainTest.java`

### Background for implementer

Look at `HiveRangerService` at `src/main/java/com/amazonaws/policyconverters/ranger/service/HiveRangerService.java` and `EmrfsRangerService` at `src/main/java/com/amazonaws/policyconverters/ranger/service/EmrfsRangerService.java` — `EmrSparkRangerService` follows exactly the same pattern.

In `ConversionServerMain.createRangerService` (lines ~483–501), add a new case for `"amazon-emr-spark"` in the switch statement. In `startServer` (lines ~387–397), add a new `else if` branch for `EmrSparkServiceAdapter` in the MetricsEmitter wiring loop.

The service definition file must exist at `conf/ranger-servicedef-amazon-emr-spark.json` — it already does (this is a read-only existing file).

- [ ] **Step 1: Write the factory test first**

Add this test to `src/test/java/com/amazonaws/policyconverters/app/ConversionServerMainTest.java`:

```java
@Test
void createRangerService_amazonEmrSpark_returnsEmrSparkRangerService() {
    com.amazonaws.policyconverters.config.RangerServiceConfig config =
            new com.amazonaws.policyconverters.config.RangerServiceConfig(
                    "amazon-emr-spark", "spark-instance", null, null);
    BaseRangerService service = ConversionServerMain.createRangerService(config);
    assertNotNull(service);
    assertEquals("amazon-emr-spark", service.getServiceType());
    assertEquals("spark-instance", service.getServiceInstanceName());
}
```

You'll need to add imports to `ConversionServerMainTest.java`:
```java
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
```

Check whether `RangerServiceConfig` has the right constructor by reading `src/main/java/com/amazonaws/policyconverters/config/RangerServiceConfig.java`.

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=ConversionServerMainTest#createRangerService_amazonEmrSpark_returnsEmrSparkRangerService -q 2>&1 | tail -10
```

Expected: test fails (no `amazon-emr-spark` case in factory yet).

- [ ] **Step 3: Create `EmrSparkRangerService`**

Create `src/main/java/com/amazonaws/policyconverters/ranger/service/EmrSparkRangerService.java`:

```java
package com.amazonaws.policyconverters.ranger.service;

import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.ranger.EmrSparkServiceAdapter;

public class EmrSparkRangerService extends BaseRangerService {

    public EmrSparkRangerService(String instanceName) {
        super("amazon-emr-spark", instanceName);
    }

    @Override
    public SourcePolicyAdapter createAdapter(AwsContext awsContext) {
        return new EmrSparkServiceAdapter(awsContext);
    }

    @Override
    public String getServiceDefinitionResourcePath() {
        return "/ranger-servicedef-amazon-emr-spark.json";
    }
}
```

- [ ] **Step 4: Register in `ConversionServerMain.createRangerService`**

In `ConversionServerMain.java`, in the `createRangerService` switch (around line 488), add after the `"amazon-emr-emrfs"` case:

```java
case "amazon-emr-spark":
    return new EmrSparkRangerService(instanceName);
```

Also add the import at the top of `ConversionServerMain.java`:
```java
import com.amazonaws.policyconverters.ranger.service.EmrSparkRangerService;
```

- [ ] **Step 5: Add MetricsEmitter wiring in `startServer`**

In `ConversionServerMain.startServer`, in the MetricsEmitter wiring loop (around lines 387–397), add after the `TrinoServiceAdapter` branch:

```java
} else if (adapter instanceof EmrSparkServiceAdapter) {
    ((EmrSparkServiceAdapter) adapter).setMetricsEmitter(metricsEmitter);
}
```

Also add the import:
```java
import com.amazonaws.policyconverters.ranger.EmrSparkServiceAdapter;
```

- [ ] **Step 6: Run all tests**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/ranger/service/EmrSparkRangerService.java \
        src/main/java/com/amazonaws/policyconverters/app/ConversionServerMain.java \
        src/test/java/com/amazonaws/policyconverters/app/ConversionServerMainTest.java
git commit -m "feat: register amazon-emr-spark service type in factory and MetricsEmitter wiring"
```

---

## Task 6: Full test suite + smoke test

**Files:** None (verification only)

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`, zero failures.

- [ ] **Step 2: Build the assessment JAR**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn package -pl . -DskipTests -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run smoke test against the customer export**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
java -jar target/assessment-jar-with-dependencies.jar assess file Ranger_Policies_20260602_001709.json 2>&1 | grep -E "(UNSUPPORTED_SERVICE_TYPE|Fully convertible|Policies scanned|amazon-emr-spark)"
```

Expected:
- `UNSUPPORTED_SERVICE_TYPE` count drops significantly (from ~37k to near 0 or 0)
- `Fully convertible` percentage increases
- `amazon-emr-spark` services show as assessed (not skipped)

- [ ] **Step 4: Commit if any final fixes were needed**

If no fixes were needed, no commit required here.
