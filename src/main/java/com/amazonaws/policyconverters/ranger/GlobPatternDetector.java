package com.amazonaws.policyconverters.ranger;

import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure utility class that determines whether a resource value is a glob pattern
 * (contains {@code *} or {@code ?}) but is not the bare {@code *} wildcard.
 * <p>
 * The bare {@code *} is excluded because Lake Formation already handles it via
 * {@code TableWildcard}, so it does not need periodic re-expansion.
 */
public final class GlobPatternDetector {

    private static final String[] RESOURCE_KEYS = {"database", "table", "column"};

    private GlobPatternDetector() {
        // utility class
    }

    /**
     * Returns {@code true} if the value contains {@code *} or {@code ?} but is not exactly {@code "*"}.
     *
     * @param value the resource value to check; may be {@code null}
     * @return {@code true} if the value is a glob pattern
     */
    public static boolean isGlobPattern(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if ("*".equals(value)) {
            return false;
        }
        return value.contains("*") || value.contains("?");
    }

    /**
     * Returns {@code true} if the policy has at least one glob pattern in its
     * database, table, or column resource values.
     *
     * @param policy the Ranger policy to inspect; may be {@code null}
     * @return {@code true} if any resource value is a glob pattern
     */
    public static boolean hasGlobPatterns(RangerPolicy policy) {
        if (policy == null) {
            return false;
        }
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            return false;
        }
        for (String key : RESOURCE_KEYS) {
            RangerPolicyResource resource = resources.get(key);
            if (resource != null && resource.getValues() != null) {
                for (String val : resource.getValues()) {
                    if (isGlobPattern(val)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Filter a list of policies to only those containing glob patterns.
     *
     * @param policies the list of policies to filter; may be {@code null}
     * @return a new list containing only policies where {@link #hasGlobPatterns} is {@code true}
     */
    public static List<RangerPolicy> filterGlobPolicies(List<RangerPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            return List.of();
        }
        return policies.stream()
                .filter(GlobPatternDetector::hasGlobPatterns)
                .collect(Collectors.toList());
    }
}
