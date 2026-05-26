package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates Ranger Hive service policy JSON payloads for the simulator workload.
 * Produces policies that cover database, table, and column levels.
 */
public class HivePolicyGenerator {
    private static final List<String> DEFAULT_DATABASES = List.of("default", "analytics", "staging");
    private static final List<String> DEFAULT_ACCESS_TYPES = List.of("select", "insert", "delete", "describe");
    private static final Random DEFAULT_RANDOM = new Random();

    private final List<String> databases;
    private final List<String> tablesPerDb;   // table names to use
    private final List<String> principalNames; // Ranger user names (not ARNs)
    private final Random random;
    private final String hiveServiceName;      // e.g. "hive" or "cm_hive"

    public HivePolicyGenerator(List<String> databases, List<String> tablesPerDb,
                               List<String> principalNames, String hiveServiceName, Random random) {
        this.databases = List.copyOf(databases);
        this.tablesPerDb = List.copyOf(tablesPerDb);
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
        String table = randomFrom(tablesPerDb);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(DEFAULT_ACCESS_TYPES, 1 + random.nextInt(3));

        return buildPolicy(policyId, db, table, null, user, accesses, false);
    }

    /**
     * Generate a table-level allow policy with delegateAdmin=true (grantable).
     */
    public Map<String, Object> generateGrantableTablePolicy(String policyId) {
        String db = randomFrom(databases);
        String table = randomFrom(tablesPerDb);
        String user = randomFrom(principalNames);

        return buildPolicy(policyId, db, table, null, user, List.of("select"), true);
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
                "id", policyId,
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(user, List.of("create_table", "drop"), false)),
                "denyPolicyItems", List.of()
        );
    }

    private Map<String, Object> buildPolicy(String policyId, String db, String table, List<String> columns,
                                             String user, List<String> accessTypes, boolean delegateAdmin) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));
        resources.put("table", Map.of("values", List.of(table), "isExcludes", false));
        if (columns != null && !columns.isEmpty()) {
            resources.put("column", Map.of("values", columns, "isExcludes", false));
        }

        return Map.of(
                "id", policyId,
                "name", "sim-policy-" + policyId,
                "service", hiveServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(user, accessTypes, delegateAdmin)),
                "denyPolicyItems", List.of()
        );
    }

    private Map<String, Object> buildItem(String user, List<String> accessTypes, boolean delegateAdmin) {
        List<Map<String, Object>> accesses = accessTypes.stream()
                .map(a -> Map.<String, Object>of("type", a, "isAllowed", true))
                .toList();
        return Map.of(
                "users", List.of(user),
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
