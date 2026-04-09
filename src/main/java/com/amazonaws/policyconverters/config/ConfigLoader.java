package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads {@link SyncConfig} from external YAML or properties files,
 * with environment variable overrides for all configuration values.
 * Sensitive values (passwords, secret keys) are masked in log output.
 */
public class ConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    private static final String MASK = "****";

    // Environment variable to config field mappings
    static final String ENV_RANGER_ADMIN_URL = "RANGER_ADMIN_URL";
    static final String ENV_RANGER_USERNAME = "RANGER_USERNAME";
    static final String ENV_RANGER_PASSWORD = "RANGER_PASSWORD";
    static final String ENV_RANGER_KERBEROS_KEYTAB = "RANGER_KERBEROS_KEYTAB";
    static final String ENV_RANGER_KERBEROS_PRINCIPAL = "RANGER_KERBEROS_PRINCIPAL";
    static final String ENV_RANGER_MAX_RETRIES = "RANGER_MAX_RETRIES";
    static final String ENV_RANGER_RETRY_BACKOFF_MS = "RANGER_RETRY_BACKOFF_MS";
    static final String ENV_AWS_REGION = "AWS_REGION";
    static final String ENV_AWS_CATALOG_ID = "AWS_CATALOG_ID";
    static final String ENV_AWS_ACCESS_KEY = "AWS_ACCESS_KEY";
    static final String ENV_AWS_SECRET_KEY = "AWS_SECRET_KEY";
    static final String ENV_AWS_ROLE_ARN = "AWS_ROLE_ARN";
    static final String ENV_POLICY_REFRESH_INTERVAL_MS = "POLICY_REFRESH_INTERVAL_MS";
    static final String ENV_MAX_LF_RETRIES = "MAX_LF_RETRIES";
    static final String ENV_LF_RETRY_BACKOFF_MS = "LF_RETRY_BACKOFF_MS";
    static final String ENV_DEAD_LETTER_LOG_PATH = "DEAD_LETTER_LOG_PATH";
    static final String ENV_CHECKPOINT_PATH = "CHECKPOINT_PATH";

    private final EnvironmentProvider environmentProvider;

    /**
     * Abstraction for reading environment variables, enabling testability.
     */
    @FunctionalInterface
    public interface EnvironmentProvider {
        String getEnv(String name);
    }

    /**
     * Creates a ConfigLoader that reads environment variables from {@link System#getenv(String)}.
     */
    public ConfigLoader() {
        this(System::getenv);
    }

    /**
     * Creates a ConfigLoader with a custom environment provider (useful for testing).
     */
    public ConfigLoader(EnvironmentProvider environmentProvider) {
        this.environmentProvider = environmentProvider;
    }

    /**
     * Load a {@link SyncConfig} from the given file path.
     * YAML files (ending in .yml or .yaml) are deserialized via Jackson YAMLFactory.
     * Properties files are manually mapped to config fields.
     * After file loading, environment variables override any file-based values.
     *
     * @param filePath path to the configuration file
     * @return the loaded SyncConfig
     * @throws IOException if the file cannot be read or parsed
     */
    public SyncConfig load(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Configuration file not found: " + filePath);
        }

        SyncConfig fileConfig;
        if (isYamlFile(filePath)) {
            fileConfig = loadFromYaml(file);
        } else {
            fileConfig = loadFromProperties(file);
        }

        return applyEnvironmentOverrides(fileConfig);
    }

    /**
     * Logs the loaded configuration with sensitive values masked.
     */
    public void logConfig(SyncConfig config) {
        LOG.info("Loaded configuration:");
        if (config.getRangerConfig() != null) {
            RangerConnectionConfig rc = config.getRangerConfig();
            LOG.info("  Ranger Admin URL: {}", rc.getRangerAdminUrl());
            LOG.info("  Ranger Username: {}", rc.getUsername());
            LOG.info("  Ranger Password: {}", mask(rc.getPassword()));
            LOG.info("  Ranger Kerberos Keytab: {}", rc.getKerberosKeytab());
            LOG.info("  Ranger Kerberos Principal: {}", rc.getKerberosPrincipal());
            LOG.info("  Ranger Max Retries: {}", rc.getMaxRetries());
            LOG.info("  Ranger Retry Backoff Ms: {}", rc.getRetryBackoffMs());
        }
        if (config.getAwsConfig() != null) {
            AwsConfig ac = config.getAwsConfig();
            LOG.info("  AWS Region: {}", ac.getRegion());
            LOG.info("  AWS Catalog ID: {}", ac.getCatalogId());
            LOG.info("  AWS Access Key: {}", mask(ac.getAccessKey()));
            LOG.info("  AWS Secret Key: {}", mask(ac.getSecretKey()));
            LOG.info("  AWS Role ARN: {}", ac.getRoleArn());
        }
        LOG.info("  Policy Refresh Interval Ms: {}", config.getPolicyRefreshIntervalMs());
        LOG.info("  Max LF Retries: {}", config.getMaxLfRetries());
        LOG.info("  LF Retry Backoff Ms: {}", config.getLfRetryBackoffMs());
        LOG.info("  Dead Letter Log Path: {}", config.getDeadLetterLogPath());
    }

    /**
     * Returns a map representation of the config with sensitive values masked.
     * Useful for structured log output.
     */
    public Map<String, String> toMaskedMap(SyncConfig config) {
        Map<String, String> map = new HashMap<>();
        if (config.getRangerConfig() != null) {
            RangerConnectionConfig rc = config.getRangerConfig();
            map.put("rangerConfig.rangerAdminUrl", rc.getRangerAdminUrl());
            map.put("rangerConfig.username", rc.getUsername());
            map.put("rangerConfig.password", mask(rc.getPassword()));
            map.put("rangerConfig.kerberosKeytab", rc.getKerberosKeytab());
            map.put("rangerConfig.kerberosPrincipal", rc.getKerberosPrincipal());
            map.put("rangerConfig.maxRetries", String.valueOf(rc.getMaxRetries()));
            map.put("rangerConfig.retryBackoffMs", String.valueOf(rc.getRetryBackoffMs()));
        }
        if (config.getAwsConfig() != null) {
            AwsConfig ac = config.getAwsConfig();
            map.put("awsConfig.region", ac.getRegion());
            map.put("awsConfig.catalogId", ac.getCatalogId());
            map.put("awsConfig.accessKey", mask(ac.getAccessKey()));
            map.put("awsConfig.secretKey", mask(ac.getSecretKey()));
            map.put("awsConfig.roleArn", ac.getRoleArn());
        }
        map.put("policyRefreshIntervalMs", String.valueOf(config.getPolicyRefreshIntervalMs()));
        map.put("maxLfRetries", String.valueOf(config.getMaxLfRetries()));
        map.put("lfRetryBackoffMs", String.valueOf(config.getLfRetryBackoffMs()));
        map.put("deadLetterLogPath", config.getDeadLetterLogPath());
        return map;
    }

    // --- Internal methods ---

    private boolean isYamlFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private SyncConfig loadFromYaml(File file) throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        LOG.debug("Loading configuration from YAML file: {}", file.getAbsolutePath());
        return yamlMapper.readValue(file, SyncConfig.class);
    }

    private SyncConfig loadFromProperties(File file) throws IOException {
        Properties props = new Properties();
        LOG.debug("Loading configuration from properties file: {}", file.getAbsolutePath());
        try (InputStream is = new FileInputStream(file)) {
            props.load(is);
        }
        return mapPropertiesToConfig(props);
    }

    private SyncConfig mapPropertiesToConfig(Properties props) {
        RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                props.getProperty("rangerConfig.rangerAdminUrl"),
                props.getProperty("rangerConfig.username"),
                props.getProperty("rangerConfig.password"),
                props.getProperty("rangerConfig.kerberosKeytab"),
                props.getProperty("rangerConfig.kerberosPrincipal"),
                parseInteger(props.getProperty("rangerConfig.maxRetries")),
                parseLong(props.getProperty("rangerConfig.retryBackoffMs"))
        );

        AwsConfig awsConfig = new AwsConfig(
                props.getProperty("awsConfig.region"),
                props.getProperty("awsConfig.catalogId"),
                props.getProperty("awsConfig.accessKey"),
                props.getProperty("awsConfig.secretKey"),
                props.getProperty("awsConfig.roleArn")
        );

        PrincipalMappingConfig principalMapping = buildPrincipalMappingFromProperties(props);

        return new SyncConfig(
                rangerConfig,
                awsConfig,
                principalMapping,
                parseLong(props.getProperty("policyRefreshIntervalMs")),
                parseInteger(props.getProperty("maxLfRetries")),
                parseLong(props.getProperty("lfRetryBackoffMs")),
                props.getProperty("deadLetterLogPath")
        );
    }

    private PrincipalMappingConfig buildPrincipalMappingFromProperties(Properties props) {
        Map<String, String> userMappings = extractPrefixedEntries(props, "principalMapping.userMappings.");
        Map<String, String> groupMappings = extractPrefixedEntries(props, "principalMapping.groupMappings.");
        Map<String, String> roleMappings = extractPrefixedEntries(props, "principalMapping.roleMappings.");

        if (userMappings.isEmpty() && groupMappings.isEmpty() && roleMappings.isEmpty()) {
            return null;
        }
        return new PrincipalMappingConfig(
                userMappings.isEmpty() ? null : userMappings,
                groupMappings.isEmpty() ? null : groupMappings,
                roleMappings.isEmpty() ? null : roleMappings
        );
    }

    private Map<String, String> extractPrefixedEntries(Properties props, String prefix) {
        Map<String, String> result = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String name = key.substring(prefix.length());
                result.put(name, props.getProperty(key));
            }
        }
        return result;
    }

    SyncConfig applyEnvironmentOverrides(SyncConfig config) {
        RangerConnectionConfig rc = config.getRangerConfig();
        AwsConfig ac = config.getAwsConfig();

        // Override Ranger config fields
        String rangerAdminUrl = envOrDefault(ENV_RANGER_ADMIN_URL, rc != null ? rc.getRangerAdminUrl() : null);
        String username = envOrDefault(ENV_RANGER_USERNAME, rc != null ? rc.getUsername() : null);
        String password = envOrDefault(ENV_RANGER_PASSWORD, rc != null ? rc.getPassword() : null);
        String kerberosKeytab = envOrDefault(ENV_RANGER_KERBEROS_KEYTAB, rc != null ? rc.getKerberosKeytab() : null);
        String kerberosPrincipal = envOrDefault(ENV_RANGER_KERBEROS_PRINCIPAL, rc != null ? rc.getKerberosPrincipal() : null);
        Integer maxRetries = envOrDefaultInt(ENV_RANGER_MAX_RETRIES, rc != null ? rc.getMaxRetries() : null);
        Long retryBackoffMs = envOrDefaultLong(ENV_RANGER_RETRY_BACKOFF_MS, rc != null ? rc.getRetryBackoffMs() : null);

        RangerConnectionConfig newRangerConfig = new RangerConnectionConfig(
                rangerAdminUrl, username, password, kerberosKeytab, kerberosPrincipal,
                maxRetries, retryBackoffMs
        );

        // Override AWS config fields
        String region = envOrDefault(ENV_AWS_REGION, ac != null ? ac.getRegion() : null);
        String catalogId = envOrDefault(ENV_AWS_CATALOG_ID, ac != null ? ac.getCatalogId() : null);
        String accessKey = envOrDefault(ENV_AWS_ACCESS_KEY, ac != null ? ac.getAccessKey() : null);
        String secretKey = envOrDefault(ENV_AWS_SECRET_KEY, ac != null ? ac.getSecretKey() : null);
        String roleArn = envOrDefault(ENV_AWS_ROLE_ARN, ac != null ? ac.getRoleArn() : null);

        AwsConfig newAwsConfig = new AwsConfig(region, catalogId, accessKey, secretKey, roleArn);

        // Override sync-level fields
        Long policyRefreshIntervalMs = envOrDefaultLong(ENV_POLICY_REFRESH_INTERVAL_MS, config.getPolicyRefreshIntervalMs());
        Integer maxLfRetries = envOrDefaultInt(ENV_MAX_LF_RETRIES, config.getMaxLfRetries());
        Long lfRetryBackoffMs = envOrDefaultLong(ENV_LF_RETRY_BACKOFF_MS, config.getLfRetryBackoffMs());
        String deadLetterLogPath = envOrDefault(ENV_DEAD_LETTER_LOG_PATH, config.getDeadLetterLogPath());
        String checkpointPath = envOrDefault(ENV_CHECKPOINT_PATH, config.getCheckpointPath());

        return new SyncConfig(
                newRangerConfig, newAwsConfig, config.getPrincipalMapping(),
                policyRefreshIntervalMs, maxLfRetries, lfRetryBackoffMs, deadLetterLogPath,
                checkpointPath
        );
    }

    private String envOrDefault(String envVar, String defaultValue) {
        String envValue = environmentProvider.getEnv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            LOG.debug("Overriding config with environment variable: {}", envVar);
            return envValue;
        }
        return defaultValue;
    }

    private Integer envOrDefaultInt(String envVar, Integer defaultValue) {
        String envValue = environmentProvider.getEnv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            try {
                LOG.debug("Overriding config with environment variable: {}", envVar);
                return Integer.parseInt(envValue);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid integer value for environment variable {}: {}", envVar, envValue);
            }
        }
        return defaultValue;
    }

    private Long envOrDefaultLong(String envVar, Long defaultValue) {
        String envValue = environmentProvider.getEnv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            try {
                LOG.debug("Overriding config with environment variable: {}", envVar);
                return Long.parseLong(envValue);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid long value for environment variable {}: {}", envVar, envValue);
            }
        }
        return defaultValue;
    }

    static String mask(String value) {
        if (value == null) {
            return null;
        }
        return MASK;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
