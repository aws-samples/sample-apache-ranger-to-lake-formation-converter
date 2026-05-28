package com.example.ranger.lakeformation.simulator.workload;

import java.util.*;

/**
 * Generates Ranger datalocation policy payloads.
 * These use the "datalocation" resource key and produce DATA_LOCATION_ACCESS in LF.
 */
public class DataLocationPolicyGenerator {
    private static final List<String> SAMPLE_PATHS = List.of(
            "s3://my-bucket/data/", "s3://my-bucket/analytics/", "s3://my-bucket/staging/"
    );

    private final List<String> s3Paths;
    private final List<String> principalNames;
    private final String hiveServiceName;
    private final Random random;

    public DataLocationPolicyGenerator(List<String> s3Paths, List<String> principalNames,
                                       String hiveServiceName, Random random) {
        this.s3Paths = s3Paths.isEmpty() ? SAMPLE_PATHS : List.copyOf(s3Paths);
        this.principalNames = List.copyOf(principalNames);
        this.hiveServiceName = hiveServiceName;
        this.random = random;
    }

    /**
     * Generate a DATA_LOCATION_ACCESS policy for a random S3 path.
     */
    public Map<String, Object> generate(String policyId) {
        String path = randomFrom(s3Paths);
        String user = randomFrom(principalNames);

        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("datalocation", Map.of("values", List.of(path), "isExcludes", false));

        Map<String, Object> item = Map.of(
                "users", List.of(user),
                "groups", List.of(),
                "roles", List.of(),
                "accesses", List.of(Map.of("type", "datalocation", "isAllowed", true)),
                "delegateAdmin", false
        );

        return Map.of(
                "name", "sim-datalocation-" + policyId,
                "service", hiveServiceName,
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
