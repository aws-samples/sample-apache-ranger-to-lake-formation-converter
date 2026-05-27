package com.example.ranger.lakeformation.simulator.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorConfigTest {

    @Test
    void nullFieldsResolveToDefaults() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(60, config.getCycleIntervalSeconds());
        assertEquals("us-east-1", config.getAwsRegion());
        assertEquals(300, config.getCycleWaitTimeoutSeconds());
        assertEquals(18080, config.getStatusPort());
        assertEquals("localhost", config.getStatusHost());
        assertEquals("reproduction-bundles", config.getReproductionBundleDir());
        assertNotNull(config.getPrincipalPool());
        assertTrue(config.getPrincipalPool().isEmpty());
        assertNotNull(config.getPrincipalMappings());
        assertTrue(config.getPrincipalMappings().isEmpty());
        assertEquals("lakeformation", config.getRangerServiceName());
        assertEquals("unknown", config.getAwsAccountId());
    }

    @Test
    void explicitValuesOverrideDefaults() {
        List<String> principals = List.of("arn:aws:iam::123456789012:role/MyRole");
        Map<String, String> mappings = Map.of("alice", "arn:aws:iam::123456789012:role/MyRole");
        SimulatorConfig config = new SimulatorConfig(
                120,
                "eu-west-1",
                "http://ranger:6080",
                "admin",
                "secret",
                principals,
                mappings,
                "hive",
                "123456789012",
                600,
                9090,
                "my-host",
                "/tmp/bundles",
                null);

        assertEquals(120, config.getCycleIntervalSeconds());
        assertEquals("eu-west-1", config.getAwsRegion());
        assertEquals("http://ranger:6080", config.getRangerAdminUrl());
        assertEquals("admin", config.getRangerAdminUser());
        assertEquals("secret", config.getRangerAdminPassword());
        assertEquals(List.of("arn:aws:iam::123456789012:role/MyRole"), config.getPrincipalPool());
        assertEquals(mappings, config.getPrincipalMappings());
        assertEquals("hive", config.getRangerServiceName());
        assertEquals("123456789012", config.getAwsAccountId());
        assertEquals(600, config.getCycleWaitTimeoutSeconds());
        assertEquals(9090, config.getStatusPort());
        assertEquals("my-host", config.getStatusHost());
        assertEquals("/tmp/bundles", config.getReproductionBundleDir());
    }

    @Test
    void principalPoolIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("arn:aws:iam::111:role/A"));
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, mutable, null, null, null, null, null, null, null, null);

        mutable.add("arn:aws:iam::222:role/B");

        assertEquals(1, config.getPrincipalPool().size(),
                "Mutating the original list must not affect the config's principalPool");
    }

    @Test
    void toStringContainsRangerAdminUrl() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, "http://ranger-admin:6080", null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(config.toString().contains("rangerAdminUrl"),
                "toString() should contain 'rangerAdminUrl' for logging diagnostics");
    }

    @Test
    void jsonRoundTripPreservesValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String json = "{"
                + "\"cycleIntervalSeconds\": 30,"
                + "\"awsRegion\": \"ap-southeast-1\","
                + "\"rangerAdminUrl\": \"http://ranger:6080\","
                + "\"rangerAdminUser\": \"admin\","
                + "\"rangerAdminPassword\": \"pass\","
                + "\"principalPool\": [\"alice\"],"
                + "\"principalMappings\": {\"alice\": \"arn:aws:iam::123:role/R1\"},"
                + "\"rangerServiceName\": \"lakeformation\","
                + "\"awsAccountId\": \"123456789012\","
                + "\"cycleWaitTimeoutSeconds\": 120,"
                + "\"statusPort\": 9999,"
                + "\"statusHost\": \"sync-host\","
                + "\"reproductionBundleDir\": \"/data/bundles\""
                + "}";

        SimulatorConfig config = mapper.readValue(json, SimulatorConfig.class);

        assertEquals(30, config.getCycleIntervalSeconds());
        assertEquals("ap-southeast-1", config.getAwsRegion());
        assertEquals("http://ranger:6080", config.getRangerAdminUrl());
        assertEquals("admin", config.getRangerAdminUser());
        assertEquals("pass", config.getRangerAdminPassword());
        assertEquals(List.of("alice"), config.getPrincipalPool());
        assertEquals(Map.of("alice", "arn:aws:iam::123:role/R1"), config.getPrincipalMappings());
        assertEquals("lakeformation", config.getRangerServiceName());
        assertEquals("123456789012", config.getAwsAccountId());
        assertEquals(120, config.getCycleWaitTimeoutSeconds());
        assertEquals(9999, config.getStatusPort());
        assertEquals("sync-host", config.getStatusHost());
        assertEquals("/data/bundles", config.getReproductionBundleDir());
    }

    @Test
    void unknownJsonFieldsAreIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"unknownField\": \"someValue\", \"cycleIntervalSeconds\": 45}";

        SimulatorConfig config = mapper.readValue(json, SimulatorConfig.class);

        assertEquals(45, config.getCycleIntervalSeconds());
    }
}
