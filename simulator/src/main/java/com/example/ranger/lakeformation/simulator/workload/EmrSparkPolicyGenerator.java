package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates Ranger EMR Spark service policy JSON payloads for the simulator workload.
 *
 * EMR Spark (amazon-emr-spark) uses the same database/table/column resource hierarchy
 * as Hive and produces LF permissions (not S3 Access Grants). Access types are a subset
 * of Hive's: select, update (→INSERT), alter, create (→CREATE_TABLE), drop, read, write.
 * "all" maps to the LF ALL action.
 */
public class EmrSparkPolicyGenerator implements PolicyGenerator {

    // Access types registered in EmrSparkServiceAdapter.CATALOG_ACTION_MAPPING.
    // "all" intentionally excluded: it maps to "ALL" which LF ListPermissions returns
    // separately — keep the workload predictable for Phase2 validation.
    private static final List<String> TABLE_ACCESS_TYPES =
            List.of("select", "update", "alter", "create", "drop", "read", "write");
    private static final List<String> COLUMN_ACCESS_TYPES = List.of("select", "read");
    private static final List<String> COLUMN_NAMES =
            List.of("id", "name", "value", "created_at", "status", "amount", "category", "region");

    private final Map<String, List<String>> databaseTables;
    private final List<String> databases;
    private final List<String> principalNames;
    private final String emrSparkServiceName;
    private final Random random;

    public EmrSparkPolicyGenerator(Map<String, List<String>> databaseTables,
                                   List<String> principalNames,
                                   String emrSparkServiceName,
                                   Random random) {
        this.databaseTables = Map.copyOf(databaseTables);
        this.databases = List.copyOf(databaseTables.keySet());
        this.principalNames = List.copyOf(principalNames);
        this.emrSparkServiceName = emrSparkServiceName;
        this.random = random;
    }

    /**
     * Generate a table-level allow policy for a random database+table combination.
     */
    @Override
    public Map<String, Object> generate(String policyId) {
        return generateTablePolicy(policyId);
    }

    /**
     * Generate a table-level allow policy for a random database+table combination.
     */
    public Map<String, Object> generateTablePolicy(String policyId) {
        String db = randomFrom(databases);
        String table = randomTable(db);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(3));
        return buildTablePolicy(policyId, db, table, List.of(user), accesses, false);
    }

    /**
     * Generate a database-scoped allow policy.
     * amazon-emr-spark requires all three resource keys; use wildcard for table and column.
     */
    public Map<String, Object> generateDatabasePolicy(String policyId) {
        String db = randomFrom(databases);
        String user = randomFrom(principalNames);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db), "isExcludes", false));
        resources.put("table",    Map.of("values", List.of("*"), "isExcludes", false));
        resources.put("column",   Map.of("values", List.of("*"), "isExcludes", false));

        return Map.of(
                "name", "sim-emrspark-" + policyId,
                "service", emrSparkServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(List.of(user), List.of("create", "drop"), false)),
                "denyPolicyItems", List.of()
        );
    }

    /**
     * Generate a column-level allow policy.
     */
    public Map<String, Object> generateColumnPolicy(String policyId) {
        String db = randomFrom(databases);
        String table = randomTable(db);
        String user = randomFrom(principalNames);
        List<String> columns = randomSubset(COLUMN_NAMES, 1 + random.nextInt(3));
        List<String> accesses = randomSubset(COLUMN_ACCESS_TYPES, 1 + random.nextInt(2));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db),      "isExcludes", false));
        resources.put("table",    Map.of("values", List.of(table),   "isExcludes", false));
        resources.put("column",   Map.of("values", columns,          "isExcludes", false));

        return Map.of(
                "name", "sim-emrspark-" + policyId,
                "service", emrSparkServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(List.of(user), accesses, false)),
                "denyPolicyItems", List.of()
        );
    }

    /**
     * Generate a table-level policy with a deny item.
     * amazon-emr-spark requires database+table+column; wildcard column covers all columns.
     */
    public Map<String, Object> generateDenyTablePolicy(String policyId) {
        String db = randomFrom(databases);
        String table = randomTable(db);
        String user = randomFrom(principalNames);
        List<String> accesses = randomSubset(TABLE_ACCESS_TYPES, 1 + random.nextInt(2));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db),    "isExcludes", false));
        resources.put("table",    Map.of("values", List.of(table), "isExcludes", false));
        resources.put("column",   Map.of("values", List.of("*"),   "isExcludes", false));

        return Map.of(
                "name", "sim-emrspark-" + policyId,
                "service", emrSparkServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(),
                "denyPolicyItems", List.of(buildItem(List.of(user), accesses, false))
        );
    }

    private Map<String, Object> buildTablePolicy(String policyId, String db, String table,
                                                  List<String> users, List<String> accessTypes,
                                                  boolean delegateAdmin) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("database", Map.of("values", List.of(db),    "isExcludes", false));
        resources.put("table",    Map.of("values", List.of(table), "isExcludes", false));
        resources.put("column",   Map.of("values", List.of("*"),   "isExcludes", false));

        return Map.of(
                "name", "sim-emrspark-" + policyId,
                "service", emrSparkServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(buildItem(users, accessTypes, delegateAdmin)),
                "denyPolicyItems", List.of()
        );
    }

    private Map<String, Object> buildItem(List<String> users, List<String> accessTypes,
                                           boolean delegateAdmin) {
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

    private String randomTable(String db) {
        List<String> tables = databaseTables.getOrDefault(db, List.of());
        return tables.isEmpty() ? "*" : randomFrom(tables);
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
