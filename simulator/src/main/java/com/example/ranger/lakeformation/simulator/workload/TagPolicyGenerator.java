package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates tag-based Ranger policy payloads.
 * Service name contains "tag" → these produce NO LF grants (gap recording only).
 * Used to verify the simulator correctly sees zero LF permissions for tag policies.
 */
public class TagPolicyGenerator implements PolicyGenerator {
    private static final List<String> SAMPLE_TAGS = List.of("PII", "CONFIDENTIAL", "PUBLIC");

    private final List<String> tagKeys;
    private final List<String> principalNames;
    private final String tagServiceName;    // must contain "tag" to trigger gap path
    private final Random random;

    public TagPolicyGenerator(List<String> tagKeys, List<String> principalNames,
                              String tagServiceName, Random random) {
        // Enforce that the service name contains "tag" so the gap path fires
        if (!tagServiceName.toLowerCase(Locale.ROOT).contains("tag")) {
            throw new IllegalArgumentException("tagServiceName must contain 'tag': " + tagServiceName);
        }
        this.tagKeys = tagKeys.isEmpty() ? SAMPLE_TAGS : List.copyOf(tagKeys);
        this.principalNames = List.copyOf(principalNames);
        this.tagServiceName = tagServiceName;
        this.random = random;
    }

    /**
     * Generate a tag-based policy for a random LF-tag key.
     * This will produce a GAP_RECORD (TAG_BASED_POLICY) in the sync service and zero LF grants.
     */
    public Map<String, Object> generate(String policyId) {
        String tagKey = randomFrom(tagKeys);
        String user = randomFrom(principalNames);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("tag", Map.of("values", List.of(tagKey + ":*"), "isExcludes", false));

        Map<String, Object> item = Map.of(
                "users", List.of(user),
                "groups", List.of(),
                "roles", List.of(),
                "accesses", List.of(Map.of("type", "lakeformation:select", "isAllowed", true)),
                "delegateAdmin", false
        );

        return Map.of(
                "name", "sim-tag-" + policyId,
                "service", tagServiceName,
                "isEnabled", true,
                "policyType", 0,
                "resources", resources,
                "policyItems", List.of(item),
                "denyPolicyItems", List.of()
        );
    }

    private <T> T randomFrom(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
