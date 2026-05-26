package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates EMRFS service policy payloads.
 * These use a custom Ranger service (emrfs) and produce S3 Access Grants — NOT LF permissions.
 * The simulator captures them via S3AgPermissionsFetcher.
 */
public class EmrfsPolicyGenerator {
    private static final List<String> SAMPLE_PREFIXES = List.of(
            "s3://my-bucket/emr-output/", "s3://my-bucket/emr-input/", "s3://my-bucket/emr-scratch/"
    );
    private static final List<String> EMRFS_ACCESS_TYPES = List.of("read", "write", "read_write");

    private final List<String> s3Prefixes;
    private final List<String> principalNames;
    private final String emrfsServiceName;
    private final Random random;

    public EmrfsPolicyGenerator(List<String> s3Prefixes, List<String> principalNames,
                                String emrfsServiceName, Random random) {
        this.s3Prefixes = s3Prefixes.isEmpty() ? SAMPLE_PREFIXES : List.copyOf(s3Prefixes);
        this.principalNames = List.copyOf(principalNames);
        this.emrfsServiceName = emrfsServiceName;
        this.random = random;
    }

    /**
     * Generate an EMRFS policy for a random S3 prefix.
     */
    public Map<String, Object> generateEmrfsPolicy(String policyId) {
        String prefix = randomFrom(s3Prefixes);
        String user = randomFrom(principalNames);
        String accessType = randomFrom(EMRFS_ACCESS_TYPES);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("s3prefix", Map.of("values", List.of(prefix), "isExcludes", false));

        Map<String, Object> item = Map.of(
                "users", List.of(user),
                "groups", List.of(),
                "roles", List.of(),
                "accesses", List.of(Map.of("type", accessType, "isAllowed", true)),
                "delegateAdmin", false
        );

        return Map.of(
                "id", policyId,
                "name", "sim-emrfs-" + policyId,
                "service", emrfsServiceName,
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
