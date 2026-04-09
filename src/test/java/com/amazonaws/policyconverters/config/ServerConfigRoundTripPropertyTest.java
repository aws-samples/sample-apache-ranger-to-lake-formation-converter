package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

// Feature: conversion-server, Property 2: ServerConfig round-trip parsing

/**
 * Property-based tests for ServerConfig YAML round-trip parsing.
 * **Validates: Requirements 3.1**
 */
class ServerConfigRoundTripPropertyTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Property(tries = 100)
    void roundTripPreservesAllFields(
            @ForAll("validServerConfigs") ServerConfig original
    ) throws Exception {
        String yaml = yamlMapper.writeValueAsString(original);
        ServerConfig deserialized = yamlMapper.readValue(yaml, ServerConfig.class);

        assertEquals(original.getShutdownTimeoutSeconds(), deserialized.getShutdownTimeoutSeconds(),
                "shutdownTimeoutSeconds should survive round-trip");
        assertEquals(original.getLogLevel(), deserialized.getLogLevel(),
                "logLevel should survive round-trip");
        assertEquals(original.getMetricsNamespace(), deserialized.getMetricsNamespace(),
                "metricsNamespace should survive round-trip");
        assertEquals(original, deserialized,
                "Full ServerConfig equality should hold after round-trip");
    }

    @Property(tries = 100)
    void omittedFieldsGetDefaults(
            @ForAll("partialServerConfigs") PartialConfig partial
    ) throws Exception {
        String yaml = yamlMapper.writeValueAsString(partial.config);
        ServerConfig deserialized = yamlMapper.readValue(yaml, ServerConfig.class);

        if (!partial.hasTimeout) {
            assertEquals(30, deserialized.getShutdownTimeoutSeconds(),
                    "Missing shutdownTimeoutSeconds should default to 30");
        }
        if (!partial.hasLogLevel) {
            assertEquals("INFO", deserialized.getLogLevel(),
                    "Missing logLevel should default to INFO");
        }
        if (!partial.hasNamespace) {
            assertEquals("RangerLFSync", deserialized.getMetricsNamespace(),
                    "Missing metricsNamespace should default to RangerLFSync");
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<ServerConfig> validServerConfigs() {
        Arbitrary<Integer> timeouts = Arbitraries.integers().between(1, 10_000);
        Arbitrary<String> logLevels = Arbitraries.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        Arbitrary<String> namespaces = Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(1).ofMaxLength(50);

        return Combinators.combine(timeouts, logLevels, namespaces)
                .as(ServerConfig::new);
    }

    @Provide
    Arbitrary<PartialConfig> partialServerConfigs() {
        Arbitrary<Integer> timeouts = Arbitraries.integers().between(1, 10_000);
        Arbitrary<String> logLevels = Arbitraries.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        Arbitrary<String> namespaces = Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(1).ofMaxLength(50);
        Arbitrary<Boolean> booleans = Arbitraries.of(true, false);

        return Combinators.combine(timeouts, logLevels, namespaces, booleans, booleans, booleans)
                .as((timeout, level, ns, hasTimeout, hasLogLevel, hasNamespace) -> {
                    ServerConfig config = new ServerConfig(
                            hasTimeout ? timeout : null,
                            hasLogLevel ? level : null,
                            hasNamespace ? ns : null
                    );
                    return new PartialConfig(config, hasTimeout, hasLogLevel, hasNamespace);
                });
    }

    // --- Helper ---

    static class PartialConfig {
        final ServerConfig config;
        final boolean hasTimeout;
        final boolean hasLogLevel;
        final boolean hasNamespace;

        PartialConfig(ServerConfig config, boolean hasTimeout, boolean hasLogLevel, boolean hasNamespace) {
            this.config = config;
            this.hasTimeout = hasTimeout;
            this.hasLogLevel = hasLogLevel;
            this.hasNamespace = hasNamespace;
        }
    }
}
