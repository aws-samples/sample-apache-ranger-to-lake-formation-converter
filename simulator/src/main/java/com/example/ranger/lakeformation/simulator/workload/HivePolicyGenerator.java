package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates Ranger Hive service policy JSON payloads for the simulator workload.
 * Produces policies that cover database, table, and column levels.
 */
public class HivePolicyGenerator {
    // Native Hive Ranger service access types (verified against ranger-servicedef-hive.json).
    // Hive vocabulary differs from LakeFormation: it has no insert/delete/describe. The
    // HiveServiceAdapter maps these to LF actions (select->SELECT, update->INSERT,
    // create->CREATE_TABLE, drop->DROP, alter->ALTER, read->SELECT, write->INSERT).
    private static final List<String> TABLE_ACCESS_TYPES =
            List.of("select", "update", "create", "drop", "alter", "read", "write");
    // Column-level policies: only "select" produces an LF grant (TABLE_WITH_COLUMNS SELECT).
    // Other access types on a column resource are accepted by Ranger but produce no LF permission.
    private static final List<String> COLUMN_ACCESS_TYPES = List.of("select");
    private static final List<String> COLUMN_NAMES =
            List.of("id", "name", "value", "created_at", "status", "amount", "category", "region");
    private static final String UNMAPPED_PRINCIPAL = "ghost_user";

    private final Map<String, List<String>> databaseTables;  // db name → table names in that db
    private final List<String> databases;                     // ordered key list for random selection
    private final List<String> principalNames;                // Ranger user names (not ARNs)
    private final Random random;
    private final String hiveServiceName;                     // the Hive Ranger service instance name

    public HivePolicyGenerator(Map<String, List<String>> databaseTables,
                               List<String> principalNames, String hiveServiceName, Random random) {
        this.databaseTables = Map.copyOf(databaseTables);
        this.databases = List.copyOf(databaseTables.keySet());
        this.principalNames = List.copyOf(principalNames);
        this.random = random;
        this.hiveServiceName = hiveServiceName;
    }

    /**
     * Generate a table-level allow policy for a random database+table combination.
     * @param policyId a unique policy ID string (assigned externally)
     */
    public Map<String, Object> generateTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, null, List.of(user), accesses, false);
    }

    /**
     * Generate a table-level allow policy with delegateAdmin=true (grantable).
     */
    public Map<String, Object> generateGrantableTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);

        return buildPolicy(policyId, db, table, null, List.of(user), List.of("select"), true);
    }

    /**
     * Generate a database-level allow policy.
     */
    public Map<String, Object> generateDatabasePolicy(String policyId) {
        String db = randomFrom(databases);
        String user = randomFrom(principalNames);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));

        return Map.of(
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(List.of(user), List.of("create", "drop"), false)),
                "denyPolicyItems", List.of()
        );
    }

    /**
     * Gap 2: Generate a table-level policy with 2-3 users in a single policyItem.
     * Exercises the multi-user path: the sync service must produce one LF grant per user.
     */
    public Map<String, Object> generateMultiUserTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        int userCount = 2 + random.nextInt(Math.min(2, principalNames.size() - 1));
        List<String> users = randomSubset(principalNames, userCount);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, null, users, accesses, false);
    }

    /**
     * Gap 4: Generate a column-level allow policy.
     * Exercises TABLE_WITH_COLUMNS handling: DESCRIBE must be stripped from LF grants.
     */
    public Map<String, Object> generateColumnPolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(COLUMN_ACCESS_TYPES, 1 + random.nextInt(2));
        List<String> columns = randomSubset(COLUMN_NAMES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, columns, List.of(user), accesses, false);
    }

    /**
     * Gap 6: Generate a table-level allow policy with a group principal.
     * The sync service must resolve the group name via principalMappings the same way it resolves users.
     */
    public Map<String, Object> generateGroupTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String group = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));
        resources.put("table", Map.of("values", List.of(table), "isExcludes", false));

        return Map.of(
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildGroupItem(List.of(group), accesses)),
                "denyPolicyItems", List.of()
        );
    }

    /**
     * Gap 6: Generate a table-level allow policy with a role principal.
     * The sync service must resolve the role name via principalMappings the same way it resolves users.
     */
    public Map<String, Object> generateRoleTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String role = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));
        resources.put("table", Map.of("values", List.of(table), "isExcludes", false));

        return Map.of(
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildRoleItem(List.of(role), accesses)),
                "denyPolicyItems", List.of()
        );
    }

    /**
     * Gap 2: Generate a deny-only table-level policy (entries in denyPolicyItems, empty policyItems).
     * The sync service converts denyPolicyItems to Cedar forbid statements; net LF grants for the
     * denied (principal, resource) combination must be zero.
     */
    public Map<String, Object> generateDenyTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));
        resources.put("table", Map.of("values", List.of(table), "isExcludes", false));

        return Map.of(
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(),
                "denyPolicyItems", List.of(buildItem(List.of(user), accesses, false))
        );
    }

    /**
     * Generate a wildcard table policy covering all tables in a random database ("*" as table value).
     * Exercises wildcard expansion: the sync service must expand "*" to all known tables via CatalogResolver.
     */
    public Map<String, Object> generateWildcardTablePolicy(String policyId) {
        String db = randomFrom(databases);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, "*", null, List.of(user), accesses, false);
    }

    /**
     * Gap 5: Generate a table-level policy using "all" access type.
     * "all" expands to SELECT, INSERT, DELETE, ALTER, DROP, DESCRIBE in LF.
     */
    public Map<String, Object> generateAllAccessTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);

        return buildPolicy(policyId, db, table, null, List.of(user), List.of("all"), false);
    }

    /**
     * Gap 9: Generate a table-level policy with an unmapped principal.
     * The sync service must produce zero LF grants and record a gap entry — no exception.
     */
    public Map<String, Object> generateUnmappedPrincipalPolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, null, List.of(UNMAPPED_PRINCIPAL), accesses, false);
    }

    /** The unmapped principal name used by {@link #generateUnmappedPrincipalPolicy}. */
    public static String getUnmappedPrincipal() {
        return UNMAPPED_PRINCIPAL;
    }

    private Map<String, Object> buildPolicy(String policyId, String db, String table, List<String> columns,
                                             List<String> users, List<String> accessTypes, boolean delegateAdmin) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));
        resources.put("table", Map.of("values", List.of(table), "isExcludes", false));
        if (columns != null && !columns.isEmpty()) {
            resources.put("column", Map.of("values", columns, "isExcludes", false));
        }

        return Map.of(
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(users, accessTypes, delegateAdmin)),
                "denyPolicyItems", List.of()
        );
    }

    private Map<String, Object> buildItem(List<String> users, List<String> accessTypes, boolean delegateAdmin) {
        List<Map<String, Object>> accesses = buildAccesses(accessTypes);
        return Map.of(
                "users", users,
                "groups", List.of(),
                "roles", List.of(),
                "accesses", accesses,
                "delegateAdmin", delegateAdmin
        );
    }

    private Map<String, Object> buildGroupItem(List<String> groups, List<String> accessTypes) {
        List<Map<String, Object>> accesses = buildAccesses(accessTypes);
        return Map.of(
                "users", List.of(),
                "groups", groups,
                "roles", List.of(),
                "accesses", accesses,
                "delegateAdmin", false
        );
    }

    private Map<String, Object> buildRoleItem(List<String> roles, List<String> accessTypes) {
        List<Map<String, Object>> accesses = buildAccesses(accessTypes);
        return Map.of(
                "users", List.of(),
                "groups", List.of(),
                "roles", roles,
                "accesses", accesses,
                "delegateAdmin", false
        );
    }

    private List<Map<String, Object>> buildAccesses(List<String> accessTypes) {
        return accessTypes.stream()
                .map(a -> Map.<String, Object>of("type", a, "isAllowed", true))
                .toList();
    }

    private <T> T randomFrom(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private List<String> randomSubset(List<String> list, int count) {
        List<String> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }
}
