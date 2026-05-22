package com.amazonaws.policyconverters.cedar;

import com.amazonaws.policyconverters.s3accessgrants.OperationType;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a validated Cedar {@link CedarPolicySet} to {@link S3AccessGrantOperation} objects
 * targeting S3 Access Grants (EMRFS path).
 *
 * <p>Only Cedar statements with S3 actions ({@code s3:GetObject}, {@code s3:PutObject},
 * {@code s3:ListObjects}, {@code s3:DeleteObject}) and resource entity type {@code S3::Object}
 * are processed. All other statements (DataCatalog-namespace) are silently ignored.
 *
 * <p>For each {@code (principal, s3Prefix)} pair, effective actions are accumulated:
 * {@code permit} adds an action; {@code forbid} removes it. No deny-exception logic is applied
 * because EMRFS does not support deny policies.
 *
 * <p>Permission aggregation:
 * <ul>
 *   <li>Read-only actions → {@link S3AccessGrantPermission#READ}</li>
 *   <li>Write-only actions → {@link S3AccessGrantPermission#WRITE}</li>
 *   <li>Mixed read+write → {@link S3AccessGrantPermission#READWRITE}</li>
 *   <li>Empty effective set → no grant produced</li>
 * </ul>
 */
public class CedarToS3AccessGrantsConverter {

    private static final Logger LOG = LoggerFactory.getLogger(CedarToS3AccessGrantsConverter.class);

    // Regex patterns reused from CedarToLFConverter
    private static final Pattern EFFECT_PATTERN = Pattern.compile(
            "^\\s*(permit|forbid)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PRINCIPAL_PATTERN = Pattern.compile(
            "principal\\s*==\\s*DataCatalog::Principal::\"([^\"]+)\"");
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "action\\s*==\\s*DataCatalog::Action::\"([^\"]+)\"");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile(
            "resource\\s*==\\s*(\\S+?)::\\s*\"([^\"]+)\"");

    private static final Set<String> S3_READ_ACTIONS;
    private static final Set<String> S3_WRITE_ACTIONS;
    private static final Set<String> S3_ALL_ACTIONS;

    static {
        Set<String> reads = new HashSet<>();
        reads.add("s3:GetObject");
        reads.add("s3:ListObjects");
        S3_READ_ACTIONS = Collections.unmodifiableSet(reads);

        Set<String> writes = new HashSet<>();
        writes.add("s3:PutObject");
        writes.add("s3:DeleteObject");
        S3_WRITE_ACTIONS = Collections.unmodifiableSet(writes);

        Set<String> all = new HashSet<>();
        all.addAll(reads);
        all.addAll(writes);
        S3_ALL_ACTIONS = Collections.unmodifiableSet(all);
    }

    private static final String S3_OBJECT_ENTITY_TYPE = "S3::Object";
    private static final String S3_ARN_PREFIX = "arn:aws:s3:::";

    /**
     * Convert a validated Cedar PolicySet to S3 Access Grants operations.
     *
     * @param policySet the validated Cedar PolicySet
     * @return list of {@link S3AccessGrantOperation} objects (never null)
     */
    public List<S3AccessGrantOperation> convert(CedarPolicySet policySet) {
        if (policySet == null) {
            return Collections.emptyList();
        }

        String cedarText = policySet.toCedarString();
        if (cedarText == null || cedarText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<ParsedStatement> statements = parseStatements(cedarText);
        if (statements.isEmpty()) {
            return Collections.emptyList();
        }

        // Group by (principal, s3Prefix):
        //   permitActions: actions added by permit statements
        //   forbidActions: actions removed by forbid statements
        // Forbid always wins over permit (no deny-exception logic for EMRFS).
        // Key: "principal\0s3Prefix"
        Map<String, Set<String>> permitActions = new HashMap<>();
        Map<String, Set<String>> forbidActions = new HashMap<>();
        Map<String, PrincipalAndPrefix> keyToInfo = new HashMap<>();

        for (ParsedStatement stmt : statements) {
            String key = stmt.principal + "\0" + stmt.s3Prefix;
            keyToInfo.putIfAbsent(key, new PrincipalAndPrefix(stmt.principal, stmt.s3Prefix));
            if ("permit".equals(stmt.effect)) {
                permitActions.computeIfAbsent(key, k -> new HashSet<>()).add(stmt.action);
            } else if ("forbid".equals(stmt.effect)) {
                forbidActions.computeIfAbsent(key, k -> new HashSet<>()).add(stmt.action);
            }
        }

        // Compute effective action sets: permit minus forbid
        Map<String, Set<String>> effectiveActions = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : permitActions.entrySet()) {
            String key = entry.getKey();
            Set<String> effective = new HashSet<>(entry.getValue());
            Set<String> forbidden = forbidActions.get(key);
            if (forbidden != null) {
                effective.removeAll(forbidden);
            }
            effectiveActions.put(key, effective);
        }

        // Build operations from effective action sets
        List<S3AccessGrantOperation> operations = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : effectiveActions.entrySet()) {
            Set<String> actions = entry.getValue();
            if (actions.isEmpty()) {
                LOG.debug("Empty effective action set for key='{}'; skipping grant", entry.getKey());
                continue;
            }

            S3AccessGrantPermission permission = aggregatePermission(actions);
            if (permission == null) {
                LOG.debug("Could not aggregate permission for actions={}; skipping", actions);
                continue;
            }

            PrincipalAndPrefix info = keyToInfo.get(entry.getKey());
            operations.add(new S3AccessGrantOperation(
                    OperationType.GRANT,
                    info.principal,
                    info.s3Prefix,
                    permission,
                    null
            ));
        }

        return operations;
    }

    /**
     * Aggregate a set of effective S3 actions into an {@link S3AccessGrantPermission} level.
     *
     * @param actions the effective action set
     * @return the permission level, or {@code null} if the set contains no recognized S3 actions
     */
    private S3AccessGrantPermission aggregatePermission(Set<String> actions) {
        boolean hasRead = false;
        boolean hasWrite = false;
        for (String action : actions) {
            if (S3_READ_ACTIONS.contains(action)) {
                hasRead = true;
            } else if (S3_WRITE_ACTIONS.contains(action)) {
                hasWrite = true;
            }
        }
        if (hasRead && hasWrite) {
            return S3AccessGrantPermission.READWRITE;
        } else if (hasRead) {
            return S3AccessGrantPermission.READ;
        } else if (hasWrite) {
            return S3AccessGrantPermission.WRITE;
        }
        return null;
    }

    /**
     * Convert an S3 ARN ({@code arn:aws:s3:::bucket/prefix}) to an {@code s3://} URI.
     *
     * @param arn the S3 ARN
     * @return the {@code s3://} URI, or {@code null} if the ARN is not an S3 ARN
     */
    private String arnToS3Prefix(String arn) {
        if (arn == null || !arn.startsWith(S3_ARN_PREFIX)) {
            return null;
        }
        String bucketAndPath = arn.substring(S3_ARN_PREFIX.length());
        return "s3://" + bucketAndPath;
    }

    /**
     * Parse Cedar policy text into individual statements, filtering to only S3 Object statements.
     */
    private List<ParsedStatement> parseStatements(String cedarText) {
        List<ParsedStatement> results = new ArrayList<>();
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
        if (start < cedarText.length()) {
            String remaining = cedarText.substring(start).trim();
            if (!remaining.isEmpty()) {
                statements.add(remaining);
            }
        }
        return statements.toArray(new String[0]);
    }

    /**
     * Parse a single Cedar statement, returning a {@link ParsedStatement} if the statement
     * passes the S3 filter (action is a known S3 action, resource type is {@code S3::Object}),
     * or {@code null} otherwise.
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

        // Filter: only process recognized S3 actions
        if (!S3_ALL_ACTIONS.contains(action)) {
            return null;
        }

        // Extract resource type and id
        Matcher resourceMatcher = RESOURCE_PATTERN.matcher(raw);
        if (!resourceMatcher.find()) {
            return null;
        }
        String resourceType = resourceMatcher.group(1).trim();
        String resourceId = resourceMatcher.group(2);

        // Filter: only process S3::Object resources
        if (!S3_OBJECT_ENTITY_TYPE.equals(resourceType)) {
            return null;
        }

        // Convert ARN to s3:// prefix
        String s3Prefix = arnToS3Prefix(resourceId);
        if (s3Prefix == null) {
            LOG.warn("Resource '{}' is not a valid S3 ARN; skipping statement", resourceId);
            return null;
        }

        return new ParsedStatement(effect, principal, action, s3Prefix);
    }

    /**
     * Minimal parsed Cedar statement holding only the fields needed for S3 Access Grants.
     */
    private static final class ParsedStatement {
        final String effect;    // "permit" or "forbid"
        final String principal; // IAM ARN
        final String action;    // e.g., "s3:GetObject"
        final String s3Prefix;  // e.g., "s3://my-bucket/my-prefix"

        ParsedStatement(String effect, String principal, String action, String s3Prefix) {
            this.effect = effect;
            this.principal = principal;
            this.action = action;
            this.s3Prefix = s3Prefix;
        }
    }

    /**
     * Holds the (principal, s3Prefix) pair reconstructed from a map key.
     */
    private static final class PrincipalAndPrefix {
        final String principal;
        final String s3Prefix;

        PrincipalAndPrefix(String principal, String s3Prefix) {
            this.principal = principal;
            this.s3Prefix = s3Prefix;
        }
    }
}
