package com.amazonaws.policyconverters.config;

import net.jqwik.api.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Feature: conversion-server, Property 3: Environment variable override precedence

/**
 * Property-based tests for environment variable override precedence.
 * When SERVER_* environment variables are set, they must always take precedence
 * over values in the configuration file.
 * **Validates: Requirements 3.2, 3.3**
 */
class ServerConfigEnvOverridePropertyTest {

    private static final String[] VALID_LOG_LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"};

    @Property(tries = 100)
    void envVarsAlwaysOverrideFileValues(
            @ForAll("fileTimeouts") int fileTimeout,
            @ForAll("fileLogLevels") String fileLogLevel,
            @ForAll("fileNamespaces") String fileNamespace,
            @ForAll("envTimeouts") int envTimeout,
            @ForAll("envLogLevels") String envLogLevel,
            @ForAll("envNamespaces") String envNamespace
    ) throws IOException {
        // Write a temp YAML file with the file values
        String yaml = "rangerConfig:\n"
                + "  rangerAdminUrl: http://ranger:6080\n"
                + "awsConfig:\n"
                + "  region: us-east-1\n"
                + "server:\n"
                + "  shutdownTimeoutSeconds: " + fileTimeout + "\n"
                + "  logLevel: " + fileLogLevel + "\n"
                + "  metricsNamespace: " + fileNamespace + "\n";

        File yamlFile = Files.createTempFile("env-override-test-", ".yaml").toFile();
        yamlFile.deleteOnExit();
        try {
            try (FileWriter writer = new FileWriter(yamlFile)) {
                writer.write(yaml);
            }

            // Create env var map with all SERVER_* overrides set
            Map<String, String> envVars = new HashMap<>();
            envVars.put("SERVER_SHUTDOWN_TIMEOUT_SECONDS", String.valueOf(envTimeout));
            envVars.put("SERVER_LOG_LEVEL", envLogLevel);
            envVars.put("SERVER_METRICS_NAMESPACE", envNamespace);

            ServerConfigLoader loader = new ServerConfigLoader(envVars::get);
            ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());
            ServerConfig serverConfig = result.getServerConfig();

            // Env var values must always win over file values
            assertEquals(envTimeout, serverConfig.getShutdownTimeoutSeconds(),
                    "shutdownTimeoutSeconds should use env var value " + envTimeout
                            + " not file value " + fileTimeout);
            assertEquals(envLogLevel.toUpperCase(), serverConfig.getLogLevel(),
                    "logLevel should use env var value " + envLogLevel
                            + " not file value " + fileLogLevel);
            assertEquals(envNamespace, serverConfig.getMetricsNamespace(),
                    "metricsNamespace should use env var value " + envNamespace
                            + " not file value " + fileNamespace);
        } finally {
            yamlFile.delete();
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Integer> fileTimeouts() {
        return Arbitraries.integers().between(1, 10_000);
    }

    @Provide
    Arbitrary<String> fileLogLevels() {
        return Arbitraries.of(VALID_LOG_LEVELS);
    }

    @Provide
    Arbitrary<String> fileNamespaces() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<Integer> envTimeouts() {
        return Arbitraries.integers().between(1, 10_000);
    }

    @Provide
    Arbitrary<String> envLogLevels() {
        return Arbitraries.of(VALID_LOG_LEVELS);
    }

    @Provide
    Arbitrary<String> envNamespaces() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }
}
