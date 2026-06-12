# Wildcard Resource Level Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote Hive resource levels upward when trailing resource keys are all wildcards, so that `db=X, table=Y, col=*` produces a table-level ARN (not a column ARN with `*` as the column name), eliminating spurious `UNMAPPED_RESOURCE` and `SCHEMA_VALIDATION_FAILURE` gaps for policies that need no GDC expansion.

**Architecture:** A new helper method `promoteResourceLevel()` in `RangerToCedarConverter` runs after `determineResourceLevel()` and before `expandResources()`. It walks down the column→table→database axis and promotes the level as long as every value for that level is a pure wildcard (`*` or `?`). Policies where the table pattern is non-trivial (e.g. `tbl_*`) but column is `*` are promoted to table-level and then expanded against GDC normally — no second call per column is needed. A `WILDCARD_PATTERN` gap is recorded for patterns that still contain wildcards after promotion (i.e. those that need a real GDC call in production) when running in passthrough mode.

**Tech Stack:** Java 17, JUnit 5, Mockito, Apache Ranger model objects, existing `RangerToCedarConverter`/`HiveServiceAdapter`/`PassthroughCatalogResolver` infrastructure.

---

## File Map

| File | Change |
|---|---|
| `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java` | Add `promoteResourceLevel()`, call it between `determineResourceLevel()` and `expandResources()`, record `WILDCARD_PATTERN` gap for unresolvable wildcard table patterns in passthrough mode |
| `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java` | Add five promotion scenario tests (Task 1) and one gap test (Task 2) |

No other files need changing: `HiveServiceAdapter.buildEntityRefFromValues()` already handles `"table"` and `"database"` resource levels correctly; the ARN builders produce the right output once given the correct level.

---

## Background: the promotion table

| Input | Old level | New level | GDC needed? |
|---|---|---|---|
| `db=X, table=Y, col=*` | column | table | No |
| `db=X, table=*, col=*` | column | database | No |
| `db=X, table=tbl_*, col=*` | column | table | Yes (table expansion) |
| `db=X, table=Y, col=Z` | column | column (unchanged) | No |
| `db=X, table=Y, col=col_*` | column | column (unchanged) | Yes (col expansion) |
| `db=X, table=*` | table | database | No |
| `db=X, table=tbl_*` | table | table (unchanged) | Yes (table expansion) |

**Definition of "all wildcards":** every value in the resource list consists solely of `*` and/or `?` characters (i.e. `isAllWildcard(List<String> values)` returns true when every element matches `[*?]+`).

---

### Task 1: Add `promoteResourceLevel()` to `RangerToCedarConverter`

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java`
- Test: `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java`

This task implements the core logic: the helper and its integration into the conversion path.

- [ ] **Step 1: Write failing tests for `promoteResourceLevel` scenarios**

Add these tests to `RangerToCedarConverterTest`. The test setup already has a `HiveServiceAdapter` registered for `"hive"` (add it if not present — see existing `setUp()` which only registers `"lakeformation"`). Each test converts a single Hive policy and asserts on the Cedar text produced.

Add a second `setUp` helper or extend the existing `converter` field to also hold a Hive-capable converter:

```java
// Near top of class, after existing converter field:
private RangerToCedarConverter hiveConverter;

// In setUp(), after building the existing converter, add:
AwsContext hiveCtx = new AwsContext("us-east-1", "123456789012", "123456789012");
HiveServiceAdapter hiveAdapter = new HiveServiceAdapter(hiveCtx);
Map<String, SourcePolicyAdapter> hiveRegistry = new HashMap<>();
hiveRegistry.put("hive", hiveAdapter);
// Use PassthroughPrincipalMapper so any username resolves without a static map.
// Use new PassthroughCatalogResolver() — NOT mockPassthroughResolver(), which returns
// empty lists for wildcards and would prevent wildcard table/column patterns from
// reaching the gap-recording code.
// Reuse gapReporter and schemaProvider from above.
PrincipalMapper passthroughMapper = new PassthroughPrincipalMapper();
hiveConverter = new RangerToCedarConverter(hiveRegistry, passthroughMapper,
        new PassthroughCatalogResolver(), gapReporter, schemaProvider);
```

Add to the imports at the top of the test file (both are needed):
```java
import com.amazonaws.policyconverters.lakeformation.PassthroughPrincipalMapper;
import com.amazonaws.policyconverters.ranger.HiveServiceAdapter;
import com.amazonaws.policyconverters.ranger.PassthroughCatalogResolver;
```

Then add the promotion scenario tests:

```java
@Test
void hive_columnWildcard_withLiteralTable_promotesToTableLevel() {
    // db=mydb, table=mytable, col=* → table-level ARN
    RangerPolicy policy = buildHivePolicy("mydb", "mytable", "*");
    CedarPolicySet result = hiveConverter.convert(List.of(policy));
    String cedar = result.toCedarString();
    // Must reference a Table ARN, not a Column ARN with literal "*"
    assertTrue(cedar.contains("DataCatalog::Table"), "Expected table-level entity");
    assertFalse(cedar.contains("DataCatalog::Column"), "Must not produce column-level entity");
    assertFalse(cedar.contains("column/mydb/mytable"), "Must not contain column-path ARN segment");
}

@Test
void hive_columnAndTableWildcard_promotesToDatabaseLevel() {
    // db=mydb, table=*, col=* → database-level Glue ARN
    RangerPolicy policy = buildHivePolicy("mydb", "*", "*");
    CedarPolicySet result = hiveConverter.convert(List.of(policy));
    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::Database"), "Expected database-level entity");
    assertTrue(cedar.contains("arn:aws:glue:"), "Expected Glue ARN (not bare db name)");
    assertFalse(cedar.contains("DataCatalog::Table"), "Must not produce table-level entity");
    assertFalse(cedar.contains("DataCatalog::Column"), "Must not produce column-level entity");
}

@Test
void hive_tableWildcardOnly_promotesToDatabaseLevel() {
    // db=mydb, table=* (no column key) → database-level Glue ARN
    RangerPolicy policy = buildHivePolicyNoColumn("mydb", "*");
    CedarPolicySet result = hiveConverter.convert(List.of(policy));
    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::Database"), "Expected database-level entity");
    assertTrue(cedar.contains("arn:aws:glue:"), "Expected Glue ARN (not bare db name)");
}

@Test
void hive_literalColumn_noPromotion() {
    // db=mydb, table=mytable, col=mycol → column-level ARN unchanged
    RangerPolicy policy = buildHivePolicy("mydb", "mytable", "mycol");
    CedarPolicySet result = hiveConverter.convert(List.of(policy));
    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::Column"), "Expected column-level entity");
    assertTrue(cedar.contains("column/mydb/mytable/mycol"), "Expected full column ARN");
}

@Test
void hive_partialWildcardTable_noPromotionToDatabase_producesTableLevel() {
    // db=mydb, table=tbl_*, col=* → stays at table level (non-trivial table pattern)
    // PassthroughCatalogResolver returns "tbl_*" as-is, so we get a table ARN with "tbl_*" in it
    RangerPolicy policy = buildHivePolicy("mydb", "tbl_*", "*");
    CedarPolicySet result = hiveConverter.convert(List.of(policy));
    String cedar = result.toCedarString();
    assertTrue(cedar.contains("DataCatalog::Table"), "Expected table-level entity after col=* promotion");
    assertFalse(cedar.contains("DataCatalog::Column"), "Must not produce column-level entity");
}
```

Also add the helper builders at the bottom of the test class:

```java
// Helper: build a Hive policy with database/table/column resources and one allow item
private RangerPolicy buildHivePolicy(String db, String table, String col) {
    RangerPolicy policy = new RangerPolicy();
    policy.setId(999L);
    policy.setName("test-hive-policy");
    policy.setService("hive");
    policy.setIsEnabled(true);

    Map<String, RangerPolicyResource> resources = new HashMap<>();
    resources.put("database", resource(db));
    resources.put("table", resource(table));
    resources.put("column", resource(col));
    policy.setResources(resources);

    RangerPolicyItem item = new RangerPolicyItem();
    // Use "alice" — PassthroughPrincipalMapper resolves it to "ranger-user:alice"
    item.setUsers(List.of("alice"));
    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
    access.setType("select");
    access.setIsAllowed(true);
    item.setAccesses(List.of(access));
    policy.setPolicyItems(List.of(item));
    return policy;
}

// Helper: build a Hive policy with only database/table (no column key)
private RangerPolicy buildHivePolicyNoColumn(String db, String table) {
    RangerPolicy policy = new RangerPolicy();
    policy.setId(998L);
    policy.setName("test-hive-table-policy");
    policy.setService("hive");
    policy.setIsEnabled(true);

    Map<String, RangerPolicyResource> resources = new HashMap<>();
    resources.put("database", resource(db));
    resources.put("table", resource(table));
    policy.setResources(resources);

    RangerPolicyItem item = new RangerPolicyItem();
    item.setUsers(List.of("alice"));
    RangerPolicyItemAccess access = new RangerPolicyItemAccess();
    access.setType("select");
    access.setIsAllowed(true);
    item.setAccesses(List.of(access));
    policy.setPolicyItems(List.of(item));
    return policy;
}

// Helper: build a single-value resource
private RangerPolicyResource resource(String value) {
    RangerPolicyResource r = new RangerPolicyResource();
    r.setValues(List.of(value));
    return r;
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/hocanint/workspace/ApacheRangerToLF
mvn test -pl . -Dtest=RangerToCedarConverterTest -q 2>&1 | tail -20
```

Expected: the 5 new tests fail. The existing tests still pass.

- [ ] **Step 3: Add `isAllWildcard` and `promoteResourceLevel` to `RangerToCedarConverter`**

In `RangerToCedarConverter.java`, add these two private methods after `determineResourceLevel()` (around line 389):

```java
/**
 * Returns true if every value in {@code values} is a pure wildcard pattern
 * (i.e. consists only of {@code *} and/or {@code ?} characters).
 * Used to decide whether a resource level can be promoted upward.
 */
private boolean isAllWildcard(List<String> values) {
    if (values == null || values.isEmpty()) return false;
    for (String v : values) {
        if (v == null || !v.matches("[*?]+")) return false;
    }
    return true;
}

/**
 * Promotes a resource level upward when trailing levels are all wildcards.
 *
 * <ul>
 *   <li>{@code col=*} with any table → table level</li>
 *   <li>{@code col=*} with {@code table=*} → database level</li>
 *   <li>{@code table=*} (no column) → database level</li>
 * </ul>
 *
 * Promotion only happens when the value is a pure wildcard ({@code [*?]+}).
 * Partial patterns like {@code tbl_*} do not trigger database promotion but
 * do still benefit from column promotion (col=* → table level).
 */
private String promoteResourceLevel(String resourceLevel,
                                    Map<String, RangerPolicyResource> resources) {
    if ("column".equals(resourceLevel)) {
        List<String> colValues = getResourceValues(resources, "column");
        if (isAllWildcard(colValues)) {
            // col=* → promote to table level; check if table is also all-wildcard
            List<String> tableValues = getResourceValues(resources, "table");
            if (isAllWildcard(tableValues)) {
                return "database";
            }
            return "table";
        }
    } else if ("table".equals(resourceLevel)) {
        List<String> tableValues = getResourceValues(resources, "table");
        if (isAllWildcard(tableValues)) {
            return "database";
        }
    }
    return resourceLevel;
}
```

- [ ] **Step 4: Wire `promoteResourceLevel()` into `convertSinglePolicy()`**

In `convertSinglePolicy()`, replace the existing single call to `determineResourceLevel()` (line ~198) with two lines:

```java
String resourceLevel = determineResourceLevel(resources);
resourceLevel = promoteResourceLevel(resourceLevel, resources);
```

Also update `expandResources()` to accept `policyId` as a 4th parameter and update its call site in `convertSinglePolicy()`. Do this all at once now so the code compiles cleanly for Step 6. The internal use of `policyId` (threading it into `expandTablePatterns`/`expandColumnPatterns`) is added in Task 2.

Update the `expandResources` signature (line ~399):
```java
private List<ResourceCombination> expandResources(
        Map<String, RangerPolicyResource> resources,
        String resourceLevel,
        SourcePolicyAdapter adapter,
        String policyId) {
```

Update the call site in `convertSinglePolicy()` (line ~201):
```java
List<ResourceCombination> resourceCombinations = expandResources(
        resources, resourceLevel, adapter, policyId);
```

The `policyId` parameter is available at that point — it was extracted a few lines above: `String policyId = policy.getId() != null ? String.valueOf(policy.getId()) : "unknown";`

- [ ] **Step 5: Fix the `buildEntityRef` fallback in `RangerToCedarConverter` to route Hive correctly**

At line ~465, the private `buildEntityRef` helper routes `RangerServiceAdapter` via `buildEntityRefFromValues` but falls through to a bare slash-path for everything else (including `HiveServiceAdapter`). This is what currently produces `database/table/column` identifiers for Hive instead of ARNs. Update the routing to also handle `HiveServiceAdapter`:

```java
private CedarEntityRef buildEntityRef(SourcePolicyAdapter adapter,
                                      String resourceLevel,
                                      String database,
                                      String table,
                                      String column,
                                      String dataLocation) {
    if (adapter instanceof RangerServiceAdapter) {
        return ((RangerServiceAdapter) adapter).buildEntityRefFromValues(
                resourceLevel, database, table, column, dataLocation);
    }
    if (adapter instanceof HiveServiceAdapter) {
        return ((HiveServiceAdapter) adapter).buildEntityRefFromValues(
                resourceLevel, database, table, column, dataLocation);
    }
    // Fallback: construct a simple entity ref (non-ARN; may produce UNMAPPED_RESOURCE later)
    String entityType;
    String entityId;
    switch (resourceLevel) {
        case "database":
            entityType = "DataCatalog::Database";
            entityId = database;
            break;
        case "table":
            entityType = "DataCatalog::Table";
            entityId = database + "/" + table;
            break;
        case "column":
            entityType = "DataCatalog::Column";
            entityId = database + "/" + table + "/" + column;
            break;
        case "datalocation":
            entityType = "DataCatalog::DataLocation";
            entityId = dataLocation;
            break;
        default:
            throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
    }
    return new CedarEntityRef(entityType, entityId);
}
```

No import needed — `HiveServiceAdapter` is in the same package as `RangerToCedarConverter` (`com.amazonaws.policyconverters.ranger`).

- [ ] **Step 6: Run tests to confirm they pass**

```bash
mvn test -pl . -Dtest=RangerToCedarConverterTest -q 2>&1 | tail -20
```

Expected: all tests pass including the 5 new ones.

- [ ] **Step 7: Run the full test suite**

```bash
mvn test -q 2>&1 | tail -30
```

Expected: no regressions. Fix any that appear before committing.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java \
        src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java
git commit -m "feat: promote Hive resource level upward when trailing keys are all wildcards"
```

---

### Task 2: Record `WILDCARD_PATTERN` gap for unresolvable table/column patterns in passthrough mode

**Files:**
- Modify: `src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java`
- Test: `src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java`

When a table or column pattern still contains wildcards after promotion (e.g. `tbl_*`), the `PassthroughCatalogResolver` returns it as-is. The caller doesn't know whether the resulting ARN reflects a real table or is a placeholder. Record a `WILDCARD_PATTERN` gap so the assessment report accurately describes this situation instead of silently emitting a plausible-looking ARN.

The gap should be recorded in `expandTablePatterns()` and `expandColumnPatterns()` when the resolver is a `PassthroughCatalogResolver` and the pattern is a wildcard. One gap per policy (not per expanded value) is sufficient — use the `policyId` from the calling context.

> **Important constraint:** The gap is informational — conversion still proceeds and the passthrough ARN is emitted. The policy is counted as "partially convertible," not "not convertible."

- [ ] **Step 1: Write failing test**

Add to `RangerToCedarConverterTest`:

```java
@Test
void hive_partialWildcardTable_recordsWildcardPatternGap() {
    // db=mydb, table=tbl_*, col=* → table level, but a WILDCARD_PATTERN gap is recorded
    // because "tbl_*" cannot be resolved without GDC
    RangerPolicy policy = buildHivePolicy("mydb", "tbl_*", "*");
    hiveConverter.convert(List.of(policy));
    List<GapEntry> gaps = gapReporter.getReport().getEntries();
    assertTrue(gaps.stream().anyMatch(g -> g.getGapType() == GapType.WILDCARD_PATTERN),
            "Expected WILDCARD_PATTERN gap for unresolvable table pattern");
}
```

Run to confirm it fails:
```bash
mvn test -pl . -Dtest=RangerToCedarConverterTest#hive_partialWildcardTable_recordsWildcardPatternGap -q 2>&1 | tail -10
```

- [ ] **Step 2: Thread `policyId` into `expandTablePatterns` and `expandColumnPatterns`**

The expand methods currently do not know the policy ID. The simplest change: add a `String policyId` parameter to each, pass it from `expandResources()`.

In `expandResources()`, `expandTablePatterns` is called **twice** — once in the `"table"` branch (~line 434) and once in the `"column"` branch (~line 446). Update **both** call sites:

```java
// In the "table" branch (~line 434):
List<String> expandedTables = expandTablePatterns(tablePatterns, db, policyId);

// In the "column" branch (~line 446):
List<String> expandedTables = expandTablePatterns(tablePatterns, db, policyId);
List<String> expandedColumns = expandColumnPatterns(columnPatterns, db, table, policyId);
```

Update method signatures:
```java
private List<String> expandTablePatterns(List<String> patterns, String database, String policyId) {
private List<String> expandColumnPatterns(List<String> patterns, String database, String table, String policyId) {
```

Also add `policyId` as a fourth parameter to `expandResources()` itself and update its call site in `convertSinglePolicy()`. The call site (line ~201, updated in Task 1 Step 4) already passes `policyId` — confirm the signature matches:

```java
private List<ResourceCombination> expandResources(
        Map<String, RangerPolicyResource> resources,
        String resourceLevel,
        SourcePolicyAdapter adapter,
        String policyId) {
```

Call site in `convertSinglePolicy()` (already updated in Task 1):
```java
List<ResourceCombination> resourceCombinations = expandResources(
        resources, resourceLevel, adapter, policyId);
```

- [ ] **Step 3: Record `WILDCARD_PATTERN` gap in `expandTablePatterns` when passthrough**

Inside `expandTablePatterns()`, when `isWildcard(pattern)` is true and the resolved list still contains a pattern (i.e. it wasn't expanded to concrete names — detectable because the result equals the input), record a gap:

```java
private List<String> expandTablePatterns(List<String> patterns, String database, String policyId) {
    if (patterns == null || patterns.isEmpty()) {
        return Collections.emptyList();
    }
    List<String> expanded = new ArrayList<>();
    for (String pattern : patterns) {
        if (isWildcard(pattern)) {
            List<String> resolved = catalogResolver.expandTables(database, pattern);
            expanded.addAll(resolved);
            // If the resolver returned the pattern as-is (passthrough mode), record a gap
            if (resolved.size() == 1 && resolved.get(0).equals(pattern)) {
                gapReporter.recordGap(new GapEntry(
                        policyId, null, GapType.WILDCARD_PATTERN,
                        database + "/" + pattern,
                        "Table pattern '" + pattern + "' in database '" + database
                                + "' could not be expanded (no AWS credentials). "
                                + "The ARN produced is a placeholder.",
                        "Re-run with AWS credentials configured to expand wildcard table patterns."
                ));
            }
        } else {
            expanded.add(pattern);
        }
    }
    return expanded;
}
```

Apply the same treatment to `expandColumnPatterns()`. Pure `col=*`/`col=?` wildcards will have been promoted away already, so anything reaching here is a partial pattern like `col_*`. Provide the full updated body explicitly:

```java
private List<String> expandColumnPatterns(List<String> patterns, String database,
                                          String table, String policyId) {
    if (patterns == null || patterns.isEmpty()) {
        return Collections.emptyList();
    }
    List<String> expanded = new ArrayList<>();
    for (String pattern : patterns) {
        if (isWildcard(pattern)) {
            List<String> resolved = catalogResolver.expandColumns(database, table, pattern);
            expanded.addAll(resolved);
            if (resolved.size() == 1 && resolved.get(0).equals(pattern)) {
                gapReporter.recordGap(new GapEntry(
                        policyId, null, GapType.WILDCARD_PATTERN,
                        database + "/" + table + "/" + pattern,
                        "Column pattern '" + pattern + "' in " + database + "." + table
                                + " could not be expanded (no AWS credentials). "
                                + "The ARN produced is a placeholder.",
                        "Re-run with AWS credentials configured to expand wildcard column patterns."
                ));
            }
        } else {
            expanded.add(pattern);
        }
    }
    return expanded;
}
```

- [ ] **Step 4: Run tests to confirm gap test passes**

```bash
mvn test -pl . -Dtest=RangerToCedarConverterTest -q 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 5: Run full suite**

```bash
mvn test -q 2>&1 | tail -30
```

Expected: no regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverter.java \
        src/test/java/com/amazonaws/policyconverters/ranger/RangerToCedarConverterTest.java
git commit -m "feat: record WILDCARD_PATTERN gap when table/column pattern cannot be expanded in passthrough mode"
```

---

### Task 3: End-to-end smoke test — re-run assessment against customer export

This task verifies the fix produces meaningful output against the real customer file `Ranger_Policies_20260602_001709.json`.

**Files:**
- No code changes — this is a verification task.

- [ ] **Step 1: Build the assessment JAR**

```bash
mvn package -DskipTests -q
```

Expected: `target/assessment-jar-with-dependencies.jar` updated.

- [ ] **Step 2: Run assessment against customer export**

```bash
java -jar target/assessment-jar-with-dependencies.jar assess file \
    --input Ranger_Policies_20260602_001709.json \
    --console-only 2>/dev/null
```

Expected output changes vs. the pre-fix run:
- `UNMAPPED_RESOURCE` count drops significantly (from ~148,550 — all hive policies that had literal db/table should now be gone)
- `WILDCARD_PATTERN` count appears for policies with partial table patterns (e.g. `tbl_*`)
- `Fully convertible` percentage rises noticeably
- `SCHEMA_VALIDATION_FAILURE` count may remain (that's the principal placeholder issue — a separate fix)

- [ ] **Step 3: Record the new numbers in the commit message**

Note the before/after gap counts for the commit message and any follow-up work.

- [ ] **Step 4: Commit (no code changes — this step is just a verification checkpoint)**

If numbers look reasonable, the plan is complete. If unexpected gaps appear, investigate before declaring done.
