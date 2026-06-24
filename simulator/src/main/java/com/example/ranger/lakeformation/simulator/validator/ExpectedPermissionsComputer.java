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
     *
     * <p>Three-pass algorithm:
     * <ol>
     *   <li>Collect all deny keys globally.</li>
     *   <li>Collect all permit entries.</li>
     *   <li>Apply TABLE/TWC conflict resolution: for each (principal, db.table) that has both
     *       a TABLE grant and a TWC grant, the policy with the lower numeric ID wins and the
     *       loser's permissions are excluded — mirroring {@code SyncService.detectAndGapTableTwcConflicts}.
     *       LF rejects any TABLE and TABLE_WITH_COLUMNS grant coexistence for the same principal
     *       and table, regardless of which permissions are involved.</li>
     * </ol>
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

        // Pass 2: collect all permit entries, tagging each with its source policy id
        Set<SimulatorPermission> permits = new HashSet<>();
        Map<SimulatorPermission, Long> permToMinPolicyId = new HashMap<>();
        for (JsonNode policy : policies) {
            if (!shouldProcess(policy)) continue;
            long policyId = policy.path("id").asLong(Long.MAX_VALUE);
            Set<SimulatorPermission> policyPerms = new HashSet<>();
            processPermitsInto(policy, policyPerms);
            for (SimulatorPermission p : policyPerms) {
                permits.add(p);
                permToMinPolicyId.merge(p, policyId, Math::min);
            }
        }

        // Subtract any permit that is matched by a deny (exact match or ancestor resource).
        // Cedar hierarchy: a database-level deny suppresses table/column permits in that database;
        // a table-level deny suppresses column permits for that table.
        // In the simulator's resource model: database="db", table/column="db.table".
        // Ancestor check: deny resourceId "db" covers permit resourceId "db.anything".
        permits.removeIf(p -> {
            String principal = p.principalArn();
            String perm = p.permission();
            String rid = p.resourceId();
            for (DenyKey dk : globalDenySet) {
                if (!dk.principalArn().equals(principal)) continue;
                if (!dk.permission().equals(perm)) continue;
                if (dk.resourceId().equals(rid)) return true;
                // Ancestor: deny on "db" covers permits on "db.table"
                if (rid.startsWith(dk.resourceId() + ".")) return true;
            }
            return false;
        });

        // Pass 3: TABLE/TWC conflict resolution (mirrors SyncService.detectAndGapTableTwcConflicts).
        // For each (principalArn, resourceId) that has both TABLE and TABLE_WITH_COLUMNS entries,
        // the type whose minimum policy ID is lower wins; all permissions from the losing type
        // are removed from the expected set.
        Map<String, Long> tableMinId = new HashMap<>();   // key → min policyId for TABLE perms
        Map<String, Long> twcMinId   = new HashMap<>();   // key → min policyId for TWC perms

        for (SimulatorPermission p : permits) {
            String key = p.principalArn() + "|" + p.resourceId();
            long id = permToMinPolicyId.getOrDefault(p, Long.MAX_VALUE);
            if ("TABLE_WITH_COLUMNS".equals(p.resourceType()) && "SELECT".equals(p.permission())
                    && !p.resourceId().endsWith(".*")) {
                // TWC permission (column-restricted SELECT — not a bare table SELECT)
                twcMinId.merge(key, id, Math::min);
            } else if ("TABLE".equals(p.resourceType())) {
                tableMinId.merge(key, id, Math::min);
            }
        }

        // Find keys that have both TABLE and TWC entries — conflict exists
        Set<String> conflictKeys = new HashSet<>(tableMinId.keySet());
        conflictKeys.retainAll(twcMinId.keySet());

        if (!conflictKeys.isEmpty()) {
            permits.removeIf(p -> {
                String key = p.principalArn() + "|" + p.resourceId();
                if (!conflictKeys.contains(key)) return false;
                long tId = tableMinId.get(key);
                long wId = twcMinId.get(key);
                long permId = permToMinPolicyId.getOrDefault(p, Long.MAX_VALUE);
                boolean isTablePerm = "TABLE".equals(p.resourceType());
                boolean isTwcPerm   = "TABLE_WITH_COLUMNS".equals(p.resourceType())
                        && "SELECT".equals(p.permission()) && !p.resourceId().endsWith(".*");
                if (!isTablePerm && !isTwcPerm) return false;
                // Same minimum ID: one policy expands to both types — not an inter-policy conflict.
                if (tId == wId) return false;
                // The side with the higher minimum policy ID loses
                if (tId < wId) {
                    // TABLE wins — remove TWC permissions for this key
                    return isTwcPerm && permId >= wId;
                } else {
                    // TWC wins — remove TABLE permissions for this key
                    return isTablePerm && permId >= tId;
                }
            });
        }

        // Final display normalization (after conflict resolution): LF's ListPermissions API returns
        // a bare-table SELECT as a TableWithColumns (all-columns) resource, which LFPermissionsFetcher
        // normalizes to TABLE_WITH_COLUMNS. Mirror that here so the expected set matches LF actual
        // output. This is intentionally done AFTER Pass 3 so bare-table SELECT does not masquerade as
        // a real column grant during conflict detection.
        return normalizeBareTableSelectToTwc(permits);
    }

    /**
     * Rewrite each bare-table {@code (TABLE, SELECT)} entry to {@code (TABLE_WITH_COLUMNS, SELECT)}
     * to match how Lake Formation's ListPermissions reports bare-table SELECT grants. Genuine
     * column-level grants are already TABLE_WITH_COLUMNS and are left unchanged.
     */
    private Set<SimulatorPermission> normalizeBareTableSelectToTwc(Set<SimulatorPermission> permits) {
        Set<SimulatorPermission> normalized = new HashSet<>(permits.size() * 2);
        for (SimulatorPermission p : permits) {
            if ("TABLE".equals(p.resourceType()) && "SELECT".equals(p.permission())) {
                normalized.add(new SimulatorPermission(
                        p.principalArn(), "TABLE_WITH_COLUMNS", p.resourceId(), p.permission(),
                        p.grantable()));
            } else {
                normalized.add(p);
            }
        }
        return normalized;
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
                // Emit the spec's real resource type here. A bare-table SELECT stays TABLE-class
                // (only genuine column-level specs are TABLE_WITH_COLUMNS) so that the TABLE/TWC
                // conflict pass keys on actual column presence — mirroring the production sync
                // service, which keys conflicts on columnNames. The bare-table-SELECT → TWC display
                // rewrite (to match LF's ListPermissions output) is applied AFTER conflict
                // resolution; see normalizeBareTableSelectToTwc.
                for (String perm : finalPerms) {
                    result.add(new SimulatorPermission(
                            arn, spec.resourceType(), spec.resourceId(), perm, grantable));
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

        // Matches EmrSparkServiceAdapter.CATALOG_ACTION_MAPPING exactly.
        // "all" intentionally absent: it maps to LF ALL which ListPermissions returns
        // as individual permissions — the simulator workload doesn't generate "all".
        Map<String, Set<String>> emrSparkMap = new HashMap<>();
        emrSparkMap.put("select", Set.of("SELECT"));
        emrSparkMap.put("update", Set.of("INSERT"));
        emrSparkMap.put("alter",  Set.of("ALTER"));
        emrSparkMap.put("create", Set.of("CREATE_TABLE"));
        emrSparkMap.put("drop",   Set.of("DROP"));
        emrSparkMap.put("read",   Set.of("SELECT"));
        emrSparkMap.put("write",  Set.of("INSERT"));

        Map<String, Map<String, Set<String>>> result = new HashMap<>();
        result.put("lakeformation",   Collections.unmodifiableMap(lfMap));
        result.put("hive",            Collections.unmodifiableMap(hiveMap));
        result.put("trino",           Collections.unmodifiableMap(trinoMap));
        result.put("amazon-emr-spark", Collections.unmodifiableMap(emrSparkMap));
        return Collections.unmodifiableMap(result);
    }
}
