package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.cedarpolicy.model.exception.InternalException;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerPolicy.RangerRowFilterPolicyItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts Ranger policies into a Cedar PolicySet using registered
 * {@link SourcePolicyAdapter} instances for service-type-specific mapping.
 *
 * <p>For each policy the converter:
 * <ol>
 *   <li>Looks up the adapter by service type</li>
 *   <li>Skips unsupported types (data masking, tag-based) → gap</li>
 *   <li>Expands wildcards via {@link CatalogResolver}</li>
 *   <li>Produces permit / forbid / deny-exception / row-filter statements</li>
 *   <li>Annotates every statement with the source policy ID</li>
 *   <li>Validates the full PolicySet against the Cedar schema</li>
 * </ol>
 */
public class RangerToCedarConverter {

    private static final Logger LOG = LoggerFactory.getLogger(RangerToCedarConverter.class);
    private static final String DEFAULT_SERVICE_TYPE = "lakeformation";

    private final Map<String, SourcePolicyAdapter> adapterRegistry;
    private final PrincipalMapper principalMapper;
    private final CatalogResolver catalogResolver;
    private final GapReporter gapReporter;
    private final CedarSchemaProvider schemaProvider;

    public RangerToCedarConverter(Map<String, SourcePolicyAdapter> adapterRegistry,
                                  PrincipalMapper principalMapper,
                                  CatalogResolver catalogResolver,
                                  GapReporter gapReporter,
                                  CedarSchemaProvider schemaProvider) {
        this.adapterRegistry = adapterRegistry;
        this.principalMapper = principalMapper;
        this.catalogResolver = catalogResolver;
        this.gapReporter = gapReporter;
        this.schemaProvider = schemaProvider;
    }

    /**
     * Convert a list of Ranger policies to a validated Cedar PolicySet.
     *
     * @param policies the Ranger policies to convert
     * @return a validated CedarPolicySet
     */
    public CedarPolicySet convert(List<RangerPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            try {
                return CedarPolicySet.fromCedarString("");
            } catch (InternalException e) {
                throw new RuntimeException("Failed to create empty CedarPolicySet", e);
            }
        }

        List<String> allStatements = new ArrayList<>();

        for (RangerPolicy policy : policies) {
            List<String> statements = convertSinglePolicy(policy);
            allStatements.addAll(statements);
        }

        String cedarText = String.join("\n", allStatements);
        return parseAndValidate(cedarText, allStatements);
    }

    private List<String> convertSinglePolicy(RangerPolicy policy) {
        String policyId = policy.getId() != null ? String.valueOf(policy.getId()) : "unknown";
        String policyName = policy.getName() != null ? policy.getName() : "unknown";

        // Look up adapter by service type
        String serviceType = policy.getService() != null ? policy.getService() : DEFAULT_SERVICE_TYPE;
        SourcePolicyAdapter adapter = adapterRegistry.get(serviceType);
        if (adapter == null) {
            // Try default
            adapter = adapterRegistry.get(DEFAULT_SERVICE_TYPE);
        }
        if (adapter == null) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.UNSUPPORTED_SERVICE_TYPE,
                    null,
                    "No adapter registered for service type: " + serviceType,
                    "Register a SourcePolicyAdapter for service type '" + serviceType + "'."
            ));
            return Collections.emptyList();
        }

        // Check if the adapter wants to process this policy (e.g., GDC catalog filtering)
        if (!adapter.shouldProcessPolicy(policy)) {
            LOG.debug("Adapter for service type '{}' skipped policy {} ({})",
                    adapter.getServiceType(), policyId, policyName);
            return Collections.emptyList();
        }

        // Check for data masking (policyType == 1)
        if (policy.getPolicyType() != null && policy.getPolicyType() == 1) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.DATA_MASKING,
                    buildResourcePath(policy),
                    "Data masking policy (policyType=1) cannot be represented in Cedar.",
                    "Consider using column-level permissions or external masking solutions."
            ));
            return Collections.emptyList();
        }

        // Check for tag-based policy
        if (serviceType.toLowerCase().contains("tag")) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.TAG_BASED_POLICY,
                    buildResourcePath(policy),
                    "Tag-based policy detected. Ranger tags are not supported in Cedar conversion.",
                    "Manually map Ranger tags to equivalent resource-based policies."
            ));
            return Collections.emptyList();
        }

        // Record gaps for custom conditions (but continue converting)
        recordCustomConditionGaps(policy, policyId, policyName);

        // Record gaps for validity schedules (but continue converting)
        if (policy.getValiditySchedules() != null && !policy.getValiditySchedules().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.VALIDITY_SCHEDULE,
                    buildResourcePath(policy),
                    "Validity schedules detected (" + policy.getValiditySchedules().size()
                            + " schedules). Cedar does not support temporal constraints.",
                    "Implement time-based access control externally or remove time constraints."
            ));
        }

        // Deny items → gap (LF has no deny model; converted to forbid for Cedar evaluation)
        if (policy.getDenyPolicyItems() != null && !policy.getDenyPolicyItems().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.DENY_POLICY,
                    buildResourcePath(policy),
                    "Deny policy items detected (" + policy.getDenyPolicyItems().size()
                            + " items). Lake Formation uses a grant-only model.",
                    "Review deny rules and implement equivalent restrictions using grant-only permissions."
            ));
        }

        // Deny exceptions → gap (LF has no deny-exception concept)
        if (policy.getDenyExceptions() != null && !policy.getDenyExceptions().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.DENY_EXCEPTION,
                    buildResourcePath(policy),
                    "Deny exception items detected (" + policy.getDenyExceptions().size()
                            + " items). Lake Formation has no deny-exception concept.",
                    "Review deny exception rules and adjust grant permissions accordingly."
            ));
        }

        // Security zone → gap (LF has no equivalent)
        if (policy.getZoneName() != null && !policy.getZoneName().trim().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.SECURITY_ZONE,
                    buildResourcePath(policy),
                    "Security zone '" + policy.getZoneName() + "' referenced. "
                            + "Lake Formation has no equivalent concept.",
                    "Review zone-based policies and create equivalent resource-scoped permissions."
            ));
        }

        // delegateAdmin is propagated as @grantable("true") in Cedar statements and
        // results in permissionsWithGrantOption being set on the LF grant.

        // Extract resources and determine resource level
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            LOG.warn("Policy {} ({}) has no resources, skipping", policyId, policyName);
            return Collections.emptyList();
        }

        // isExcludes=true means "all resources EXCEPT these" — LF has no such model
        for (Map.Entry<String, RangerPolicyResource> entry : resources.entrySet()) {
            if (entry.getValue() != null && Boolean.TRUE.equals(entry.getValue().getIsExcludes())) {
                gapReporter.recordGap(new GapEntry(
                        policyId, policyName, GapType.EXCLUDES_PATTERN,
                        buildResourcePath(policy),
                        "Resource '" + entry.getKey() + "' uses isExcludes=true. "
                                + "Lake Formation has no 'all except' resource model.",
                        "Replace exclusion patterns with explicit allow lists."
                ));
                return Collections.emptyList();
            }
        }

        String resourceLevel = determineResourceLevel(resources);
        resourceLevel = promoteResourceLevel(resourceLevel, resources);

        // Expand wildcards and build resource combinations
        List<ResourceCombination> resourceCombinations = expandResources(
                resources, resourceLevel, adapter, policyId);

        if (resourceCombinations.isEmpty()) {
            LOG.warn("Policy {} ({}) - no resources resolved after expansion", policyId, policyName);
            return Collections.emptyList();
        }

        List<String> statements = new ArrayList<>();

        // Allow items → permit statements
        List<RangerPolicyItem> allowItems = policy.getPolicyItems();
        if (allowItems != null) {
            for (RangerPolicyItem item : allowItems) {
                statements.addAll(generateStatements(
                        item, "permit", policyId, adapter, resourceCombinations, null));
            }
        }

        // Deny items → forbid statements
        List<RangerPolicyItem> denyItems = policy.getDenyPolicyItems();
        if (denyItems != null) {
            for (RangerPolicyItem item : denyItems) {
                statements.addAll(generateStatements(
                        item, "forbid", policyId, adapter, resourceCombinations, null));
            }
        }

        // Deny exception items → permit statements with @denyException annotation
        List<RangerPolicyItem> denyExceptionItems = policy.getDenyExceptions();
        if (denyExceptionItems != null) {
            for (RangerPolicyItem item : denyExceptionItems) {
                statements.addAll(generateStatements(
                        item, "permit", policyId, adapter, resourceCombinations, "denyException"));
            }
        }

        // Row filter items → permit statements with when clause
        List<RangerRowFilterPolicyItem> rowFilterItems = policy.getRowFilterPolicyItems();
        if (rowFilterItems != null) {
            for (RangerRowFilterPolicyItem filterItem : rowFilterItems) {
                String filterExpr = null;
                if (filterItem.getRowFilterInfo() != null) {
                    filterExpr = filterItem.getRowFilterInfo().getFilterExpr();
                }
                if (filterExpr == null || filterExpr.trim().isEmpty()) {
                    LOG.warn("Policy {} ({}) - row filter item has empty filter expression, skipping",
                            policyId, policyName);
                    continue;
                }
                statements.addAll(generateStatements(
                        filterItem, "permit", policyId, adapter, resourceCombinations, filterExpr));
            }
        }

        return statements;
    }

    /**
     * Generate Cedar statements for a policy item.
     *
     * @param item                the policy item (allow, deny, deny-exception, or row-filter)
     * @param effect              "permit" or "forbid"
     * @param policyId            the source Ranger policy ID
     * @param adapter             the source policy adapter
     * @param resourceCombinations expanded resource combinations
     * @param extraAnnotation     if "denyException" → add @denyException("true");
     *                            if non-null and not "denyException" → treat as row filter expression
     * @return list of Cedar statement strings
     */
    private List<String> generateStatements(RangerPolicyItem item,
                                            String effect,
                                            String policyId,
                                            SourcePolicyAdapter adapter,
                                            List<ResourceCombination> resourceCombinations,
                                            String extraAnnotation) {
        List<String> statements = new ArrayList<>();

        // Resolve principals
        List<String> principalArns = resolvePrincipals(item);
        if (principalArns.isEmpty()) {
            return statements;
        }

        // Check for custom conditions on this item
        if (item.getConditions() != null && !item.getConditions().isEmpty()) {
            // Gap already recorded at policy level; conditions are excluded from Cedar
        }

        for (String principalArn : principalArns) {
            String principalRef = adapter.buildPrincipalRef(principalArn);
            for (ResourceCombination rc : resourceCombinations) {
                Set<String> cedarActions = extractCedarActions(item, adapter, rc.resourceLevel, policyId);
                if (cedarActions.isEmpty()) continue;
                for (String action : cedarActions) {
                    StringBuilder sb = new StringBuilder();

                    // Annotations — prefix with service type for namespace isolation
                    sb.append("@source(\"").append(adapter.getServiceType())
                            .append(":").append(policyId).append("\")\n");
                    if ("denyException".equals(extraAnnotation)) {
                        sb.append("@denyException(\"true\")\n");
                    }
                    if (Boolean.TRUE.equals(item.getDelegateAdmin())) {
                        sb.append("@grantable(\"true\")\n");
                    }

                    // Effect and scope
                    sb.append(effect).append("(\n");
                    String actionNamespace = action.startsWith("s3:") ? "S3" : "DataCatalog";
                    sb.append("    principal == DataCatalog::Principal::\"")
                            .append(principalRef).append("\",\n");
                    sb.append("    action == ").append(actionNamespace).append("::Action::\"")
                            .append(action).append("\",\n");
                    sb.append("    resource == ").append(rc.entityRef.getEntityType())
                            .append("::\"").append(rc.entityRef.getEntityId()).append("\"\n");
                    sb.append(")");

                    // Row filter when clause
                    if (extraAnnotation != null && !"denyException".equals(extraAnnotation)) {
                        sb.append("\nwhen { resource.rowFilter == \"")
                                .append(escapeQuotes(extraAnnotation)).append("\" }");
                    }

                    sb.append(";\n");
                    statements.add(sb.toString());
                }
            }
        }

        return statements;
    }

    private Set<String> extractCedarActions(RangerPolicyItem item, SourcePolicyAdapter adapter,
                                             String resourceLevel, String policyId) {
        if (item.getAccesses() == null || item.getAccesses().isEmpty()) {
            return Collections.emptySet();
        }
        java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
        for (RangerPolicyItemAccess access : item.getAccesses()) {
            if (access.getIsAllowed() == null || access.getIsAllowed()) {
                String accessType = access.getType();
                Set<String> mapped = adapter.mapAccessTypeToCedarActions(accessType, resourceLevel);
                if (mapped.isEmpty()) {
                    // If the 1-arg overload also returns empty, the type is genuinely unknown —
                    // the adapter already logs it, but we must also surface a gap so it is
                    // visible in the assessment report and not silently dropped.
                    Set<String> rawMapped = adapter.mapAccessTypeToCedarActions(accessType);
                    if (rawMapped.isEmpty()) {
                        gapReporter.recordGap(new GapEntry(
                                policyId, null, GapType.UNSUPPORTED_ACTION,
                                null,
                                "Access type '" + accessType + "' has no Cedar mapping for service '"
                                        + adapter.getServiceType() + "' and will be skipped.",
                                "Review the Ranger policy and remove or replace the unsupported access type."
                        ));
                    }
                    // If rawMapped is non-empty the type is known but filtered at this resource
                    // level (e.g. SELECT filtered from database level) — intentional, no gap.
                } else {
                    actions.addAll(mapped);
                }
            }
        }
        return actions;
    }

    private List<String> resolvePrincipals(RangerPolicyItem item) {
        List<String> arns = new ArrayList<>();

        if (item.getUsers() != null) {
            for (String user : item.getUsers()) {
                Optional<String> arn = principalMapper.resolveUser(user);
                arn.ifPresent(arns::add);
            }
        }

        if (item.getGroups() != null) {
            for (String group : item.getGroups()) {
                Optional<String> arn = principalMapper.resolveGroup(group);
                arn.ifPresent(arns::add);
            }
        }

        if (item.getRoles() != null) {
            for (String role : item.getRoles()) {
                Optional<String> arn = principalMapper.resolveRole(role);
                arn.ifPresent(arns::add);
            }
        }

        return arns;
    }

    private String determineResourceLevel(Map<String, RangerPolicyResource> resources) {
        if (hasResource(resources, "sthreeresource")) {
            return "sthreeresource";
        }
        if (hasResource(resources, "url")) {
            return "url";
        }
        if (hasResource(resources, "datalocation")) {
            return "datalocation";
        }
        if (hasResource(resources, "column")) {
            return "column";
        }
        if (hasResource(resources, "table")) {
            return "table";
        }
        return "database";
    }

    private boolean hasResource(Map<String, RangerPolicyResource> resources, String key) {
        RangerPolicyResource res = resources.get(key);
        return res != null && res.getValues() != null && !res.getValues().isEmpty();
    }

    private boolean isAllWildcard(List<String> values) {
        if (values == null || values.isEmpty()) return false;
        for (String v : values) {
            if (v == null || !v.matches("[*?]+")) return false;
        }
        return true;
    }

    private String promoteResourceLevel(String resourceLevel,
                                        Map<String, RangerPolicyResource> resources) {
        if ("column".equals(resourceLevel)) {
            List<String> colValues = getResourceValues(resources, "column");
            if (isAllWildcard(colValues)) {
                List<String> tableValues = getResourceValues(resources, "table");
                if (isAllWildcard(tableValues)) {
                    return "database";
                }
                return "table";
            }
        } else if ("table".equals(resourceLevel)) {
            List<String> tableValues = getResourceValues(resources, "table");
            if (isAllWildcard(tableValues)) {
                return "database";
            }
        }
        return resourceLevel;
    }

    /**
     * Expand wildcard patterns in resources and produce all concrete resource combinations.
     */
    private List<ResourceCombination> expandResources(
            Map<String, RangerPolicyResource> resources,
            String resourceLevel,
            SourcePolicyAdapter adapter,
            String policyId) {

        List<ResourceCombination> combinations = new ArrayList<>();

        if ("sthreeresource".equals(resourceLevel)) {
            if (!(adapter instanceof EmrfsServiceAdapter)) {
                LOG.error("Resource level 'sthreeresource' encountered with non-EMRFS adapter '{}' "
                        + "in policy {} — no Cedar statements will be produced.",
                        adapter.getClass().getSimpleName(), policyId);
                return combinations;
            }
            EmrfsServiceAdapter emrfsAdapter = (EmrfsServiceAdapter) adapter;
            RangerPolicyResource s3Resource = resources.get("sthreeresource");
            List<String> values = s3Resource != null ? s3Resource.getValues() : Collections.emptyList();
            boolean isRecursive = s3Resource != null && Boolean.TRUE.equals(s3Resource.getIsRecursive());
            List<CedarEntityRef> refs = emrfsAdapter.buildEntityRefFromValues(
                    "sthreeresource", values, isRecursive);
            for (CedarEntityRef ref : refs) {
                combinations.add(new ResourceCombination(ref, resourceLevel));
            }
            return combinations;
        }

        if ("datalocation".equals(resourceLevel)) {
            List<String> locations = getResourceValues(resources, "datalocation");
            for (String loc : locations) {
                Optional<String> normalized = normalizeS3Location(loc, policyId);
                if (!normalized.isPresent()) {
                    continue;
                }
                CedarEntityRef ref = buildEntityRef(adapter, "datalocation", null, null, null, normalized.get());
                if (ref != null) combinations.add(new ResourceCombination(ref, resourceLevel));
            }
            return combinations;
        }

        if ("url".equals(resourceLevel)) {
            List<String> urls = getResourceValues(resources, "url");
            for (String url : urls) {
                // Strip trailing wildcard suffix — s3://bucket/* → s3://bucket/ (same LF semantics)
                String effective = url;
                if (isWildcard(url)) {
                    // Only strip if this looks like an S3 URL with a trailing wildcard path component
                    int starIdx = url.indexOf('*');
                    int qIdx = url.indexOf('?');
                    int wildcardIdx = (starIdx >= 0 && qIdx >= 0) ? Math.min(starIdx, qIdx)
                            : (starIdx >= 0 ? starIdx : qIdx);
                    effective = url.substring(0, wildcardIdx);
                    // If stripping left nothing useful (e.g. just "s3://"), treat as wildcard gap
                    if (!effective.startsWith("s3://") && !effective.startsWith("s3a://")
                            && !effective.startsWith("s3n://")) {
                        gapReporter.recordGap(new GapEntry(
                                policyId, null, GapType.WILDCARD_PATTERN,
                                url,
                                "URL pattern '" + url + "' cannot be mapped to a Lake Formation data location.",
                                "Register specific S3 paths in Lake Formation as data locations."
                        ));
                        continue;
                    }
                }
                Optional<String> normalized = normalizeS3Location(effective, policyId);
                if (!normalized.isPresent()) {
                    continue;
                }
                CedarEntityRef ref = buildEntityRef(adapter, "url", null, null, null, normalized.get());
                if (ref != null) combinations.add(new ResourceCombination(ref, resourceLevel));
            }
            return combinations;
        }

        List<String> dbPatterns = getResourceValues(resources, "database");
        if (dbPatterns.isEmpty()) {
            return combinations;
        }

        List<String> expandedDatabases = expandPatterns(dbPatterns);

        if ("database".equals(resourceLevel)) {
            for (String db : expandedDatabases) {
                CedarEntityRef ref = buildEntityRef(adapter, "database", db, null, null, null);
                if (ref != null) combinations.add(new ResourceCombination(ref, resourceLevel));
            }
            return combinations;
        }

        List<String> tablePatterns = getResourceValues(resources, "table");

        if ("table".equals(resourceLevel)) {
            for (String db : expandedDatabases) {
                List<String> expandedTables = expandTablePatterns(tablePatterns, db, policyId);
                for (String table : expandedTables) {
                    CedarEntityRef ref = buildEntityRef(adapter, "table", db, table, null, null);
                    if (ref != null) combinations.add(new ResourceCombination(ref, resourceLevel));
                }
            }
            return combinations;
        }

        // column level
        List<String> columnPatterns = getResourceValues(resources, "column");
        for (String db : expandedDatabases) {
            List<String> expandedTables = expandTablePatterns(tablePatterns, db, policyId);
            for (String table : expandedTables) {
                List<String> expandedColumns = expandColumnPatterns(columnPatterns, db, table, policyId);
                for (String col : expandedColumns) {
                    CedarEntityRef ref = buildEntityRef(adapter, "column", db, table, col, null);
                    if (ref != null) combinations.add(new ResourceCombination(ref, resourceLevel));
                }
            }
        }

        return combinations;
    }

    private CedarEntityRef buildEntityRef(SourcePolicyAdapter adapter,
                                          String resourceLevel,
                                          String database,
                                          String table,
                                          String column,
                                          String dataLocation) {
        if (adapter instanceof RangerServiceAdapter) {
            return ((RangerServiceAdapter) adapter).buildEntityRefFromValues(
                    resourceLevel, database, table, column, dataLocation);
        }
        if (adapter instanceof EmrSparkServiceAdapter) {
            return ((EmrSparkServiceAdapter) adapter).buildEntityRefFromValues(
                    resourceLevel, database, table, column, dataLocation);
        }
        if (adapter instanceof HiveServiceAdapter) {
            return ((HiveServiceAdapter) adapter).buildEntityRefFromValues(
                    resourceLevel, database, table, column, dataLocation);
        }
        if (adapter instanceof PrestoServiceAdapter) {
            return ((PrestoServiceAdapter) adapter).buildEntityRefFromValues(
                    resourceLevel, database, table, column, dataLocation);
        }
        if (adapter instanceof TrinoServiceAdapter) {
            return ((TrinoServiceAdapter) adapter).buildEntityRefFromValues(
                    resourceLevel, database, table, column, dataLocation);
        }
        // No instanceof matched — a new adapter was added without a corresponding case here.
        LOG.error("No buildEntityRef case for adapter type '{}' at resource level '{}' — "
                + "this policy will produce no Cedar statements. Add an instanceof case to "
                + "RangerToCedarConverter.buildEntityRef().",
                adapter.getClass().getSimpleName(), resourceLevel);
        return null;
    }

    private List<String> expandPatterns(List<String> patterns) {
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                List<String> resolved = catalogResolver.expandDatabases(pattern);
                expanded.addAll(resolved);
            } else {
                expanded.add(pattern);
            }
        }
        return expanded;
    }

    private List<String> expandTablePatterns(List<String> patterns, String database, String policyId) {
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                List<String> resolved = catalogResolver.expandTables(database, pattern);
                expanded.addAll(resolved);
                if (resolved.size() == 1 && resolved.get(0).equals(pattern)) {
                    gapReporter.recordGap(new GapEntry(
                            policyId, null, GapType.WILDCARD_PATTERN,
                            database + "/" + pattern,
                            "Table pattern '" + pattern + "' in database '" + database
                                    + "' could not be expanded (no Glue catalog access). "
                                    + "The ARN produced is a placeholder.",
                            "Re-run with AWS credentials configured to expand wildcard table patterns."
                    ));
                }
            } else {
                expanded.add(pattern);
            }
        }
        return expanded;
    }

    private List<String> expandColumnPatterns(List<String> patterns, String database,
                                              String table, String policyId) {
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                List<String> resolved = catalogResolver.expandColumns(database, table, pattern);
                expanded.addAll(resolved);
                if (resolved.size() == 1 && resolved.get(0).equals(pattern)) {
                    gapReporter.recordGap(new GapEntry(
                            policyId, null, GapType.WILDCARD_PATTERN,
                            database + "/" + table + "/" + pattern,
                            "Column pattern '" + pattern + "' in " + database + "." + table
                                    + " could not be expanded (no Glue catalog access). "
                                    + "The ARN produced is a placeholder.",
                            "Re-run with AWS credentials configured to expand wildcard column patterns."
                    ));
                }
            } else {
                expanded.add(pattern);
            }
        }
        return expanded;
    }

    private boolean isWildcard(String pattern) {
        return pattern != null && (pattern.contains("*") || pattern.contains("?"));
    }

    private List<String> getResourceValues(Map<String, RangerPolicyResource> resources, String key) {
        if (resources == null) {
            return Collections.emptyList();
        }
        RangerPolicyResource res = resources.get(key);
        if (res == null || res.getValues() == null) {
            return Collections.emptyList();
        }
        return res.getValues();
    }

    private void recordCustomConditionGaps(RangerPolicy policy, String policyId, String policyName) {
        // Check policy-level conditions
        if (policy.getConditions() != null && !policy.getConditions().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.CUSTOM_CONDITION,
                    buildResourcePath(policy),
                    "Custom policy conditions detected. Cedar does not support runtime context conditions.",
                    "Implement conditional access control externally or remove conditions."
            ));
        }

        // Check item-level conditions
        boolean hasItemConditions = false;
        if (policy.getPolicyItems() != null) {
            for (RangerPolicyItem item : policy.getPolicyItems()) {
                if (item.getConditions() != null && !item.getConditions().isEmpty()) {
                    hasItemConditions = true;
                    break;
                }
            }
        }
        if (!hasItemConditions && policy.getDenyPolicyItems() != null) {
            for (RangerPolicyItem item : policy.getDenyPolicyItems()) {
                if (item.getConditions() != null && !item.getConditions().isEmpty()) {
                    hasItemConditions = true;
                    break;
                }
            }
        }
        if (hasItemConditions && (policy.getConditions() == null || policy.getConditions().isEmpty())) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.CUSTOM_CONDITION,
                    buildResourcePath(policy),
                    "Custom conditions detected on policy items. Cedar does not support runtime context conditions.",
                    "Implement conditional access control externally or remove conditions."
            ));
        }
    }

    /**
     * Parse the concatenated Cedar text and validate against the schema.
     * Invalid statements are excluded and recorded as gaps.
     */
    private CedarPolicySet parseAndValidate(String cedarText, List<String> individualStatements) {
        if (cedarText == null || cedarText.trim().isEmpty()) {
            try {
                return CedarPolicySet.fromCedarString("");
            } catch (InternalException e) {
                throw new RuntimeException("Failed to create empty CedarPolicySet", e);
            }
        }

        try {
            CedarPolicySet policySet = CedarPolicySet.fromCedarString(cedarText);

            // Validate against schema
            List<String> validationErrors = schemaProvider.validatePolicySet(
                    policySet.getInternalPolicySet());

            if (validationErrors.isEmpty()) {
                return policySet;
            }

            // Validation failed — try to identify and exclude invalid statements
            LOG.warn("Cedar PolicySet validation failed with {} errors, attempting per-statement validation",
                    validationErrors.size());

            return rebuildExcludingInvalid(individualStatements);

        } catch (InternalException e) {
            LOG.error("Failed to parse Cedar policy text: {}", e.getMessage());
            // Try per-statement parsing to salvage valid statements
            return rebuildExcludingInvalid(individualStatements);
        }
    }

    /**
     * Rebuild the PolicySet by parsing each statement individually,
     * excluding those that fail parsing or schema validation.
     */
    private CedarPolicySet rebuildExcludingInvalid(List<String> statements) {
        List<String> validStatements = new ArrayList<>();

        for (String statement : statements) {
            try {
                CedarPolicySet single = CedarPolicySet.fromCedarString(statement);
                List<String> errors = schemaProvider.validatePolicySet(single.getInternalPolicySet());
                if (errors.isEmpty()) {
                    validStatements.add(statement);
                } else {
                    String policyId = extractPolicyIdFromStatement(statement);
                    gapReporter.recordGap(new GapEntry(
                            policyId, null, GapType.SCHEMA_VALIDATION_FAILURE,
                            null,
                            "Cedar statement failed schema validation: " + String.join("; ", errors),
                            "Review the generated Cedar statement and fix the resource/action mapping."
                    ));
                    LOG.warn("Excluding invalid Cedar statement for policy {}: {}", policyId, errors);
                }
            } catch (InternalException e) {
                String policyId = extractPolicyIdFromStatement(statement);
                gapReporter.recordGap(new GapEntry(
                        policyId, null, GapType.SCHEMA_VALIDATION_FAILURE,
                        null,
                        "Cedar statement failed to parse: " + e.getMessage(),
                        "Review the generated Cedar statement syntax."
                ));
                LOG.warn("Excluding unparseable Cedar statement for policy {}: {}", policyId, e.getMessage());
            }
        }

        String validText = String.join("\n", validStatements);
        try {
            return CedarPolicySet.fromCedarString(validText.isEmpty() ? "" : validText);
        } catch (InternalException e) {
            LOG.error("Failed to parse validated Cedar statements: {}", e.getMessage());
            try {
                return CedarPolicySet.fromCedarString("");
            } catch (InternalException ex) {
                throw new RuntimeException("Failed to create empty CedarPolicySet", ex);
            }
        }
    }

    private String extractPolicyIdFromStatement(String statement) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("@source\\(\"([^\"]+)\"\\)").matcher(statement);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    private String buildResourcePath(RangerPolicy policy) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            return "<no resources>";
        }
        StringBuilder sb = new StringBuilder();
        appendResourcePart(sb, resources, "database");
        appendResourcePart(sb, resources, "table");
        appendResourcePart(sb, resources, "column");
        appendResourcePart(sb, resources, "url");
        return sb.length() > 0 ? sb.toString() : "<no resources>";
    }

    private void appendResourcePart(StringBuilder sb, Map<String, RangerPolicyResource> resources, String key) {
        RangerPolicyResource res = resources.get(key);
        if (res != null && res.getValues() != null && !res.getValues().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(String.join(",", res.getValues()));
        }
    }

    private String escapeQuotes(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Normalize a raw location value from a Ranger policy resource to a canonical
     * {@code s3://} URL, or return empty if the scheme is not S3-compatible.
     *
     * <p>Supported normalizations:
     * <ul>
     *   <li>{@code s3://bucket/path}  → kept as-is</li>
     *   <li>{@code s3a://bucket/path} → {@code s3://bucket/path}</li>
     *   <li>{@code s3n://bucket/path} → {@code s3://bucket/path}</li>
     * </ul>
     *
     * <p>HDFS paths ({@code hdfs://}, bare {@code /...}), {@code file://} URIs,
     * and any other non-S3 scheme are not convertible to Lake Formation data
     * locations and are returned as empty — callers must record a gap and skip.
     *
     * @param rawLocation the raw string value from the Ranger policy
     * @param policyId    the source policy ID used when recording a gap
     * @return {@code Optional.of(normalizedS3Url)} if S3-compatible, or
     *         {@code Optional.empty()} if the location is unsupported (gap recorded)
     */
    Optional<String> normalizeS3Location(String rawLocation, String policyId) {
        if (rawLocation == null || rawLocation.trim().isEmpty()) {
            return Optional.empty();
        }
        String loc = rawLocation.trim();

        if (loc.startsWith("s3://")) {
            return Optional.of(loc);
        }
        if (loc.startsWith("s3a://")) {
            return Optional.of("s3://" + loc.substring("s3a://".length()));
        }
        if (loc.startsWith("s3n://")) {
            return Optional.of("s3://" + loc.substring("s3n://".length()));
        }

        // Unsupported: HDFS, file://, bare paths, etc.
        String scheme = loc.contains("://") ? loc.substring(0, loc.indexOf("://") + 3) : "(no scheme)";
        LOG.warn("Skipping non-S3 location '{}' in policy {} — scheme {} is not supported by Lake Formation",
                loc, policyId, scheme);
        gapReporter.recordGap(new GapEntry(
                policyId, null, GapType.UNMAPPED_RESOURCE,
                loc,
                "Location '" + loc + "' uses an unsupported scheme (" + scheme
                        + ") and cannot be mapped to a Lake Formation data location.",
                "Convert HDFS/file paths to S3 URIs (s3://) and re-run the assessment."
        ));
        return Optional.empty();
    }

    /**
     * Simple holder for an expanded resource entity reference.
     */
    static class ResourceCombination {
        final CedarEntityRef entityRef;
        final String resourceLevel;

        ResourceCombination(CedarEntityRef entityRef, String resourceLevel) {
            this.entityRef = entityRef;
            this.resourceLevel = resourceLevel;
        }
    }
}
