package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EmrfsPolicyGeneratorTest {

    private static final List<String> S3_PREFIXES = List.of(
            "s3://my-bucket/emr-output/", "s3://my-bucket/emr-input/"
    );
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String EMRFS_SERVICE = "emrfs-custom";

    private EmrfsPolicyGenerator generator(long seed) {
        return new EmrfsPolicyGenerator(S3_PREFIXES, PRINCIPALS, EMRFS_SERVICE, new Random(seed));
    }

    // 1. generate() returns map with "sthreeresource" in resources (matches EmrfsServiceAdapter.RESOURCE_KEY)
    @Test
    void generateEmrfsPolicy_hasSthreeresourceInResources() {
        Map<String, Object> policy = generator(42).generate("p1");
        assertNotNull(policy);
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("sthreeresource"),
                "resources should have 'sthreeresource' key (not 's3prefix')");
    }

    // 2. Service name is the emrfsServiceName passed to constructor
    @Test
    void generateEmrfsPolicy_serviceNameMatchesConstructorArg() {
        Map<String, Object> policy = generator(7).generate("p2");
        assertEquals(EMRFS_SERVICE, policy.get("service"),
                "service name should match the emrfsServiceName passed to constructor");
    }

    // 4. No "id" key in payload — Ranger rejects string 'id' fields
    @Test
    void generateEmrfsPolicy_noIdInPayload() {
        Map<String, Object> policy = generator(42).generate("test-id");
        assertFalse(policy.containsKey("id"),
                "Ranger rejects string 'id' fields — payload must not include 'id'");
    }

    // 3. "policyItems" has one item with a valid EMRFS access type (matching EmrfsServiceAdapter)
    @Test
    void generateEmrfsPolicy_accessTypeIsValidEmrfsType() {
        Set<String> validTypes = Set.of("GetObject", "PutObject", "ListObjects", "DeleteObject");
        // Run several times with different seeds to exercise all access types
        for (long seed = 0; seed < 20; seed++) {
            Map<String, Object> policy = generator(seed).generate("p3-" + seed);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> policyItems = (List<Map<String, Object>>) policy.get("policyItems");
            assertNotNull(policyItems);
            assertEquals(1, policyItems.size(), "should have exactly one policy item");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accesses = (List<Map<String, Object>>) policyItems.get(0).get("accesses");
            assertNotNull(accesses);
            assertFalse(accesses.isEmpty(), "accesses should not be empty");
            String accessType = (String) accesses.get(0).get("type");
            assertTrue(validTypes.contains(accessType),
                    "access type '" + accessType + "' should be one of " + validTypes);
        }
    }
}
