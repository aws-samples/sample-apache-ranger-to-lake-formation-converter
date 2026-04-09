package com.amazonaws.policyconverters.config;

import com.amazonaws.policyconverters.config.ReverseSyncConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Map<String, String> envVars;
    private ServerConfigLoader loader;

    @BeforeEach
    void setUp() {
        envVars = new HashMap<>();
        loader = new ServerConfigLoader(envVars::get);
    }

    @Test
    void load_fullConfigWithServerSection() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  username: admin\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "server:\n" +
                "  shutdownTimeoutSeconds: 60\n" +
                "  logLevel: DEBUG\n" +
                "  metricsNamespace: MyNamespace\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        assertNotNull(result);
        // SyncConfig loaded correctly
        SyncConfig syncConfig = result.getSyncConfig();
        assertNotNull(syncConfig);
        assertEquals("http://ranger:6080", syncConfig.getRangerConfig().getRangerAdminUrl());
        assertEquals("us-east-1", syncConfig.getAwsConfig().getRegion());

        // ServerConfig loaded correctly
        ServerConfig serverConfig = result.getServerConfig();
        assertNotNull(serverConfig);
        assertEquals(60, serverConfig.getShutdownTimeoutSeconds());
        assertEquals("DEBUG", serverConfig.getLogLevel());
        assertEquals("MyNamespace", serverConfig.getMetricsNamespace());
    }

    @Test
    void load_missingServerSection_returnsDefaults() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ServerConfig serverConfig = result.getServerConfig();
        assertEquals(30, serverConfig.getShutdownTimeoutSeconds());
        assertEquals("INFO", serverConfig.getLogLevel());
        assertEquals("RangerLFSync", serverConfig.getMetricsNamespace());
    }

    @Test
    void load_envVarsOverrideFileValues() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "server:\n" +
                "  shutdownTimeoutSeconds: 60\n" +
                "  logLevel: DEBUG\n" +
                "  metricsNamespace: FileNamespace\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        envVars.put("SERVER_SHUTDOWN_TIMEOUT_SECONDS", "120");
        envVars.put("SERVER_LOG_LEVEL", "ERROR");
        envVars.put("SERVER_METRICS_NAMESPACE", "EnvNamespace");

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ServerConfig serverConfig = result.getServerConfig();
        assertEquals(120, serverConfig.getShutdownTimeoutSeconds());
        assertEquals("ERROR", serverConfig.getLogLevel());
        assertEquals("EnvNamespace", serverConfig.getMetricsNamespace());
    }

    @Test
    void load_envVarsOverrideDefaults() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        envVars.put("SERVER_SHUTDOWN_TIMEOUT_SECONDS", "90");
        envVars.put("SERVER_LOG_LEVEL", "WARN");
        envVars.put("SERVER_METRICS_NAMESPACE", "CustomNS");

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ServerConfig serverConfig = result.getServerConfig();
        assertEquals(90, serverConfig.getShutdownTimeoutSeconds());
        assertEquals("WARN", serverConfig.getLogLevel());
        assertEquals("CustomNS", serverConfig.getMetricsNamespace());
    }

    @Test
    void load_emptyEnvVarsDoNotOverride() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "server:\n" +
                "  shutdownTimeoutSeconds: 45\n" +
                "  logLevel: TRACE\n" +
                "  metricsNamespace: FileNS\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        envVars.put("SERVER_SHUTDOWN_TIMEOUT_SECONDS", "");
        envVars.put("SERVER_LOG_LEVEL", "");
        envVars.put("SERVER_METRICS_NAMESPACE", "");

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ServerConfig serverConfig = result.getServerConfig();
        assertEquals(45, serverConfig.getShutdownTimeoutSeconds());
        assertEquals("TRACE", serverConfig.getLogLevel());
        assertEquals("FileNS", serverConfig.getMetricsNamespace());
    }

    @Test
    void load_invalidTimeoutEnvVar_keepsFileValue() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "server:\n" +
                "  shutdownTimeoutSeconds: 45\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        envVars.put("SERVER_SHUTDOWN_TIMEOUT_SECONDS", "not-a-number");

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        assertEquals(45, result.getServerConfig().getShutdownTimeoutSeconds());
    }

    @Test
    void load_fileNotFound_throwsIOException() {
        IOException ex = assertThrows(IOException.class,
                () -> loader.load("/nonexistent/path/config.yaml"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void load_partialServerSection() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "server:\n" +
                "  logLevel: WARN\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ServerConfig serverConfig = result.getServerConfig();
        assertEquals(30, serverConfig.getShutdownTimeoutSeconds()); // default
        assertEquals("WARN", serverConfig.getLogLevel());
        assertEquals("RangerLFSync", serverConfig.getMetricsNamespace()); // default
    }

    @Test
    void load_propertiesFile_returnsDefaultServerConfig() throws IOException {
        String props =
                "rangerConfig.rangerAdminUrl=http://ranger:6080\n" +
                "awsConfig.region=us-east-1\n";

        File propsFile = tempDir.resolve("config.properties").toFile();
        writeFile(propsFile, props);

        ServerConfigLoader.CompositeConfig result = loader.load(propsFile.getAbsolutePath());

        // SyncConfig loaded from properties
        assertNotNull(result.getSyncConfig());
        assertEquals("http://ranger:6080", result.getSyncConfig().getRangerConfig().getRangerAdminUrl());

        // ServerConfig defaults since properties files don't have a server section
        ServerConfig serverConfig = result.getServerConfig();
        assertEquals(30, serverConfig.getShutdownTimeoutSeconds());
        assertEquals("INFO", serverConfig.getLogLevel());
        assertEquals("RangerLFSync", serverConfig.getMetricsNamespace());
    }

    @Test
    void compositeConfig_equalsAndHashCode() {
        SyncConfig sync = new SyncConfig(null, null, null, null, null, null, null);
        ServerConfig server = new ServerConfig(30, "INFO", "RangerLFSync");

        ServerConfigLoader.CompositeConfig a = new ServerConfigLoader.CompositeConfig(sync, server);
        ServerConfigLoader.CompositeConfig b = new ServerConfigLoader.CompositeConfig(sync, server);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void compositeConfig_nullSyncConfigThrows() {
        ServerConfig server = new ServerConfig(30, "INFO", "RangerLFSync");
        assertThrows(NullPointerException.class,
                () -> new ServerConfigLoader.CompositeConfig(null, server));
    }

    @Test
    void compositeConfig_nullServerConfigThrows() {
        SyncConfig sync = new SyncConfig(null, null, null, null, null, null, null);
        assertThrows(NullPointerException.class,
                () -> new ServerConfigLoader.CompositeConfig(sync, null));
    }

    @Test
    void load_ymlExtensionRecognized() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-west-2\n" +
                "server:\n" +
                "  logLevel: ERROR\n";

        File ymlFile = writeYaml("config.yml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(ymlFile.getAbsolutePath());

        assertEquals("ERROR", result.getServerConfig().getLogLevel());
        assertEquals("us-west-2", result.getSyncConfig().getAwsConfig().getRegion());
    }

    // --- Reverse-sync configuration loading tests (Task 10.4) ---

    @Test
    void load_reverseSyncSection_deserializedIntoReverseSyncConfig() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n" +
                "reverseSync:\n" +
                "  enabled: true\n" +
                "  reportOnly: true\n" +
                "  dryRun: false\n" +
                "  periodicIntervalMs: 60000\n" +
                "  catalogId: '999888777666'\n" +
                "  exclusionFilter:\n" +
                "    excludedPrincipals:\n" +
                "      - 'arn:aws:iam::123456789012:role/LFAdmin'\n" +
                "    excludedResourcePatterns:\n" +
                "      - 'system_db/*'\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ReverseSyncConfig rsConfig = result.getReverseSyncConfig();
        assertNotNull(rsConfig, "ReverseSyncConfig should not be null");
        assertTrue(rsConfig.isEnabled());
        assertTrue(rsConfig.isReportOnly());
        assertFalse(rsConfig.isDryRun());
        assertEquals(60000L, rsConfig.getPeriodicIntervalMs());
        assertEquals("999888777666", rsConfig.getCatalogId());
        assertNotNull(rsConfig.getFilter());
        assertTrue(rsConfig.getFilter().getExcludedPrincipals().contains(
                "arn:aws:iam::123456789012:role/LFAdmin"));
        assertTrue(rsConfig.getFilter().getExcludedResourcePatterns().contains("system_db/*"));
    }

    @Test
    void load_reverseSyncAbsent_returnsNullReverseSyncConfig() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        assertNull(result.getReverseSyncConfig(),
                "ReverseSyncConfig should be null when section is absent");
    }

    @Test
    void load_reverseSyncCatalogIdDefaultsToAwsConfigCatalogId() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n" +
                "reverseSync:\n" +
                "  enabled: true\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ReverseSyncConfig rsConfig = result.getReverseSyncConfig();
        assertNotNull(rsConfig);
        assertEquals("123456789012", rsConfig.getCatalogId(),
                "catalogId should default to awsConfig.catalogId");
    }

    @Test
    void load_reverseSyncExplicitCatalogIdNotOverridden() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n" +
                "reverseSync:\n" +
                "  enabled: true\n" +
                "  catalogId: '999888777666'\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ReverseSyncConfig rsConfig = result.getReverseSyncConfig();
        assertNotNull(rsConfig);
        assertEquals("999888777666", rsConfig.getCatalogId(),
                "Explicit catalogId should not be overridden by awsConfig.catalogId");
    }

    @Test
    void load_reverseSyncExclusionFilterMapsToPermissionFilter() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n" +
                "reverseSync:\n" +
                "  enabled: true\n" +
                "  exclusionFilter:\n" +
                "    excludedPrincipals:\n" +
                "      - 'arn:aws:iam::123456789012:role/Admin'\n" +
                "      - 'arn:aws:iam::123456789012:role/Service'\n" +
                "    excludedResourcePatterns:\n" +
                "      - 'system_db/*'\n" +
                "      - 'temp_*'\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ReverseSyncConfig rsConfig = result.getReverseSyncConfig();
        assertNotNull(rsConfig);
        assertNotNull(rsConfig.getFilter(), "Filter should be populated from exclusionFilter");
        assertEquals(2, rsConfig.getFilter().getExcludedPrincipals().size());
        assertTrue(rsConfig.getFilter().getExcludedPrincipals().contains(
                "arn:aws:iam::123456789012:role/Admin"));
        assertTrue(rsConfig.getFilter().getExcludedPrincipals().contains(
                "arn:aws:iam::123456789012:role/Service"));
        assertEquals(2, rsConfig.getFilter().getExcludedResourcePatterns().size());
        assertTrue(rsConfig.getFilter().getExcludedResourcePatterns().contains("system_db/*"));
        assertTrue(rsConfig.getFilter().getExcludedResourcePatterns().contains("temp_*"));
    }

    @Test
    void load_reverseSyncDefaultValues_whenSectionPresentButEmpty() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: '123456789012'\n" +
                "reverseSync:\n" +
                "  enabled: false\n";

        File yamlFile = writeYaml("config.yaml", yaml);

        ServerConfigLoader.CompositeConfig result = loader.load(yamlFile.getAbsolutePath());

        ReverseSyncConfig rsConfig = result.getReverseSyncConfig();
        assertNotNull(rsConfig);
        assertFalse(rsConfig.isEnabled());
        assertFalse(rsConfig.isReportOnly());
        assertFalse(rsConfig.isDryRun());
        assertEquals(0L, rsConfig.getPeriodicIntervalMs());
        // catalogId defaults to awsConfig.catalogId
        assertEquals("123456789012", rsConfig.getCatalogId());
        assertNull(rsConfig.getFilter());
    }

    @Test
    void load_reverseSyncPropertiesFile_returnsNullReverseSyncConfig() throws IOException {
        String props =
                "rangerConfig.rangerAdminUrl=http://ranger:6080\n" +
                "awsConfig.region=us-east-1\n";

        File propsFile = tempDir.resolve("config.properties").toFile();
        writeFile(propsFile, props);

        ServerConfigLoader.CompositeConfig result = loader.load(propsFile.getAbsolutePath());

        assertNull(result.getReverseSyncConfig(),
                "Properties files should not have reverse-sync config");
    }

    private File writeYaml(String filename, String content) throws IOException {
        File file = tempDir.resolve(filename).toFile();
        writeFile(file, content);
        return file;
    }

    private void writeFile(File file, String content) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
