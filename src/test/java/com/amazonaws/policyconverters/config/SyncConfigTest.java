package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SyncConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultValues() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null);
        assertEquals(30000L, config.getPolicyRefreshIntervalMs());
        assertEquals(5, config.getMaxLfRetries());
        assertEquals(2000L, config.getLfRetryBackoffMs());
    }

    @Test
    void customValues() {
        RangerConnectionConfig ranger = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "pass",
                null, null, null, null);
        AwsConfig aws = new AwsConfig("us-east-1", "123", null, null, null);
        PrincipalMappingConfig pm = new PrincipalMappingConfig(
                Collections.singletonMap("alice", "arn:aws:iam::123456789012:user/alice"),
                null, null);

        SyncConfig config = new SyncConfig(ranger, aws, pm,
                60000L, 10, 5000L, "/var/log/dead-letter.log");
        assertEquals(ranger, config.getRangerConfig());
        assertEquals(aws, config.getAwsConfig());
        assertEquals(pm, config.getPrincipalMapping());
        assertEquals(60000L, config.getPolicyRefreshIntervalMs());
        assertEquals(10, config.getMaxLfRetries());
        assertEquals(5000L, config.getLfRetryBackoffMs());
        assertEquals("/var/log/dead-letter.log", config.getDeadLetterLogPath());
    }

    @Test
    void equalsAndHashCode() {
        RangerConnectionConfig ranger = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "pass",
                null, null, 3, 1000L);
        AwsConfig aws = new AwsConfig("us-east-1", "123", null, null, null);

        SyncConfig a = new SyncConfig(ranger, aws, null,
                30000L, 5, 2000L, "/tmp/dl.log");
        SyncConfig b = new SyncConfig(ranger, aws, null,
                30000L, 5, 2000L, "/tmp/dl.log");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        SyncConfig a = new SyncConfig(null, null, null,
                30000L, 5, 2000L, null);
        SyncConfig b = new SyncConfig(null, null, null,
                60000L, 5, 2000L, null);
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        RangerConnectionConfig ranger = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "secret",
                "/etc/keytab", "ranger@REALM", 5, 2000L);
        AwsConfig aws = new AwsConfig("us-east-1", "123456789012",
                "AKID", "SK", "arn:aws:iam::123456789012:role/R");
        PrincipalMappingConfig pm = new PrincipalMappingConfig(
                Collections.singletonMap("alice", "arn:aws:iam::123456789012:user/alice"),
                Collections.singletonMap("analysts", "arn:aws:iam::123456789012:role/Analysts"),
                Collections.singletonMap("admin", "arn:aws:iam::123456789012:role/Admin"));

        SyncConfig original = new SyncConfig(ranger, aws, pm,
                60000L, 10, 5000L, "/var/log/dead-letter.log");
        String json = mapper.writeValueAsString(original);
        SyncConfig deserialized = mapper.readValue(json, SyncConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithDefaults() throws Exception {
        SyncConfig original = new SyncConfig(null, null, null,
                null, null, null, null);
        String json = mapper.writeValueAsString(original);
        SyncConfig deserialized = mapper.readValue(json, SyncConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringContainsFields() {
        SyncConfig config = new SyncConfig(null, null, null,
                30000L, 5, 2000L, "/tmp/dl.log");
        String str = config.toString();
        assertTrue(str.contains("policyRefreshIntervalMs=30000"));
        assertTrue(str.contains("maxLfRetries=5"));
        assertTrue(str.contains("deadLetterLogPath='/tmp/dl.log'"));
    }

    // --- wildcardRefreshIntervalSeconds tests (Task 2.4) ---

    @Test
    void wildcardRefreshInterval_absentDefaultsToZero() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null, null, null);
        assertEquals(0, config.getWildcardRefreshIntervalSeconds());
    }

    @Test
    void wildcardRefreshInterval_nullDefaultsToZero() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null, null, null);
        assertEquals(0, config.getWildcardRefreshIntervalSeconds());
    }

    @Test
    void wildcardRefreshInterval_zeroStoresAsZero() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null, null, 0);
        assertEquals(0, config.getWildcardRefreshIntervalSeconds());
    }

    @Test
    void wildcardRefreshInterval_positiveValueStoresCorrectly() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null, null, 300);
        assertEquals(300, config.getWildcardRefreshIntervalSeconds());
    }

    @Test
    void wildcardRefreshInterval_backwardCompatibleConstructorDefaultsToZero() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null);
        assertEquals(0, config.getWildcardRefreshIntervalSeconds());
    }

    @Test
    void wildcardRefreshInterval_yamlDeserialization() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  username: admin\n" +
                "  password: secret\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n" +
                "wildcardRefreshIntervalSeconds: 300\n";

        SyncConfig config = yamlMapper.readValue(yaml, SyncConfig.class);

        assertEquals(300, config.getWildcardRefreshIntervalSeconds());
        assertEquals("http://ranger:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("us-east-1", config.getAwsConfig().getRegion());
    }

    @Test
    void wildcardRefreshInterval_yamlDeserializationAbsent() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n";

        SyncConfig config = yamlMapper.readValue(yaml, SyncConfig.class);

        assertEquals(0, config.getWildcardRefreshIntervalSeconds());
    }

    @Test
    void wildcardRefreshInterval_includedInToString() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null, null, 600);
        String str = config.toString();
        assertTrue(str.contains("wildcardRefreshIntervalSeconds=600"));
    }

    @Test
    void wildcardRefreshInterval_includedInEquals() {
        SyncConfig a = new SyncConfig(null, null, null,
                null, null, null, null, null, 300);
        SyncConfig b = new SyncConfig(null, null, null,
                null, null, null, null, null, 300);
        SyncConfig c = new SyncConfig(null, null, null,
                null, null, null, null, null, 600);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void wildcardRefreshInterval_jsonRoundTrip() throws Exception {
        SyncConfig original = new SyncConfig(null, null, null,
                null, null, null, null, null, 300);
        String json = mapper.writeValueAsString(original);
        SyncConfig deserialized = mapper.readValue(json, SyncConfig.class);
        assertEquals(original, deserialized);
        assertEquals(300, deserialized.getWildcardRefreshIntervalSeconds());
    }

    // --- reverseSyncConfig tests ---

    @Test
    void reverseSyncConfig_defaultsToDisabled() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null);
        assertNotNull(config.getReverseSyncConfig());
        assertFalse(config.getReverseSyncConfig().isEnabled());
    }

    @Test
    void reverseSyncConfig_backwardCompatConstructors_allDefaultToDisabled() {
        SyncConfig a = new SyncConfig(null, null, null, null, null, null, null, null, null);
        SyncConfig b = new SyncConfig(null, null, null, null, null, null, null, null, null, null);
        SyncConfig c = new SyncConfig(null, null, null, null, null, null, null, null, null, null, null);
        SyncConfig d = new SyncConfig(null, null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(a.getReverseSyncConfig().isEnabled());
        assertFalse(b.getReverseSyncConfig().isEnabled());
        assertFalse(c.getReverseSyncConfig().isEnabled());
        assertFalse(d.getReverseSyncConfig().isEnabled());
    }

    @Test
    void reverseSyncConfig_yamlDeserialization() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "reverseSync:\n" +
                "  enabled: true\n" +
                "  reportOnly: false\n";

        SyncConfig config = yamlMapper.readValue(yaml, SyncConfig.class);

        assertNotNull(config.getReverseSyncConfig());
        assertTrue(config.getReverseSyncConfig().isEnabled());
        assertFalse(config.getReverseSyncConfig().isReportOnly());
    }

    @Test
    void reverseSyncConfig_jsonRoundTrip() throws Exception {
        ReverseSyncConfig rsc = new ReverseSyncConfig(true, "123456789012", false, false, null, null, 0L);
        SyncConfig original = new SyncConfig(null, null, null,
                null, null, null, null, null, null, null, null, null, rsc);
        String json = mapper.writeValueAsString(original);
        SyncConfig deserialized = mapper.readValue(json, SyncConfig.class);
        assertTrue(deserialized.getReverseSyncConfig().isEnabled());
        assertEquals("123456789012", deserialized.getReverseSyncConfig().getCatalogId());
    }

    @Test
    void reverseSyncConfig_includedInEquals() {
        ReverseSyncConfig rsc = new ReverseSyncConfig(true, null, false, false, null, null, 0L);
        SyncConfig withRsc = new SyncConfig(null, null, null,
                null, null, null, null, null, null, null, null, null, rsc);
        SyncConfig withoutRsc = new SyncConfig(null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertNotEquals(withRsc, withoutRsc);
    }

    @Test
    void useRestPolicyFetch_defaultsToFalse() {
        SyncConfig config = new SyncConfig(null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertFalse(config.isUseRestPolicyFetch());
    }

    @Test
    void useRestPolicyFetch_jsonRoundTrip() throws Exception {
        SyncConfig original = new SyncConfig(null, null, null,
                null, null, null, null, null, null, null, null, null, null, true);
        String json = mapper.writeValueAsString(original);
        SyncConfig deserialized = mapper.readValue(json, SyncConfig.class);
        assertTrue(deserialized.isUseRestPolicyFetch());
    }

    @Test
    void useRestPolicyFetch_deserializesFromYaml() throws Exception {
        String yaml = "rangerConfig:\n"
                + "  rangerAdminUrl: \"http://localhost:6080\"\n"
                + "useRestPolicyFetch: true\n";
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        SyncConfig config = yamlMapper.readValue(yaml, SyncConfig.class);
        assertTrue(config.isUseRestPolicyFetch());
    }
}
