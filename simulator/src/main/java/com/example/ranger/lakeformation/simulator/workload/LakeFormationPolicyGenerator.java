package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates Ranger LakeFormation service policy JSON payloads for the simulator workload.
 *
 * <p>Emits the native LakeFormation access-type vocabulary
 * ({@code select, insert, delete, describe, alter, drop} at table level;
 * {@code create_table, drop} at database level). This is distinct from
 * {@link HivePolicyGenerator}, which emits the Hive vocabulary
 * ({@code select, update, create, read, write, ...}) that the HiveServiceAdapter
 * maps to LF actions. Keeping the two generators separate lets the simulator
 * exercise the LakeFormation adapter and the Hive adapter independently.
 */
public class LakeFormationPolicyGenerator {
    // Native LakeFormation Ranger service access types (verified against ranger-servicedef-lakeformation.json).
    private static final List<String> TABLE_ACCESS_TYPES =
            List.of("select", "insert", "delete", "describe", "alter", "drop");
    // Column-level policies: only "select" produces an LF grant (TABLE_WITH_COLUMNS SELECT).
    // insert/delete on a column resource are accepted by Ranger but produce no LF permission.
    private static final List<String> COLUMN_ACCESS_TYPES = List.of("select");
    private static final List<String> COLUMN_NAMES =
            List.of("id", "name", "value", "created_at", "status", "amount", "category", "region");
    private static final String UNMAPPED_PRINCIPAL = "ghost_user";

    private final Map<String, List<String>> databaseTables;  // db name → table names in that db
    private final List<String> databases;                     // ordered key list for random selection
    private final List<String> principalNames;                // Ranger user names (not ARNs)
    private final Random random;
    private final String lfServiceName;                       // the LakeFormation Ranger service instance name

    public LakeFormationPolicyGenerator(Map<String, List<String>> databaseTables,
                                        List<String> principalNames, String lfServiceName, Random random) {
        this.databaseTables = Map.copyOf(databaseTables);
        this.databases = List.copyOf(databaseTables.keySet());
        this.principalNames = List.copyOf(principalNames);
        this.random = random;
        this.lfServiceName = lfServiceName;
    }

    /** Generate a table-level allow policy for a random database+table combination. */
    public Map<String, Object> generateTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, null, List.of(user), accesses, false);
    }

    /** Generate a table-level allow policy with delegateAdmin=true (grantable). */
    public Map<String, Object> generateGrantableTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);

        return buildPolicy(policyId, db, table, null, List.of(user), List.of("select"), true);
    }

    /** Generate a database-level allow policy using LF vocabulary (create_table, drop). */
    public Map<String, Object> generateDatabasePolicy(String policyId) {
        String db = randomFrom(databases);
        String user = randomFrom(principalNames);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));

        return Map.of(
                "name", "sim-policy-" + policyId,
                "service", lfServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(List.of(user), List.of("create_table", "drop"), false)),
                "denyPolicyItems", List.of()
        );
    }

    /** Generate a table-level policy with 2-3 users in a single policyItem. */
    public Map<String, Object> generateMultiUserTablePolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        int userCount = 2 + random.nextInt(Math.min(2, principalNames.size() - 1));
        List<String> users = randomSubset(principalNames, userCount);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, null, users, accesses, false);
    }

    /** Generate a column-level allow policy (TABLE_WITH_COLUMNS SELECT). */
    public Map<String, Object> generateColumnPolicy(String policyId) {
        String db = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(COLUMN_ACCESS_TYPES, 1);
        List<String> columns = randomSubset(COLUMN_NAMES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, columns, List.of(user), accesses, false);
    }

    /** Generate a deny-only table-level policy (entries in denyPolicyItems, empty policyItems). */
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
                "service", lfServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(),
                "denyPolicyItems", List.of(buildItem(List.of(user), accesses, false))
        );
    }

    /** Generate a table-level policy with an unmapped principal (zero LF grants + gap entry). */
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
                "service", lfServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(users, accessTypes, delegateAdmin)),
                "denyPolicyItems", List.of()
        );
    }

    private Map<String, Object> buildItem(List<String> users, List<String> accessTypes, boolean delegateAdmin) {
        List<Map<String, Object>> accesses = accessTypes.stream()
                .map(a -> Map.<String, Object>of("type", a, "isAllowed", true))
                .toList();
        return Map.of(
                "users", users,
                "groups", List.of(),
                "roles", List.of(),
                "accesses", accesses,
                "delegateAdmin", delegateAdmin
        );
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
