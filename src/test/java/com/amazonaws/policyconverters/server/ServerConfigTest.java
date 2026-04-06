package com.amazonaws.policyconverters.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultValues() {
        ServerConfig config = new ServerConfig(null, null, null);
        assertEquals(30, config.getShutdownTimeoutSeconds());
        assertEquals("INFO", config.getLogLevel());
        assertEquals("RangerLFSync", config.getMetricsNamespace());
    }

    @Test
    void customValues() {
        ServerConfig config = new ServerConfig(60, "DEBUG", "MyNamespace");
        assertEquals(60, config.getShutdownTimeoutSeconds());
        assertEquals("DEBUG", config.getLogLevel());
        assertEquals("MyNamespace", config.getMetricsNamespace());
    }

    @Test
    void logLevelValidation_validLevels() {
        for (String level : new String[]{"TRACE", "DEBUG", "INFO", "WARN", "ERROR"}) {
            ServerConfig config = new ServerConfig(null, level, null);
            assertEquals(level, config.getLogLevel());
        }
    }

    @Test
    void logLevelValidation_caseInsensitive() {
        ServerConfig config = new ServerConfig(null, "debug", null);
        assertEquals("DEBUG", config.getLogLevel());
    }

    @Test
    void logLevelValidation_invalidFallsBackToInfo() {
        ServerConfig config = new ServerConfig(null, "INVALID", null);
        assertEquals("INFO", config.getLogLevel());
    }

    @Test
    void equalsAndHashCode() {
        ServerConfig a = new ServerConfig(30, "INFO", "RangerLFSync");
        ServerConfig b = new ServerConfig(30, "INFO", "RangerLFSync");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        ServerConfig a = new ServerConfig(30, "INFO", "RangerLFSync");
        ServerConfig b = new ServerConfig(60, "DEBUG", "Other");
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ServerConfig original = new ServerConfig(45, "WARN", "CustomNS");
        String json = mapper.writeValueAsString(original);
        ServerConfig deserialized = mapper.readValue(json, ServerConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jsonRoundTripWithDefaults() throws Exception {
        ServerConfig original = new ServerConfig(null, null, null);
        String json = mapper.writeValueAsString(original);
        ServerConfig deserialized = mapper.readValue(json, ServerConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringContainsFields() {
        ServerConfig config = new ServerConfig(30, "INFO", "RangerLFSync");
        String str = config.toString();
        assertTrue(str.contains("shutdownTimeoutSeconds=30"));
        assertTrue(str.contains("logLevel='INFO'"));
        assertTrue(str.contains("metricsNamespace='RangerLFSync'"));
    }
}
