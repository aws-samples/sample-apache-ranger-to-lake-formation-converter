package org.apache.ranger.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
