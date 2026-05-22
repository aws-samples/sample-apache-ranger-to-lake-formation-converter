package com.amazonaws.policyconverters.lakeformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Maps Ranger users, groups, and roles to AWS IAM principal ARNs.
 * Supports loading mappings from a {@link PrincipalMappingConfig} object,
 * or from a JSON or properties file.
 */
public class StaticPrincipalMapper implements PrincipalMapper {

    private static final Logger LOG = LoggerFactory.getLogger(StaticPrincipalMapper.class);

    private final Map<String, String> userMappings;
    private final Map<String, String> groupMappings;
    private final Map<String, String> roleMappings;
    private final MetricsEmitter metricsEmitter;

    private StaticPrincipalMapper(Map<String, String> userMappings,
                                  Map<String, String> groupMappings,
                                  Map<String, String> roleMappings,
                                  MetricsEmitter metricsEmitter) {
        this.userMappings = Collections.unmodifiableMap(new HashMap<>(userMappings));
        this.groupMappings = Collections.unmodifiableMap(new HashMap<>(groupMappings));
        this.roleMappings = Collections.unmodifiableMap(new HashMap<>(roleMappings));
        this.metricsEmitter = metricsEmitter;
    }

    /**
     * Create a StaticPrincipalMapper from a {@link PrincipalMappingConfig}.
     */
    public static StaticPrincipalMapper fromConfig(PrincipalMappingConfig config, MetricsEmitter metricsEmitter) {
        if (config == null) {
            throw new IllegalArgumentException("PrincipalMappingConfig must not be null");
        }
        return new StaticPrincipalMapper(
                config.getUserMappings(),
                config.getGroupMappings(),
                config.getRoleMappings(),
                metricsEmitter
        );
    }

    /**
     * Create a StaticPrincipalMapper by loading mappings from a JSON or properties file.
     * <p>
     * JSON files are deserialized into {@link PrincipalMappingConfig} using Jackson.
     * Properties files use the format:
     * <pre>
     *   user.alice=arn:aws:iam::123:user/alice
     *   group.analysts=arn:aws:iam::123:role/AnalystRole
     *   role.admin=arn:aws:iam::123:role/AdminRole
     * </pre>
     *
     * @param filePath path to the JSON or properties file
     * @return a StaticPrincipalMapper loaded from the file
     * @throws IOException if the file cannot be read or parsed
     */
    public static StaticPrincipalMapper fromFile(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path must not be null or empty");
        }
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Principal mapping file not found: " + filePath);
        }

        if (filePath.endsWith(".json")) {
            return loadFromJson(file);
        } else if (filePath.endsWith(".properties")) {
            return loadFromProperties(file);
        } else {
            throw new IOException("Unsupported file format. Expected .json or .properties: " + filePath);
        }
    }

    /**
     * Resolve a Ranger user name to an IAM principal ARN.
     *
     * @param rangerUser the Ranger user name
     * @return the IAM ARN if a mapping exists, or {@link Optional#empty()} otherwise
     */
    @Override
    public Optional<String> resolveUser(String rangerUser) {
        return resolve("user", rangerUser, userMappings);
    }

    /**
     * Resolve a Ranger group name to an IAM principal ARN.
     *
     * @param rangerGroup the Ranger group name
     * @return the IAM ARN if a mapping exists, or {@link Optional#empty()} otherwise
     */
    @Override
    public Optional<String> resolveGroup(String rangerGroup) {
        return resolve("group", rangerGroup, groupMappings);
    }

    /**
     * Resolve a Ranger role name to an IAM principal ARN.
     *
     * @param rangerRole the Ranger role name
     * @return the IAM ARN if a mapping exists, or {@link Optional#empty()} otherwise
     */
    @Override
    public Optional<String> resolveRole(String rangerRole) {
        return resolve("role", rangerRole, roleMappings);
    }

    private Optional<String> resolve(String principalType, String name, Map<String, String> mappings) {
        if (name == null) {
            LOG.warn("Attempted to resolve null {} principal, returning empty", principalType);
            return Optional.empty();
        }
        String arn = mappings.get(name);
        if (arn == null) {
            LOG.warn("No mapping found for {} principal '{}', skipping", principalType, name);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedPrincipal(principalType);
            }
            return Optional.empty();
        }
        return Optional.of(arn);
    }

    private static StaticPrincipalMapper loadFromJson(File file) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        PrincipalMappingConfig config = objectMapper.readValue(file, PrincipalMappingConfig.class);
        return fromConfig(config, null);
    }

    private static StaticPrincipalMapper loadFromProperties(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }

        Map<String, String> userMappings = new HashMap<>();
        Map<String, String> groupMappings = new HashMap<>();
        Map<String, String> roleMappings = new HashMap<>();

        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (key.startsWith("user.")) {
                userMappings.put(key.substring("user.".length()), value);
            } else if (key.startsWith("group.")) {
                groupMappings.put(key.substring("group.".length()), value);
            } else if (key.startsWith("role.")) {
                roleMappings.put(key.substring("role.".length()), value);
            } else {
                LOG.warn("Ignoring unrecognized principal mapping key: {}", key);
            }
        }

        return new StaticPrincipalMapper(userMappings, groupMappings, roleMappings, null);
    }
}
