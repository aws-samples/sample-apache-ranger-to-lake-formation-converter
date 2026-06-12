package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EmrSparkPolicyGeneratorTest {

    private static final Map<String, List<String>> DB_TABLES = Map.of(
            "db1", List.of("orders", "items"),
            "db2", List.of("events")
    );
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String SERVICE_NAME = "amazon-emr-spark";

    private EmrSparkPolicyGenerator generator(long seed) {
        return new EmrSparkPolicyGenerator(DB_TABLES, PRINCIPALS, SERVICE_NAME, new Random(seed));
    }

    // 1. Table policy: contains database and table resource keys, no column key
    @Test
    void generateTablePolicy_hasExpectedStructure() {
        Map<String, Object> policy = generator(1).generateTablePolicy("p1");
        assertNotNull(policy);
        assertEquals(SERVICE_NAME, policy.get("service"));
        assertEquals(true, policy.get("isEnabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertTrue(resources.containsKey("database"), "must have 'database' resource key");
        assertTrue(resources.containsKey("table"),    "must have 'table' resource key");
        assertFalse(resources.containsKey("column"),  "table policy must not have 'column' key");
    }

    // 2. Table policy: access type is from the valid EMR Spark catalog set
    @Test
    void generateTablePolicy_accessTypeIsValid() {
        Set<String> validTypes = Set.of("select", "update", "alter", "create", "drop", "read", "write");
        for (long seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateTablePolicy("p-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertFalse(items.isEmpty());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accesses = (List<Map<String, Object>>) items.get(0).get("accesses");
            assertFalse(accesses.isEmpty());
            for (Map<String, Object> access : accesses) {
                String type = (String) access.get("type");
                assertTrue(validTypes.contains(type),
                        "access type '" + type + "' not in valid EMR Spark set");
            }
        }
    }

    // 3. Database policy: has only database key, no table or column
    @Test
    void generateDatabasePolicy_hasOnlyDatabaseResource() {
        Map<String, Object> policy = generator(2).generateDatabasePolicy("p2");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertTrue(resources.containsKey("database"), "must have 'database' resource key");
        assertFalse(resources.containsKey("table"),  "database policy must not have 'table' key");
        assertFalse(resources.containsKey("column"), "database policy must not have 'column' key");
    }

    // 4. Column policy: has database, table, and column resource keys
    @Test
    void generateColumnPolicy_hasColumnResource() {
        Map<String, Object> policy = generator(3).generateColumnPolicy("p3");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertTrue(resources.containsKey("database"), "must have 'database' key");
        assertTrue(resources.containsKey("table"),    "must have 'table' key");
        assertTrue(resources.containsKey("column"),   "column policy must have 'column' key");
    }

    // 5. Column policy: access type is from the column-level subset
    @Test
    void generateColumnPolicy_accessTypeIsColumnLevel() {
        Set<String> validColTypes = Set.of("select", "read");
        for (long seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateColumnPolicy("c-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accesses = (List<Map<String, Object>>) items.get(0).get("accesses");
            for (Map<String, Object> access : accesses) {
                String type = (String) access.get("type");
                assertTrue(validColTypes.contains(type),
                        "column access type '" + type + "' not in valid set " + validColTypes);
            }
        }
    }

    // 6. Deny policy: policyItems is empty, denyPolicyItems is non-empty
    @Test
    void generateDenyTablePolicy_policyItemsEmptyDenyItemsPresent() {
        Map<String, Object> policy = generator(4).generateDenyTablePolicy("p4");
        @SuppressWarnings("unchecked")
        List<Object> policyItems = (List<Object>) policy.get("policyItems");
        @SuppressWarnings("unchecked")
        List<Object> denyItems = (List<Object>) policy.get("denyPolicyItems");
        assertTrue(policyItems.isEmpty(),   "policyItems must be empty for a deny-only policy");
        assertFalse(denyItems.isEmpty(),    "denyPolicyItems must be non-empty");
    }

    // 7. generate() delegates to generateTablePolicy()
    @Test
    void generate_delegatesToGenerateTablePolicy() {
        EmrSparkPolicyGenerator gen = generator(5);
        Map<String, Object> via_generate = gen.generate("g1");
        assertEquals(SERVICE_NAME, via_generate.get("service"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) via_generate.get("resources");
        assertTrue(resources.containsKey("database") && resources.containsKey("table"),
                "generate() must produce a table-level policy");
    }

    // 8. Service name in payload matches constructor argument
    @Test
    void allGenerators_serviceNameMatchesConstructorArg() {
        EmrSparkPolicyGenerator gen = generator(6);
        for (Map<String, Object> policy : List.of(
                gen.generateTablePolicy("t"),
                gen.generateDatabasePolicy("d"),
                gen.generateColumnPolicy("c"),
                gen.generateDenyTablePolicy("n"))) {
            assertEquals(SERVICE_NAME, policy.get("service"),
                    "service name must match constructor argument in all generators");
        }
    }

    // 9. No "id" key in payload
    @Test
    void generateTablePolicy_noIdInPayload() {
        Map<String, Object> policy = generator(7).generateTablePolicy("p5");
        assertFalse(policy.containsKey("id"),
                "Ranger rejects string 'id' fields — payload must not include 'id'");
    }
}
