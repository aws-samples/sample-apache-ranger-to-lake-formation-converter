package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DataLocationPolicyGeneratorTest {

    private static final List<String> S3_PATHS = List.of(
            "s3://my-bucket/data/", "s3://my-bucket/analytics/"
    );
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String SERVICE = "lakeformation";

    private DataLocationPolicyGenerator generator(long seed) {
        return new DataLocationPolicyGenerator(S3_PATHS, PRINCIPALS, SERVICE, new Random(seed));
    }

    // 1. generate() returns map with "datalocation" in resources
    @Test
    void generateDataLocationPolicy_hasDataLocationInResources() {
        Map<String, Object> policy = generator(42).generate("p1");
        assertNotNull(policy);
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) policy.get("resources");
        assertNotNull(resources);
        assertTrue(resources.containsKey("datalocation"), "resources should have 'datalocation' key");
    }

    // 2. "policyItems" contains one item with access type "datalocation"
    @Test
    void generateDataLocationPolicy_policyItemHasDatalocationAccessType() {
        Map<String, Object> policy = generator(7).generate("p2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policyItems = (List<Map<String, Object>>) policy.get("policyItems");
        assertNotNull(policyItems);
        assertEquals(1, policyItems.size(), "should have exactly one policy item");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accesses = (List<Map<String, Object>>) policyItems.get(0).get("accesses");
        assertNotNull(accesses);
        assertFalse(accesses.isEmpty(), "accesses should not be empty");
        assertEquals("datalocation", accesses.get(0).get("type"),
                "access type should be 'datalocation'");
    }

    // 3. "policyType" is 0 (allow policy)
    @Test
    void generateDataLocationPolicy_policyTypeIsZero() {
        Map<String, Object> policy = generator(99).generate("p3");
        assertEquals(0, policy.get("policyType"), "policyType should be 0 (allow policy)");
    }

    // 5. No "id" key in payload — Ranger rejects string 'id' fields
    @Test
    void generateDataLocationPolicy_noIdInPayload() {
        Map<String, Object> policy = generator(42).generate("test-id");
        assertFalse(policy.containsKey("id"),
                "Ranger rejects string 'id' fields — payload must not include 'id'");
    }

    // 4. Fixed seed → deterministic output
    @Test
    void generateDataLocationPolicy_deterministicWithFixedSeed() {
        Map<String, Object> first = generator(12345).generate("p4");
        Map<String, Object> second = generator(12345).generate("p4");
        assertEquals(first.get("name"), second.get("name"));
        assertEquals(first.get("service"), second.get("service"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resources1 = (Map<String, Object>) first.get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources2 = (Map<String, Object>) second.get("resources");
        assertEquals(resources1.get("datalocation"), resources2.get("datalocation"));
    }
}
