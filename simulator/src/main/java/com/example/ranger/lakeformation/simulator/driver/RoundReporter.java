package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.validator.SimulatorPermission;
import com.example.ranger.lakeformation.simulator.workload.MutationOperation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes a human-readable, round-by-round report of what the simulator did each cycle:
 *
 * <pre>
 * Round N:
 * ==========
 * Ranger Policies Created:
 * - Policy Id &lt;id&gt;: Grant/Delete/Deny ...
 *
 * LF Grants/Revoked:
 * - LF [Grant|Revoke]: Principal: X Resource: Y Actions: Z
 *
 * Ranger State (enabled policies):
 * - Policy Id &lt;id&gt;: Grant [service] resource | allow user:[..] -&gt; actions
 *
 * Current LF State:
 * - LF policy: Principal: X Resource: Y Actions: Z
 * </pre>
 *
 * <p>The three sections come from different sources within one cycle:
 * <ul>
 *   <li><b>Ranger Policies Created</b> — the mutation batch the simulator applied this cycle,
 *       summarized from each {@link MutationOperation} and its resolved Ranger numeric ID.</li>
 *   <li><b>LF Grants/Revoked</b> — <i>derived</i> by diffing the previous cycle's LF snapshot
 *       against the current one. Permissions newly present are GRANTs; permissions no longer
 *       present are REVOKEs. This is the net effect the sync service produced this cycle, which
 *       is what a reader wants to validate (the sync service performs the actual API calls in a
 *       separate process).</li>
 *   <li><b>Current LF State</b> — the full filtered {@code ListPermissions} snapshot after sync.</li>
 * </ul>
 *
 * <p>The report is appended to a single file across the whole run so rounds read top-to-bottom.
 */
public class RoundReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path reportPath;

    public RoundReporter(Path reportPath) throws IOException {
        this.reportPath = reportPath;
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
    }

    public Path getReportPath() {
        return reportPath;
    }

    /**
     * Append one round to the report.
     *
     * @param roundNumber    the simulator cycle number
     * @param batch          the mutations applied this cycle
     * @param driver         used to resolve internal policy IDs to Ranger numeric IDs
     * @param rangerPolicies current Ranger policies across all services; disabled policies are
     *                       excluded from the Ranger State section. May be empty if unavailable.
     * @param previousState  LF snapshot from the end of the previous cycle (empty for round 0)
     * @param currentState   LF snapshot after this cycle's sync completed
     */
    public void appendRound(long roundNumber,
                            List<MutationOperation> batch,
                            MutationDriver driver,
                            List<JsonNode> rangerPolicies,
                            Set<SimulatorPermission> previousState,
                            Set<SimulatorPermission> currentState) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Round ").append(roundNumber).append(":\n");
        sb.append("==========\n");

        // --- Ranger Policies Created (the mutations applied this cycle) ---
        sb.append("Ranger Policies Created:\n");
        if (batch.isEmpty()) {
            sb.append("- (no mutations this round)\n");
        } else {
            for (MutationOperation op : batch) {
                sb.append("- ").append(describeMutation(op, driver)).append('\n');
            }
        }
        sb.append('\n');

        // --- LF Grants/Revoked (net delta this cycle: current vs previous snapshot) ---
        // Actions are grouped by (principal, resource, grantable) onto one line so each line
        // mirrors a single LF GrantPermissions/RevokePermissions call — which carries all actions
        // for a (principal, resource) pair together, not one call per action.
        sb.append("LF Grants/Revoked:\n");
        Set<SimulatorPermission> grants = new HashSet<>(currentState);
        grants.removeAll(previousState);

        Set<SimulatorPermission> revokes = new HashSet<>(previousState);
        revokes.removeAll(currentState);

        if (grants.isEmpty() && revokes.isEmpty()) {
            sb.append("- (no LF changes this round)\n");
        } else {
            for (String line : groupByResource(grants)) {
                sb.append("- LF Grant: ").append(line).append('\n');
            }
            for (String line : groupByResource(revokes)) {
                sb.append("- LF Revoke: ").append(line).append('\n');
            }
        }
        sb.append('\n');

        // --- Ranger State (all currently-enabled policies; disabled excluded) ---
        // This is the live Ranger policy set the sync service acts on, so it can be compared
        // directly against the LF state below. Disabled policies are excluded because they
        // produce no LF grants and would only add noise to the comparison.
        sb.append("Ranger State (enabled policies):\n");
        List<String> rangerLines = describeRangerState(rangerPolicies);
        if (rangerLines.isEmpty()) {
            sb.append("- (no enabled Ranger policies)\n");
        } else {
            for (String line : rangerLines) {
                sb.append("- ").append(line).append('\n');
            }
        }
        sb.append('\n');

        // --- Current LF State (full snapshot after sync) ---
        sb.append("Current LF State:\n");
        if (currentState.isEmpty()) {
            sb.append("- (no LF permissions for managed principals)\n");
        } else {
            for (String line : groupByResource(currentState)) {
                sb.append("- LF policy: ").append(line).append('\n');
            }
        }
        sb.append("\n\n");

        Files.writeString(reportPath, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** One-line summary of a Ranger mutation, e.g. "Policy Id 8: Grant table analytics.events ...". */
    @SuppressWarnings("unchecked")
    private static String describeMutation(MutationOperation op, MutationDriver driver) {
        if (op instanceof MutationOperation.CreatePolicy c) {
            String rangerId = driver.resolveRangerId(c.policyId());
            return "Policy Id " + rangerId + ": " + summarizePayload("Grant", c.policyPayload());
        } else if (op instanceof MutationOperation.UpdatePolicy u) {
            String rangerId = driver.resolveRangerId(u.policyId());
            return "Policy Id " + rangerId + ": " + summarizePayload("Update", u.policyPayload());
        } else if (op instanceof MutationOperation.DisablePolicy d) {
            return "Policy Id " + driver.resolveRangerId(d.policyId()) + ": Disable";
        } else if (op instanceof MutationOperation.EnablePolicy e) {
            return "Policy Id " + driver.resolveRangerId(e.policyId()) + ": Enable";
        } else if (op instanceof MutationOperation.DeletePolicy del) {
            return "Policy Id " + driver.resolveRangerId(del.policyId()) + ": Delete";
        }
        return "Unknown mutation: " + op;
    }

    /**
     * Render the currently-enabled Ranger policies as compact one-line summaries, sorted by
     * policy id for stable, diff-friendly output. Disabled policies ({@code isEnabled == false})
     * are excluded. Each line reuses the same {@link #summarizePayload} rendering as the mutation
     * section so the two are directly comparable.
     */
    private static List<String> describeRangerState(List<JsonNode> rangerPolicies) {
        if (rangerPolicies == null || rangerPolicies.isEmpty()) {
            return List.of();
        }
        // Sort by numeric policy id where available so the section reads in a stable order.
        Map<Long, String> byId = new TreeMap<>();
        List<String> unkeyed = new ArrayList<>();
        for (JsonNode policy : rangerPolicies) {
            if (policy == null) continue;
            // Exclude disabled policies — isEnabled defaults to true when the field is absent.
            JsonNode enabled = policy.get("isEnabled");
            if (enabled != null && !enabled.asBoolean(true)) {
                continue;
            }
            Map<String, Object> asMap = MAPPER.convertValue(policy, MAP_TYPE);
            String summary = summarizePayload("Grant", asMap);
            JsonNode idNode = policy.get("id");
            String line = "Policy Id " + (idNode != null ? idNode.asText() : "?") + ": " + summary;
            if (idNode != null && idNode.canConvertToLong()) {
                byId.put(idNode.asLong(), line);
            } else {
                unkeyed.add(line);
            }
        }
        List<String> lines = new ArrayList<>(byId.values());
        lines.addAll(unkeyed);
        return lines;
    }

    /**
     * Summarize a Ranger policy payload map into a compact grant/deny description. Handles the
     * shapes the simulator's generators emit: {@code service}, {@code resources.{database,table,
     * column,...}}, {@code policyItems}, {@code denyPolicyItems}, and {@code isEnabled}.
     */
    @SuppressWarnings("unchecked")
    private static String summarizePayload(String verb, Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return verb + " " + payload;
        }
        Map<String, Object> policy = (Map<String, Object>) map;
        StringBuilder sb = new StringBuilder(verb);

        Object service = policy.get("service");
        if (service != null) {
            sb.append(" [").append(service).append(']');
        }

        // Resource
        Object resources = policy.get("resources");
        if (resources instanceof Map<?, ?> res) {
            Map<String, Object> resMap = (Map<String, Object>) res;
            String db = firstValue(resMap.get("database"));
            String table = firstValue(resMap.get("table"));
            String column = firstValue(resMap.get("column"));
            // Trino uses catalog/schema/table; DataLocation/EMRFS use a path-style resource.
            String catalog = firstValue(resMap.get("catalog"));
            String schema = firstValue(resMap.get("schema"));
            String dataLoc = firstValue(resMap.get("data-location"));
            String prefix = firstValue(resMap.get("prefix"));

            if (dataLoc != null) {
                sb.append(" data-location ").append(dataLoc);
            } else if (prefix != null) {
                sb.append(" prefix ").append(prefix);
            } else if (catalog != null) {
                sb.append(" ").append(join(catalog, schema, table));
            } else if (db != null) {
                String res2 = join(db, table);
                if (column != null) {
                    res2 = res2 + " cols[" + column + "]";
                }
                sb.append(" ").append(res2);
            }
        }

        // Allow items → users/groups/roles + accesses
        appendItems(sb, policy.get("policyItems"), "allow");
        appendItems(sb, policy.get("denyPolicyItems"), "deny");

        if (Boolean.FALSE.equals(policy.get("isEnabled"))) {
            sb.append(" (disabled)");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendItems(StringBuilder sb, Object items, String kind) {
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        for (Object itemObj : list) {
            if (!(itemObj instanceof Map<?, ?> item)) continue;
            Map<String, Object> itemMap = (Map<String, Object>) item;
            String principals = joinPrincipals(itemMap);
            String accesses = joinAccesses(itemMap.get("accesses"));
            sb.append(" | ").append(kind).append(' ').append(principals)
              .append(" -> ").append(accesses);
        }
    }

    @SuppressWarnings("unchecked")
    private static String joinPrincipals(Map<String, Object> item) {
        StringBuilder sb = new StringBuilder();
        appendList(sb, item.get("users"), "user");
        appendList(sb, item.get("groups"), "group");
        appendList(sb, item.get("roles"), "role");
        return sb.length() == 0 ? "(no principal)" : sb.toString().trim();
    }

    private static void appendList(StringBuilder sb, Object value, String label) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            sb.append(label).append(':').append(list).append(' ');
        }
    }

    @SuppressWarnings("unchecked")
    private static String joinAccesses(Object accesses) {
        if (!(accesses instanceof List<?> list) || list.isEmpty()) {
            return "(no accesses)";
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : list) {
            if (a instanceof Map<?, ?> accessMap) {
                Object type = ((Map<String, Object>) accessMap).get("type");
                if (type != null) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(type);
                }
            }
        }
        return sb.length() == 0 ? "(no accesses)" : sb.toString();
    }

    /** Extract the first entry of a Ranger resource element's "values" list. */
    @SuppressWarnings("unchecked")
    private static String firstValue(Object resourceElement) {
        if (resourceElement instanceof Map<?, ?> m) {
            Object values = ((Map<String, Object>) m).get("values");
            if (values instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        }
        return null;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * Group a flat set of single-action permissions by (principal, resourceType, resourceId,
     * grantable) and render one "Principal: X Resource: Y Actions: A, B, C" line per group.
     * This mirrors how the sync service issues LF grants/revokes — one call per (principal,
     * resource) carrying all actions — rather than the one-permission-per-entry shape that
     * {@code ListPermissions} and {@link SimulatorPermission} use internally.
     *
     * @return rendered lines, sorted for deterministic, diff-friendly output
     */
    private static List<String> groupByResource(Set<SimulatorPermission> perms) {
        // key -> sorted set of action names
        Map<GroupKey, TreeSet<String>> grouped = new TreeMap<>();
        for (SimulatorPermission p : perms) {
            GroupKey key = new GroupKey(p.principalArn(), p.resourceType(), p.resourceId(), p.grantable());
            grouped.computeIfAbsent(key, k -> new TreeSet<>()).add(p.permission());
        }
        List<String> lines = new ArrayList<>(grouped.size());
        for (Map.Entry<GroupKey, TreeSet<String>> e : grouped.entrySet()) {
            GroupKey k = e.getKey();
            String grantable = k.grantable() ? " (WITH GRANT OPTION)" : "";
            lines.add("Principal: " + shortenArn(k.principalArn())
                    + " Resource: " + k.resourceType() + " " + k.resourceId()
                    + " Actions: " + String.join(", ", e.getValue()) + grantable);
        }
        return lines;
    }

    /** Grouping key for collapsing per-action permissions back into per-(principal,resource) grants. */
    private record GroupKey(String principalArn, String resourceType, String resourceId, boolean grantable)
            implements Comparable<GroupKey> {
        @Override
        public int compareTo(GroupKey o) {
            return Comparator.comparing(GroupKey::principalArn)
                    .thenComparing(GroupKey::resourceType)
                    .thenComparing(GroupKey::resourceId)
                    .thenComparing(GroupKey::grantable)
                    .compare(this, o);
        }
    }

    /** Trim an IAM ARN down to its role/user name for readability, keeping full ARN if unusual. */
    private static String shortenArn(String arn) {
        if (arn == null) return "(null)";
        int slash = arn.lastIndexOf('/');
        return slash >= 0 && slash < arn.length() - 1 ? arn.substring(slash + 1) : arn;
    }
}
