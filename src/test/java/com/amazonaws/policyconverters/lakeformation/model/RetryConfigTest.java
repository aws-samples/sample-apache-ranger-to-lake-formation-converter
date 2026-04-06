package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultValues() {
        RetryConfig config = new RetryConfig();
        assertEquals(3, config.getMaxRetries());
        assertEquals(1000L, config.getInitialBackoffMs());
        assertEquals(2.0, config.getBackoffMultiplier(), 0.001);
        assertEquals(30000L, config.getMaxBackoffMs());
    }

    @Test
    void customValues() {
        RetryConfig config = new RetryConfig(5, 500L, 1.5, 10000L);
        assertEquals(5, config.getMaxRetries());
        assertEquals(500L, config.getInitialBackoffMs());
        assertEquals(1.5, config.getBackoffMultiplier(), 0.001);
        assertEquals(10000L, config.getMaxBackoffMs());
    }

    @Test
    void equalsAndHashCode() {
        RetryConfig a = new RetryConfig(3, 1000L, 2.0, 30000L);
        RetryConfig b = new RetryConfig(3, 1000L, 2.0, 30000L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        RetryConfig a = new RetryConfig(3, 1000L, 2.0, 30000L);
        RetryConfig b = new RetryConfig(5, 1000L, 2.0, 30000L);
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        RetryConfig original = new RetryConfig(5, 2000L, 3.0, 60000L);
        String json = mapper.writeValueAsString(original);
        RetryConfig deserialized = mapper.readValue(json, RetryConfig.class);
        assertEquals(original, deserialized);
    }

    @Test
    void toStringContainsValues() {
        RetryConfig config = new RetryConfig(3, 1000L, 2.0, 30000L);
        String str = config.toString();
        assertTrue(str.contains("maxRetries=3"));
        assertTrue(str.contains("initialBackoffMs=1000"));
    }
}
