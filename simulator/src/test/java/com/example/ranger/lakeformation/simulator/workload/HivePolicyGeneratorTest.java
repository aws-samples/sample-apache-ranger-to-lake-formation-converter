package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HivePolicyGeneratorTest {

    private static final Map<String, List<String>> DATABASE_TABLES = Map.of(
            "db1", List.of("table_a", "table_b"),
            "db2", List.of("table_c"));
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String SERVICE = "hive";

    private HivePolicyGenerator generator(long seed) {
        return new HivePolicyGenerator(DATABASE_TABLES, PRINCIPALS, SERVICE, new Random(seed));
    }

    // 1. generateTablePolicy() returns a non-null map with "policyItems", "resources", "service"
    @Test
    void generateTablePolicy_hasRequiredTopLevelKeys() {
        Map<String, Object> policy = generator(42).generateTablePolicy("p1");
        assertNotNull(policy);
        assertTrue(policy.containsKey("policyItems"), "should have policyItems");
        assertTrue(policy.containsKey("resources"), "should have resources");
        assertTrue(policy.containsKey("service"), "should have service");
        assertEquals(SERVICE, policy.get("service"));
    }

    // 2. generateTablePolicy() includes "database" and "table" in resources
    @Test
    void generateTablePolicy_resourcesContainDatabaseAndTable() {
        Map<String, Object> policy = generator(7).generateTablePolicy("p2");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("database"), "resources should have 'database'");
        assertTrue(resources.containsKey("table"), "resources should have 'table'");
    }

    // 3. generateGrantableTablePolicy() has delegateAdmin=true in first policy item
    @Test
    void generateGrantableTablePolicy_delegateAdminIsTrue() {
        Map<String, Object> policy = generator(99).generateGrantableTablePolicy("p3");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyItems = (List<Map<String, Object>>) policy.get("policyItems");
        assertNotNull(policyItems);
        assertFalse(policyItems.isEmpty(), "policyItems should not be empty");
        Map<String, Object> firstItem = policyItems.get(0);
        assertEquals(Boolean.TRUE, firstItem.get("delegateAdmin"),
                "delegateAdmin should be true for grantable policy");
    }

    // 4. generateDatabasePolicy() has "database" in resources but no "table"
    @Test
    void generateDatabasePolicy_hasOnlyDatabaseInResources() {
        Map<String, Object> policy = generator(13).generateDatabasePolicy("p4");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("database"), "resources should have 'database'");
        assertFalse(resources.containsKey("table"), "database policy should NOT have 'table' in resources");
    }

    // 5. Fixed seed → deterministic output (same policy generated twice with same seed)
    @Test
    void generateTablePolicy_deterministicWithFixedSeed() {
        Map<String, Object> first = generator(12345).generateTablePolicy("p5");
        Map<String, Object> second = generator(12345).generateTablePolicy("p5");
        assertEquals(first.get("name"), second.get("name"));
        assertEquals(first.get("service"), second.get("service"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resources1 = (Map<String, Object>) first.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources2 = (Map<String, Object>) second.get("resources");
        assertEquals(resources1.get("database"), resources2.get("database"));
        assertEquals(resources1.get("table"), resources2.get("table"));
    }
}
