package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangerConnectionConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultValues() {
        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "admin123",
                null, null, null, null);
        assertEquals(3, config.getMaxRetries());
        assertEquals(1000L, config.getRetryBackoffMs());
    }

    @Test
    void customValues() {
        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "secret",
                "/etc/keytab", "ranger@REALM", 5, 2000L);
        assertEquals("http://ranger:6080", config.getRangerAdminUrl());
        assertEquals("admin", config.getUsername());
        assertEquals("secret", config.getPassword());
        assertEquals("/etc/keytab", config.getKerberosKeytab());
        assertEquals("ranger@REALM", config.getKerberosPrincipal());
        assertEquals(5, config.getMaxRetries());
        assertEquals(2000L, config.getRetryBackoffMs());
    }

    @Test
    void equalsAndHashCode() {
        RangerConnectionConfig a = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "pass",
                null, null, 3, 1000L);
        RangerConnectionConfig b = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "pass",
                null, null, 3, 1000L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        RangerConnectionConfig a = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "pass",
                null, null, 3, 1000L);
        RangerConnectionConfig b = new RangerConnectionConfig(
                "http://other:6080", "admin", "pass",
                null, null, 3, 1000L);
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        RangerConnectionConfig original = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "secret",
                "/etc/keytab", "ranger@REALM", 5, 2000L);
        String json = mapper.writeValueAsString(original);
        RangerConnectionConfig deserialized = mapper.readValue(json, RangerConnectionConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithNulls() throws Exception {
        RangerConnectionConfig original = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "pass",
                null, null, null, null);
        String json = mapper.writeValueAsString(original);
        RangerConnectionConfig deserialized = mapper.readValue(json, RangerConnectionConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringMasksPassword() {
        RangerConnectionConfig config = new RangerConnectionConfig(
                "http://ranger:6080", "admin", "supersecret",
                null, null, 3, 1000L);
        String str = config.toString();
        assertTrue(str.contains("rangerAdminUrl='http://ranger:6080'"));
        assertTrue(str.contains("password='****'"));
        assertFalse(str.contains("supersecret"));
    }
}
