package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates Ranger Presto service policy JSON payloads for the simulator workload.
 * Presto is the predecessor Ranger service type to Trino: {@code PrestoServiceAdapter} is
 * line-for-line identical to {@code TrinoServiceAdapter} in its action mapping, and
 * {@code ExpectedPermissionsComputer} shares the same access-type table for both
 * ("presto" and "trino" both resolve to the same map — see buildServiceAccessMaps()).
 * Before this generator existed, no workload ever authored a Presto policy, and no Ranger
 * "presto" service instance was even provisioned, so Presto was entirely untested end-to-end.
 *
 * <p>Uses {@code schema} (not {@code database}) as the resource key, matching Trino/Presto's
 * catalog/schema model. Policies are always authored under catalog {@code "hive"} to match the
 * {@code gdcCatalogName} configured for the "presto" service instance in
 * {@code server-config-simulator-test.yaml} — the production {@code PrestoServiceAdapter} skips
 * any policy whose catalog resource does not match the configured GDC catalog name.
 */
public class PrestoServiceGenerator implements PolicyGenerator {
    // Matches the access types declared by the bundled Presto servicedef
    // (conf/ranger-servicedef-presto.json) that PrestoServiceAdapter actually maps to an LF
    // permission. "all"/"read"/"write" are deliberately NOT included here: the bundled Presto
    // servicedef accepts "all" too, but this generator intentionally mirrors the original,
    // narrower Trino vocabulary to keep the two generators' coverage distinct and predictable.
    private static final List<String> PRESTO_ACCESS_TYPES =
        List.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");

    private final Map<String, List<String>> databaseTables;
    private final List<String> databases;
    private final List<String> principalNames;
    private final String prestoServiceName;
    private final Random random;

    public PrestoServiceGenerator(Map<String, List<String>> databaseTables,
                                   List<String> principalNames,
                                   String prestoServiceName,
                                   Random random) {
        this.databaseTables    = Map.copyOf(databaseTables);
        this.databases         = List.copyOf(databaseTables.keySet());
        this.principalNames    = List.copyOf(principalNames);
        this.prestoServiceName = prestoServiceName;
        this.random            = random;
    }

    @Override
    public Map<String, Object> generate(String policyId) {
        String schema = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(schema, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user  = randomFrom(principalNames);
        List<String> accesses = randomSubset(PRESTO_ACCESS_TYPES, 1 + random.nextInt(3));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("catalog", Map.of("values", List.of("hive"), "isExcludes", false));
        resources.put("schema",  Map.of("values", List.of(schema), "isExcludes", false));
        resources.put("table",   Map.of("values", List.of(table),  "isExcludes", false));

        return Map.of(
            "name",            policyId,
            "service",         prestoServiceName,
            "isEnabled",       true,
            "policyType",      0,
            "resources",       resources,
            "policyItems",     List.of(buildItem(user, accesses)),
            "denyPolicyItems", List.of()
        );
    }

    private Map<String, Object> buildItem(String user, List<String> accessTypes) {
        List<Map<String, Object>> accesses = new ArrayList<>();
        for (String type : accessTypes) {
            accesses.add(Map.of("type", type, "isAllowed", true));
        }
        return Map.of(
            "users",         List.of(user),
            "groups",        List.of(),
            "roles",         List.of(),
            "accesses",      accesses,
            "delegateAdmin", false
        );
    }

    private <T> T randomFrom(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private List<String> randomSubset(List<String> list, int count) {
        List<String> copy = new ArrayList<>(list);
        Collections.shuffle(copy, random);
        return List.copyOf(copy.subList(0, Math.min(count, copy.size())));
    }
}
