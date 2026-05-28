# Simulator Phase 1 — Multi-Service Expansion Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire the dead generators, add Trino as a real service, fix the DataLocation/EMRFS/Tag `"id"` payload bug, make `WorkloadOrchestrator` multi-service, and harden unit tests to verify correctness rather than just structure.

**Architecture:** `WorkloadOrchestrator` becomes a weighted dispatcher over a list of named generators. Each generator targets one Ranger service instance with the correct access types for that service. `ExpectedPermissionsComputer` gains per-service access-type maps and full cross-service forbid semantics so Phase 2 validation is accurate.

**Tech Stack:** Java 17, JUnit 5, Jackson, AWS SDK v2 (Glue already wired)

---

## Background: Service Type Distinctions

The sync service supports three distinct Ranger service types that each have different access type vocabularies and resource hierarchies. The simulator must generate policies that match each service's actual schema.

### `lakeformation` service (RangerServiceAdapter)
Resource hierarchy: `database` → `table` → `column` → `datalocation`
Access types: `select` (→SELECT), `insert` (→INSERT), `delete` (→DELETE), `describe` (→DESCRIBE), `alter` (→ALTER), `drop` (→DROP), `create_database` (→CREATE_DATABASE), `create_table` (→CREATE_TABLE), `update` (→INSERT), `create` (→CREATE_TABLE), `read` (→SELECT), `write` (→INSERT), `all` (→SELECT+INSERT+DELETE+ALTER+DROP+DESCRIBE), `datalocation` (→DATA_LOCATION_ACCESS), `data_location_access` (→DATA_LOCATION_ACCESS)

### `hive` service (HiveServiceAdapter)
Resource hierarchy: `database` → `table` → `column`
Access types: `select` (→SELECT), `update` (→INSERT), `create` (→CREATE_TABLE), `drop` (→DROP), `alter` (→ALTER), `read` (→SELECT), `write` (→INSERT), `all` (→SUPER — **not an LF permission**, results in zero LF grants — a documented gap)
Unmapped (silently skipped): `index`, `lock`
**Note:** Hive has no `insert`, `delete`, or `describe` access types. The current `HivePolicyGenerator` incorrectly uses lakeformation access types — this is fixed in this phase.

### `trino` service (TrinoServiceAdapter)
Resource hierarchy: `schema` → `table` → `column` (**note: uses `schema` not `database`**)
Access types: `select` (→SELECT), `insert` (→INSERT), `delete` (→DELETE), `create` (→CREATE_TABLE), `drop` (→DROP), `alter` (→ALTER), `use` (→DESCRIBE), `show` (→DESCRIBE)
Unmapped (silently skipped): `grant`, `revoke`
**Note:** Trino has no `datalocation` or `all` access types.

---

## Cross-Service Deny Semantics (Position 1 — documented)

The sync service merges all policies from all configured Ranger services into a single flat list before Cedar conversion (`SyncService.executeSyncCycle()` lines 442–499). Cedar's `forbid`-wins semantics therefore operate **across services** — a `forbid` from any service suppresses a `permit` from any other service for the same `(principal, action, resource)` triple.

**Consequence:** A Trino `deny` on `analytics.events.SELECT` for `analyst` will suppress the LF grant that a Hive `select` permit on the same resource would produce — even though the deny came from a different query engine.

**This is Position 1 behavior (agreed 2026-05-28): cross-service forbid wins.** The `ExpectedPermissionsComputer` must implement the same semantics. This behavior must be documented in both the simulator README and the root README.

---

## Files to Create

| File | Purpose |
|------|---------|
| `simulator/.../workload/TrinoServiceGenerator.java` | Generates Ranger `trino` service policies with correct resource key (`schema`) and Trino access types |
| `simulator/.../workload/GeneratorEntry.java` | Record: `(String name, PolicyGenerator generator, int weight)` |
| `simulator/.../workload/PolicyGenerator.java` | Functional interface: `Map<String,Object> generate(String policyId)` |

## Files to Modify

| File | Change |
|------|--------|
| `workload/DataLocationPolicyGenerator.java` | Remove `"id": policyId` from payload map |
| `workload/EmrfsPolicyGenerator.java` | Remove `"id": policyId` from payload map |
| `workload/TagPolicyGenerator.java` | Remove `"id": policyId` from payload map |
| `workload/HivePolicyGenerator.java` | Fix access types to Hive vocabulary; fix `generateDatabasePolicy()` to use `create` not `create_table`; service name comes from caller |
| `workload/WorkloadOrchestrator.java` | Replace single generator with weighted `List<GeneratorEntry>`; rewrite `generateBatch()`/`pickOperation()` to dispatch to whichever generator is selected |
| `driver/SimulatorConfig.java` | Add 4 optional fields: `trinoServiceName`, `emrfsServiceName`, `tagServiceName`, `s3Prefixes`; update `SimulatorConfigTest` accordingly |
| `driver/SimulatorMain.java` | Build all generators; wire into orchestrator; fetch Ranger policies from all configured service names for Phase 2 |
| `validator/ExpectedPermissionsComputer.java` | Add per-service ACCESS_MAP; implement `denyPolicyItems` processing (currently missing); implement cross-service forbid semantics |
| `simulator/README.md` | Add "Cross-Service Deny Semantics" and "Generators" sections |
| Root `README.md` | Add note about cross-service deny behavior |
| `test/.../workload/HivePolicyGeneratorTest.java` | Update access types to Hive vocabulary; add db-consistency invariant test |
| `test/.../workload/DataLocationPolicyGeneratorTest.java` | Assert no `"id"` key in payload |
| `test/.../workload/EmrfsPolicyGeneratorTest.java` | Assert no `"id"` key in payload |
| `test/.../workload/TagPolicyGeneratorTest.java` | Assert no `"id"` key in payload |
| `test/.../workload/TrinoServiceGeneratorTest.java` | New: structure + correctness tests |
| `test/.../workload/WorkloadOrchestratorTest.java` | Update constructor; assert all five generator service names fire across 200 iterations |
| `test/.../validator/ExpectedPermissionsComputerTest.java` | Update `allAccessExpandsToMultiplePermissions` (was hive service, must move to lakeformation); add Hive, Trino, and cross-service forbid cases |
| `test/.../driver/SimulatorConfigTest.java` | Add 14th null arg to all 13-arg constructor calls; add tests for new fields |
| `conf/simulator-config.json` | Add `trinoServiceName`, `tagServiceName`, `emrfsServiceName` example values |

---

## Detailed Design

### `PolicyGenerator` functional interface

```java
@FunctionalInterface
public interface PolicyGenerator {
    Map<String, Object> generate(String policyId);
}
```

### `GeneratorEntry` record

```java
public record GeneratorEntry(String name, PolicyGenerator generator, int weight) {}
```

`name` is a short label used as the policy ID prefix (e.g. `"hive"`, `"trino"`, `"datalocation"`) so `MutationDriver`'s ID map stays readable in logs.

### `WorkloadOrchestrator` — new constructor and `generateBatch()` internals

**Constructor:**

```java
public WorkloadOrchestrator(List<String> principalPool, List<String> existingPolicyIds,
                            List<GeneratorEntry> generators, Random random)
```

Internally normalise weights into a cumulative threshold array. Compute `totalWeight = sum of all entry weights`.

**`generateBatch()` / `pickOperation()` change:**

The current `pickOperation()` picks an *operation type* (CREATE/UPDATE/DISABLE/ENABLE/DELETE) with fixed weights, then calls `policyGenerator.generateTablePolicy(id)`. After the change:

1. First roll `[0, 100)` to pick the operation type (same weights as today).
2. For CREATE and UPDATE only — second roll `[0, totalWeight)` to pick which `GeneratorEntry` fires. Call `entry.generator().generate(policyId)`.
3. DISABLE/ENABLE/DELETE do not need a generator — they only need a policy ID, which already comes from `existingPolicyIds`. No generator roll needed for those operations.

Policy IDs for CREATE operations become `"{entry.name()}-sim-{nanoTime}"` so they stay namespaced per service in `MutationDriver`'s ID map and in logs.

**No changes to DISABLE/ENABLE/DELETE logic** — they are operation-type-only and generator-agnostic.

### `TrinoServiceGenerator`

**Constructor:**

```java
public TrinoServiceGenerator(Map<String, List<String>> databaseTables,
                              List<String> principalNames,
                              String trinoServiceName,
                              Random random)
```

Uses resource key `schema` (not `database`). Access types drawn from Trino vocabulary only. ~20% of generated policies include a `denyPolicyItems` entry (same user, same resource, subset of access types) to exercise cross-service forbid scenarios.

```java
private static final List<String> TRINO_ACCESS_TYPES =
    List.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");

public Map<String, Object> generate(String policyId) {
    String schema = randomFrom(databases);
    List<String> tables = databaseTables.getOrDefault(schema, List.of());
    String table = tables.isEmpty() ? "*" : randomFrom(tables);
    String user = randomFrom(principalNames);
    List<String> accesses = randomSubset(TRINO_ACCESS_TYPES, 1 + random.nextInt(3));

    Map<String, Object> resources = new LinkedHashMap<>();
    resources.put("schema", Map.of("values", List.of(schema), "isExcludes", false));
    resources.put("table",  Map.of("values", List.of(table),  "isExcludes", false));

    // ~20% chance: add a denyPolicyItems entry to exercise cross-service forbid
    List<Object> denyItems = random.nextInt(100) < 20
        ? List.of(buildItem(user, randomSubset(accesses, 1), false))
        : List.of();

    return Map.of(
        "name",           policyId,
        "service",        trinoServiceName,
        "isEnabled",      true,
        "policyType",     0,
        "resources",      resources,
        "policyItems",    List.of(buildItem(user, accesses, false)),
        "denyPolicyItems", denyItems
    );
}
```

### `HivePolicyGenerator` access type correction

**Fix `HIVE_ACCESS_TYPES`:**

```java
// Was: List.of("select", "insert", "delete", "describe")  — WRONG, those are lakeformation types
private static final List<String> HIVE_ACCESS_TYPES =
    List.of("select", "update", "read", "write", "create", "drop", "alter");
```

**Fix `generateDatabasePolicy()`:** Currently uses `create_table` and `drop` (lakeformation types). Hive does not have `create_table` — it has `create`. Change to:

```java
"policyItems", List.of(buildItem(user, List.of("create", "drop"), false))
```

### `ExpectedPermissionsComputer` — full redesign of access-type resolution and forbid handling

**Current state (gaps to fix):**
1. Single `ACCESS_MAP` is lakeformation-only — all other service types use the wrong mappings
2. `processPolicyInto()` reads only `policyItems`, completely ignores `denyPolicyItems` — no forbid logic exists
3. Cross-service forbid: not implemented at all

**New design:**

**Step 1 — Per-service ACCESS_MAP**

Replace the single `ACCESS_MAP` with a `Map<String, Map<String, Set<String>>> SERVICE_ACCESS_MAPS`:

```java
private static final Map<String, Map<String, Set<String>>> SERVICE_ACCESS_MAPS = buildServiceAccessMaps();

private static Map<String, Map<String, Set<String>>> buildServiceAccessMaps() {
    // lakeformation map: existing ACCESS_MAP content (all → 6 permissions, datalocation, etc.)
    Map<String, Set<String>> lfMap = new HashMap<>();
    lfMap.put("select",               Set.of("SELECT"));
    lfMap.put("insert",               Set.of("INSERT"));
    // ... same as today ...
    lfMap.put("all",                  Set.of("SELECT","INSERT","DELETE","ALTER","DROP","DESCRIBE"));
    lfMap.put("datalocation",         Set.of("DATA_LOCATION_ACCESS"));

    // hive map: no insert/delete/describe, no datalocation, all→nothing (SUPER gap)
    Map<String, Set<String>> hiveMap = new HashMap<>();
    hiveMap.put("select",  Set.of("SELECT"));
    hiveMap.put("update",  Set.of("INSERT"));
    hiveMap.put("create",  Set.of("CREATE_TABLE"));
    hiveMap.put("drop",    Set.of("DROP"));
    hiveMap.put("alter",   Set.of("ALTER"));
    hiveMap.put("read",    Set.of("SELECT"));
    hiveMap.put("write",   Set.of("INSERT"));
    // "all" intentionally absent — maps to SUPER which is not an LF permission

    // trino map: use/show → DESCRIBE, no datalocation, no all
    Map<String, Set<String>> trinoMap = new HashMap<>();
    trinoMap.put("select",  Set.of("SELECT"));
    trinoMap.put("insert",  Set.of("INSERT"));
    trinoMap.put("delete",  Set.of("DELETE"));
    trinoMap.put("create",  Set.of("CREATE_TABLE"));
    trinoMap.put("drop",    Set.of("DROP"));
    trinoMap.put("alter",   Set.of("ALTER"));
    trinoMap.put("use",     Set.of("DESCRIBE"));
    trinoMap.put("show",    Set.of("DESCRIBE"));

    Map<String, Map<String, Set<String>>> result = new HashMap<>();
    result.put("lakeformation", Collections.unmodifiableMap(lfMap));
    result.put("hive",          Collections.unmodifiableMap(hiveMap));
    result.put("trino",         Collections.unmodifiableMap(trinoMap));
    return Collections.unmodifiableMap(result);
}
```

Lookup helper:

```java
private Map<String, Set<String>> accessMapForService(String serviceName) {
    String key = serviceName == null ? "lakeformation"
               : serviceName.toLowerCase(Locale.ROOT);
    Map<String, Set<String>> map = SERVICE_ACCESS_MAPS.get(key);
    if (map == null) {
        LOG.warn("Unknown service name '{}'; falling back to lakeformation access map", serviceName);
        return SERVICE_ACCESS_MAPS.get("lakeformation");
    }
    return map;
}
```

**Important constraint:** `accessMapForService()` matches on the exact lowercase service name. The canonical keys are `"lakeformation"`, `"hive"`, and `"trino"`. Any other name (e.g. `"trino-prod"`, `"my-hive"`) silently falls back to the lakeformation map and will produce incorrect expected permissions. The defaults in `SimulatorConfig` (`trinoServiceName="trino"`, etc.) are chosen to match these canonical keys exactly. If a customer overrides a service name in config, the `accessMapForService()` lookup will silently use the wrong map — this is a known limitation, not a bug to fix in Phase 1.

**Step 2 — Trino `schema` resource key**

`extractResourceSpecs()` currently reads `resources.path("database")`. Add a fallback: if `database` is missing, try `schema`. Trino policies use `schema` as the resource key; the result maps to the same Glue database name.

```java
// In extractResourceSpecs():
List<String> databases = resourceValues(resources, "database");
if (databases.isEmpty()) {
    databases = resourceValues(resources, "schema");  // Trino uses "schema"
}
```

**Step 3 — `denyPolicyItems` processing (currently entirely absent)**

`compute()` currently calls `processPolicyInto()` which only reads `policyItems`. Cross-service forbid requires collecting ALL deny items across ALL policies first, then filtering permits.

Redesign `compute()` to be two-pass:

```java
public Set<SimulatorPermission> compute(List<JsonNode> policies) {
    // Pass 1: collect all (principal, resourceId, permission) triples that are denied
    Set<DenyKey> globalDenySet = new HashSet<>();
    for (JsonNode policy : policies) {
        if (!shouldProcess(policy)) continue;
        collectDenies(policy, globalDenySet);
    }

    // Pass 2: collect all permits, then subtract denies
    Set<SimulatorPermission> permits = new HashSet<>();
    for (JsonNode policy : policies) {
        if (!shouldProcess(policy)) continue;
        processPermitsInto(policy, permits);
    }

    // Remove any permit that has a matching deny
    permits.removeIf(p ->
        globalDenySet.contains(new DenyKey(p.principalArn(), p.resourceId(), p.permission())));

    return permits;
}
```

`DenyKey` is a private record: `record DenyKey(String principalArn, String resourceId, String permission) {}`

`collectDenies()` reads `denyPolicyItems`, resolves principals via the principal mapping, resolves access types using the same per-service `accessMapForService()` and the same `extractResourceSpecs()` helper used in `processPermitsInto()`, and adds `DenyKey` entries to the set. It applies the same `shouldProcess()` guard as Pass 2 (the guard is already applied in the outer loop above, so `collectDenies()` can assume it will only be called for enabled, non-masking, non-tag policies).

`processPermitsInto()` is the existing `processPolicyInto()` renamed, reading only `policyItems`.

The `shouldProcess()` helper extracts the guard logic (disabled, tag service, masking policy checks) currently inlined in `processPolicyInto()`.

**The existing `processItemInto()` method stays unchanged** — it only processes allow items and is called from `processPermitsInto()`.

### `SimulatorMain` — multi-service Ranger policy fetch for Phase 2

**`buildAllServiceNames(config)` helper:**

```java
private static List<String> buildAllServiceNames(SimulatorConfig config) {
    List<String> names = new ArrayList<>();
    names.add(config.getRangerServiceName()); // lakeformation service
    if (config.getTrinoServiceName() != null) names.add(config.getTrinoServiceName());
    if (config.getEmrfsServiceName()  != null) names.add(config.getEmrfsServiceName());
    if (config.getTagServiceName()    != null) names.add(config.getTagServiceName());
    // hive service name = getRangerServiceName() already covers the primary service;
    // if a separate hive service is configured, add a dedicated config field in Phase 2
    return names;
}
```

**`runOneCycle()` — replace single-service fetch with multi-service merge:**

Currently `runOneCycle()` fetches policies from one service:

```java
var policiesNode = rangerClient.listPolicies(config.getRangerServiceName());
```

After the change, fetch from all configured service names and merge:

```java
List<String> allServiceNames = buildAllServiceNames(config);  // lakeformation + trino + emrfs + tag
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

**`SimulatorMain.run()` — WorkloadOrchestrator wiring:**

When building the `WorkloadOrchestrator`, replace the current single generator with a `List<GeneratorEntry>`:

```java
List<GeneratorEntry> generators = List.of(
    new GeneratorEntry("hive",       hivePolicyGenerator::generateTablePolicy, 45),
    new GeneratorEntry("trino",      trinoServiceGenerator::generate,          25),
    new GeneratorEntry("datalocation", dataLocationGenerator::generate,        15),
    new GeneratorEntry("tag",        tagPolicyGenerator::generate,             10),
    new GeneratorEntry("emrfs",      emrfsPolicyGenerator::generate,           5)
);
WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(
    config.getPrincipalPool(), existingPolicyIds, generators, random);
```

This merged list is passed to both `expectedComputer.compute(rangerPolicies)` and stored in the reproduction bundle — matching how the production sync service merges before conversion.

### `SimulatorConfig` new optional fields

Add 4 optional fields (all nullable/defaulted — existing configs continue to work):

```java
@JsonProperty("trinoServiceName")  String trinoServiceName   // default: "trino"
@JsonProperty("emrfsServiceName")  String emrfsServiceName   // default: "emrfs"
@JsonProperty("tagServiceName")    String tagServiceName      // default: "cl_tag"
@JsonProperty("s3Prefixes")        List<String> s3Prefixes   // default: sample paths
```

`SimulatorConfigTest`: add a `null` as the 15th–18th argument to all existing constructor calls; add tests verifying each new field defaults correctly and is defensively copied where applicable.

### Default generator weights in `SimulatorMain`

```
HivePolicyGenerator        45%   (hive service name from config)
TrinoServiceGenerator      25%   (trino service name from config)
DataLocationGenerator      15%   (lakeformation service name)
TagPolicyGenerator         10%   (tag service name from config)
EmrfsPolicyGenerator        5%   (emrfs service name from config)
```

Total: 100%. Weights are hardcoded in `SimulatorMain`; not exposed in config (YAGNI).

---

## Cross-Service Deny — README Documentation

The simulator README gets a new "Cross-Service Deny Semantics" section:

1. The sync service merges all Ranger services into one Cedar evaluation namespace
2. A `forbid` from any service wins over a `permit` from any other service for the same `(principal, action, resource)`
3. Concrete example: Hive grants `analyst` SELECT on `analytics.events`; Trino denies `analyst` SELECT on `analytics.events` → effective LF permission: **no grant** (deny wins)
4. Customer implication: an explicit deny anywhere in any configured service will suppress access granted by any other service — scope denies carefully
5. The simulator exercises this by generating Trino deny policies at ~20% of Trino creates

---

## Unit Test Changes

### `HivePolicyGeneratorTest`
- Replace `insert`/`delete`/`describe` with `update`/`read`/`write` in access type assertions
- Add: run 100 iterations with fixed seed; assert every generated table name is in `databaseTables.get(chosenDb)` — no cross-db table picks

### `DataLocationPolicyGeneratorTest`, `EmrfsPolicyGeneratorTest`, `TagPolicyGeneratorTest`
- Add: assert no `"id"` key in returned map payload

### `TrinoServiceGeneratorTest` (new)
- Resource key is `schema` (not `database`)
- Access types drawn only from Trino vocabulary (not lakeformation)
- Service name matches configured name
- Deterministic with fixed seed
- denyPolicyItems is a list (may be empty or non-empty)

### `WorkloadOrchestratorTest`
- Rewrite helper to build a `List<GeneratorEntry>` with one entry per service, each wrapping a minimal lambda generator
- Retain all existing test cases (batch size, determinism, create adds ID, delete removes ID, unmodifiable view)
- Add: over 200 iterations, collect all service names from generated `CreatePolicy` payloads; assert all five service names appear at least once

### `ExpectedPermissionsComputerTest`
- **Update** `allAccessExpandsToMultiplePermissions`: change its policy `service` field from `hive` to `lakeformation` (lakeformation's `all` still expands to 6; the test was always testing the lakeformation mapping)
- **Update** `databaseLevelPermission` (or equivalent test that uses `service=hive` with access `create_database`): change `service` to `lakeformation`. The Hive service has no `create_database` access type; using `service=hive` with `create_database` would produce zero results and break the test assertion. The `lakeformation` map contains `create_database → CREATE_DATABASE`.
- Add Hive service cases: `service=hive`, access type `select` → SELECT; `update` → INSERT; `read` → SELECT; `all` → no grant (SUPER gap, zero results)
- Add Trino service cases: `service=trino`, `use` → DESCRIBE, `show` → DESCRIBE; `schema` resource key resolves same as `database`
- Add cross-service forbid case: two policies — policy A has `service=hive`, `policyItems` with `select`; policy B has `service=trino`, `denyPolicyItems` with `select`; same principal and `db.table` resource → `compute(List.of(policyA, policyB))` returns empty set

### `SimulatorConfigTest`
- Add `null` for each new field to all existing 14-arg constructor calls (they become 18-arg)
- Add `trinoServiceNameDefaultsToTrino()`, `tagServiceNameDefaultsToCl_tag()`, etc.

---

## Out of Scope for Phase 1

The following are Phase 2:
- Column-level policies
- Wildcard table patterns (`events_*`, `*`)
- Multi-user policy items
- `delegateAdmin=true` grantable policies
- Overlapping grants from two policies for the same resource
- Deny-exception (`@denyException`) scenarios
- Database-level policies (no table resource)
