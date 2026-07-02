package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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

    // 6. generateTablePolicy() only produces valid Hive service access types.
    // Hive vocabulary differs from LakeFormation: insert/delete/describe are NOT Hive
    // access types (the Hive servicedef rejects them). The HiveServiceAdapter maps the
    // Hive vocabulary to LF actions (e.g. update->INSERT, create->CREATE_TABLE).
    @Test
    void generateTablePolicy_usesHiveAccessTypes() {
        Set<String> validTypes = Set.of("select", "update", "create", "drop", "alter", "read", "write");
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
                            "Access type '" + type + "' is not a valid Hive service access type");
                }
            }
        }
    }

    // 7. generateTablePolicy() table name belongs to the chosen database
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

    // 8. generateDatabasePolicy() uses the native Hive service access types
    @Test
    void generateDatabasePolicy_usesHiveCreate() {
        // generateDatabasePolicy must use "create" and "drop" (native Hive service access types).
        // "create" maps to LF CREATE_TABLE via the HiveServiceAdapter.
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateDatabasePolicy("dp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
                for (Map<String, Object> access : accesses) {
                    String type = (String) access.get("type");
                    assertTrue(Set.of("create", "drop").contains(type),
                            "generateDatabasePolicy access type '" + type + "' must be 'create' or 'drop'");
                }
            }
        }
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

    // Gap 2: generateMultiUserTablePolicy() has 2-3 users in the single policyItem
    @Test
    void generateMultiUserTablePolicy_hasTwoOrThreeUsers() {
        for (int seed = 0; seed < 50; seed++) {
            Map<String, Object> policy = generator(seed).generateMultiUserTablePolicy("mp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items);
            assertFalse(items.isEmpty());
            @SuppressWarnings("unchecked")
            List<String> users = (List<String>) items.get(0).get("users");
            assertNotNull(users);
            assertTrue(users.size() >= 2 && users.size() <= 3,
                    "Expected 2-3 users in multi-user policy, got " + users.size());
            // All users must be from the principal pool
            assertTrue(PRINCIPALS.containsAll(users),
                    "All users must be from the principal pool, got: " + users);
        }
    }

    // Gap 2: generateMultiUserTablePolicy() users are distinct (no duplicates)
    @Test
    void generateMultiUserTablePolicy_usersAreDistinct() {
        for (int seed = 0; seed < 50; seed++) {
            Map<String, Object> policy = generator(seed).generateMultiUserTablePolicy("mp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            @SuppressWarnings("unchecked")
            List<String> users = (List<String>) items.get(0).get("users");
            assertEquals(users.size(), Set.copyOf(users).size(),
                    "Users in multi-user policy must be distinct, got: " + users);
        }
    }

    // Gap 4: generateColumnPolicy() includes "column" in resources
    @Test
    void generateColumnPolicy_hasColumnInResources() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateColumnPolicy("cp-" + seed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
            assertNotNull(resources);
            assertTrue(resources.containsKey("column"), "column policy must have 'column' in resources");
            assertTrue(resources.containsKey("database"), "column policy must have 'database' in resources");
            assertTrue(resources.containsKey("table"), "column policy must have 'table' in resources");
        }
    }

    // Gap 4: generateColumnPolicy() always uses "select" — the only access type that
    // produces an LF TABLE_WITH_COLUMNS grant; insert/delete on column resources are no-ops in LF.
    @Test
    void generateColumnPolicy_usesSelectOnly() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateColumnPolicy("cp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> accesses = (List<Map<String, Object>>) item.get("accesses");
                assertEquals(1, accesses.size(), "column policy should have exactly one access entry");
                assertEquals("select", accesses.get(0).get("type"),
                        "column policy must use 'select' (the only access type producing an LF grant)");
            }
        }
    }

    // Gap 5: generateAllAccessTablePolicy() uses exactly "all" as the single access type
    @Test
    void generateAllAccessTablePolicy_hasAllAccessType() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateAllAccessTablePolicy("ap-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items);
            assertFalse(items.isEmpty());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accesses = (List<Map<String, Object>>) items.get(0).get("accesses");
            assertEquals(1, accesses.size(), "all-access policy should have exactly one access entry");
            assertEquals("all", accesses.get(0).get("type"),
                    "all-access policy must use 'all' access type");
        }
    }

    // Gap 5: generateAllAccessTablePolicy() has table and database resources
    @Test
    void generateAllAccessTablePolicy_hasTableAndDatabaseResources() {
        Map<String, Object> policy = generator(77).generateAllAccessTablePolicy("ap-77");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("database"));
        assertTrue(resources.containsKey("table"));
        assertFalse(resources.containsKey("column"), "all-access table policy should not have column resource");
    }

    // Gap 6: generateGroupTablePolicy() places principal in "groups", not "users"
    @Test
    void generateGroupTablePolicy_principalIsInGroupsField() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateGroupTablePolicy("gp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items);
            assertFalse(items.isEmpty());
            Map<String, Object> item = items.get(0);
            @SuppressWarnings("unchecked")
            List<String> users = (List<String>) item.get("users");
            @SuppressWarnings("unchecked")
            List<String> groups = (List<String>) item.get("groups");
            assertTrue(users.isEmpty(), "users must be empty in a group policy");
            assertFalse(groups.isEmpty(), "groups must be non-empty in a group policy");
            assertTrue(PRINCIPALS.containsAll(groups),
                    "group principals must come from the principal pool");
        }
    }

    // Gap 6: generateRoleTablePolicy() places principal in "roles", not "users"
    @Test
    void generateRoleTablePolicy_principalIsInRolesField() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateRoleTablePolicy("rp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items);
            assertFalse(items.isEmpty());
            Map<String, Object> item = items.get(0);
            @SuppressWarnings("unchecked")
            List<String> users = (List<String>) item.get("users");
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) item.get("roles");
            assertTrue(users.isEmpty(), "users must be empty in a role policy");
            assertFalse(roles.isEmpty(), "roles must be non-empty in a role policy");
            assertTrue(PRINCIPALS.containsAll(roles),
                    "role principals must come from the principal pool");
        }
    }

    // Gap 2: generateDenyTablePolicy() has entries in denyPolicyItems and empty policyItems
    @Test
    void generateDenyTablePolicy_hasDenyItemsAndEmptyAllowItems() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateDenyTablePolicy("dp-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> policyItems = (List<Map<String, Object>>) policy.get("policyItems");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> denyItems = (List<Map<String, Object>>) policy.get("denyPolicyItems");
            assertNotNull(policyItems, "policyItems must not be null");
            assertNotNull(denyItems, "denyPolicyItems must not be null");
            assertTrue(policyItems.isEmpty(), "allow policyItems must be empty in a deny-only policy");
            assertFalse(denyItems.isEmpty(), "denyPolicyItems must not be empty");
        }
    }

    // Gap 2: generateDenyTablePolicy() has "database" and "table" in resources
    @Test
    void generateDenyTablePolicy_hasTableAndDatabaseResources() {
        Map<String, Object> policy = generator(55).generateDenyTablePolicy("dp-55");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("database"), "deny policy must have 'database'");
        assertTrue(resources.containsKey("table"), "deny policy must have 'table'");
        assertFalse(resources.containsKey("column"), "deny table policy must not have 'column'");
    }

    // Gap 1: generateWildcardTablePolicy() always uses "*" as the table value
    @Test
    void generateWildcardTablePolicy_tableValueIsWildcard() {
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateWildcardTablePolicy("wp-" + seed);
            @SuppressWarnings("unchecked")
            Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
            assertNotNull(resources);
            assertTrue(resources.containsKey("database"), "wildcard policy must have 'database'");
            assertTrue(resources.containsKey("table"), "wildcard policy must have 'table'");
            @SuppressWarnings("unchecked")
            List<String> tableValues = (List<String>) ((Map<String, Object>) resources.get("table")).get("values");
            assertEquals(List.of("*"), tableValues,
                    "wildcard policy table value must be '*'");
        }
    }

    // Gap 1: generateWildcardTablePolicy() does not include "column" in resources
    @Test
    void generateWildcardTablePolicy_hasNoColumnResource() {
        Map<String, Object> policy = generator(42).generateWildcardTablePolicy("wp-42");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertFalse(resources.containsKey("column"),
                "wildcard table policy must not have 'column' in resources");
    }

    // Gap 9: generateUnmappedPrincipalPolicy() uses the unmapped principal name
    @Test
    void generateUnmappedPrincipalPolicy_usesUnmappedPrincipal() {
        String unmapped = HivePolicyGenerator.getUnmappedPrincipal();
        for (int seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generateUnmappedPrincipalPolicy("up-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(items);
            assertFalse(items.isEmpty());
            @SuppressWarnings("unchecked")
            List<String> users = (List<String>) items.get(0).get("users");
            assertEquals(List.of(unmapped), users,
                    "unmapped policy must use the unmapped principal '" + unmapped + "'");
            // The unmapped principal must NOT be in the principal pool
            assertFalse(PRINCIPALS.contains(unmapped),
                    "Unmapped principal '" + unmapped + "' must not be in the standard principal pool");
        }
    }

    // Gap 9: generateUnmappedPrincipalPolicy() has valid table and database resources
    @Test
    void generateUnmappedPrincipalPolicy_hasTableResources() {
        Map<String, Object> policy = generator(55).generateUnmappedPrincipalPolicy("up-55");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("database"));
        assertTrue(resources.containsKey("table"));
    }
}
