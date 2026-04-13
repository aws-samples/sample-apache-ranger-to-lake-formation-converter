package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
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

        // Extract resources and determine resource level
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            LOG.warn("Policy {} ({}) has no resources, skipping", policyId, policyName);
            return Collections.emptyList();
        }

        String resourceLevel = determineResourceLevel(resources);

        // Expand wildcards and build resource combinations
        List<ResourceCombination> resourceCombinations = expandResources(
                resources, resourceLevel, adapter);

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

        // Extract Cedar actions
        Set<String> cedarActions = extractCedarActions(item, adapter);
        if (cedarActions.isEmpty()) {
            return statements;
        }

        // Check for custom conditions on this item
        if (item.getConditions() != null && !item.getConditions().isEmpty()) {
            // Gap already recorded at policy level; conditions are excluded from Cedar
        }

        for (String principalArn : principalArns) {
            String principalRef = adapter.buildPrincipalRef(principalArn);
            for (ResourceCombination rc : resourceCombinations) {
                for (String action : cedarActions) {
                    StringBuilder sb = new StringBuilder();

                    // Annotations — prefix with service type for namespace isolation
                    sb.append("@source(\"").append(adapter.getServiceType())
                            .append(":").append(policyId).append("\")\n");
                    if ("denyException".equals(extraAnnotation)) {
                        sb.append("@denyException(\"true\")\n");
                    }

                    // Effect and scope
                    sb.append(effect).append("(\n");
                    sb.append("    principal == DataCatalog::Principal::\"")
                            .append(principalRef).append("\",\n");
                    sb.append("    action == DataCatalog::Action::\"")
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

    private Set<String> extractCedarActions(RangerPolicyItem item, SourcePolicyAdapter adapter) {
        if (item.getAccesses() == null || item.getAccesses().isEmpty()) {
            return Collections.emptySet();
        }
        java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
        for (RangerPolicyItemAccess access : item.getAccesses()) {
            if (access.getIsAllowed() == null || access.getIsAllowed()) {
                Set<String> mapped = adapter.mapAccessTypeToCedarActions(access.getType());
                actions.addAll(mapped);
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

    /**
     * Expand wildcard patterns in resources and produce all concrete resource combinations.
     */
    private List<ResourceCombination> expandResources(
            Map<String, RangerPolicyResource> resources,
            String resourceLevel,
            SourcePolicyAdapter adapter) {

        List<ResourceCombination> combinations = new ArrayList<>();

        if ("datalocation".equals(resourceLevel)) {
            List<String> locations = getResourceValues(resources, "datalocation");
            for (String loc : locations) {
                CedarEntityRef ref = buildEntityRef(adapter, "datalocation", null, null, null, loc);
                combinations.add(new ResourceCombination(ref));
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
                combinations.add(new ResourceCombination(ref));
            }
            return combinations;
        }

        List<String> tablePatterns = getResourceValues(resources, "table");

        if ("table".equals(resourceLevel)) {
            for (String db : expandedDatabases) {
                List<String> expandedTables = expandTablePatterns(tablePatterns, db);
                for (String table : expandedTables) {
                    CedarEntityRef ref = buildEntityRef(adapter, "table", db, table, null, null);
                    combinations.add(new ResourceCombination(ref));
                }
            }
            return combinations;
        }

        // column level
        List<String> columnPatterns = getResourceValues(resources, "column");
        for (String db : expandedDatabases) {
            List<String> expandedTables = expandTablePatterns(tablePatterns, db);
            for (String table : expandedTables) {
                List<String> expandedColumns = expandColumnPatterns(columnPatterns, db, table);
                for (String col : expandedColumns) {
                    CedarEntityRef ref = buildEntityRef(adapter, "column", db, table, col, null);
                    combinations.add(new ResourceCombination(ref));
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
        // Fallback: construct a simple entity ref
        String entityType;
        String entityId;
        switch (resourceLevel) {
            case "database":
                entityType = "DataCatalog::Database";
                entityId = database;
                break;
            case "table":
                entityType = "DataCatalog::Table";
                entityId = database + "/" + table;
                break;
            case "column":
                entityType = "DataCatalog::Column";
                entityId = database + "/" + table + "/" + column;
                break;
            case "datalocation":
                entityType = "DataCatalog::DataLocation";
                entityId = dataLocation;
                break;
            default:
                throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
        }
        return new CedarEntityRef(entityType, entityId);
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

    private List<String> expandTablePatterns(List<String> patterns, String database) {
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                expanded.addAll(catalogResolver.expandTables(database, pattern));
            } else {
                expanded.add(pattern);
            }
        }
        return expanded;
    }

    private List<String> expandColumnPatterns(List<String> patterns, String database, String table) {
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                expanded.addAll(catalogResolver.expandColumns(database, table, pattern));
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
     * Simple holder for an expanded resource entity reference.
     */
    static class ResourceCombination {
        final CedarEntityRef entityRef;

        ResourceCombination(CedarEntityRef entityRef) {
            this.entityRef = entityRef;
        }
    }
}
