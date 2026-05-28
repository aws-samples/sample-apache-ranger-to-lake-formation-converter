package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

public class TrinoServiceGenerator implements PolicyGenerator {
    private static final List<String> TRINO_ACCESS_TYPES =
        List.of("select", "insert", "delete", "create", "drop", "alter", "use", "show");

    private final Map<String, List<String>> databaseTables;
    private final List<String> databases;
    private final List<String> principalNames;
    private final String trinoServiceName;
    private final Random random;

    public TrinoServiceGenerator(Map<String, List<String>> databaseTables,
                                  List<String> principalNames,
                                  String trinoServiceName,
                                  Random random) {
        this.databaseTables   = Map.copyOf(databaseTables);
        this.databases        = List.copyOf(databaseTables.keySet());
        this.principalNames   = List.copyOf(principalNames);
        this.trinoServiceName = trinoServiceName;
        this.random           = random;
    }

    @Override
    public Map<String, Object> generate(String policyId) {
        String schema = randomFrom(databases);
        List<String> tables = databaseTables.getOrDefault(schema, List.of());
        String table = tables.isEmpty() ? "*" : randomFrom(tables);
        String user  = randomFrom(principalNames);
        List<String> accesses = randomSubset(TRINO_ACCESS_TYPES, 1 + random.nextInt(3));

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("schema", Map.of("values", List.of(schema), "isExcludes", false));
        resources.put("table",  Map.of("values", List.of(table),  "isExcludes", false));

        List<Object> denyItems = random.nextInt(100) < 20
            ? List.of(buildItem(user, randomSubset(accesses, 1), false))
            : List.of();

        return Map.of(
            "name",            policyId,
            "service",         trinoServiceName,
            "isEnabled",       true,
            "policyType",      0,
            "resources",       resources,
            "policyItems",     List.of(buildItem(user, accesses, false)),
            "denyPolicyItems", denyItems
        );
    }

    private Map<String, Object> buildItem(String user, List<String> accessTypes,
                                           boolean delegateAdmin) {
        List<Map<String, Object>> accesses = new ArrayList<>();
        for (String type : accessTypes) {
            accesses.add(Map.of("type", type, "isAllowed", true));
        }
        return Map.of(
            "users",         List.of(user),
            "groups",        List.of(),
            "roles",         List.of(),
            "accesses",      accesses,
            "delegateAdmin", delegateAdmin
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
