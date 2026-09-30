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
    // When non-null, a CONCRETE (non-wildcard) table resource only produces expected permissions
    // if this predicate reports the table still exists in the Glue catalog. Lake Formation
    // auto-removes all permissions when a table is deleted, so a Ranger policy that still
    // references a deleted table must expect ZERO grants — otherwise the oracle would report a
    // false under-grant every cycle. Wildcard tables already reflect deletions via the
    // TableExpander (which reads the same live catalog). Null → assume every table exists
    // (backward-compatible; used by all unit tests and offline runs).
    private final TableExistenceChecker tableExistenceChecker;

    /** Predicate: does {@code table} currently exist in {@code database} in the Glue catalog? */
    @FunctionalInterface
    public interface TableExistenceChecker {
        boolean exists(String database, String table);
    }

    public ExpectedPermissionsComputer(Map<String, String> principalMap, TableExpander tableExpander) {
        this(principalMap, tableExpander, null, null);
    }

    public ExpectedPermissionsComputer(Map<String, String> principalMap, TableExpander tableExpander,
                                       Set<String> managedServiceNames) {
        this(principalMap, tableExpander, managedServiceNames, null);
    }

    public ExpectedPermissionsComputer(Map<String, String> principalMap, TableExpander tableExpander,
                                       Set<String> managedServiceNames,
                                       TableExistenceChecker tableExistenceChecker) {
        this.principalMap = Map.copyOf(principalMap);
        this.tableExpander = tableExpander;
        this.managedServiceNames = managedServiceNames != null ? Set.copyOf(managedServiceNames) : null;
        this.tableExistenceChecker = tableExistenceChecker;
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
        // Pass 1: collect all deny keys across all services, plus deny-EXCEPTION keys. A
        // denyExceptions item is converted by the sync to a @denyException permit that overrides a
        // matching forbid (CedarToLFConverter: a forbidden permit survives if its key is in the
        // deny-exception set). So a deny-exception (principal, resource, permission) must NOT be
        // subtracted by a matching deny below.
        Set<DenyKey> globalDenySet = new HashSet<>();
        Set<DenyKey> globalDenyExceptionSet = new HashSet<>();
        for (JsonNode policy : policies) {
            if (!shouldProcess(policy)) continue;
            collectDenies(policy, globalDenySet);
            collectDenyExceptions(policy, globalDenyExceptionSet);
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
            // A matching deny-exception restores the permit (deny-exception wins over deny),
            // mirroring CedarToLFConverter's denyExceptionSet check.
            if (globalDenyExceptionSet.contains(new DenyKey(principal, rid, perm))) return false;
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
        // For each (principalArn, resourceId) that has both a TABLE grant and a TWC grant:
        //   Rule B (subsumption): if any TABLE permission is SELECT, a table-level SELECT covers
        //     every column, so the column-restricted TWC SELECT is redundant — drop the TWC and
        //     keep all TABLE permissions. Not a real conflict.
        //   Rule A (genuine conflict): otherwise the lowest active policy id decides the winning
        //     TYPE. If TWC has the lower id, TWC wins and all TABLE perms are removed; otherwise
        //     the TWC perms are removed and all TABLE perms win.
        // At this point a bare-table SELECT is still (TABLE, SELECT) — the display rewrite to
        // TABLE_WITH_COLUMNS happens afterwards — so "table has SELECT" is detected correctly here.
        Map<String, Long> tableMinId = new HashMap<>();   // key → min policyId for TABLE perms
        Map<String, Long> twcMinId   = new HashMap<>();   // key → min policyId for TWC perms
        Set<String> tableSelectKeys  = new HashSet<>();   // keys with a TABLE-level SELECT

        for (SimulatorPermission p : permits) {
            String key = p.principalArn() + "|" + p.resourceId();
            long id = permToMinPolicyId.getOrDefault(p, Long.MAX_VALUE);
            if (isTwcPerm(p)) {
                twcMinId.merge(key, id, Math::min);
            } else if ("TABLE".equals(p.resourceType())) {
                tableMinId.merge(key, id, Math::min);
                if ("SELECT".equals(p.permission())) {
                    tableSelectKeys.add(key);
                }
            }
        }

        // Keys that have both TABLE and TWC entries — a conflict to resolve.
        Set<String> conflictKeys = new HashSet<>(tableMinId.keySet());
        conflictKeys.retainAll(twcMinId.keySet());

        if (!conflictKeys.isEmpty()) {
            permits.removeIf(p -> {
                String key = p.principalArn() + "|" + p.resourceId();
                if (!conflictKeys.contains(key)) return false;
                boolean isTablePerm = "TABLE".equals(p.resourceType());
                boolean isTwcPerm   = isTwcPerm(p);
                if (!isTablePerm && !isTwcPerm) return false;

                // Rule B: table-level SELECT subsumes the TWC → drop only the TWC.
                if (tableSelectKeys.contains(key)) {
                    return isTwcPerm;
                }

                // Rule A: lowest-id type wins the whole (principal, table).
                long tId = tableMinId.get(key);
                long wId = twcMinId.get(key);
                if (tId == wId) return false; // one policy expands to both types — not inter-policy
                if (tId < wId) {
                    return isTwcPerm;   // TABLE type wins — remove TWC
                } else {
                    return isTablePerm; // TWC type wins — remove TABLE
                }
            });
        }

        // Final display normalization (after conflict resolution): LF's ListPermissions API returns
        // a bare-table SELECT as a TableWithColumns (all-columns) resource, which LFPermissionsFetcher
        // normalizes to TABLE_WITH_COLUMNS. Mirror that here so the expected set matches LF actual
        // output. This is intentionally done AFTER Pass 3 so bare-table SELECT does not masquerade as
        // a real column grant during conflict detection.
        Set<SimulatorPermission> normalized = normalizeBareTableSelectToTwc(permits);

        // Coalesce grantable duplicates: when two policies grant the same
        // (principal, resourceType, resourceId, permission) but with different delegateAdmin,
        // LF stores a SINGLE grant whose grant-option is the OR of the two. The oracle would
        // otherwise emit both a grantable=false and a grantable=true entry, and the diff against
        // LF's single (grantable=true) entry would report a permanent false under-grant. Merge
        // them here, keeping grantable=true if ANY contributing policy is grantable.
        return coalesceGrantable(normalized);
    }

    /**
     * Merge entries that are identical except for {@code grantable}, keeping a single entry whose
     * grantable flag is the OR across the merged entries. Mirrors Lake Formation, which holds one
     * grant per (principal, resource, permission) and turns on the grant option if any source
     * policy granted it with {@code delegateAdmin=true}.
     */
    private static Set<SimulatorPermission> coalesceGrantable(Set<SimulatorPermission> permits) {
        // key (principal, resourceType, resourceId, permission) → whether any variant is grantable
        Map<List<String>, Boolean> grantableByKey = new HashMap<>();
        for (SimulatorPermission p : permits) {
            List<String> key = List.of(p.principalArn(), p.resourceType(), p.resourceId(), p.permission());
            grantableByKey.merge(key, p.grantable(), Boolean::logicalOr);
        }
        Set<SimulatorPermission> result = new HashSet<>(grantableByKey.size() * 2);
        for (Map.Entry<List<String>, Boolean> e : grantableByKey.entrySet()) {
            List<String> k = e.getKey();
            result.add(new SimulatorPermission(k.get(0), k.get(1), k.get(2), k.get(3), e.getValue()));
        }
        return result;
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
        // isExcludes=true means "all resources EXCEPT these" — LF has no such model, so the sync
        // service gaps the ENTIRE policy (RangerToCedarConverter: EXCLUDES_PATTERN → empty). Any
        // resource element carrying isExcludes=true drops the whole policy; the oracle must too or
        // it produces phantom grants the sync never issues.
        for (JsonNode res : resources) {
            if (res.path("isExcludes").asBoolean(false)) return false;
        }
        return true;
    }

    private void processPermitsInto(JsonNode policy, Set<SimulatorPermission> result) {
        String serviceName = policy.path("service").asText(null);
        Map<String, Set<String>> accessMap = accessMapForService(serviceName);
        JsonNode resources = policy.path("resources");
        for (JsonNode item : policy.path("policyItems")) {
            processItemInto(item, resources, result, accessMap);
        }
        // denyExceptions items also produce permits: the sync converts each to a @denyException
        // permit statement (RangerToCedarConverter), which grants like a normal allow item AND
        // overrides a matching deny (handled via globalDenyExceptionSet in compute()).
        for (JsonNode item : policy.path("denyExceptions")) {
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
                // Column scope collapses to SELECT, or to nothing.
                //
                // Lake Formation rejects ALL on a TableWithColumns resource outright
                // (InvalidInputException, verified against the live API), so the production
                // adapters narrow a column-scoped "all" to SELECT rather than dropping the grant.
                // This oracle must expect the same, or every column-scoped "all" policy reports a
                // spurious over- and under-grant pair.
                //
                // INSERT/DELETE/ALTER/DROP/DESCRIBE on a column resource remain dropped — LF has
                // no column-granular form of them.
                if (permissions.contains("ALL") || permissions.contains("SELECT")) {
                    finalPerms = Set.of("SELECT");
                } else {
                    finalPerms = Set.of();
                }
            } else {
                // Filter to permissions valid at this resource level, mirroring the production
                // service adapters (e.g. TrinoServiceAdapter.TABLE_VALID_ACTIONS /
                // DATABASE_VALID_ACTIONS). Without this, a table-level policy with a "create"
                // access type would emit (TABLE, CREATE_TABLE) — but CREATE_TABLE is a database
                // permission that LF rejects on a table, so the sync service never grants it.
                Set<String> valid = validActionsFor(spec.resourceType());
                if (valid == null) {
                    finalPerms = permissions; // no resource-level filter (e.g. DATA_LOCATION)
                } else {
                    finalPerms = new LinkedHashSet<>(permissions);
                    finalPerms.retainAll(valid);
                }
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
        // Data location. "url" is read before "datalocation" to match production's
        // determineResourceLevel (RangerToCedarConverter), which checks "url" first. No bundled
        // servicedef declares both keys on the same policy, so the ordering cannot actually
        // diverge between the two implementations in practice — matched anyway so the two do not
        // differ for a reason nobody can later reconstruct. "url" is used by amazon-emr-trino.json
        // and amazon-emr-spark.json; "datalocation" by lakeformation/hive.
        if (hasNonEmptyResource(resources, "url")) {
            for (String path : resourceValues(resources, "url")) {
                specs.add(new ResourceSpec("DATA_LOCATION", path, false));
            }
            return specs;
        }
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

        // Mirror production RangerToCedarConverter.promoteResourceLevel: a column resource of
        // "*" on a concrete (non-wildcard) table means "all columns" = the whole table, so it is
        // promoted to TABLE level (full actions), NOT treated as a column-restricted
        // TABLE_WITH_COLUMNS SELECT-only grant. A genuine specific-column grant stays TWC.
        List<String> tablePatterns = resourceValues(resources, "table");
        boolean columnIsWildcard = hasColumn && isAllWildcard(resourceValues(resources, "column"));
        // Mirror production RangerToCedarConverter.expandResources: a bare table='*' (every value
        // a wildcard) maps to a single Lake Formation TableWildcard grant instead of a per-table
        // Glue expansion — the sync service now emits exactly one grant for this shape, so the
        // table expander must not be consulted at all.
        boolean bareTableWildcard = isAllWildcard(tablePatterns);

        for (String db : databases) {
            if (bareTableWildcard) {
                if (hasColumn && !columnIsWildcard) {
                    // table='*' combined with a SPECIFIC column: Lake Formation's TableWildcard is
                    // table-level only and cannot express a column restriction across it. Production
                    // gaps this shape and produces no grant — the oracle must expect none either.
                    continue;
                }
                // column='*' (or no column key at all) carries no restriction beyond the table
                // wildcard itself → one TABLE-class expectation for the whole database.
                specs.add(new ResourceSpec("TABLE", db + ".*", false));
                continue;
            }
            for (String tablePattern : tablePatterns) {
                // column=* on a concrete table → promote to TABLE. column=* on table=* stays
                // column-scoped (per-table expansion below yields TWC per concrete table).
                boolean promoteToTable = columnIsWildcard && !isWildcardPattern(tablePattern);
                boolean isColumnScoped = hasColumn && !promoteToTable;

                List<String> tables = resolveTablePattern(db, tablePattern);
                for (String table : tables) {
                    specs.add(new ResourceSpec(isColumnScoped ? "TABLE_WITH_COLUMNS" : "TABLE",
                            db + "." + table, isColumnScoped));
                }
            }
        }
        return specs;
    }

    /** True if every value is one or more '*'/'?' wildcard characters (mirrors production isAllWildcard). */
    private boolean isAllWildcard(List<String> values) {
        if (values == null || values.isEmpty()) return false;
        for (String v : values) {
            if (v == null || !v.matches("[*?]+")) return false;
        }
        return true;
    }

    private boolean isWildcardPattern(String pattern) {
        return pattern != null && (pattern.contains("*") || pattern.contains("?"));
    }

    private List<String> resolveTablePattern(String db, String pattern) {
        if (pattern.contains("*") || pattern.contains("?")) {
            // Wildcard: the expander reads the live catalog, so deleted tables are already excluded.
            return tableExpander.expand(db, pattern);
        }
        // Concrete table: exclude it if the catalog says it no longer exists. LF auto-removes
        // permissions on deletion, so the expected set must drop the deleted table too.
        if (tableExistenceChecker != null && !tableExistenceChecker.exists(db, pattern)) {
            return List.of();
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

    // Permissions LF accepts at each resource level, mirroring the production service adapters'
    // TABLE_VALID_ACTIONS / DATABASE_VALID_ACTIONS. CREATE_TABLE/CREATE_DATABASE are database-only;
    // SELECT/INSERT/DELETE are table-only. The sync service drops out-of-level permissions, so the
    // oracle must too or it produces phantom under-grants (e.g. table-level "create" → CREATE_TABLE).
    // "ALL" is valid at both table and database level (Hive/Trino "all" → LF ALL, stored as-is).
    private static final Set<String> TABLE_VALID_ACTIONS =
            Set.of("SELECT", "INSERT", "DELETE", "DESCRIBE", "ALTER", "DROP", "ALL");
    private static final Set<String> DATABASE_VALID_ACTIONS =
            Set.of("CREATE_TABLE", "CREATE_DATABASE", "ALTER", "DROP", "DESCRIBE", "ALL");

    private static Set<String> validActionsFor(String resourceType) {
        if ("DATABASE".equals(resourceType)) return DATABASE_VALID_ACTIONS;
        if ("TABLE".equals(resourceType)) return TABLE_VALID_ACTIONS;
        // TABLE_WITH_COLUMNS is handled via the isColumn() branch; DATA_LOCATION passes through.
        return null; // null = no filtering (data location / other)
    }

    /**
     * True for a genuine column-restricted TWC SELECT permission (not a bare-table SELECT, which
     * is TABLE-class until the final display normalization, and not a wildcard-table entry).
     */
    private static boolean isTwcPerm(SimulatorPermission p) {
        return "TABLE_WITH_COLUMNS".equals(p.resourceType())
                && "SELECT".equals(p.permission())
                && !p.resourceId().endsWith(".*");
    }

    private record ResourceSpec(String resourceType, String resourceId, boolean isColumn) {}

    private record DenyKey(String principalArn, String resourceId, String permission) {}

    private void collectDenies(JsonNode policy, Set<DenyKey> denySet) {
        collectKeysFrom(policy, "denyPolicyItems", denySet);
    }

    /**
     * Collect deny-EXCEPTION keys — the (principal, resource, permission) triples that a
     * denyExceptions item re-allows even when a matching deny exists. Structurally identical to
     * {@link #collectDenies}, but reads the {@code denyExceptions} array.
     */
    private void collectDenyExceptions(JsonNode policy, Set<DenyKey> exceptionSet) {
        collectKeysFrom(policy, "denyExceptions", exceptionSet);
    }

    /** Build (principal, resourceId, permission) keys from the named policy-item array. */
    private void collectKeysFrom(JsonNode policy, String itemArrayField, Set<DenyKey> out) {
        String serviceName = policy.path("service").asText(null);
        Map<String, Set<String>> accessMap = accessMapForService(serviceName);
        JsonNode resources = policy.path("resources");

        for (JsonNode item : policy.path(itemArrayField)) {
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
                        out.add(new DenyKey(principalArn, spec.resourceId(), permission));
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
        // "all" -> LF ALL: passes through rather than expanding to a fixed subset, matching
        // hiveMap below and the decision recorded in DESIGN.md ("Why 'all' maps to LF ALL").
        // Ranger's impliedGrants for "all" include privileges LF cannot express at all (Hive's
        // index/lock/repladmin/serviceadmin/refresh; Presto/Trino's grant/revoke/impersonate/
        // execute), so a faithful expansion is impossible; ALL is the closer translation of
        // "everything".
        lfMap.put("all",                  Set.of("ALL"));
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
        // "all" → LF "ALL": HiveServiceAdapter maps Hive "all" to the LF ALL permission, which
        // ListPermissions returns as the single token "ALL" (verified: LF does not expand it into
        // individual permissions). The oracle must expect exactly {"ALL"} to match.
        hiveMap.put("all",    Set.of("ALL"));
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
        trinoMap.put("all",    Set.of("ALL"));
        trinoMap.put("update", Set.of("INSERT"));
        trinoMap.put("read",   Set.of("SELECT"));
        trinoMap.put("write",  Set.of("INSERT"));
        // Deliberately still absent: "grant", "revoke", "execute", "impersonate". These are
        // declared known-unmappable by TrinoServiceAdapter/PrestoServiceAdapter. An access type
        // missing from this map falls through accessMap.get(type) as null and contributes no
        // permission, which is the required behaviour — do NOT "complete" the map with them.

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
        // PrestoServiceAdapter and TrinoServiceAdapter are line-for-line identical in their
        // action mappings, so sharing one map is correct here and avoids two tables drifting
        // apart. (Previously "presto" was unregistered entirely, which meant Presto policies
        // fell back to lfMap wholesale — see divergence #4 in the design spec.)
        result.put("presto",          Collections.unmodifiableMap(trinoMap));
        result.put("amazon-emr-spark", Collections.unmodifiableMap(emrSparkMap));
        return Collections.unmodifiableMap(result);
    }
}
