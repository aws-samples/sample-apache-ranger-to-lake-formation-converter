# Simulator Phase 1 — Multi-Service Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire dead generators (DataLocation, EMRFS, Tag), add Trino as a real service, fix the `"id"` payload bug, make `WorkloadOrchestrator` a multi-service weighted dispatcher, and give `ExpectedPermissionsComputer` per-service access maps with full cross-service forbid semantics.

**Architecture:** `PolicyGenerator` (functional interface) and `GeneratorEntry` (record) are the foundational types. `WorkloadOrchestrator` switches from holding a single `HivePolicyGenerator` to dispatching across a weighted `List<GeneratorEntry>`. `ExpectedPermissionsComputer.compute()` becomes two-pass: first collect all deny triples across all policies, then collect permits and subtract. `SimulatorMain` builds all five generators and wires them into the orchestrator.

**Tech Stack:** Java 17, JUnit 5, Jackson, AWS SDK v2 (Maven module `simulator/`)

**Base path:** All `simulator/src/main/java` paths are under `com/example/ranger/lakeformation/simulator/`; all `simulator/src/test/java` paths mirror the same package.

**Run tests:** `mvn test -pl simulator -q` (all); `mvn test -pl simulator -Dtest="ClassName" -q` (single class)

---

## File Map

| Action | File |
|--------|------|
| Create | `simulator/src/main/java/.../workload/PolicyGenerator.java` |
| Create | `simulator/src/main/java/.../workload/GeneratorEntry.java` |
| Create | `simulator/src/main/java/.../workload/TrinoServiceGenerator.java` |
| Create | `simulator/src/test/java/.../workload/TrinoServiceGeneratorTest.java` |
| Modify | `simulator/src/main/java/.../driver/SimulatorConfig.java` |
| Modify | `simulator/src/test/java/.../driver/SimulatorConfigTest.java` |
| Modify | `simulator/src/main/java/.../workload/DataLocationPolicyGenerator.java` |
| Modify | `simulator/src/test/java/.../workload/DataLocationPolicyGeneratorTest.java` |
| Modify | `simulator/src/main/java/.../workload/EmrfsPolicyGenerator.java` |
| Modify | `simulator/src/test/java/.../workload/EmrfsPolicyGeneratorTest.java` |
| Modify | `simulator/src/main/java/.../workload/TagPolicyGenerator.java` |
| Modify | `simulator/src/test/java/.../workload/TagPolicyGeneratorTest.java` |
| Modify | `simulator/src/main/java/.../workload/HivePolicyGenerator.java` |
| Modify | `simulator/src/test/java/.../workload/HivePolicyGeneratorTest.java` |
| Modify | `simulator/src/main/java/.../workload/WorkloadOrchestrator.java` |
| Modify | `simulator/src/test/java/.../workload/WorkloadOrchestratorTest.java` |
| Modify | `simulator/src/main/java/.../validator/ExpectedPermissionsComputer.java` |
| Modify | `simulator/src/test/java/.../validator/ExpectedPermissionsComputerTest.java` |
| Modify | `simulator/src/main/java/.../driver/SimulatorMain.java` |
| Modify | `conf/simulator-config.json` |
| Modify | `simulator/README.md` |
| Modify | `README.md` (root) |

---

## Task 1: Create `PolicyGenerator` interface and `GeneratorEntry` record

These are the two foundational types that all later tasks depend on.

**Files:**
- Create: `simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/PolicyGenerator.java`
- Create: `simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/GeneratorEntry.java`

- [ ] **Step 1: Create `PolicyGenerator.java`**

```java
package com.example.ranger.lakeformation.simulator.workload;

import java.util.Map;

@FunctionalInterface
public interface PolicyGenerator {
    Map<String, Object> generate(String policyId);
}
```

- [ ] **Step 2: Create `GeneratorEntry.java`**

```java
package com.example.ranger.lakeformation.simulator.workload;

public record GeneratorEntry(String name, PolicyGenerator generator, int weight) {}
```

`name` is used as a policy ID prefix (e.g. `"hive"`, `"trino"`, `"datalocation"`) to keep IDs readable in logs.

- [ ] **Step 3: Verify compilation**

```
mvn compile -pl simulator -q
```

Expected: BUILD SUCCESS (no errors; these types are not yet referenced)

- [ ] **Step 4: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/PolicyGenerator.java \
        simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/GeneratorEntry.java
git commit -m "feat(simulator): add PolicyGenerator interface and GeneratorEntry record"
```

---

## Task 2: Add 4 optional fields to `SimulatorConfig`

Adds `trinoServiceName`, `emrfsServiceName`, `tagServiceName`, `s3Prefixes` to the config. All are nullable; existing JSON configs work unchanged.

**Files:**
- Modify: `simulator/src/main/java/com/example/ranger/lakeformation/simulator/driver/SimulatorConfig.java`
- Modify: `simulator/src/test/java/com/example/ranger/lakeformation/simulator/driver/SimulatorConfigTest.java`

**Background:** `SimulatorConfig` uses a 14-arg `@JsonCreator` constructor. Adding 4 fields makes it 18-arg. Every test that calls the constructor directly must add 4 `null` args.

- [ ] **Step 1: Write failing tests for new fields**

Add these test methods to `SimulatorConfigTest.java`:

```java
@Test
void trinoServiceNameDefaultsToTrino() {
    SimulatorConfig config = new SimulatorConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    assertEquals("trino", config.getTrinoServiceName());
}

@Test
void emrfsServiceNameDefaultsToEmrfs() {
    SimulatorConfig config = new SimulatorConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    assertEquals("emrfs", config.getEmrfsServiceName());
}

@Test
void tagServiceNameDefaultsToClTag() {
    SimulatorConfig config = new SimulatorConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    assertEquals("cl_tag", config.getTagServiceName());
}

@Test
void s3PrefixesDefaultsToSamplePaths() {
    SimulatorConfig config = new SimulatorConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    assertNotNull(config.getS3Prefixes());
    assertFalse(config.getS3Prefixes().isEmpty());
}

@Test
void s3PrefixesIsDefensivelyCopied() {
    List<String> mutable = new ArrayList<>(List.of("s3://bucket/path/"));
    SimulatorConfig config = new SimulatorConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, mutable);
    mutable.add("s3://bucket/other/");
    assertEquals(1, config.getS3Prefixes().size(),
            "Mutating original list must not affect s3Prefixes");
}

@Test
void explicitServiceNamesOverrideDefaults() {
    SimulatorConfig config = new SimulatorConfig(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            "my-trino", "my-emrfs", "my-tag", null);
    assertEquals("my-trino", config.getTrinoServiceName());
    assertEquals("my-emrfs", config.getEmrfsServiceName());
    assertEquals("my-tag",   config.getTagServiceName());
}
```

- [ ] **Step 2: Update all existing 14-arg constructor calls in `SimulatorConfigTest` to 18-arg**

Every existing call `new SimulatorConfig(a1, a2, ..., a14)` becomes `new SimulatorConfig(a1, a2, ..., a14, null, null, null, null)`. There are 4 such calls in the existing tests:

- `nullFieldsResolveToDefaults`: append `, null, null, null, null)` — 14 nulls becomes 18 nulls
- `principalPoolIsDefensivelyCopied`: append `, null, null, null, null)` — 14 args becomes 18
- `toStringContainsRangerAdminUrl`: append `, null, null, null, null)` — 14 args becomes 18
- `explicitValuesOverrideDefaults`: append `, null, null, null, null)` to the 14-arg call — verify the test still passes

- [ ] **Step 3: Run tests to see them fail**

```
mvn test -pl simulator -Dtest="SimulatorConfigTest" -q
```

Expected: compilation failure (constructor still has 14 params) or assertion errors for new tests.

- [ ] **Step 4: Implement new fields in `SimulatorConfig.java`**

Add 4 private final fields after `databases`:

```java
private final String trinoServiceName;
private final String emrfsServiceName;
private final String tagServiceName;
private final List<String> s3Prefixes;
```

Add 4 defaults as private static final constants near the top of the class:

```java
private static final String DEFAULT_TRINO_SERVICE_NAME = "trino";
private static final String DEFAULT_EMRFS_SERVICE_NAME = "emrfs";
private static final String DEFAULT_TAG_SERVICE_NAME   = "cl_tag";
private static final List<String> DEFAULT_S3_PREFIXES  =
    List.of("s3://my-bucket/data/", "s3://my-bucket/logs/");
```

Extend the `@JsonCreator` constructor to 18 params (append 4 new params after `databases`):

```java
@JsonCreator
public SimulatorConfig(
        @JsonProperty("cycleIntervalSeconds")    Integer cycleIntervalSeconds,
        @JsonProperty("awsRegion")               String  awsRegion,
        @JsonProperty("rangerAdminUrl")          String  rangerAdminUrl,
        @JsonProperty("rangerAdminUser")         String  rangerAdminUser,
        @JsonProperty("rangerAdminPassword")     String  rangerAdminPassword,
        @JsonProperty("principalPool")           List<String> principalPool,
        @JsonProperty("principalMappings")       Map<String, String> principalMappings,
        @JsonProperty("rangerServiceName")       String  rangerServiceName,
        @JsonProperty("awsAccountId")            String  awsAccountId,
        @JsonProperty("cycleWaitTimeoutSeconds") Integer cycleWaitTimeoutSeconds,
        @JsonProperty("statusPort")              Integer statusPort,
        @JsonProperty("statusHost")              String  statusHost,
        @JsonProperty("reproductionBundleDir")   String  reproductionBundleDir,
        @JsonProperty("databases")               Map<String, List<String>> databases,
        @JsonProperty("trinoServiceName")        String  trinoServiceName,
        @JsonProperty("emrfsServiceName")        String  emrfsServiceName,
        @JsonProperty("tagServiceName")          String  tagServiceName,
        @JsonProperty("s3Prefixes")              List<String> s3Prefixes) {
    // ... existing assignments unchanged ...
    this.trinoServiceName = trinoServiceName != null ? trinoServiceName : DEFAULT_TRINO_SERVICE_NAME;
    this.emrfsServiceName = emrfsServiceName != null ? emrfsServiceName : DEFAULT_EMRFS_SERVICE_NAME;
    this.tagServiceName   = tagServiceName   != null ? tagServiceName   : DEFAULT_TAG_SERVICE_NAME;
    this.s3Prefixes       = s3Prefixes != null && !s3Prefixes.isEmpty()
                            ? List.copyOf(s3Prefixes)
                            : DEFAULT_S3_PREFIXES;
}
```

Add 4 getters:

```java
public String       getTrinoServiceName() { return trinoServiceName; }
public String       getEmrfsServiceName() { return emrfsServiceName; }
public String       getTagServiceName()   { return tagServiceName; }
public List<String> getS3Prefixes()       { return s3Prefixes; }
```

- [ ] **Step 5: Run tests — all must pass**

```
mvn test -pl simulator -Dtest="SimulatorConfigTest" -q
```

Expected: BUILD SUCCESS, all tests pass including the 6 new ones.

- [ ] **Step 6: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/driver/SimulatorConfig.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/driver/SimulatorConfigTest.java
git commit -m "feat(simulator): add trinoServiceName, emrfsServiceName, tagServiceName, s3Prefixes to SimulatorConfig"
```

---

## Task 3: Fix payload bug + rename public methods in DataLocation, EMRFS, Tag generators

All three generators include `"id": policyId` in the returned map. Ranger rejects string values in the numeric `id` field — remove it. Also rename each generator's public method to `generate(String)` so it implements the `PolicyGenerator` interface via method reference.

**Files:**
- Modify: `simulator/src/main/java/.../workload/DataLocationPolicyGenerator.java`
- Modify: `simulator/src/main/java/.../workload/EmrfsPolicyGenerator.java`
- Modify: `simulator/src/main/java/.../workload/TagPolicyGenerator.java`
- Modify: `simulator/src/test/java/.../workload/DataLocationPolicyGeneratorTest.java`
- Modify: `simulator/src/test/java/.../workload/EmrfsPolicyGeneratorTest.java`
- Modify: `simulator/src/test/java/.../workload/TagPolicyGeneratorTest.java`

- [ ] **Step 1: Write failing test — no `"id"` key in DataLocation payload**

Add to `DataLocationPolicyGeneratorTest.java`:

```java
@Test
void generateDataLocationPolicy_noIdInPayload() {
    Map<String, Object> policy = generator(42).generate("test-id");
    assertFalse(policy.containsKey("id"),
            "Ranger rejects string 'id' fields — payload must not include 'id'");
}
```

Note: this calls `generate()` not `generateDataLocationPolicy()` — the test uses the new method name.

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -pl simulator -Dtest="DataLocationPolicyGeneratorTest" -q
```

Expected: compilation error (`generate()` method does not exist yet).

- [ ] **Step 3: Fix `DataLocationPolicyGenerator.java`**

Rename `generateDataLocationPolicy(String policyId)` → `generate(String policyId)`.

Remove `"id", policyId,` from the returned map. The returned map should have: `"name"`, `"service"`, `"isEnabled"`, `"policyType"`, `"resources"`, `"policyItems"`, `"denyPolicyItems"`.

Update all test call sites that use `generateDataLocationPolicy(...)` to `generate(...)`.

- [ ] **Step 4: Write failing test — no `"id"` key in EMRFS payload**

Add to `EmrfsPolicyGeneratorTest.java`:

```java
@Test
void generateEmrfsPolicy_noIdInPayload() {
    Map<String, Object> policy = generator(42).generate("test-id");
    assertFalse(policy.containsKey("id"),
            "Ranger rejects string 'id' fields — payload must not include 'id'");
}
```

- [ ] **Step 5: Fix `EmrfsPolicyGenerator.java`**

Rename `generateEmrfsPolicy(String policyId)` → `generate(String policyId)`.

Remove `"id", policyId,` from the returned map.

Update test call sites: `generateEmrfsPolicy(...)` → `generate(...)`.

- [ ] **Step 6: Write failing test — no `"id"` key in Tag payload**

Add to `TagPolicyGeneratorTest.java`:

```java
@Test
void generateTagPolicy_noIdInPayload() {
    Map<String, Object> policy = generator(42).generate("test-id");
    assertFalse(policy.containsKey("id"),
            "Ranger rejects string 'id' fields — payload must not include 'id'");
}
```

- [ ] **Step 7: Fix `TagPolicyGenerator.java`**

Rename `generateTagPolicy(String policyId)` → `generate(String policyId)`.

Remove `"id", policyId,` from the returned map.

Update test call sites: `generateTagPolicy(...)` → `generate(...)`.

Note: The existing `tagPolicy_producesNoExpectedPermissions` test currently calls `generator(99).generateTagPolicy("p3")` — update this to `generator(99).generate("p3")` as part of this step.

- [ ] **Step 8: Run all three generator tests**

```
mvn test -pl simulator -Dtest="DataLocationPolicyGeneratorTest,EmrfsPolicyGeneratorTest,TagPolicyGeneratorTest" -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/DataLocationPolicyGenerator.java \
        simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/EmrfsPolicyGenerator.java \
        simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/TagPolicyGenerator.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/workload/DataLocationPolicyGeneratorTest.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/workload/EmrfsPolicyGeneratorTest.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/workload/TagPolicyGeneratorTest.java
git commit -m "fix(simulator): remove 'id' from generator payloads; rename methods to generate()"
```

---

## Task 4: Fix `HivePolicyGenerator` access types

`HivePolicyGenerator` currently uses lakeformation access types (`insert`, `delete`, `describe`). The Hive Ranger service has no such types — it uses `update`, `read`, `write`. Fix also `generateDatabasePolicy()` which uses `create_table` (lakeformation) instead of `create` (Hive).

**Files:**
- Modify: `simulator/src/main/java/.../workload/HivePolicyGenerator.java`
- Modify: `simulator/src/test/java/.../workload/HivePolicyGeneratorTest.java`

- [ ] **Step 1: Write failing tests for correct Hive access types**

Add to `HivePolicyGeneratorTest.java`:

```java
@Test
void generateTablePolicy_usesHiveAccessTypes() {
    Set<String> validHiveTypes = Set.of("select", "update", "read", "write", "create", "drop", "alter");
    for (int seed = 0; seed < 20; seed++) {
        Map<String, Object> policy = generator(seed).generateTablePolicy("p-" + seed);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
        assertNotNull(items);
        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
            for (Map<String, Object> access : accesses) {
                String type = (String) access.get("type");
                assertTrue(validHiveTypes.contains(type),
                        "Access type '" + type + "' is not a valid Hive access type");
            }
        }
    }
}

@Test
void generateTablePolicy_tableNameBelongsToChosenDatabase() {
    // Run 100 iterations with fixed seed; every table must be in databaseTables.get(db)
    for (int seed = 0; seed < 100; seed++) {
        Map<String, Object> policy = generator(seed).generateTablePolicy("p-" + seed);
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        @SuppressWarnings("unchecked")
        List<String> dbs = (List<String>) ((Map<String, Object>) resources.get("database")).get("values");
        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) ((Map<String, Object>) resources.get("table")).get("values");
        String db = dbs.get(0);
        String table = tables.get(0);
        if (!"*".equals(table)) {
            assertTrue(DATABASE_TABLES.getOrDefault(db, List.of()).contains(table),
                    "Table '" + table + "' does not belong to db '" + db + "'");
        }
    }
}

@Test
void generateDatabasePolicy_usesHiveCreateNotCreateTable() {
    // generateDatabasePolicy should use "create" and "drop", not "create_table"
    for (int seed = 0; seed < 20; seed++) {
        Map<String, Object> policy = generator(seed).generateDatabasePolicy("dp-" + seed);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
            for (Map<String, Object> access : accesses) {
                String type = (String) access.get("type");
                assertNotEquals("create_table", type,
                        "generateDatabasePolicy must use 'create' not 'create_table'");
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -pl simulator -Dtest="HivePolicyGeneratorTest" -q
```

Expected: `generateTablePolicy_usesHiveAccessTypes` and `generateDatabasePolicy_usesHiveCreateNotCreateTable` fail.

- [ ] **Step 3: Fix `HivePolicyGenerator.java`**

Replace the access types constant:

```java
// Was:
private static final List<String> DEFAULT_ACCESS_TYPES = List.of("select", "insert", "delete", "describe");

// Becomes:
private static final List<String> HIVE_ACCESS_TYPES =
    List.of("select", "update", "read", "write", "create", "drop", "alter");
```

Update all internal references from `DEFAULT_ACCESS_TYPES` to `HIVE_ACCESS_TYPES`.

Fix `generateDatabasePolicy()`: change `List.of("create_table", "drop")` to `List.of("create", "drop")`.

- [ ] **Step 4: Run all `HivePolicyGeneratorTest` tests**

```
mvn test -pl simulator -Dtest="HivePolicyGeneratorTest" -q
```

Expected: BUILD SUCCESS, all tests pass (including the 3 existing ones).

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/HivePolicyGenerator.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/workload/HivePolicyGeneratorTest.java
git commit -m "fix(simulator): correct HivePolicyGenerator to use Hive access type vocabulary"
```

---

## Task 5: Create `TrinoServiceGenerator` and its test

Trino policies use resource key `schema` (not `database`) and the Trino access type vocabulary. ~20% of generated policies include a `denyPolicyItems` entry to exercise cross-service forbid scenarios.

**Files:**
- Create: `simulator/src/main/java/.../workload/TrinoServiceGenerator.java`
- Create: `simulator/src/test/java/.../workload/TrinoServiceGeneratorTest.java`

- [ ] **Step 1: Write the failing test first**

Create `TrinoServiceGeneratorTest.java`:

```java
package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrinoServiceGeneratorTest {
    private static final Map<String, List<String>> DB_TABLES = Map.of(
            "analytics", List.of("events", "sessions"),
            "warehouse", List.of("orders"));
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String TRINO_SERVICE = "trino";
    private static final Set<String> VALID_TRINO_TYPES =
            Set.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");

    private TrinoServiceGenerator gen(long seed) {
        return new TrinoServiceGenerator(DB_TABLES, PRINCIPALS, TRINO_SERVICE, new Random(seed));
    }

    @Test
    void generate_hasSchemaNotDatabaseInResources() {
        Map<String, Object> policy = gen(42).generate("t-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources, "resources must be present");
        assertTrue(resources.containsKey("schema"), "Trino policies must use 'schema' resource key");
        assertFalse(resources.containsKey("database"), "Trino policies must NOT use 'database' key");
    }

    @Test
    void generate_serviceNameMatchesConstructorArg() {
        Map<String, Object> policy = gen(7).generate("t-2");
        assertEquals(TRINO_SERVICE, policy.get("service"));
    }

    @Test
    void generate_accessTypesAreFromTrinoVocabulary() {
        for (int seed = 0; seed < 30; seed++) {
            Map<String, Object> policy = gen(seed).generate("t-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items, "policyItems must be present");
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
                for (Map<String, Object> access : accesses) {
                    String type = (String) access.get("type");
                    assertTrue(VALID_TRINO_TYPES.contains(type),
                            "Access type '" + type + "' is not a valid Trino type");
                }
            }
        }
    }

    @Test
    void generate_denyPolicyItemsIsAlwaysAList() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = gen(seed).generate("t-" + seed);
            Object denyItems = policy.get("denyPolicyItems");
            assertNotNull(denyItems, "denyPolicyItems must be present (may be empty list)");
            assertInstanceOf(List.class, denyItems, "denyPolicyItems must be a List");
        }
    }

    @Test
    void generate_isDeterministicWithFixedSeed() {
        Map<String, Object> p1 = gen(12345).generate("id-x");
        Map<String, Object> p2 = gen(12345).generate("id-x");
        assertEquals(p1.get("name"),    p2.get("name"));
        assertEquals(p1.get("service"), p2.get("service"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r1 = (Map<String, Object>) p1.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) p2.get("resources");
        assertEquals(r1.get("schema"), r2.get("schema"));
        assertEquals(r1.get("table"),  r2.get("table"));
    }

    @Test
    void generate_noIdKeyInPayload() {
        Map<String, Object> policy = gen(99).generate("t-99");
        assertFalse(policy.containsKey("id"),
                "Payload must not include 'id' key (Ranger rejects string id fields)");
    }

    @Test
    void generate_tableNameBelongsToChosenSchema() {
        for (int seed = 0; seed < 100; seed++) {
            Map<String, Object> policy = gen(seed).generate("t-" + seed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
            @SuppressWarnings("unchecked")
            List<String> schemas = (List<String>) ((Map<String, Object>) resources.get("schema")).get("values");
            @SuppressWarnings("unchecked")
            List<String> tables  = (List<String>) ((Map<String, Object>) resources.get("table")).get("values");
            String schema = schemas.get(0);
            String table  = tables.get(0);
            if (!"*".equals(table)) {
                assertTrue(DB_TABLES.getOrDefault(schema, List.of()).contains(table),
                        "Table '" + table + "' does not belong to schema '" + schema + "'");
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails (class doesn't exist)**

```
mvn test -pl simulator -Dtest="TrinoServiceGeneratorTest" -q
```

Expected: compilation error — `TrinoServiceGenerator` does not exist.

- [ ] **Step 3: Implement `TrinoServiceGenerator.java`**

```java
package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

public class TrinoServiceGenerator {
    private static final List<String> TRINO_ACCESS_TYPES =
        List.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");

    private final Map<String, List<String>> databaseTables;
    private final List<String> databases;
    private final List<String> principalNames;
    private final String trinoServiceName;
    private final Random random;

    public TrinoServiceGenerator(Map<String, List<String>> databaseTables,
                                  List<String> principalNames,
                                  String trinoServiceName,
                                  Random random) {
        this.databaseTables   = Map.copyOf(databaseTables);
        this.databases        = List.copyOf(databaseTables.keySet());
        this.principalNames   = List.copyOf(principalNames);
        this.trinoServiceName = trinoServiceName;
        this.random           = random;
    }

    public Map<String, Object> generate(String policyId) {
        String schema = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(schema, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user  = randomFrom(principalNames);
        List<String> accesses = randomSubset(TRINO_ACCESS_TYPES, 1 + random.nextInt(3));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("schema", Map.of("values", List.of(schema), "isExcludes", false));
        resources.put("table",  Map.of("values", List.of(table),  "isExcludes", false));

        List<Object> denyItems = random.nextInt(100) < 20
            ? List.of(buildItem(user, randomSubset(accesses, 1), false))
            : List.of();

        return Map.of(
            "name",            policyId,
            "service",         trinoServiceName,
            "isEnabled",       true,
            "policyType",      0,
            "resources",       resources,
            "policyItems",     List.of(buildItem(user, accesses, false)),
            "denyPolicyItems", denyItems
        );
    }

    private Map<String, Object> buildItem(String user, List<String> accessTypes,
                                           boolean delegateAdmin) {
        List<Map<String, Object>> accesses = new ArrayList<>();
        for (String type : accessTypes) {
            accesses.add(Map.of("type", type, "isAllowed", true));
        }
        return Map.of(
            "users",         List.of(user),
            "groups",        List.of(),
            "roles",         List.of(),
            "accesses",      accesses,
            "delegateAdmin", delegateAdmin
        );
    }

    private <T> T randomFrom(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private List<String> randomSubset(List<String> list, int count) {
        List<String> copy = new ArrayList<>(list);
        Collections.shuffle(copy, random);
        return List.copyOf(copy.subList(0, Math.min(count, copy.size())));
    }
}
```

- [ ] **Step 4: Run all `TrinoServiceGeneratorTest` tests**

```
mvn test -pl simulator -Dtest="TrinoServiceGeneratorTest" -q
```

Expected: BUILD SUCCESS, all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/TrinoServiceGenerator.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/workload/TrinoServiceGeneratorTest.java
git commit -m "feat(simulator): add TrinoServiceGenerator with schema resource key and Trino access types"
```

---

## Task 6: Rewrite `WorkloadOrchestrator` to multi-service dispatcher

Replace the single `HivePolicyGenerator policyGenerator` field with a weighted `List<GeneratorEntry>`. The `pickOperation()` method does a two-roll approach: first pick operation type, then (for CREATE/UPDATE) pick a generator by weight.

**Files:**
- Modify: `simulator/src/main/java/.../workload/WorkloadOrchestrator.java`
- Modify: `simulator/src/test/java/.../workload/WorkloadOrchestratorTest.java`

**Background on existing `pickOperation()` weight constants:**
```
WEIGHT_CREATE  = 30  →  roll < 30: CREATE
WEIGHT_UPDATE  = 50  →  30 ≤ roll < 50: UPDATE
WEIGHT_DISABLE = 65  →  50 ≤ roll < 65: DISABLE
WEIGHT_ENABLE  = 80  →  65 ≤ roll < 80: ENABLE
WEIGHT_DELETE  = 90  →  80 ≤ roll < 90: DELETE
                         90–99: null (no-op)
```

- [ ] **Step 1: Rewrite `WorkloadOrchestratorTest.java`**

Replace the existing test file with the following. All 6 original test behaviors are preserved; the constructor helper is updated to the new 4-arg form; a 7th test for generator coverage is added.

```java
package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadOrchestratorTest {

    private static final List<String> PRINCIPALS = List.of("user:alice", "user:bob");
    private static final long FIXED_SEED = 42L;

    // Build a 5-entry generator list; each lambda captures the service name in the payload
    private static List<GeneratorEntry> makeGenerators() {
        return List.of(
            new GeneratorEntry("hive",         id -> Map.of("service", "hive",         "name", id), 45),
            new GeneratorEntry("trino",        id -> Map.of("service", "trino",        "name", id), 25),
            new GeneratorEntry("datalocation", id -> Map.of("service", "lakeformation","name", id), 15),
            new GeneratorEntry("tag",          id -> Map.of("service", "cl_tag",       "name", id), 10),
            new GeneratorEntry("emrfs",        id -> Map.of("service", "emrfs",        "name", id),  5)
        );
    }

    private WorkloadOrchestrator orchestrator(List<String> policyIds, long seed) {
        return new WorkloadOrchestrator(PRINCIPALS, policyIds, makeGenerators(), new Random(seed));
    }

    @Test
    void generateBatchReturnsBetweenZeroAndFiveOperations() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        List<MutationOperation> batch = orch.generateBatch();
        assertNotNull(batch);
        assertTrue(batch.size() >= 0 && batch.size() <= 5);
    }

    @Test
    void generateBatchIsDeterministicWithFixedSeed() {
        WorkloadOrchestrator o1 = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        WorkloadOrchestrator o2 = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        List<MutationOperation> b1 = o1.generateBatch();
        List<MutationOperation> b2 = o2.generateBatch();
        assertEquals(b1.size(), b2.size());
        for (int i = 0; i < b1.size(); i++) {
            assertEquals(b1.get(i).getClass(), b2.get(i).getClass(), "Same op type at index " + i);
        }
    }

    @Test
    void createPolicyAddsIdToExistingPolicyIds() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 1L);
        boolean foundCreate = false;
        for (int cycle = 0; cycle < 20 && !foundCreate; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.CreatePolicy create) {
                    assertTrue(orch.getExistingPolicyIds().contains(create.policyId()));
                    foundCreate = true;
                    break;
                }
            }
        }
        assertTrue(foundCreate, "Should generate at least one CreatePolicy in 20 cycles");
    }

    @Test
    void deletePolicyRemovesIdFromExistingPolicyIds() {
        WorkloadOrchestrator orch = orchestrator(
                new ArrayList<>(List.of("seed-1", "seed-2", "seed-3")), 7L);
        boolean foundDelete = false;
        for (int cycle = 0; cycle < 50 && !foundDelete; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.DeletePolicy delete) {
                    assertFalse(orch.getExistingPolicyIds().contains(delete.policyId()));
                    foundDelete = true;
                    break;
                }
            }
        }
        assertTrue(foundDelete, "Should generate at least one DeletePolicy in 50 cycles");
    }

    @Test
    void batchIsNeverNull() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 99L);
        for (int i = 0; i < 10; i++) {
            assertNotNull(orch.generateBatch());
        }
    }

    @Test
    void getExistingPolicyIdsReturnsUnmodifiableView() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(List.of("p1")), 0L);
        assertThrows(UnsupportedOperationException.class,
                () -> orch.getExistingPolicyIds().add("injected"));
    }

    @Test
    void allFiveGeneratorNamesFireAcrossManyIterations() {
        // Run 1000 batch cycles; collect the service prefix from every CreatePolicy policyId.
        // With weights [45,25,15,10,5] and CREATE at 30% of ops, expected emrfs creates ≈ 15
        // (0.30 * 5 ops/batch avg * 0.05 * 1000 cycles). Fixed seed ensures determinism.
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 77L);
        Set<String> observedPrefixes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.CreatePolicy create) {
                    // policyId format: "{entry.name()}-sim-{nanoTime}" → split on "-sim-"
                    String prefix = create.policyId().split("-sim-")[0];
                    observedPrefixes.add(prefix);
                }
            }
        }
        Set<String> expected = Set.of("hive", "trino", "datalocation", "tag", "emrfs");
        assertEquals(expected, observedPrefixes,
                "All five generator names must appear in CreatePolicy IDs over 1000 cycles");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (constructor mismatch)**

```
mvn test -pl simulator -Dtest="WorkloadOrchestratorTest" -q
```

Expected: compilation error — constructor args don't match yet.

- [ ] **Step 3: Rewrite `WorkloadOrchestrator.java`**

Key changes:
1. Remove `HivePolicyGenerator policyGenerator` field and its import
2. Add `List<GeneratorEntry> generators` and `int totalWeight` fields
3. Replace 5-arg constructor with 4-arg constructor
4. Add `pickGenerator()` helper
5. Update `pickOperation()` for CREATE and UPDATE

Full implementation:

```java
package com.example.ranger.lakeformation.simulator.workload;

import java.time.Instant;
import java.util.*;

public class WorkloadOrchestrator {
    private static final int BATCH_MIN = 1;
    private static final int BATCH_MAX = 5;
    private static final int WEIGHT_CREATE  = 30;
    private static final int WEIGHT_UPDATE  = 50;
    private static final int WEIGHT_DISABLE = 65;
    private static final int WEIGHT_ENABLE  = 80;
    private static final int WEIGHT_DELETE  = 90;

    private final List<String> principalPool;
    private final List<String> existingPolicyIds;
    private final List<GeneratorEntry> generators;
    private final int totalWeight;
    private final Random random;

    public WorkloadOrchestrator(List<String> principalPool, List<String> existingPolicyIds,
                                List<GeneratorEntry> generators, Random random) {
        this.principalPool     = List.copyOf(principalPool);
        this.existingPolicyIds = new ArrayList<>(existingPolicyIds);
        this.generators        = List.copyOf(generators);
        this.totalWeight       = generators.stream().mapToInt(GeneratorEntry::weight).sum();
        this.random            = random;
    }

    public List<MutationOperation> generateBatch() {
        int batchSize = BATCH_MIN + random.nextInt(BATCH_MAX - BATCH_MIN + 1);
        List<MutationOperation> batch = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            MutationOperation op = pickOperation(random.nextInt(100));
            if (op != null) batch.add(op);
        }
        return batch;
    }

    public List<String> getExistingPolicyIds() {
        return Collections.unmodifiableList(existingPolicyIds);
    }

    private MutationOperation pickOperation(int roll) {
        if (roll < WEIGHT_CREATE) {
            GeneratorEntry entry = pickGenerator();
            String newId = entry.name() + "-sim-" + System.nanoTime();
            Map<String, Object> payload = entry.generator().generate(newId);
            existingPolicyIds.add(newId);
            return new MutationOperation.CreatePolicy(Instant.now(), newId, payload);
        }
        if (existingPolicyIds.isEmpty()) return null;
        if (roll < WEIGHT_UPDATE) {
            GeneratorEntry entry = pickGenerator();
            String id = randomFrom(existingPolicyIds);
            Map<String, Object> payload = entry.generator().generate(id);
            return new MutationOperation.UpdatePolicy(Instant.now(), id, payload);
        }
        if (roll < WEIGHT_DISABLE) {
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.DisablePolicy(Instant.now(), id);
        }
        if (roll < WEIGHT_ENABLE) {
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.EnablePolicy(Instant.now(), id);
        }
        if (roll < WEIGHT_DELETE) {
            String id = randomFrom(existingPolicyIds);
            existingPolicyIds.remove(id);
            return new MutationOperation.DeletePolicy(Instant.now(), id);
        }
        return null; // 90–99: no-op
    }

    private GeneratorEntry pickGenerator() {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (GeneratorEntry entry : generators) {
            cumulative += entry.weight();
            if (roll < cumulative) return entry;
        }
        return generators.getLast(); // unreachable but safe fallback
    }

    private String randomFrom(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }
}
```

- [ ] **Step 4: Run all `WorkloadOrchestratorTest` tests**

```
mvn test -pl simulator -Dtest="WorkloadOrchestratorTest" -q
```

Expected: BUILD SUCCESS, all 7 tests pass. The `allFiveGeneratorNamesFireAcrossManyIterations` test may take a few seconds.

If it fails due to a missing `MutationOperation` subtype (e.g. `UpdatePolicy`), check the existing `MutationOperation.java` sealed type definition and add any missing subtypes that the test exercises.

- [ ] **Step 5: Skip full-suite run — `SimulatorMain` still references the deleted 5-arg constructor**

After this task, `SimulatorMain.java` still calls `new WorkloadOrchestrator(principals, new ArrayList<>(), databaseTables, config.getRangerServiceName(), new Random())` — that constructor no longer exists. The full simulator module will **fail to compile** until Task 8 fixes `SimulatorMain`. Do NOT run `mvn test -pl simulator -q` here; proceed directly to Task 7 and then Task 8 which will fix `SimulatorMain` and restore compilation.

- [ ] **Step 6: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/workload/WorkloadOrchestrator.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/workload/WorkloadOrchestratorTest.java
git commit -m "feat(simulator): rewrite WorkloadOrchestrator as multi-service weighted dispatcher"
```

---

## Task 7: Rewrite `ExpectedPermissionsComputer` — per-service maps + cross-service forbid

This task adds three capabilities:
1. Per-service `ACCESS_MAP` (lakeformation / hive / trino) replacing the single map
2. Trino `schema` resource key fallback in `extractResourceSpecs()`
3. Two-pass `compute()`: collect all deny triples first, then subtract from permits

**Files:**
- Modify: `simulator/src/main/java/.../validator/ExpectedPermissionsComputer.java`
- Modify: `simulator/src/test/java/.../validator/ExpectedPermissionsComputerTest.java`

**Background on existing `processPolicyInto()` guard logic (lines 43–63):**
1. `isEnabled == false` → skip
2. `service.toLowerCase().contains("tag")` → skip
3. `policyType == 1` (data masking) → skip
4. `resources` missing/empty → skip
5. Only reads `policyItems`; `denyPolicyItems` ignored entirely

**Background on existing tests that need updating:**
- Test 6 `allAccessExpandsToMultiplePermissions`: uses `service="hive"`, access `"all"`. After adding the hive map (where `all` is absent → zero grants), this test breaks. Change service to `"lakeformation"`.
- Test 9 `databaseLevelPermission`: uses `service="hive"`, access `"create_database"`. Hive map has no `create_database`. Change service to `"lakeformation"`.

- [ ] **Step 1: Update the four broken existing tests in `ExpectedPermissionsComputerTest.java`**

After introducing the per-service hive map (which has no `all` or `datalocation` entries), four existing tests that use `service="hive"` with those access types will fail:

1. `allAccessExpandsToMultiplePermissions`: uses `service="hive"`, access `"all"` → change service to `"lakeformation"`.
2. `databaseLevelPermission`: uses `service="hive"`, access `"create_database"` → change service to `"lakeformation"`.
3. `columnLevelGrantStripsDescribe`: uses `service="hive"`, access `"all"` → change service to `"lakeformation"`.
4. `dataLocationPermission`: uses `service="hive"`, access `"datalocation"` → change service to `"lakeformation"`.

For each: find the JSON string in the test body and change `"service":"hive"` (or `"service": "hive"`) to `"service": "lakeformation"`. The underlying assertions remain valid — these access types are all correctly mapped in the lakeformation map.

- [ ] **Step 2: Add new failing tests to `ExpectedPermissionsComputerTest.java`**

```java
// --- Hive service tests ---

@Test
void hiveSelect_mapsToSelectPermission() {
    String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
            + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}],"
            + "\"denyPolicyItems\":[]}";
    Set<SimulatorPermission> result = compute(json);
    assertFalse(result.isEmpty(), "hive select should produce a permission");
    assertTrue(result.stream().anyMatch(p -> "SELECT".equals(p.permission())),
            "hive select must map to SELECT");
}

@Test
void hiveAll_producesNoPermissions() {
    // "all" in hive maps to SUPER which is not an LF permission — zero grants expected
    String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
            + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"all\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}],"
            + "\"denyPolicyItems\":[]}";
    Set<SimulatorPermission> result = compute(json);
    assertTrue(result.isEmpty(),
            "hive 'all' maps to SUPER (not an LF permission) — must produce zero grants");
}

@Test
void hiveUpdate_mapsToInsertPermission() {
    String json = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
            + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"update\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}],"
            + "\"denyPolicyItems\":[]}";
    Set<SimulatorPermission> result = compute(json);
    assertTrue(result.stream().anyMatch(p -> "INSERT".equals(p.permission())),
            "hive 'update' must map to INSERT");
}

// --- Trino service tests ---

@Test
void trinoUse_mapsToDescribePermission() {
    String json = "{\"service\":\"trino\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"schema\":{\"values\":[\"mydb\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
            + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"use\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}],"
            + "\"denyPolicyItems\":[]}";
    Set<SimulatorPermission> result = compute(json);
    assertTrue(result.stream().anyMatch(p -> "DESCRIBE".equals(p.permission())),
            "trino 'use' must map to DESCRIBE");
}

@Test
void trinoSchemaKey_resolvesSameAsDatabaseKey() {
    // Trino uses "schema" resource key; ExpectedPermissionsComputer must treat it as the database name
    String json = "{\"service\":\"trino\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"schema\":{\"values\":[\"analytics\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"events\"],\"isExcludes\":false}},"
            + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}],"
            + "\"denyPolicyItems\":[]}";
    Set<SimulatorPermission> result = compute(json);
    assertFalse(result.isEmpty(), "Trino schema key should produce permissions");
    assertTrue(result.stream().anyMatch(p -> p.resourceId().startsWith("analytics.")),
            "Resource id should use the schema name as the database component");
}

// --- Cross-service forbid test ---

@Test
void crossServiceForbid_trinoDeniesSuppressesHiveGrant() {
    // Policy A: Hive grants alice SELECT on db1.t1
    // Policy B: Trino denies alice SELECT on db1.t1 (same logical resource, different service)
    // Expected: compute([A, B]) returns empty set — deny wins
    String policyA = "{\"service\":\"hive\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"database\":{\"values\":[\"db1\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
            + "\"policyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}],"
            + "\"denyPolicyItems\":[]}";
    String policyB = "{\"service\":\"trino\",\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"schema\":{\"values\":[\"db1\"],\"isExcludes\":false},"
            + "              \"table\":{\"values\":[\"t1\"],\"isExcludes\":false}},"
            + "\"policyItems\":[],"
            + "\"denyPolicyItems\":[{\"users\":[\"alice\"],\"groups\":[],\"roles\":[],"
            + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}],"
            + "  \"delegateAdmin\":false}]}";
    Set<SimulatorPermission> result = computeFromJsonStrings(policyA, policyB);
    assertTrue(result.isEmpty(),
            "Trino deny must suppress Hive grant for same (principal, resource, permission)");
}
```

Add two private helper methods to the test class. The existing tests call `computer.compute(List.of(policy))` directly with a `JsonNode` — these helpers wrap that for the new string-based tests:

```java
// Single-policy convenience helper used by the new hive/trino tests
private Set<SimulatorPermission> compute(String json) {
    try {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode policy = mapper.readTree(json);
        return computer.compute(List.of(policy));
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}

// Multi-policy helper used by the cross-service forbid test
private Set<SimulatorPermission> computeFromJsonStrings(String... jsonPolicies) {
    try {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> nodes = new ArrayList<>();
        for (String json : jsonPolicies) {
            nodes.add(mapper.readTree(json));
        }
        return computer.compute(nodes);
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

Also add `import com.fasterxml.jackson.databind.ObjectMapper;` and `import java.util.ArrayList;` if not already present in the test class.

- [ ] **Step 3: Run failing tests**

```
mvn test -pl simulator -Dtest="ExpectedPermissionsComputerTest" -q
```

Expected: the 5 new tests fail + `allAccessExpandsToMultiplePermissions` and `databaseLevelPermission` may fail if service was changed (they should still pass once the service is `lakeformation`). The cross-service forbid test will pass vacuously (deny items are currently ignored) if the Hive SELECT is produced and the deny doesn't suppress it — actually `crossServiceForbid_trinoDeniesSuppressesHiveGrant` will FAIL because currently deny items are ignored, so the hive grant will appear.

- [ ] **Step 4: Implement the changes in `ExpectedPermissionsComputer.java`**

**4a. Add `SERVICE_ACCESS_MAPS` and `accessMapForService()`**

Replace the existing `ACCESS_MAP` static field and `buildAccessMap()` method with:

```java
private static final Map<String, Map<String, Set<String>>> SERVICE_ACCESS_MAPS = buildServiceAccessMaps();

private static Map<String, Map<String, Set<String>>> buildServiceAccessMaps() {
    Map<String, Set<String>> lfMap = new HashMap<>();
    lfMap.put("select",              Set.of("SELECT"));
    lfMap.put("insert",              Set.of("INSERT"));
    lfMap.put("delete",              Set.of("DELETE"));
    lfMap.put("describe",            Set.of("DESCRIBE"));
    lfMap.put("alter",               Set.of("ALTER"));
    lfMap.put("drop",                Set.of("DROP"));
    lfMap.put("create_database",     Set.of("CREATE_DATABASE"));
    lfMap.put("create_table",        Set.of("CREATE_TABLE"));
    lfMap.put("update",              Set.of("INSERT"));
    lfMap.put("create",              Set.of("CREATE_TABLE"));
    lfMap.put("read",                Set.of("SELECT"));
    lfMap.put("write",               Set.of("INSERT"));
    lfMap.put("all",                 Set.of("SELECT","INSERT","DELETE","ALTER","DROP","DESCRIBE"));
    lfMap.put("datalocation",        Set.of("DATA_LOCATION_ACCESS"));
    lfMap.put("data_location_access",Set.of("DATA_LOCATION_ACCESS"));

    Map<String, Set<String>> hiveMap = new HashMap<>();
    hiveMap.put("select", Set.of("SELECT"));
    hiveMap.put("update", Set.of("INSERT"));
    hiveMap.put("create", Set.of("CREATE_TABLE"));
    hiveMap.put("drop",   Set.of("DROP"));
    hiveMap.put("alter",  Set.of("ALTER"));
    hiveMap.put("read",   Set.of("SELECT"));
    hiveMap.put("write",  Set.of("INSERT"));
    // "all" intentionally absent: maps to SUPER which is not an LF permission

    Map<String, Set<String>> trinoMap = new HashMap<>();
    trinoMap.put("select", Set.of("SELECT"));
    trinoMap.put("insert", Set.of("INSERT"));
    trinoMap.put("delete", Set.of("DELETE"));
    trinoMap.put("create", Set.of("CREATE_TABLE"));
    trinoMap.put("drop",   Set.of("DROP"));
    trinoMap.put("alter",  Set.of("ALTER"));
    trinoMap.put("use",    Set.of("DESCRIBE"));
    trinoMap.put("show",   Set.of("DESCRIBE"));

    Map<String, Map<String, Set<String>>> result = new HashMap<>();
    result.put("lakeformation", Collections.unmodifiableMap(lfMap));
    result.put("hive",          Collections.unmodifiableMap(hiveMap));
    result.put("trino",         Collections.unmodifiableMap(trinoMap));
    return Collections.unmodifiableMap(result);
}

private Map<String, Set<String>> accessMapForService(String serviceName) {
    String key = (serviceName == null || serviceName.isEmpty())
                 ? "lakeformation"
                 : serviceName.toLowerCase(Locale.ROOT);
    Map<String, Set<String>> map = SERVICE_ACCESS_MAPS.get(key);
    if (map == null) {
        LOG.warn("Unknown service name '{}'; falling back to lakeformation access map", serviceName);
        return SERVICE_ACCESS_MAPS.get("lakeformation");
    }
    return map;
}
```

Also add `import java.util.Locale;` if not already present.

Update `extractPermissions()` to use `accessMapForService()` instead of the static `ACCESS_MAP`. The method currently does `ACCESS_MAP.getOrDefault(type, Set.of())`. It needs to receive the resolved access map as a parameter. The simplest change: pass the access map in:

```java
private Set<String> extractPermissions(JsonNode accesses, Map<String, Set<String>> accessMap) {
    Set<String> result = new HashSet<>();
    for (JsonNode a : accesses) {
        String type = a.path("type").asText("").toLowerCase(Locale.ROOT);
        Set<String> mapped = accessMap.get(type);
        if (mapped != null) result.addAll(mapped);
    }
    return result;
}
```

Because `extractPermissions()` now requires the access map, the `processItemInto()` method (which calls it) also needs the access map. Update `processItemInto()` signature to accept a fourth parameter:

```java
private void processItemInto(JsonNode item, JsonNode resources,
                              Set<SimulatorPermission> result,
                              Map<String, Set<String>> accessMap)
```

Then pass `accessMapForService(serviceName)` from `processPermitsInto()` down to each `processItemInto()` call. `serviceName` is already available at the top of `processPermitsInto()` — read it as `policy.path("service").asText(null)` if not already done.

**4b. Add `schema` fallback in `extractResourceSpecs()`**

Find the line `List<String> databases = resourceValues(resources, "database");` and add the fallback immediately after:

```java
List<String> databases = resourceValues(resources, "database");
if (databases.isEmpty()) {
    databases = resourceValues(resources, "schema"); // Trino uses "schema" not "database"
}
```

**4c. Extract `shouldProcess()` helper**

Extract the guard logic currently inline in `processPolicyInto()` into a new private method:

```java
private boolean shouldProcess(JsonNode policy) {
    if (!policy.path("isEnabled").asBoolean(true)) return false;
    String svc = policy.path("service").asText("").toLowerCase(Locale.ROOT);
    if (svc.contains("tag")) return false;
    if (policy.path("policyType").asInt(0) == 1) return false;
    JsonNode resources = policy.path("resources");
    if (resources.isMissingNode() || resources.isEmpty()) return false;
    return true;
}
```

**4d. Rename `processPolicyInto()` → `processPermitsInto()`**

In `processPermitsInto()`, remove the guard logic (now in `shouldProcess()`). The method reads the service name from the policy, resolves the access map once via `accessMapForService(serviceName)`, then iterates `policyItems` and calls `processItemInto(item, resources, result, accessMap)` for each. Signature:

```java
private void processPermitsInto(JsonNode policy, Set<SimulatorPermission> result) {
    String serviceName = policy.path("service").asText(null);
    Map<String, Set<String>> accessMap = accessMapForService(serviceName);
    JsonNode resources = policy.path("resources");
    for (JsonNode item : policy.path("policyItems")) {
        processItemInto(item, resources, result, accessMap);
    }
}
```

**4e. Add `DenyKey` record and `collectDenies()` method**

Add at the bottom of the class (before the closing `}`):

```java
private record DenyKey(String principalArn, String resourceId, String permission) {}

private void collectDenies(JsonNode policy, Set<DenyKey> denySet) {
    String serviceName = policy.path("service").asText(null);
    Map<String, Set<String>> accessMap = accessMapForService(serviceName);
    JsonNode resources = policy.path("resources");

    for (JsonNode item : policy.path("denyPolicyItems")) {
        Set<String> lfPermissions = new HashSet<>();
        for (JsonNode acc : item.path("accesses")) {
            String type = acc.path("type").asText("").toLowerCase(Locale.ROOT);
            Set<String> mapped = accessMap.get(type);
            if (mapped != null) lfPermissions.addAll(mapped);
        }
        if (lfPermissions.isEmpty()) continue;

        List<ResourceSpec> specs = extractResourceSpecs(resources, lfPermissions);
        for (String principalArn : resolvePrincipals(item)) {
            for (ResourceSpec spec : specs) {
                for (String permission : lfPermissions) {
                    denySet.add(new DenyKey(principalArn, spec.resourceId(), permission));
                }
            }
        }
    }
}
```

**4f. Redesign `compute()` to two-pass**

Replace the existing `compute()` body with:

```java
public Set<SimulatorPermission> compute(List<JsonNode> policies) {
    Set<DenyKey> globalDenySet = new HashSet<>();
    for (JsonNode policy : policies) {
        if (!shouldProcess(policy)) continue;
        collectDenies(policy, globalDenySet);
    }

    Set<SimulatorPermission> permits = new HashSet<>();
    for (JsonNode policy : policies) {
        if (!shouldProcess(policy)) continue;
        processPermitsInto(policy, permits);
    }

    permits.removeIf(p ->
        globalDenySet.contains(new DenyKey(p.principalArn(), p.resourceId(), p.permission())));

    return permits;
}
```

- [ ] **Step 5: Run all `ExpectedPermissionsComputerTest` tests**

```
mvn test -pl simulator -Dtest="ExpectedPermissionsComputerTest" -q
```

Expected: BUILD SUCCESS, all tests pass including the 5 new ones. If `denyItemsProduceNoGrants` fails (it tests that deny items don't produce positive grants), verify its assertion still holds — it should, since `processPermitsInto` only reads `policyItems`.

- [ ] **Step 6: Run all simulator tests**

```
mvn test -pl simulator -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/validator/ExpectedPermissionsComputer.java \
        simulator/src/test/java/com/example/ranger/lakeformation/simulator/validator/ExpectedPermissionsComputerTest.java
git commit -m "feat(simulator): add per-service access maps and cross-service forbid semantics to ExpectedPermissionsComputer"
```

---

## Task 8: Update `SimulatorMain` — wire all generators and multi-service Ranger fetch

Wire all five generators into `WorkloadOrchestrator` and fetch Ranger policies from all configured service names before Phase 2 validation.

**Files:**
- Modify: `simulator/src/main/java/.../driver/SimulatorMain.java`
- Modify: `conf/simulator-config.json`

**Background:** `SimulatorMain` currently constructs `WorkloadOrchestrator` with the old 5-arg constructor (line ~72). It also fetches policies from a single service name. Both must change.

- [ ] **Step 1: Add `buildAllServiceNames()` helper and update generator wiring**

In `SimulatorMain.java`:

Add a private static helper after the `run()` method:

```java
private static List<String> buildAllServiceNames(SimulatorConfig config) {
    List<String> names = new ArrayList<>();
    names.add(config.getRangerServiceName()); // lakeformation service (primary)
    if (config.getTrinoServiceName() != null) names.add(config.getTrinoServiceName());
    if (config.getEmrfsServiceName()  != null) names.add(config.getEmrfsServiceName());
    if (config.getTagServiceName()    != null) names.add(config.getTagServiceName());
    return names;
}
```

In the `run()` method, replace the existing `WorkloadOrchestrator` construction (currently 5-arg using `databaseTables` and `config.getRangerServiceName()`) with the following block. Note: the current code uses `new Random()` inline; extract it as a local variable first. There is no existing `existingPolicyIds` variable — use `new ArrayList<>()` directly:

```java
Random random = new Random();

HivePolicyGenerator hivePolicyGenerator = new HivePolicyGenerator(
        databaseTables, principals, config.getRangerServiceName(), random);
TrinoServiceGenerator trinoServiceGenerator = new TrinoServiceGenerator(
        databaseTables, principals, config.getTrinoServiceName(), random);
DataLocationPolicyGenerator dataLocationGenerator = new DataLocationPolicyGenerator(
        config.getS3Prefixes(), principals, config.getRangerServiceName(), random);
TagPolicyGenerator tagPolicyGenerator = new TagPolicyGenerator(
        List.of(), principals, config.getTagServiceName(), random);
EmrfsPolicyGenerator emrfsPolicyGenerator = new EmrfsPolicyGenerator(
        config.getS3Prefixes(), principals, config.getEmrfsServiceName(), random);

List<GeneratorEntry> generators = List.of(
    new GeneratorEntry("hive",         hivePolicyGenerator::generateTablePolicy, 45),
    new GeneratorEntry("trino",        trinoServiceGenerator::generate,          25),
    new GeneratorEntry("datalocation", dataLocationGenerator::generate,          15),
    new GeneratorEntry("tag",          tagPolicyGenerator::generate,             10),
    new GeneratorEntry("emrfs",        emrfsPolicyGenerator::generate,            5)
);
WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(
        principals, new ArrayList<>(), generators, random);
```

If `random` is already used elsewhere in `run()` (e.g. passed to `GlueCatalogDiscovery`), consolidate to a single `Random random = new Random();` declaration and pass it everywhere.

Note: `TagPolicyGenerator` constructor validates that the service name contains `"tag"`. `config.getTagServiceName()` defaults to `"cl_tag"` which satisfies that check. If the constructor validation is a `.contains("tag")` check, `"cl_tag"` passes.

Also add the required imports if not already present:
```java
import com.example.ranger.lakeformation.simulator.workload.TrinoServiceGenerator;
import com.example.ranger.lakeformation.simulator.workload.GeneratorEntry;
import com.example.ranger.lakeformation.simulator.workload.PolicyGenerator;
```

- [ ] **Step 2: Update `runOneCycle()` — replace single-service policy fetch with multi-service merge**

Find the line:
```java
var policiesNode = rangerClient.listPolicies(config.getRangerServiceName());
```

Replace with:

```java
List<String> allServiceNames = buildAllServiceNames(config);
List<JsonNode> rangerPolicies = new ArrayList<>();
for (String svcName : allServiceNames) {
    try {
        var node = rangerClient.listPolicies(svcName);
        if (node.isArray()) node.forEach(rangerPolicies::add);
    } catch (Exception e) {
        LOG.warn("Failed to fetch Ranger policies for service {}: {}", svcName, e.getMessage());
    }
}
```

Then pass `rangerPolicies` (a `List<JsonNode>`) to `expectedComputer.compute()` and to the reproduction bundle writer where the old `policiesNode` was used. The `ExpectedPermissionsComputer.compute()` already takes `List<JsonNode>` so this is compatible.

- [ ] **Step 3: Update `conf/simulator-config.json`**

Add the three new optional service name fields after `rangerServiceName`:

```json
  "trinoServiceName": "trino",
  "emrfsServiceName": "emrfs",
  "tagServiceName": "cl_tag",
```

- [ ] **Step 4: Compile and run all simulator tests**

```
mvn test -pl simulator -q
```

Expected: BUILD SUCCESS. All tests pass. If there are compilation errors, fix imports and method references.

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/java/com/example/ranger/lakeformation/simulator/driver/SimulatorMain.java \
        conf/simulator-config.json
git commit -m "feat(simulator): wire all five generators into WorkloadOrchestrator; multi-service Ranger policy fetch"
```

---

## Task 9: Update READMEs with cross-service deny semantics documentation

**Files:**
- Modify: `simulator/README.md`
- Modify: `README.md` (root)

- [ ] **Step 1: Add "Cross-Service Deny Semantics" section to `simulator/README.md`**

Add a new section after the "How It Works" section:

```markdown
## Cross-Service Deny Semantics

The sync service merges policies from **all configured Ranger services** into a single Cedar evaluation namespace before converting to Lake Formation permissions. Cedar's `forbid`-wins semantics apply **across service boundaries**:

> A `forbid` from any service suppresses a `permit` from any other service for the same `(principal, action, resource)` triple.

**Example:** Hive grants `analyst` SELECT on `analytics.events`. Trino denies `analyst` SELECT on `analytics.events`. Effective Lake Formation permission: **no grant** — the Trino deny wins even though the grant came from Hive.

**Customer implication:** An explicit deny anywhere in any configured Ranger service will suppress access granted by any other service. Scope deny policies carefully to avoid unintended suppression across query engines.

**Simulator behavior:** The `TrinoServiceGenerator` emits deny items in ~20% of generated Trino policies to continuously exercise this code path and ensure the validator catches regressions.
```

Also add a "Generators" section that lists the five generators and their weights.

- [ ] **Step 2: Add a note to root `README.md`**

Find the section that describes the supported Ranger services (or the architecture overview). Add a short note:

```markdown
### Cross-Service Deny Semantics

All configured Ranger services are merged into a single Cedar evaluation namespace. A `forbid` from any service suppresses a `permit` from any other service for the same principal, action, and resource. This means a Trino deny policy will suppress a Hive grant for the same resource. Scope deny policies carefully when using multiple Ranger services.
```

- [ ] **Step 3: Run full test suite one final time**

```
mvn test -pl simulator -q
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add simulator/README.md README.md
git commit -m "docs: document cross-service deny semantics and Phase 1 generator weights"
```

---

## Final verification

- [ ] **Run complete simulator test suite**

```
mvn test -pl simulator -q
```

Expected: BUILD SUCCESS. Count should be higher than before (new tests added in Tasks 1–7).

- [ ] **Verify compilation of full project**

```
mvn compile -q
```

Expected: BUILD SUCCESS. No compilation errors in any module.
