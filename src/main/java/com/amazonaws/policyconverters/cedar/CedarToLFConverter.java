package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.lakeformation.ArnParser;
import com.amazonaws.policyconverters.lakeformation.GlueResourceRef;
import com.amazonaws.policyconverters.lakeformation.S3ResourceRef;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses Cedar permit/forbid/deny-exception resolution to materialize effective
 * permissions per principal, then converts to {@link LFPermissionOperation} objects.
 *
 * <p>For each unique principal in the PolicySet:
 * <ol>
 *   <li>Collect all permit statements → set of (action, resource, policyId, rowFilter)</li>
 *   <li>Collect all forbid statements → set of (action, resource)</li>
 *   <li>Collect all deny-exception permits → set of (action, resource)</li>
 *   <li>For each permit: if forbid exists and no deny-exception → skip (deny wins)</li>
 *   <li>Otherwise → create GRANT operation</li>
 *   <li>Skip unsupported actions → UNSUPPORTED_ACTION gap</li>
 *   <li>Skip non-ARN identifiers → UNMAPPED_RESOURCE gap</li>
 * </ol>
 */
public class CedarToLFConverter {

    private static final Logger LOG = LoggerFactory.getLogger(CedarToLFConverter.class);

    // Regex patterns for parsing Cedar policy statements
    private static final Pattern EFFECT_PATTERN = Pattern.compile(
            "^\\s*(permit|forbid)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PRINCIPAL_PATTERN = Pattern.compile(
            "principal\\s*==\\s*DataCatalog::Principal::\"([^\"]+)\"");
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "action\\s*==\\s*DataCatalog::Action::\"([^\"]+)\"");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile(
            "resource\\s*==\\s*(DataCatalog::\\w+)::\"([^\"]+)\"");
    private static final Pattern SOURCE_ANNOTATION_PATTERN = Pattern.compile(
            "@source\\(\"([^\"]+)\"\\)");
    private static final Pattern DENY_EXCEPTION_PATTERN = Pattern.compile(
            "@denyException\\(\"true\"\\)");
    private static final Pattern GRANTABLE_PATTERN = Pattern.compile(
            "@grantable\\(\"true\"\\)");
    private static final Pattern ROW_FILTER_PATTERN = Pattern.compile(
            "resource\\.rowFilter\\s*==\\s*\"((?>[^\"\\\\]|\\\\.)*)\"");

    /** Maps Cedar action names to LFPermission values. */
    private static final Map<String, LFPermission> ACTION_TO_PERMISSION;
    static {
        Map<String, LFPermission> map = new HashMap<>();
        map.put("SELECT", LFPermission.SELECT);
        map.put("INSERT", LFPermission.INSERT);
        map.put("DELETE", LFPermission.DELETE);
        map.put("DESCRIBE", LFPermission.DESCRIBE);
        map.put("ALTER", LFPermission.ALTER);
        map.put("DROP", LFPermission.DROP);
        map.put("CREATE_DATABASE", LFPermission.CREATE_DATABASE);
        map.put("CREATE_TABLE", LFPermission.CREATE_TABLE);
        map.put("DATA_LOCATION_ACCESS", LFPermission.DATA_LOCATION_ACCESS);
        ACTION_TO_PERMISSION = Collections.unmodifiableMap(map);
    }

    private final CedarSchemaProvider schemaProvider;
    private final GapReporter gapReporter;
    private final ArnParser arnParser;

    public CedarToLFConverter(CedarSchemaProvider schemaProvider,
                              GapReporter gapReporter,
                              ArnParser arnParser) {
        this.schemaProvider = schemaProvider;
        this.gapReporter = gapReporter;
        this.arnParser = arnParser;
    }

    /**
     * Convert a validated Cedar PolicySet to LF permission operations.
     *
     * @param policySet the validated Cedar PolicySet
     * @return list of LFPermissionOperation objects
     */
    public List<LFPermissionOperation> convert(CedarPolicySet policySet) {
        if (policySet == null) {
            return Collections.emptyList();
        }

        String cedarText = policySet.toCedarString();
        if (cedarText == null || cedarText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Parse all statements from the Cedar text
        List<ParsedStatement> statements = parseStatements(cedarText);
        if (statements.isEmpty()) {
            return Collections.emptyList();
        }

        // Group statements by principal
        Map<String, List<ParsedStatement>> byPrincipal = new HashMap<>();
        for (ParsedStatement stmt : statements) {
            byPrincipal.computeIfAbsent(stmt.principal, k -> new ArrayList<>()).add(stmt);
        }

        List<LFPermissionOperation> operations = new ArrayList<>();

        for (Map.Entry<String, List<ParsedStatement>> entry : byPrincipal.entrySet()) {
            String principal = entry.getKey();
            List<ParsedStatement> principalStatements = entry.getValue();

            List<LFPermissionOperation> principalOps = resolveEffectiveGrants(
                    principal, principalStatements);
            operations.addAll(principalOps);
        }

        return operations;
    }

    /**
     * Resolve effective grants for a single principal by applying
     * permit/forbid/deny-exception semantics.
     */
    private List<LFPermissionOperation> resolveEffectiveGrants(
            String principal, List<ParsedStatement> statements) {

        // Collect permits, forbids, and deny-exceptions
        List<ParsedStatement> permits = new ArrayList<>();
        Set<ActionResourceKey> forbidSet = new HashSet<>();
        Set<ActionResourceKey> denyExceptionSet = new HashSet<>();

        for (ParsedStatement stmt : statements) {
            ActionResourceKey key = new ActionResourceKey(stmt.action, stmt.resourceId);
            if ("forbid".equals(stmt.effect)) {
                forbidSet.add(key);
            } else if ("permit".equals(stmt.effect) && stmt.isDenyException) {
                denyExceptionSet.add(key);
                permits.add(stmt);
            } else if ("permit".equals(stmt.effect)) {
                permits.add(stmt);
            }
        }

        List<LFPermissionOperation> operations = new ArrayList<>();

        for (ParsedStatement permit : permits) {
            ActionResourceKey key = new ActionResourceKey(permit.action, permit.resourceId);

            // If there's a forbid for this (action, resource) and no deny-exception → skip
            if (forbidSet.contains(key) && !denyExceptionSet.contains(key)) {
                LOG.debug("Deny overrides permit for principal={}, action={}, resource={}",
                        principal, permit.action, permit.resourceId);
                continue;
            }

            // Map Cedar action to LFPermission
            LFPermission lfPermission = ACTION_TO_PERMISSION.get(permit.action);
            if (lfPermission == null) {
                if (permit.action != null && permit.action.startsWith("s3:")) {
                    LOG.debug("Skipping s3: action '{}' — not a Lake Formation action", permit.action);
                    continue;
                }
                gapReporter.recordGap(new GapEntry(
                        permit.sourcePolicyId, null, GapType.UNSUPPORTED_ACTION,
                        permit.resourceId,
                        "Cedar action '" + permit.action + "' is not supported by Lake Formation.",
                        "Remove or remap the unsupported action."
                ));
                continue;
            }

            // Parse resource identifier
            LFResource lfResource = parseResourceIdentifier(
                    permit.resourceId, permit.resourceType, permit.rowFilter, permit.sourcePolicyId);
            if (lfResource == null) {
                // Gap already recorded in parseResourceIdentifier
                continue;
            }

            LFPermissionOperation op = new LFPermissionOperation(
                    OperationType.GRANT,
                    permit.sourcePolicyId,
                    principal,
                    lfResource,
                    EnumSet.of(lfPermission),
                    permit.grantable
            );
            operations.add(op);
        }

        return operations;
    }

    /**
     * Parse a resource identifier (ARN) into an LFResource.
     *
     * @return LFResource or null if the identifier cannot be parsed
     */
    private LFResource parseResourceIdentifier(String resourceId, String resourceType,
                                                String rowFilter, String sourcePolicyId) {
        if (!ArnParser.isArn(resourceId)) {
            gapReporter.recordGap(new GapEntry(
                    sourcePolicyId, null, GapType.UNMAPPED_RESOURCE,
                    resourceId,
                    "Non-ARN entity identifier '" + resourceId
                            + "' cannot be mapped to a Lake Formation resource.",
                    "Configure a resource mapping for non-ARN identifiers or convert to ARN format."
            ));
            return null;
        }

        try {
            if (resourceId.startsWith("arn:aws:s3")) {
                S3ResourceRef s3Ref = ArnParser.parseS3Arn(resourceId);
                String dataLocationPath = "s3://" + s3Ref.getBucket();
                if (s3Ref.getPath() != null && !s3Ref.getPath().isEmpty()) {
                    dataLocationPath += "/" + s3Ref.getPath();
                }
                return new LFResource(null, null, null, null, null, dataLocationPath);
            }

            // Glue ARN
            GlueResourceRef glueRef = ArnParser.parseGlueArn(resourceId);
            String catalogId = glueRef.getAccountId();
            String databaseName = glueRef.getDatabaseName();
            String tableName = glueRef.getTableName();
            Set<String> columnNames = null;
            if (glueRef.getColumnName() != null) {
                columnNames = Collections.singleton(glueRef.getColumnName());
            }

            return new LFResource(catalogId, databaseName, tableName,
                    columnNames, rowFilter, null);

        } catch (IllegalArgumentException e) {
            LOG.warn("Failed to parse ARN '{}': {}", resourceId, e.getMessage());
            gapReporter.recordGap(new GapEntry(
                    sourcePolicyId, null, GapType.UNMAPPED_RESOURCE,
                    resourceId,
                    "Failed to parse ARN: " + e.getMessage(),
                    "Verify the ARN format is correct."
            ));
            return null;
        }
    }

    /**
     * Parse Cedar policy text into individual parsed statements.
     */
    private List<ParsedStatement> parseStatements(String cedarText) {
        List<ParsedStatement> results = new ArrayList<>();

        // Split on statement boundaries: each statement ends with ";"
        // We split by looking for annotation+effect blocks
        String[] rawStatements = splitStatements(cedarText);

        for (String raw : rawStatements) {
            if (raw.trim().isEmpty()) {
                continue;
            }

            ParsedStatement stmt = parseOneStatement(raw);
            if (stmt != null) {
                results.add(stmt);
            }
        }

        return results;
    }

    /**
     * Split Cedar text into individual statement strings.
     * Each statement ends with a semicolon.
     */
    private String[] splitStatements(String cedarText) {
        // Split on semicolons that end statements, keeping the content
        List<String> statements = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < cedarText.length(); i++) {
            char c = cedarText.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                statements.add(cedarText.substring(start, i + 1));
                start = i + 1;
            }
        }
        // Handle trailing content without semicolon
        if (start < cedarText.length()) {
            String remaining = cedarText.substring(start).trim();
            if (!remaining.isEmpty()) {
                statements.add(remaining);
            }
        }
        return statements.toArray(new String[0]);
    }

    /**
     * Parse a single Cedar statement string into a ParsedStatement.
     */
    private ParsedStatement parseOneStatement(String raw) {
        // Extract effect
        Matcher effectMatcher = EFFECT_PATTERN.matcher(raw);
        if (!effectMatcher.find()) {
            return null;
        }
        String effect = effectMatcher.group(1);

        // Extract principal
        Matcher principalMatcher = PRINCIPAL_PATTERN.matcher(raw);
        if (!principalMatcher.find()) {
            return null;
        }
        String principal = principalMatcher.group(1);

        // Extract action
        Matcher actionMatcher = ACTION_PATTERN.matcher(raw);
        if (!actionMatcher.find()) {
            return null;
        }
        String action = actionMatcher.group(1);

        // Extract resource type and id
        Matcher resourceMatcher = RESOURCE_PATTERN.matcher(raw);
        if (!resourceMatcher.find()) {
            return null;
        }
        String resourceType = resourceMatcher.group(1);
        String resourceId = resourceMatcher.group(2);

        // Extract source policy ID
        Matcher sourceMatcher = SOURCE_ANNOTATION_PATTERN.matcher(raw);
        String sourcePolicyId = sourceMatcher.find() ? sourceMatcher.group(1) : null;

        // Check for deny-exception annotation
        boolean isDenyException = DENY_EXCEPTION_PATTERN.matcher(raw).find();

        // Check for grantable annotation (delegateAdmin → LF permissionsWithGrantOption)
        boolean grantable = GRANTABLE_PATTERN.matcher(raw).find();

        // Extract row filter
        Matcher rowFilterMatcher = ROW_FILTER_PATTERN.matcher(raw);
        String rowFilter = null;
        if (rowFilterMatcher.find()) {
            rowFilter = unescapeQuotes(rowFilterMatcher.group(1));
        }

        return new ParsedStatement(effect, principal, action, resourceType,
                resourceId, sourcePolicyId, isDenyException, rowFilter, grantable);
    }

    /**
     * Unescape backslash-escaped quotes in a string.
     */
    private String unescapeQuotes(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Represents a parsed Cedar policy statement.
     */
    static class ParsedStatement {
        final String effect;        // "permit" or "forbid"
        final String principal;     // principal identifier (e.g., IAM ARN)
        final String action;        // Cedar action name (e.g., "SELECT")
        final String resourceType;  // Cedar entity type (e.g., "DataCatalog::Table")
        final String resourceId;    // resource identifier (e.g., ARN)
        final String sourcePolicyId;
        final boolean isDenyException;
        final String rowFilter;
        final boolean grantable;    // from @grantable("true") annotation (delegateAdmin)

        ParsedStatement(String effect, String principal, String action,
                        String resourceType, String resourceId, String sourcePolicyId,
                        boolean isDenyException, String rowFilter) {
            this(effect, principal, action, resourceType, resourceId,
                    sourcePolicyId, isDenyException, rowFilter, false);
        }

        ParsedStatement(String effect, String principal, String action,
                        String resourceType, String resourceId, String sourcePolicyId,
                        boolean isDenyException, String rowFilter, boolean grantable) {
            this.effect = effect;
            this.principal = principal;
            this.action = action;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.sourcePolicyId = sourcePolicyId;
            this.isDenyException = isDenyException;
            this.rowFilter = rowFilter;
            this.grantable = grantable;
        }
    }

    /**
     * Key for identifying unique (action, resource) pairs for forbid/deny-exception resolution.
     */
    private static final class ActionResourceKey {
        private final String action;
        private final String resourceId;

        ActionResourceKey(String action, String resourceId) {
            this.action = action;
            this.resourceId = resourceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActionResourceKey that = (ActionResourceKey) o;
            return Objects.equals(action, that.action)
                    && Objects.equals(resourceId, that.resourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, resourceId);
        }
    }
}
