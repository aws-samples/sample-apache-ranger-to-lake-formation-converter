package com.example.ranger.lakeformation.simulator.workload;

import com.example.ranger.lakeformation.simulator.validator.ExpectedPermissionsComputer;
import com.example.ranger.lakeformation.simulator.validator.SimulatorPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TagPolicyGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> TAG_KEYS = List.of("PII", "CONFIDENTIAL");
    private static final List<String> PRINCIPALS = List.of("alice", "bob");
    private static final String TAG_SERVICE = "hive_tag";

    private TagPolicyGenerator generator(long seed) {
        return new TagPolicyGenerator(TAG_KEYS, PRINCIPALS, TAG_SERVICE, new Random(seed));
    }

    // 1. generate() returns map with service name containing "tag"
    @Test
    void generateTagPolicy_serviceNameContainsTag() {
        Map<String, Object> policy = generator(42).generate("p1");
        assertNotNull(policy);
        String serviceName = (String) policy.get("service");
        assertNotNull(serviceName);
        assertTrue(serviceName.toLowerCase().contains("tag"),
                "service name should contain 'tag', was: " + serviceName);
    }

    // 2. Constructor throws IllegalArgumentException when service name doesn't contain "tag"
    @Test
    void constructor_throwsWhenServiceNameMissingTag() {
        assertThrows(IllegalArgumentException.class, () ->
                new TagPolicyGenerator(TAG_KEYS, PRINCIPALS, "hive", new Random(0)),
                "Should throw IllegalArgumentException when tagServiceName does not contain 'tag'"
        );
    }

    // 4. No "id" key in payload — Ranger rejects string 'id' fields
    @Test
    void generateTagPolicy_noIdInPayload() {
        Map<String, Object> policy = generator(42).generate("test-id");
        assertFalse(policy.containsKey("id"),
                "Ranger rejects string 'id' fields — payload must not include 'id'");
    }

    // 3. ExpectedPermissionsComputer.compute() returns empty set for a tag policy
    @Test
    void tagPolicy_producesNoExpectedPermissions() {
        Map<String, Object> tagPolicy = generator(99).generate("p3");
        JsonNode policyNode = MAPPER.valueToTree(tagPolicy);

        Map<String, String> principalMap = Map.of(
                "alice", "arn:aws:iam::123:role/alice",
                "bob", "arn:aws:iam::123:role/bob"
        );
        ExpectedPermissionsComputer computer = new ExpectedPermissionsComputer(
                principalMap, (db, pattern) -> List.of(pattern));

        Set<SimulatorPermission> result = computer.compute(List.of(policyNode));

        assertTrue(result.isEmpty(),
                "Tag-based policy should produce no LF permissions (gap recording only)");
    }
}
