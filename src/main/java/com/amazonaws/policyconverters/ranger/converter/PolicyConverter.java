package com.amazonaws.policyconverters.ranger.converter;

import com.amazonaws.policyconverters.ranger.catalog.CatalogResolver;
import com.amazonaws.policyconverters.ranger.mapper.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.model.GapEntry;
import com.amazonaws.policyconverters.lakeformation.model.GapEntry.GapType;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.reporter.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItem;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyItemAccess;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.model.RangerPolicy.RangerRowFilterPolicyItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts Apache Ranger policies into AWS Lake Formation permission operations.
 * <p>
 * For each policy, the converter:
 * <ul>
 *   <li>Detects and records unsupported features (data masking, tag-based, deny, etc.) in the GapReporter</li>
 *   <li>Extracts database/table/column resources and expands wildcards via CatalogResolver</li>
 *   <li>Converts allow policy items into LF GRANT operations using AccessTypeMapper and PrincipalMapper</li>
 *   <li>Converts row filter policy items into LF operations with row filter expressions</li>
 *   <li>Skips malformed policies with error logging</li>
 * </ul>
 */
public class PolicyConverter {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyConverter.class);

    private final String catalogId;

    /**
     * Create a PolicyConverter with the given AWS Glue catalog ID.
     *
     * @param catalogId the AWS Glue Data Catalog ID to use in LFResource objects
     */
    public PolicyConverter(String catalogId) {
        this.catalogId = catalogId;
    }

    /**
     * Convert a single Ranger policy into LF permission operations.
     * Unsupported features are recorded in the GapReporter.
     *
     * @param policy          the Ranger policy to convert
     * @param principalMapper maps Ranger principals to IAM ARNs
     * @param catalogResolver resolves wildcards against Glue Catalog
     * @param gapReporter     collects unsupported feature entries
     * @return list of LF permission operations (grants)
     */
    public List<LFPermissionOperation> convert(
            RangerPolicy policy,
            PrincipalMapper principalMapper,
            CatalogResolver catalogResolver,
            GapReporter gapReporter) {

        if (policy == null) {
            LOG.error("PolicyConverter: null policy provided, skipping");
            return Collections.emptyList();
        }

        String policyId = policy.getId() != null ? String.valueOf(policy.getId()) : "unknown";
        String policyName = policy.getName() != null ? policy.getName() : "unknown";

        // Check for tag-based policy (policyType=1 is datamask, but tag-based is identified by service type)
        if (isTagBasedPolicy(policy)) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.TAG_BASED_POLICY,
                    buildResourcePath(policy),
                    "Tag-based policy detected. Ranger tags are structurally different from LF-Tags.",
                    "Manually map Ranger tags to LF-Tags or create equivalent resource-based policies."
            ));
            return Collections.emptyList();
        }

        // Validate resources exist
        if (!hasValidResources(policy)) {
            LOG.error("PolicyConverter: Policy {} ({}) has missing or empty resources, skipping",
                    policyId, policyName);
            return Collections.emptyList();
        }

        // Record unsupported features as gaps (but continue converting supported portions)
        recordUnsupportedFeatures(policy, policyId, policyName, gapReporter);

        List<LFPermissionOperation> operations = new ArrayList<>();

        // Extract resource definitions
        Map<String, RangerPolicyResource> resources = policy.getResources();
        List<String> databases = getResourceValues(resources, "database");
        List<String> tables = getResourceValues(resources, "table");
        List<String> columns = getResourceValues(resources, "column");
        List<String> dataLocations = getResourceValues(resources, "datalocation");

        // Handle data location resources (S3 paths)
        if (!dataLocations.isEmpty()) {
            List<RangerPolicyItem> allowItems = policy.getPolicyItems();
            if (allowItems != null) {
                for (RangerPolicyItem item : allowItems) {
                    boolean delegateAdmin = item.getDelegateAdmin() != null && item.getDelegateAdmin();
                    Set<LFPermission> permissions = extractPermissions(item);
                    // For data location policies, only DATA_LOCATION_ACCESS is valid
                    if (!permissions.contains(LFPermission.DATA_LOCATION_ACCESS)) {
                        continue;
                    }
                    Set<LFPermission> dlPermissions = Collections.unmodifiableSet(
                            EnumSet.of(LFPermission.DATA_LOCATION_ACCESS));

                    List<String> principalArns = resolvePrincipals(item, principalMapper);
                    for (String arn : principalArns) {
                        for (String path : dataLocations) {
                            LFResource resource = new LFResource(
                                    catalogId, null, null, null, null, path);
                            operations.add(new LFPermissionOperation(
                                    OperationType.GRANT, policyId, arn, resource,
                                    dlPermissions, delegateAdmin));
                        }
                    }
                }
            }
        }

        // Handle database/table/column resources
        if (databases.isEmpty()) {
            // No database resources — return whatever data location operations we have
            return operations;
        }

        // Expand wildcards
        List<String> expandedDatabases = expandPatterns(databases, catalogResolver);
        if (expandedDatabases.isEmpty()) {
            LOG.warn("PolicyConverter: Policy {} ({}) - no databases resolved after expansion, skipping",
                    policyId, policyName);
            return Collections.emptyList();
        }

        // Convert allow policy items
        List<RangerPolicyItem> allowItems = policy.getPolicyItems();
        if (allowItems != null && !allowItems.isEmpty()) {
            for (RangerPolicyItem item : allowItems) {
                boolean delegateAdmin = item.getDelegateAdmin() != null && item.getDelegateAdmin();
                if (delegateAdmin) {
                    gapReporter.recordGap(new GapEntry(
                            policyId, policyName, GapType.DELEGATED_ADMIN,
                            buildResourcePath(policy),
                            "Delegated admin permission detected on allow policy item.",
                            "Lake Formation uses a different admin delegation model. Review and configure manually."
                    ));
                }

                Set<LFPermission> permissions = extractPermissions(item);
                if (permissions.isEmpty()) {
                    continue;
                }

                List<String> principalArns = resolvePrincipals(item, principalMapper);
                if (principalArns.isEmpty()) {
                    continue;
                }

                // Generate operations for each expanded resource combination
                for (String arn : principalArns) {
                    operations.addAll(buildResourceOperations(
                            policyId, arn, permissions, delegateAdmin,
                            expandedDatabases, tables, columns, catalogResolver));
                }
            }
        }

        // Convert row filter policy items
        List<RangerRowFilterPolicyItem> rowFilterItems = policy.getRowFilterPolicyItems();
        if (rowFilterItems != null && !rowFilterItems.isEmpty()) {
            for (RangerRowFilterPolicyItem filterItem : rowFilterItems) {
                String filterExpr = null;
                if (filterItem.getRowFilterInfo() != null) {
                    filterExpr = filterItem.getRowFilterInfo().getFilterExpr();
                }
                if (filterExpr == null || filterExpr.trim().isEmpty()) {
                    LOG.warn("PolicyConverter: Policy {} ({}) - row filter item has empty filter expression, skipping",
                            policyId, policyName);
                    continue;
                }

                boolean delegateAdmin = filterItem.getDelegateAdmin() != null && filterItem.getDelegateAdmin();
                Set<LFPermission> permissions = extractPermissions(filterItem);
                if (permissions.isEmpty()) {
                    // Row filters typically imply SELECT
                    permissions = EnumSet.of(LFPermission.SELECT);
                }

                List<String> principalArns = resolvePrincipals(filterItem, principalMapper);
                if (principalArns.isEmpty()) {
                    continue;
                }

                for (String arn : principalArns) {
                    for (String db : expandedDatabases) {
                        List<String> expandedTables = expandTablePatterns(tables, db, catalogResolver);
                        for (String table : expandedTables) {
                            LFResource resource = new LFResource(catalogId, db, table, null, filterExpr);
                            operations.add(new LFPermissionOperation(
                                    OperationType.GRANT, policyId, arn, resource, permissions, delegateAdmin));
                        }
                    }
                }
            }
        }

        return operations;
    }

    /**
     * Convert a batch of Ranger policies.
     *
     * @param policies        the policies to convert
     * @param principalMapper maps Ranger principals to IAM ARNs
     * @param catalogResolver resolves wildcards against Glue Catalog
     * @param gapReporter     collects unsupported feature entries
     * @return ConversionResult with operations, success count, and skipped count
     */
    public ConversionResult convertBatch(
            List<RangerPolicy> policies,
            PrincipalMapper principalMapper,
            CatalogResolver catalogResolver,
            GapReporter gapReporter) {

        if (policies == null || policies.isEmpty()) {
            return new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 0, 0);
        }

        List<LFPermissionOperation> allOperations = new ArrayList<>();
        int successCount = 0;
        int skippedCount = 0;

        for (RangerPolicy policy : policies) {
            try {
                List<LFPermissionOperation> ops = convert(policy, principalMapper, catalogResolver, gapReporter);
                allOperations.addAll(ops);
                successCount++;
            } catch (Exception e) {
                String policyId = policy != null && policy.getId() != null
                        ? String.valueOf(policy.getId()) : "unknown";
                String policyName = policy != null && policy.getName() != null
                        ? policy.getName() : "unknown";
                LOG.error("PolicyConverter: Failed to convert policy {} ({}): {}",
                        policyId, policyName, e.getMessage(), e);
                skippedCount++;
            }
        }

        LOG.info("PolicyConverter: Batch conversion complete - {} succeeded, {} skipped, {} operations generated",
                successCount, skippedCount, allOperations.size());

        return new ConversionResult(allOperations, successCount, skippedCount);
    }

    /**
     * Check if a policy is tag-based. Tag-based policies have policyType != 0
     * or belong to a tag-based service (service name containing "tag").
     */
    private boolean isTagBasedPolicy(RangerPolicy policy) {
        // policyType: 0=Access, 1=Datamask, 2=RowFilter
        // Tag-based policies are typically identified by the service type containing "tag"
        if (policy.getService() != null && policy.getService().toLowerCase().contains("tag")) {
            return true;
        }
        return false;
    }

    /**
     * Check if the policy has valid (non-empty) resource definitions.
     */
    private boolean hasValidResources(RangerPolicy policy) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            return false;
        }
        // Must have at least a database resource or a datalocation resource
        RangerPolicyResource dbResource = resources.get("database");
        boolean hasDatabase = dbResource != null && dbResource.getValues() != null && !dbResource.getValues().isEmpty();
        RangerPolicyResource dlResource = resources.get("datalocation");
        boolean hasDataLocation = dlResource != null && dlResource.getValues() != null && !dlResource.getValues().isEmpty();
        return hasDatabase || hasDataLocation;
    }

    /**
     * Record all unsupported features found in the policy as gap entries.
     */
    private void recordUnsupportedFeatures(RangerPolicy policy, String policyId, String policyName,
                                           GapReporter gapReporter) {
        String resourcePath = buildResourcePath(policy);

        // Data masking
        if (policy.getDataMaskPolicyItems() != null && !policy.getDataMaskPolicyItems().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.DATA_MASKING, resourcePath,
                    "Data masking policy items detected (" + policy.getDataMaskPolicyItems().size() + " items). "
                            + "Lake Formation does not support native data masking.",
                    "Consider using column-level permissions or external masking solutions."
            ));
        }

        // Deny policy items
        if (policy.getDenyPolicyItems() != null && !policy.getDenyPolicyItems().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.DENY_POLICY, resourcePath,
                    "Deny policy items detected (" + policy.getDenyPolicyItems().size() + " items). "
                            + "Lake Formation uses a grant-only model.",
                    "Review deny rules and implement equivalent restrictions using grant-only permissions."
            ));
        }

        // Deny exceptions
        if (policy.getDenyExceptions() != null && !policy.getDenyExceptions().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.DENY_EXCEPTION, resourcePath,
                    "Deny exception items detected (" + policy.getDenyExceptions().size() + " items). "
                            + "Lake Formation uses a grant-only model.",
                    "Review deny exception rules and adjust grant permissions accordingly."
            ));
        }

        // Validity schedules
        if (policy.getValiditySchedules() != null && !policy.getValiditySchedules().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.VALIDITY_SCHEDULE, resourcePath,
                    "Validity schedules detected (" + policy.getValiditySchedules().size() + " schedules). "
                            + "Lake Formation does not support temporal policy constraints.",
                    "Implement time-based access control externally or remove time constraints."
            ));
        }

        // Custom conditions
        if (policy.getConditions() != null && !policy.getConditions().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.CUSTOM_CONDITION, resourcePath,
                    "Custom policy conditions detected (" + policy.getConditions().size() + " conditions). "
                            + "Lake Formation does not support conditional policies.",
                    "Implement conditional access control externally or remove conditions."
            ));
        }

        // Security zone
        if (policy.getZoneName() != null && !policy.getZoneName().trim().isEmpty()) {
            gapReporter.recordGap(new GapEntry(
                    policyId, policyName, GapType.SECURITY_ZONE, resourcePath,
                    "Security zone '" + policy.getZoneName() + "' referenced. "
                            + "Lake Formation has no equivalent concept.",
                    "Review zone-based policies and create equivalent resource-scoped permissions."
            ));
        }
    }

    /**
     * Build a human-readable resource path string from the policy's resources.
     */
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

    /**
     * Get the list of values for a resource key, or empty list if not present.
     */
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

    /**
     * Expand database patterns using the CatalogResolver.
     * Non-wildcard patterns are passed through as-is.
     */
    private List<String> expandPatterns(List<String> patterns, CatalogResolver catalogResolver) {
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                List<String> resolved = catalogResolver.expandDatabases(pattern);
                if (resolved.isEmpty()) {
                    LOG.warn("PolicyConverter: Wildcard database pattern '{}' matched no databases", pattern);
                }
                expanded.addAll(resolved);
            } else {
                expanded.add(pattern);
            }
        }
        return expanded;
    }

    /**
     * Expand table patterns within a database using the CatalogResolver.
     * Non-wildcard patterns are passed through as-is.
     * If no table patterns are specified, returns a single-element list with null
     * to indicate database-level permission.
     */
    private List<String> expandTablePatterns(List<String> patterns, String database,
                                             CatalogResolver catalogResolver) {
        if (patterns == null || patterns.isEmpty()) {
            return Collections.singletonList(null);
        }
        List<String> expanded = new ArrayList<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                List<String> resolved = catalogResolver.expandTables(database, pattern);
                if (resolved.isEmpty()) {
                    LOG.warn("PolicyConverter: Wildcard table pattern '{}' in database '{}' matched no tables",
                            pattern, database);
                }
                expanded.addAll(resolved);
            } else {
                expanded.add(pattern);
            }
        }
        return expanded.isEmpty() ? Collections.singletonList((String) null) : expanded;
    }

    /**
     * Expand column patterns within a database/table using the CatalogResolver.
     * Non-wildcard patterns are passed through as-is.
     */
    private Set<String> expandColumnPatterns(List<String> patterns, String database, String table,
                                             CatalogResolver catalogResolver) {
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> expanded = new HashSet<>();
        for (String pattern : patterns) {
            if (isWildcard(pattern)) {
                expanded.addAll(catalogResolver.expandColumns(database, table, pattern));
            } else {
                expanded.add(pattern);
            }
        }
        return expanded;
    }

    /**
     * Check if a pattern contains wildcard characters.
     */
    private boolean isWildcard(String pattern) {
        return pattern != null && (pattern.contains("*") || pattern.contains("?"));
    }

    /**
     * Extract LF permissions from a policy item's access types.
     */
    private Set<LFPermission> extractPermissions(RangerPolicyItem item) {
        if (item.getAccesses() == null || item.getAccesses().isEmpty()) {
            return Collections.emptySet();
        }
        List<String> accessTypes = new ArrayList<>();
        for (RangerPolicyItemAccess access : item.getAccesses()) {
            if (access.getIsAllowed() == null || access.getIsAllowed()) {
                accessTypes.add(access.getType());
            }
        }
        return AccessTypeMapper.mapAccessTypes(accessTypes);
    }

    /**
     * Resolve all principals (users, groups, roles) from a policy item to IAM ARNs.
     */
    private List<String> resolvePrincipals(RangerPolicyItem item, PrincipalMapper principalMapper) {
        List<String> arns = new ArrayList<>();

        if (item.getUsers() != null) {
            for (String user : item.getUsers()) {
                Optional<String> arn = principalMapper.resolveUser(user);
                if (arn.isPresent()) {
                    arns.add(arn.get());
                }
            }
        }

        if (item.getGroups() != null) {
            for (String group : item.getGroups()) {
                Optional<String> arn = principalMapper.resolveGroup(group);
                if (arn.isPresent()) {
                    arns.add(arn.get());
                }
            }
        }

        if (item.getRoles() != null) {
            for (String role : item.getRoles()) {
                Optional<String> arn = principalMapper.resolveRole(role);
                if (arn.isPresent()) {
                    arns.add(arn.get());
                }
            }
        }

        return arns;
    }

    /**
     * Build LF permission operations for all expanded resource combinations.
     */
    private List<LFPermissionOperation> buildResourceOperations(
            String policyId, String principalArn, Set<LFPermission> permissions, boolean delegateAdmin,
            List<String> databases, List<String> tablePatterns, List<String> columnPatterns,
            CatalogResolver catalogResolver) {

        List<LFPermissionOperation> operations = new ArrayList<>();

        for (String db : databases) {
            List<String> expandedTables = expandTablePatterns(tablePatterns, db, catalogResolver);

            for (String table : expandedTables) {
                if (table == null) {
                    // Database-level permission
                    LFResource resource = new LFResource(catalogId, db, null, null, null);
                    operations.add(new LFPermissionOperation(
                            OperationType.GRANT, policyId, principalArn, resource, permissions, delegateAdmin));
                } else {
                    Set<String> expandedColumns = expandColumnPatterns(columnPatterns, db, table, catalogResolver);

                    if (expandedColumns.isEmpty()) {
                        // Table-level permission (no columns specified)
                        LFResource resource = new LFResource(catalogId, db, table, null, null);
                        operations.add(new LFPermissionOperation(
                                OperationType.GRANT, policyId, principalArn, resource, permissions, delegateAdmin));
                    } else {
                        // Column-level permission — DESCRIBE is not valid at column level in LF
                        Set<LFPermission> columnPermissions = EnumSet.copyOf(permissions);
                        columnPermissions.remove(LFPermission.DESCRIBE);
                        if (columnPermissions.isEmpty()) {
                            LOG.warn("PolicyConverter: skipping column-level operation for policy {} "
                                    + "on {}.{} — no valid permissions remain after removing DESCRIBE",
                                    policyId, db, table);
                            continue;
                        }
                        LFResource resource = new LFResource(catalogId, db, table, expandedColumns, null);
                        operations.add(new LFPermissionOperation(
                                OperationType.GRANT, policyId, principalArn, resource,
                                Collections.unmodifiableSet(columnPermissions), delegateAdmin));
                    }
                }
            }
        }

        return operations;
    }
}
