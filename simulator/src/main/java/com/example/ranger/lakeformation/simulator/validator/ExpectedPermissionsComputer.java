package com.example.ranger.lakeformation.simulator.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Independent reimplementation of the Ranger→LF permission mapping.
 * MUST NOT import any class from com.amazonaws.policyconverters.
 * Works from raw Ranger policy JSON (JsonNode).
 */
public class ExpectedPermissionsComputer {
    private static final Logger LOG = LoggerFactory.getLogger(ExpectedPermissionsComputer.class);

    private static final Map<String, Map<String, Set<String>>> SERVICE_ACCESS_MAPS = buildServiceAccessMaps();

    private final Map<String, String> principalMap;  // Ranger name → IAM ARN
    private final TableExpander tableExpander;
    // When non-null, only policies whose service name is in this set are processed.
    private final Set<String> managedServiceNames;

    public ExpectedPermissionsComputer(Map<String, String> principalMap, TableExpander tableExpander) {
        this(principalMap, tableExpander, null);
    }

    public ExpectedPermissionsComputer(Map<String, String> principalMap, TableExpander tableExpander,
                                       Set<String> managedServiceNames) {
        this.principalMap = Map.copyOf(principalMap);
        this.tableExpander = tableExpander;
        this.managedServiceNames = managedServiceNames != null ? Set.copyOf(managedServiceNames) : null;
    }

    /**
     * Compute expected permissions from a list of raw Ranger policies.
     * Two-pass: first collect all deny triples globally, then collect permits
     * and subtract those matching any deny.
     *
     * @param policies array of Ranger policy JSON nodes (from Ranger REST API)
     * @return flat set of expected SimulatorPermissions
     */
    public Set<SimulatorPermission> compute(List<JsonNode> policies) {
        // Pass 1: collect all deny keys across all services
        Set<DenyKey> globalDenySet = new HashSet<>();
        for (JsonNode policy : policies) {
            if (!shouldProcess(policy)) continue;
            collectDenies(policy, globalDenySet);
        }

        // Pass 2: collect all permit entries
        Set<SimulatorPermission> permits = new HashSet<>();
        for (JsonNode policy : policies) {
            if (!shouldProcess(policy)) continue;
            processPermitsInto(policy, permits);
        }

        // Subtract any permit that is matched by a deny
        permits.removeIf(p ->
            globalDenySet.contains(new DenyKey(p.principalArn(), p.resourceId(), p.permission())));

        return permits;
    }

    private boolean shouldProcess(JsonNode policy) {
        if (!policy.path("isEnabled").asBoolean(true)) return false;
        String svc = policy.path("service").asText("").toLowerCase(Locale.ROOT);
        if (svc.contains("tag")) return false;
        if (managedServiceNames != null && !managedServiceNames.contains(svc)) return false;
        if (policy.path("policyType").asInt(0) == 1) return false;
        JsonNode resources = policy.path("resources");
        if (resources.isMissingNode() || resources.isEmpty()) return false;
        return true;
    }

    private void processPermitsInto(JsonNode policy, Set<SimulatorPermission> result) {
        String serviceName = policy.path("service").asText(null);
        Map<String, Set<String>> accessMap = accessMapForService(serviceName);
        JsonNode resources = policy.path("resources");
        for (JsonNode item : policy.path("policyItems")) {
            processItemInto(item, resources, result, accessMap);
        }
    }

    private void processItemInto(JsonNode item, JsonNode resources,
                                  Set<SimulatorPermission> result,
                                  Map<String, Set<String>> accessMap) {
        // Resolve permissions from accesses
        Set<String> permissions = extractPermissions(item.path("accesses"), accessMap);
        if (permissions.isEmpty()) return;

        // Resolve principals
        List<String> arns = resolvePrincipals(item);
        if (arns.isEmpty()) return;

        boolean grantable = item.path("delegateAdmin").asBoolean(false);

        // Determine resource type and IDs
        List<ResourceSpec> specs = extractResourceSpecs(resources, permissions);
        for (ResourceSpec spec : specs) {
            Set<String> finalPerms;
            if (spec.isColumn()) {
                // LF TABLE_WITH_COLUMNS only supports SELECT — strip everything else.
                // INSERT/DELETE/ALTER etc. on a column resource are ignored by the sync service.
                finalPerms = permissions.contains("SELECT") ? Set.of("SELECT") : Set.of();
            } else {
                finalPerms = permissions;
            }
            if (finalPerms.isEmpty()) continue;
            for (String arn : arns) {
                // LF's ListPermissions API returns bare-table SELECT as TableWithColumns (cols=None).
                // Mirror that in the expected set so the comparison matches LF actual output.
                // LFPermissionsFetcher normalizes TableWithColumns/cols=None → TABLE_WITH_COLUMNS.
                for (String perm : finalPerms) {
                    String resourceType = spec.resourceType();
                    if ("TABLE".equals(resourceType) && "SELECT".equals(perm)) {
                        resourceType = "TABLE_WITH_COLUMNS";
                    }
                    result.add(new SimulatorPermission(arn, resourceType, spec.resourceId(), perm, grantable));
                }
            }
        }
    }

    private Set<String> extractPermissions(JsonNode accesses, Map<String, Set<String>> accessMap) {
        Set<String> result = new LinkedHashSet<>();
        if (!accesses.isArray()) return result;
        for (JsonNode access : accesses) {
            if (!access.path("isAllowed").asBoolean(true)) continue;
            String type = access.path("type").asText("").toLowerCase(Locale.ROOT).trim();
            Set<String> mapped = accessMap.get(type);
            if (mapped != null) result.addAll(mapped);
        }
        return result;
    }

    private List<String> resolvePrincipals(JsonNode item) {
        List<String> arns = new ArrayList<>();
        addMapped(item.path("users"), arns);
        addMapped(item.path("groups"), arns);
        addMapped(item.path("roles"), arns);
        return arns;
    }

    private void addMapped(JsonNode names, List<String> arns) {
        if (!names.isArray()) return;
        for (JsonNode n : names) {
            String name = n.asText();
            String arn = principalMap.get(name);
            if (arn != null) arns.add(arn);
        }
    }

    private List<ResourceSpec> extractResourceSpecs(JsonNode resources, Set<String> permissions) {
        List<ResourceSpec> specs = new ArrayList<>();
        // Data location
        if (hasNonEmptyResource(resources, "datalocation")) {
            for (String path : resourceValues(resources, "datalocation")) {
                specs.add(new ResourceSpec("DATA_LOCATION", path, false));
            }
            return specs;
        }
        // Database names — also check "schema" key (used by Trino)
        List<String> databases = resourceValues(resources, "database");
        if (databases.isEmpty()) {
            databases = resourceValues(resources, "schema"); // Trino uses "schema" not "database"
        }
        if (databases.isEmpty()) return specs;

        boolean hasTable = hasNonEmptyResource(resources, "table");
        boolean hasColumn = hasNonEmptyResource(resources, "column");

        if (!hasTable) {
            // Database-level
            for (String db : databases) {
                specs.add(new ResourceSpec("DATABASE", db, false));
            }
            return specs;
        }

        // Table-level (with or without columns)
        List<String> tablePatterns = resourceValues(resources, "table");
        for (String db : databases) {
            for (String tablePattern : tablePatterns) {
                List<String> tables = resolveTablePattern(db, tablePattern);
                for (String table : tables) {
                    specs.add(new ResourceSpec(hasColumn ? "TABLE_WITH_COLUMNS" : "TABLE",
                            db + "." + table, hasColumn));
                }
            }
        }
        return specs;
    }

    private List<String> resolveTablePattern(String db, String pattern) {
        if (pattern.contains("*") || pattern.contains("?")) {
            return tableExpander.expand(db, pattern);
        }
        return List.of(pattern);
    }

    private boolean hasNonEmptyResource(JsonNode resources, String key) {
        JsonNode r = resources.path(key);
        if (r.isMissingNode()) return false;
        JsonNode values = r.path("values");
        return values.isArray() && !values.isEmpty();
    }

    private List<String> resourceValues(JsonNode resources, String key) {
        JsonNode r = resources.path(key);
        if (r.isMissingNode()) return List.of();
        JsonNode values = r.path("values");
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode v : values) result.add(v.asText());
        return result;
    }

    private Set<String> withoutDescribe(Set<String> perms) {
        Set<String> copy = new LinkedHashSet<>(perms);
        copy.remove("DESCRIBE");
        return copy;
    }

    private record ResourceSpec(String resourceType, String resourceId, boolean isColumn) {}

    private record DenyKey(String principalArn, String resourceId, String permission) {}

    private void collectDenies(JsonNode policy, Set<DenyKey> denySet) {
        String serviceName = policy.path("service").asText(null);
        Map<String, Set<String>> accessMap = accessMapForService(serviceName);
        JsonNode resources = policy.path("resources");

        for (JsonNode item : policy.path("denyPolicyItems")) {
            Set<String> lfPermissions = new HashSet<>();
            for (JsonNode acc : item.path("accesses")) {
                if (!acc.path("isAllowed").asBoolean(true)) continue;
                String type = acc.path("type").asText("").toLowerCase(Locale.ROOT);
                Set<String> mapped = accessMap.get(type);
                if (mapped != null) lfPermissions.addAll(mapped);
            }
            if (lfPermissions.isEmpty()) continue;

            List<ResourceSpec> specs = extractResourceSpecs(resources, lfPermissions);
            for (String principalArn : resolvePrincipals(item)) {
                for (ResourceSpec spec : specs) {
                    for (String permission : lfPermissions) {
                        denySet.add(new DenyKey(principalArn, spec.resourceId(), permission));
                    }
                }
            }
        }
    }

    private Map<String, Set<String>> accessMapForService(String serviceName) {
        String key = (serviceName == null || serviceName.isEmpty())
                     ? "lakeformation"
                     : serviceName.toLowerCase(Locale.ROOT);
        Map<String, Set<String>> map = SERVICE_ACCESS_MAPS.get(key);
        if (map == null) {
            LOG.warn("Unknown service name '{}'; falling back to lakeformation access map", serviceName);
            return SERVICE_ACCESS_MAPS.get("lakeformation");
        }
        return map;
    }

    private static Map<String, Map<String, Set<String>>> buildServiceAccessMaps() {
        Map<String, Set<String>> lfMap = new HashMap<>();
        lfMap.put("select",               Set.of("SELECT"));
        lfMap.put("insert",               Set.of("INSERT"));
        lfMap.put("delete",               Set.of("DELETE"));
        lfMap.put("describe",             Set.of("DESCRIBE"));
        lfMap.put("alter",                Set.of("ALTER"));
        lfMap.put("drop",                 Set.of("DROP"));
        lfMap.put("create_database",      Set.of("CREATE_DATABASE"));
        lfMap.put("create_table",         Set.of("CREATE_TABLE"));
        lfMap.put("update",               Set.of("INSERT"));
        lfMap.put("create",               Set.of("CREATE_TABLE"));
        lfMap.put("read",                 Set.of("SELECT"));
        lfMap.put("write",                Set.of("INSERT"));
        lfMap.put("all",                  Set.of("SELECT", "INSERT", "DELETE", "ALTER", "DROP", "DESCRIBE"));
        lfMap.put("datalocation",         Set.of("DATA_LOCATION_ACCESS"));
        lfMap.put("data_location_access", Set.of("DATA_LOCATION_ACCESS"));

        Map<String, Set<String>> hiveMap = new HashMap<>();
        hiveMap.put("select", Set.of("SELECT"));
        hiveMap.put("update", Set.of("INSERT"));
        hiveMap.put("create", Set.of("CREATE_TABLE"));
        hiveMap.put("drop",   Set.of("DROP"));
        hiveMap.put("alter",  Set.of("ALTER"));
        hiveMap.put("read",   Set.of("SELECT"));
        hiveMap.put("write",  Set.of("INSERT"));
        // "all" intentionally absent: HiveServiceAdapter maps it to "SUPER" which is not an LF permission → zero grants
        // "describe" intentionally absent: not a registered access type in the Hive Ranger service definition
        // "insert" intentionally absent: Hive uses "write" for insert semantics; "insert" is not a Hive access type

        Map<String, Set<String>> trinoMap = new HashMap<>();
        trinoMap.put("select", Set.of("SELECT"));
        trinoMap.put("insert", Set.of("INSERT"));
        trinoMap.put("delete", Set.of("DELETE"));
        trinoMap.put("create", Set.of("CREATE_TABLE"));
        trinoMap.put("drop",   Set.of("DROP"));
        trinoMap.put("alter",  Set.of("ALTER"));
        trinoMap.put("use",    Set.of("DESCRIBE"));
        trinoMap.put("show",   Set.of("DESCRIBE"));

        Map<String, Map<String, Set<String>>> result = new HashMap<>();
        result.put("lakeformation", Collections.unmodifiableMap(lfMap));
        result.put("hive",          Collections.unmodifiableMap(hiveMap));
        result.put("trino",         Collections.unmodifiableMap(trinoMap));
        return Collections.unmodifiableMap(result);
    }
}
