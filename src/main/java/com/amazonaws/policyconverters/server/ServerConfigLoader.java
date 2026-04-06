package com.amazonaws.policyconverters.server;

import com.amazonaws.policyconverters.lakeformation.model.SyncConfig;
import com.amazonaws.policyconverters.ranger.config.ConfigLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Loads both {@link SyncConfig} and {@link ServerConfig} from a single YAML configuration file.
 * Delegates SyncConfig loading to the existing {@link ConfigLoader} and additionally parses
 * the {@code server} YAML section into a {@link ServerConfig}.
 *
 * <p>Environment variable overrides (SERVER_SHUTDOWN_TIMEOUT_SECONDS, SERVER_LOG_LEVEL,
 * SERVER_METRICS_NAMESPACE) take precedence over file values.</p>
 */
public class ServerConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ServerConfigLoader.class);

    static final String ENV_SERVER_SHUTDOWN_TIMEOUT_SECONDS = "SERVER_SHUTDOWN_TIMEOUT_SECONDS";
    static final String ENV_SERVER_LOG_LEVEL = "SERVER_LOG_LEVEL";
    static final String ENV_SERVER_METRICS_NAMESPACE = "SERVER_METRICS_NAMESPACE";

    private final ConfigLoader configLoader;
    private final ConfigLoader.EnvironmentProvider environmentProvider;
    private final ObjectMapper yamlMapper;

    /**
     * Creates a ServerConfigLoader that reads environment variables from {@link System#getenv(String)}.
     */
    public ServerConfigLoader() {
        this(System::getenv);
    }

    /**
     * Creates a ServerConfigLoader with a custom environment provider (useful for testing).
     */
    public ServerConfigLoader(ConfigLoader.EnvironmentProvider environmentProvider) {
        this.configLoader = new ConfigLoader(environmentProvider);
        this.environmentProvider = environmentProvider;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Loads both SyncConfig and ServerConfig from the given file path.
     *
     * @param filePath path to the configuration file
     * @return a {@link CompositeConfig} containing both configs
     * @throws IOException if the file cannot be read or parsed
     */
    public CompositeConfig load(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Configuration file not found: " + filePath);
        }
        if (!file.canRead()) {
            throw new IOException("Configuration file is not readable: " + filePath);
        }

        boolean isYaml = isYamlFile(filePath);

        SyncConfig syncConfig;
        ServerConfig serverConfig;

        if (isYaml) {
            // Parse the full YAML tree
            JsonNode root = yamlMapper.readTree(file);

            // Extract and remove the server node before delegating to ConfigLoader
            serverConfig = extractServerConfig(root);

            // Write a sanitized YAML (without server section) to a temp file for ConfigLoader
            File sanitizedFile = writeSanitizedYaml(root, filePath);
            try {
                syncConfig = configLoader.load(sanitizedFile.getAbsolutePath());
            } finally {
                sanitizedFile.delete();
            }
        } else {
            // Properties files don't have a server section
            syncConfig = configLoader.load(filePath);
            serverConfig = new ServerConfig(null, null, null);
        }

        // Apply SERVER_* environment variable overrides
        serverConfig = applyServerEnvironmentOverrides(serverConfig);

        LOG.debug("Loaded server config: {}", serverConfig);
        return new CompositeConfig(syncConfig, serverConfig);
    }

    private boolean isYamlFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private ServerConfig extractServerConfig(JsonNode root) {
        JsonNode serverNode = root.get("server");
        if (serverNode == null || serverNode.isMissingNode() || serverNode.isNull()) {
            LOG.debug("No 'server' section found in config file; using defaults");
            return new ServerConfig(null, null, null);
        }
        try {
            return yamlMapper.treeToValue(serverNode, ServerConfig.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse 'server' section; using defaults: {}", e.getMessage());
            return new ServerConfig(null, null, null);
        }
    }

    /**
     * Writes a YAML file without the "server" key so ConfigLoader can parse it
     * without encountering an unknown property.
     */
    private File writeSanitizedYaml(JsonNode root, String originalPath) throws IOException {
        if (root.isObject()) {
            ((ObjectNode) root).remove("server");
        }
        String suffix = originalPath.toLowerCase().endsWith(".yml") ? ".yml" : ".yaml";
        File tempFile = Files.createTempFile("server-config-loader-", suffix).toFile();
        yamlMapper.writeValue(tempFile, root);
        return tempFile;
    }

    ServerConfig applyServerEnvironmentOverrides(ServerConfig config) {
        String envTimeout = environmentProvider.getEnv(ENV_SERVER_SHUTDOWN_TIMEOUT_SECONDS);
        String envLogLevel = environmentProvider.getEnv(ENV_SERVER_LOG_LEVEL);
        String envNamespace = environmentProvider.getEnv(ENV_SERVER_METRICS_NAMESPACE);

        Integer shutdownTimeout = config.getShutdownTimeoutSeconds();
        String logLevel = config.getLogLevel();
        String metricsNamespace = config.getMetricsNamespace();

        if (envTimeout != null && !envTimeout.isEmpty()) {
            try {
                shutdownTimeout = Integer.parseInt(envTimeout);
                LOG.debug("Overriding shutdownTimeoutSeconds with env var: {}", envTimeout);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid integer for {}: {}", ENV_SERVER_SHUTDOWN_TIMEOUT_SECONDS, envTimeout);
            }
        }

        if (envLogLevel != null && !envLogLevel.isEmpty()) {
            logLevel = envLogLevel;
            LOG.debug("Overriding logLevel with env var: {}", envLogLevel);
        }

        if (envNamespace != null && !envNamespace.isEmpty()) {
            metricsNamespace = envNamespace;
            LOG.debug("Overriding metricsNamespace with env var: {}", envNamespace);
        }

        return new ServerConfig(shutdownTimeout, logLevel, metricsNamespace);
    }

    /**
     * Composite configuration holding both the sync pipeline config and server process config.
     */
    public static final class CompositeConfig {
        private final SyncConfig syncConfig;
        private final ServerConfig serverConfig;

        public CompositeConfig(SyncConfig syncConfig, ServerConfig serverConfig) {
            this.syncConfig = Objects.requireNonNull(syncConfig, "syncConfig must not be null");
            this.serverConfig = Objects.requireNonNull(serverConfig, "serverConfig must not be null");
        }

        public SyncConfig getSyncConfig() {
            return syncConfig;
        }

        public ServerConfig getServerConfig() {
            return serverConfig;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CompositeConfig that = (CompositeConfig) o;
            return Objects.equals(syncConfig, that.syncConfig)
                    && Objects.equals(serverConfig, that.serverConfig);
        }

        @Override
        public int hashCode() {
            return Objects.hash(syncConfig, serverConfig);
        }

        @Override
        public String toString() {
            return "CompositeConfig{syncConfig=" + syncConfig + ", serverConfig=" + serverConfig + '}';
        }
    }
}
