package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LakeFormationPolicyGenerator}, which emits native LakeFormation
 * Ranger service policies (LF access-type vocabulary), distinct from the Hive
 * generator which uses Hive vocabulary.
 */
class LakeFormationPolicyGeneratorTest {

    private static final Map<String, List<String>> DATABASE_TABLES = Map.of(
            "db1", List.of("table_a", "table_b"),
            "db2", List.of("table_c"));
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String SERVICE = "lakeformation";

    private LakeFormationPolicyGenerator generator(long seed) {
        return new LakeFormationPolicyGenerator(DATABASE_TABLES, PRINCIPALS, SERVICE, new Random(seed));
    }

    @Test
    void generateTablePolicy_hasRequiredTopLevelKeysAndService() {
        Map<String, Object> policy = generator(42).generateTablePolicy("p1");
        assertNotNull(policy);
        assertTrue(policy.containsKey("policyItems"));
        assertTrue(policy.containsKey("resources"));
        assertEquals(SERVICE, policy.get("service"));
    }

    @Test
    void generateTablePolicy_usesLakeFormationAccessTypes() {
        Set<String> validTypes = Set.of("select", "insert", "delete", "describe", "alter", "drop");
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
                    assertTrue(validTypes.contains(type),
                            "Access type '" + type + "' is not a valid LakeFormation access type");
                }
            }
        }
    }

    @Test
    void generateDatabasePolicy_usesCreateTableVocabulary() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateDatabasePolicy("dp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
                for (Map<String, Object> access : accesses) {
                    String type = (String) access.get("type");
                    assertTrue(Set.of("create_table", "drop").contains(type),
                            "database policy access type '" + type + "' must be 'create_table' or 'drop'");
                }
            }
        }
    }

    @Test
    void generateColumnPolicy_usesSelectOnly() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateColumnPolicy("cp-" + seed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
            assertTrue(resources.containsKey("column"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
                for (Map<String, Object> access : accesses) {
                    assertEquals("select", access.get("type"));
                }
            }
        }
    }

    @Test
    void generateDenyTablePolicy_hasDenyItemsAndEmptyAllowItems() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateDenyTablePolicy("dp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> policyItems = (List<Map<String, Object>>) policy.get("policyItems");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> denyItems = (List<Map<String, Object>>) policy.get("denyPolicyItems");
            assertTrue(policyItems.isEmpty());
            assertFalse(denyItems.isEmpty());
        }
    }

    @Test
    void generateTablePolicy_deterministicWithFixedSeed() {
        Map<String, Object> first = generator(12345).generateTablePolicy("p5");
        Map<String, Object> second = generator(12345).generateTablePolicy("p5");
        assertEquals(first.get("name"), second.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r1 = (Map<String, Object>) first.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) second.get("resources");
        assertEquals(r1.get("table"), r2.get("table"));
    }

    @Test
    void generateTablePolicy_tableBelongsToChosenDatabase() {
        for (int seed = 0; seed < 100; seed++) {
            Map<String, Object> policy = generator(seed).generateTablePolicy("p-" + seed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
            @SuppressWarnings("unchecked")
            List<String> dbs = (List<String>) ((Map<String, Object>) resources.get("database")).get("values");
            @SuppressWarnings("unchecked")
            List<String> tables = (List<String>) ((Map<String, Object>) resources.get("table")).get("values");
            String table = tables.get(0);
            if (!"*".equals(table)) {
                assertTrue(DATABASE_TABLES.getOrDefault(dbs.get(0), List.of()).contains(table));
            }
        }
    }
}
