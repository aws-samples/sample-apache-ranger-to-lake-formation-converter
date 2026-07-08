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
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

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
                null,
                null, null, null, null, null, null, null, null, null, null, null);

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
                null, null, null, null, null, mutable, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        mutable.add("arn:aws:iam::222:role/B");

        assertEquals(1, config.getPrincipalPool().size(),
                "Mutating the original list must not affect the config's principalPool");
    }

    @Test
    void toStringContainsRangerAdminUrl() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, "http://ranger-admin:6080", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

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

    @Test
    void trinoServiceNameDefaultsToTrino() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals("trino", config.getTrinoServiceName());
    }

    @Test
    void emrfsServiceNameDefaultsToAmazonEmrEmrfs() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        // Default matches the amazon-emr-emrfs service instance name that the sync
        // service's RangerServiceConfig and provision-data-services.sh use.
        assertEquals("amazon-emr-emrfs", config.getEmrfsServiceName());
    }

    @Test
    void emrSparkServiceNameDefaultsToAmazonEmrSpark() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertEquals("amazon-emr-spark", config.getEmrSparkServiceName());
    }

    @Test
    void tagServiceNameDefaultsToClTag() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        // Default matches the built-in Ranger tag service instance name (cl_tag).
        assertEquals("cl_tag", config.getTagServiceName());
    }

    @Test
    void s3PrefixesDefaultsToSamplePaths() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertNotNull(config.getS3Prefixes());
        assertFalse(config.getS3Prefixes().isEmpty());
    }

    @Test
    void s3PrefixesIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("s3://bucket/path/"));
        // s3Prefixes is at position 19 (0-indexed: 18)
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, mutable, null, null, null, null, null, null);
        mutable.add("s3://bucket/other/");
        assertEquals(1, config.getS3Prefixes().size(),
                "Mutating original list must not affect s3Prefixes");
    }

    @Test
    void explicitServiceNamesOverrideDefaults() {
        // positions: 15=trinoServiceName, 16=emrfsServiceName, 17=emrSparkServiceName, 18=tagServiceName
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null,
                "my-trino", "my-emrfs", "my-emrspark", "my-tag", null, null, null, null, null, null, null);
        assertEquals("my-trino",    config.getTrinoServiceName());
        assertEquals("my-emrfs",    config.getEmrfsServiceName());
        assertEquals("my-emrspark", config.getEmrSparkServiceName());
        assertEquals("my-tag",      config.getTagServiceName());
    }

    @Test
    void serviceNameDefaultsMatchProvisionedRangerServices() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        // Defaults must match the service instance names provision-data-services.sh installs.
        assertEquals("hive", config.getHiveServiceName());
        assertEquals("trino", config.getTrinoServiceName());
        assertEquals("amazon-emr-emrfs", config.getEmrfsServiceName());
        assertEquals("amazon-emr-spark", config.getEmrSparkServiceName());
        assertEquals("cl_tag", config.getTagServiceName());
    }

    @Test
    void hiveServiceName_deserializesFromJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SimulatorConfig config = mapper.readValue(
                "{\"hiveServiceName\": \"my-hive\"}", SimulatorConfig.class);
        assertEquals("my-hive", config.getHiveServiceName());
    }

    @Test
    void validateEmrSparkDefaultsFalse() {
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(config.isValidateEmrSpark(),
                "validateEmrSpark should default to false");
    }

    @Test
    void validateEmrSparkCanBeEnabled() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SimulatorConfig config = mapper.readValue(
                "{\"validateEmrSpark\": true}", SimulatorConfig.class);
        assertTrue(config.isValidateEmrSpark());
    }

    @Test
    void s3PrefixesEmptyListFallsBackToDefaults() {
        // s3Prefixes is at position 19 (0-indexed: 18)
        SimulatorConfig config = new SimulatorConfig(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of(), null, null, null, null, null, null);
        assertNotNull(config.getS3Prefixes());
        assertFalse(config.getS3Prefixes().isEmpty(),
                "Empty s3Prefixes list should fall back to default sample paths");
    }
}
