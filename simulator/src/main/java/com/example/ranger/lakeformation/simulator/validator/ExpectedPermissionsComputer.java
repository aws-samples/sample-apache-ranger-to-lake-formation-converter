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

    private static final Map<String, Set<String>> ACCESS_MAP = buildAccessMap();

    private final Map<String, String> principalMap;  // Ranger name → IAM ARN
    private final TableExpander tableExpander;

    public ExpectedPermissionsComputer(Map<String, String> principalMap, TableExpander tableExpander) {
        this.principalMap = Map.copyOf(principalMap);
        this.tableExpander = tableExpander;
    }

    /**
     * Compute expected permissions from a list of raw Ranger policies.
     *
     * @param policies array of Ranger policy JSON nodes (from Ranger REST API)
     * @return flat set of expected SimulatorPermissions
     */
    public Set<SimulatorPermission> compute(List<JsonNode> policies) {
        Set<SimulatorPermission> result = new HashSet<>();
        for (JsonNode policy : policies) {
            processPolicyInto(policy, result);
        }
        return result;
    }

    private void processPolicyInto(JsonNode policy, Set<SimulatorPermission> result) {
        // 1. Skip disabled
        if (!policy.path("isEnabled").asBoolean(true)) {
            LOG.debug("Skipping disabled policy {}", policy.path("id").asText());
            return;
        }
        // 2. Skip tag-based (service name contains "tag")
        String serviceName = policy.path("service").asText("");
        if (serviceName.toLowerCase(Locale.ROOT).contains("tag")) {
            LOG.debug("Skipping tag-based policy {}", policy.path("id").asText());
            return;
        }
        // 3. Skip data masking
        if (policy.path("policyType").asInt(0) == 1) {
            LOG.debug("Skipping data masking policy {}", policy.path("id").asText());
            return;
        }
        // 4. Determine resource context
        JsonNode resources = policy.path("resources");
        if (resources.isMissingNode() || resources.isEmpty()) {
            LOG.debug("Skipping policy with no resources {}", policy.path("id").asText());
            return;
        }
        // 5. Process only allow items (not deny items)
        JsonNode policyItems = policy.path("policyItems");
        if (!policyItems.isArray()) return;
        for (JsonNode item : policyItems) {
            processItemInto(item, resources, result);
        }
    }

    private void processItemInto(JsonNode item, JsonNode resources, Set<SimulatorPermission> result) {
        // Resolve permissions from accesses
        Set<String> permissions = extractPermissions(item.path("accesses"));
        if (permissions.isEmpty()) return;

        // Resolve principals
        List<String> arns = resolvePrincipals(item);
        if (arns.isEmpty()) return;

        boolean grantable = item.path("delegateAdmin").asBoolean(false);

        // Determine resource type and IDs
        List<ResourceSpec> specs = extractResourceSpecs(resources, permissions);
        for (ResourceSpec spec : specs) {
            Set<String> finalPerms = spec.isColumn() ? withoutDescribe(permissions) : permissions;
            if (finalPerms.isEmpty()) continue;
            for (String arn : arns) {
                for (String perm : finalPerms) {
                    result.add(new SimulatorPermission(arn, spec.resourceType(), spec.resourceId(), perm, grantable));
                }
            }
        }
    }

    private Set<String> extractPermissions(JsonNode accesses) {
        Set<String> result = new LinkedHashSet<>();
        if (!accesses.isArray()) return result;
        for (JsonNode access : accesses) {
            if (!access.path("isAllowed").asBoolean(true)) continue;
            String type = access.path("type").asText("").toLowerCase(Locale.ROOT).trim();
            Set<String> mapped = ACCESS_MAP.get(type);
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
        // Database names
        List<String> databases = resourceValues(resources, "database");
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
                    specs.add(new ResourceSpec("TABLE", db + "." + table, hasColumn));
                }
            }
        }
        return specs;
    }

    private List<String> resolveTablePattern(String db, String pattern) {
        if ("*".equals(pattern)) {
            return List.of("*");  // bare wildcard — don't expand, use db.*
        }
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

    private static Map<String, Set<String>> buildAccessMap() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("select",               Set.of("SELECT"));
        m.put("insert",               Set.of("INSERT"));
        m.put("delete",               Set.of("DELETE"));
        m.put("describe",             Set.of("DESCRIBE"));
        m.put("alter",                Set.of("ALTER"));
        m.put("drop",                 Set.of("DROP"));
        m.put("create_database",      Set.of("CREATE_DATABASE"));
        m.put("create_table",         Set.of("CREATE_TABLE"));
        m.put("update",               Set.of("INSERT"));           // legacy alias
        m.put("create",               Set.of("CREATE_TABLE"));     // legacy alias
        m.put("read",                 Set.of("SELECT"));           // legacy alias
        m.put("write",                Set.of("INSERT"));           // legacy alias
        m.put("all",                  Set.of("SELECT", "INSERT", "DELETE", "ALTER", "DROP", "DESCRIBE"));
        m.put("datalocation",         Set.of("DATA_LOCATION_ACCESS"));
        m.put("data_location_access", Set.of("DATA_LOCATION_ACCESS"));
        return Collections.unmodifiableMap(m);
    }
}
