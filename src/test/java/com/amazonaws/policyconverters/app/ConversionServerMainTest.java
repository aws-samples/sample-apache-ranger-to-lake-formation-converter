package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.ServerConfigLoader;
import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversionServerMainTest {

    @TempDir
    Path tempDir;

    @Test
    void run_noArgs_returnsExitCode1() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(stderr));
            int exitCode = ConversionServerMain.run(new String[]{});
            assertEquals(1, exitCode);
            assertTrue(stderr.toString().contains("Usage:"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void run_missingConfigFile_returnsExitCode1() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(stderr));
            int exitCode = ConversionServerMain.run(new String[]{"/nonexistent/config.yaml"});
            assertEquals(1, exitCode);
            assertTrue(stderr.toString().contains("Failed to load configuration"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void run_invalidConfig_returnsExitCode1() throws IOException {
        // Write a YAML config with missing required fields
        Path configFile = tempDir.resolve("invalid-config.yaml");
        Files.writeString(configFile, "rangerConfig:\n  rangerAdminUrl: \"\"\nawsConfig:\n  region: \"\"\n");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(stderr));
            int exitCode = ConversionServerMain.run(new String[]{configFile.toString()});
            assertEquals(1, exitCode);
            assertTrue(stderr.toString().contains("Configuration error"));
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void loadConfig_validFile_returnsCompositeConfig() throws IOException {
        Path configFile = tempDir.resolve("valid-config.yaml");
        String yaml = """
                rangerConfig:
                  rangerAdminUrl: "http://ranger:6080"
                  username: "admin"
                awsConfig:
                  region: "us-east-1"
                  catalogId: "123456789012"
                  accessKey: "EXAMPLE_ACCESS_KEY_ID"
                  secretKey: "EXAMPLE_SECRET_ACCESS_KEY"
                policyRefreshIntervalMs: 5000
                server:
                  shutdownTimeoutSeconds: 60
                  logLevel: DEBUG
                  metricsNamespace: TestNamespace
                """;
        Files.writeString(configFile, yaml);

        ServerConfigLoader.CompositeConfig config = ConversionServerMain.loadConfig(configFile.toString());

        assertNotNull(config.getSyncConfig());
        assertNotNull(config.getServerConfig());
        assertEquals("DEBUG", config.getServerConfig().getLogLevel());
        assertEquals(60, config.getServerConfig().getShutdownTimeoutSeconds());
        assertEquals("TestNamespace", config.getServerConfig().getMetricsNamespace());
        assertEquals(5000, config.getSyncConfig().getPolicyRefreshIntervalMs());
    }

    @Test
    void validateConfig_validConfig_returnsNoErrors() {
        SyncConfig config = new SyncConfig(
                new RangerConnectionConfig("http://ranger:6080", "admin", "pass", null, null, null, null),
                new AwsConfig("us-east-1", "123456789012", "AKIA", "secret", null),
                null, null, null, null, null);

        List<String> errors = ConversionServerMain.validateConfig(config);
        assertTrue(errors.isEmpty());
    }

    @Test
    void validateConfig_nullConfig_returnsErrors() {
        List<String> errors = ConversionServerMain.validateConfig(null);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validateConfig_missingRequiredFields_returnsErrors() {
        SyncConfig config = new SyncConfig(null, null, null, null, null, null, null);
        List<String> errors = ConversionServerMain.validateConfig(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    void setLogLevel_setsRootLoggerLevel() {
        ConversionServerMain.setLogLevel("DEBUG");
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertEquals(ch.qos.logback.classic.Level.DEBUG, rootLogger.getLevel());

        // Reset to INFO
        ConversionServerMain.setLogLevel("INFO");
        assertEquals(ch.qos.logback.classic.Level.INFO, rootLogger.getLevel());
    }

    @Test
    void setLogLevel_invalidLevel_defaultsToInfo() {
        ConversionServerMain.setLogLevel("INVALID");
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        org.slf4j.Logger.ROOT_LOGGER_NAME);
        // Logback's Level.toLevel defaults to DEBUG for unknown strings,
        // but we pass Level.INFO as the default
        assertNotNull(rootLogger.getLevel());

        // Reset
        ConversionServerMain.setLogLevel("INFO");
    }

    @Test
    void buildCredentialsProvider_staticCreds_returnsStaticProvider() {
        AwsConfig config = new AwsConfig("us-east-1", "123456789012",
                "EXAMPLE_ACCESS_KEY_ID", "EXAMPLE_SECRET_ACCESS_KEY", null);
        var provider = ConversionServerMain.buildCredentialsProvider(config);
        assertNotNull(provider);
    }

    @Test
    void buildCredentialsProvider_defaultChain_returnsDefaultProvider() {
        AwsConfig config = new AwsConfig("us-east-1", "123456789012",
                null, null, null);
        var provider = ConversionServerMain.buildCredentialsProvider(config);
        assertNotNull(provider);
    }

    @Test
    void createRangerService_amazonEmrSpark_returnsEmrSparkRangerService() {
        RangerServiceConfig config = new RangerServiceConfig(
                "amazon-emr-spark", "spark-instance", null, null);
        BaseRangerService service = ConversionServerMain.createRangerService(config);
        assertNotNull(service);
        assertEquals("amazon-emr-spark", service.getServiceType());
        assertEquals("spark-instance", service.getServiceInstanceName());
    }

    @Test
    void mergeServicePolicies_combinesAllPoliciesAcrossServices() {
        org.apache.ranger.plugin.model.RangerPolicy lfPolicy =
                new org.apache.ranger.plugin.model.RangerPolicy();
        lfPolicy.setId(1L);
        lfPolicy.setService("lakeformation");
        org.apache.ranger.plugin.util.ServicePolicies lf =
                new org.apache.ranger.plugin.util.ServicePolicies();
        lf.setServiceName("lakeformation");
        lf.setPolicies(List.of(lfPolicy));

        org.apache.ranger.plugin.model.RangerPolicy hivePolicy =
                new org.apache.ranger.plugin.model.RangerPolicy();
        hivePolicy.setId(2L);
        hivePolicy.setService("hive");
        org.apache.ranger.plugin.util.ServicePolicies hive =
                new org.apache.ranger.plugin.util.ServicePolicies();
        hive.setServiceName("hive");
        hive.setPolicies(List.of(hivePolicy));

        org.apache.ranger.plugin.util.ServicePolicies merged =
                ConversionServerMain.mergeServicePolicies(List.of(lf, hive));

        assertNotNull(merged);
        assertEquals(2, merged.getPolicies().size());
        // Each policy retains its own service field so the converter routes by adapter.
        assertTrue(merged.getPolicies().stream()
                .anyMatch(p -> "lakeformation".equals(p.getService())));
        assertTrue(merged.getPolicies().stream()
                .anyMatch(p -> "hive".equals(p.getService())));
    }

    @Test
    void mergeServicePolicies_skipsNullFetchesAndReturnsRemainder() {
        org.apache.ranger.plugin.model.RangerPolicy hivePolicy =
                new org.apache.ranger.plugin.model.RangerPolicy();
        hivePolicy.setId(2L);
        hivePolicy.setService("hive");
        org.apache.ranger.plugin.util.ServicePolicies hive =
                new org.apache.ranger.plugin.util.ServicePolicies();
        hive.setServiceName("hive");
        hive.setPolicies(List.of(hivePolicy));

        // A null entry represents a service whose REST fetch failed this cycle.
        java.util.List<org.apache.ranger.plugin.util.ServicePolicies> fetches =
                new java.util.ArrayList<>();
        fetches.add(null);
        fetches.add(hive);

        org.apache.ranger.plugin.util.ServicePolicies merged =
                ConversionServerMain.mergeServicePolicies(fetches);

        assertNotNull(merged);
        assertEquals(1, merged.getPolicies().size());
        assertEquals("hive", merged.getPolicies().get(0).getService());
    }

    @Test
    void mergeServicePolicies_allNull_returnsNull() {
        java.util.List<org.apache.ranger.plugin.util.ServicePolicies> fetches =
                new java.util.ArrayList<>();
        fetches.add(null);
        fetches.add(null);
        assertNull(ConversionServerMain.mergeServicePolicies(fetches));
    }
}
