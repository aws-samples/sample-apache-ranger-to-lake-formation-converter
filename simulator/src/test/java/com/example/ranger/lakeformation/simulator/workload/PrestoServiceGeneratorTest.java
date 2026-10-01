package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrestoServiceGeneratorTest {
    private static final Map<String, List<String>> DB_TABLES = Map.of(
            "analytics", List.of("events", "sessions"),
            "warehouse", List.of("orders"));
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String PRESTO_SERVICE = "presto";
    private static final Set<String> VALID_PRESTO_TYPES =
            Set.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");

    private PrestoServiceGenerator gen(long seed) {
        return new PrestoServiceGenerator(DB_TABLES, PRINCIPALS, PRESTO_SERVICE, new Random(seed));
    }

    @Test
    void generate_serviceNameIsPresto() {
        Map<String, Object> policy = gen(7).generate("p-1");
        assertEquals(PRESTO_SERVICE, policy.get("service"));
    }

    @Test
    void generate_hasCatalogSchemaTableResources() {
        Map<String, Object> policy = gen(42).generate("p-2");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("catalog"), "Presto policies must have 'catalog'");
        assertTrue(resources.containsKey("schema"), "Presto policies must have 'schema'");
        assertTrue(resources.containsKey("table"), "Presto policies must have 'table'");
        assertFalse(resources.containsKey("database"), "Presto policies must NOT use 'database' key");
    }

    @Test
    void generate_catalogIsConfiguredCatalog() {
        // The generator always authors policies under catalog "hive", matching the
        // gdcCatalogName configured for the "presto" service instance.
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = gen(seed).generate("p-" + seed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
            @SuppressWarnings("unchecked")
            List<String> catalogValues =
                    (List<String>) ((Map<String, Object>) resources.get("catalog")).get("values");
            assertEquals(List.of("hive"), catalogValues);
        }
    }

    @Test
    void generate_accessTypesAreFromPrestoVocabulary() {
        for (int seed = 0; seed < 30; seed++) {
            Map<String, Object> policy = gen(seed).generate("p-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items, "policyItems must be present");
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
                for (Map<String, Object> access : accesses) {
                    String type = (String) access.get("type");
                    assertTrue(VALID_PRESTO_TYPES.contains(type),
                            "Access type '" + type + "' is not a valid Presto type");
                }
            }
        }
    }

    @Test
    void generate_denyPolicyItemsIsAlwaysAnEmptyList() {
        Map<String, Object> policy = gen(3).generate("p-3");
        Object denyItems = policy.get("denyPolicyItems");
        assertNotNull(denyItems);
        assertInstanceOf(List.class, denyItems);
        assertTrue(((List<?>) denyItems).isEmpty());
    }

    @Test
    void generate_noIdKeyInPayload() {
        Map<String, Object> policy = gen(99).generate("p-99");
        assertFalse(policy.containsKey("id"),
                "Payload must not include 'id' key (Ranger rejects string id fields)");
    }

    @Test
    void generate_isDeterministicWithFixedSeed() {
        Map<String, Object> p1 = gen(12345).generate("id-x");
        Map<String, Object> p2 = gen(12345).generate("id-x");
        assertEquals(p1.get("name"), p2.get("name"));
        assertEquals(p1.get("service"), p2.get("service"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r1 = (Map<String, Object>) p1.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) p2.get("resources");
        assertEquals(r1.get("schema"), r2.get("schema"));
        assertEquals(r1.get("table"), r2.get("table"));
    }

    @Test
    void generate_tableNameBelongsToChosenSchema() {
        for (int seed = 0; seed < 100; seed++) {
            Map<String, Object> policy = gen(seed).generate("p-" + seed);
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

    @Test
    void generate_hasSingleUserPolicyItem() {
        Map<String, Object> policy = gen(1).generate("p-single");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
        assertEquals(1, items.size());
        @SuppressWarnings("unchecked")
        List<String> users = (List<String>) items.get(0).get("users");
        assertEquals(1, users.size());
        assertTrue(PRINCIPALS.contains(users.get(0)));
    }
}
