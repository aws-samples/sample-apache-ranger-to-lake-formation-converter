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
