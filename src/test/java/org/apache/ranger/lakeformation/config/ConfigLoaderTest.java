package org.apache.ranger.lakeformation.config;

import org.apache.ranger.lakeformation.model.AwsConfig;
import org.apache.ranger.lakeformation.model.PrincipalMappingConfig;
import org.apache.ranger.lakeformation.model.RangerConnectionConfig;
import org.apache.ranger.lakeformation.model.SyncConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Map<String, String> envVars;
    private ConfigLoader loader;

    @BeforeEach
    void setUp() {
        envVars = new HashMap<>();
        loader = new ConfigLoader(envVars::get);
    }

    @Test
    void loadFromYaml_fullConfig() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  username: admin\n" +
                "  password: secret123\n" +
                "  maxRetries: 5\n" +
                "  retryBackoffMs: 2000\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  catalogId: \"123456789012\"\n" +
                "  accessKey: AKIAIOSFODNN7EXAMPLE\n" +
                "  secretKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n" +
                "  roleArn: arn:aws:iam::123456789012:role/LFRole\n" +
                "policyRefreshIntervalMs: 60000\n" +
                "maxLfRetries: 10\n" +
                "lfRetryBackoffMs: 5000\n" +
                "deadLetterLogPath: /var/log/dead-letter.log\n";

        File yamlFile = tempDir.resolve("config.yaml").toFile();
        writeFile(yamlFile, yaml);

        SyncConfig config = loader.load(yamlFile.getAbsolutePath());

        assertNotNull(config);
        assertNotNull(config.getRangerConfig());
        assertEquals("http://ranger:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("admin", config.getRangerConfig().getUsername());
        assertEquals("secret123", config.getRangerConfig().getPassword());
        assertEquals(5, config.getRangerConfig().getMaxRetries());
        assertEquals(2000L, config.getRangerConfig().getRetryBackoffMs());

        assertNotNull(config.getAwsConfig());
        assertEquals("us-east-1", config.getAwsConfig().getRegion());
        assertEquals("123456789012", config.getAwsConfig().getCatalogId());
        assertEquals("AKIAIOSFODNN7EXAMPLE", config.getAwsConfig().getAccessKey());
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", config.getAwsConfig().getSecretKey());
        assertEquals("arn:aws:iam::123456789012:role/LFRole", config.getAwsConfig().getRoleArn());

        assertEquals(60000L, config.getPolicyRefreshIntervalMs());
        assertEquals(10, config.getMaxLfRetries());
        assertEquals(5000L, config.getLfRetryBackoffMs());
        assertEquals("/var/log/dead-letter.log", config.getDeadLetterLogPath());
    }

    @Test
    void loadFromYml_extensionRecognized() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "awsConfig:\n" +
                "  region: us-west-2\n";

        File ymlFile = tempDir.resolve("config.yml").toFile();
        writeFile(ymlFile, yaml);

        SyncConfig config = loader.load(ymlFile.getAbsolutePath());

        assertEquals("http://ranger:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("us-west-2", config.getAwsConfig().getRegion());
    }

    @Test
    void loadFromProperties_fullConfig() throws IOException {
        String props =
                "rangerConfig.rangerAdminUrl=http://ranger:6080\n" +
                "rangerConfig.username=admin\n" +
                "rangerConfig.password=secret123\n" +
                "rangerConfig.maxRetries=5\n" +
                "rangerConfig.retryBackoffMs=2000\n" +
                "awsConfig.region=us-east-1\n" +
                "awsConfig.catalogId=123456789012\n" +
                "awsConfig.accessKey=AKIAIOSFODNN7EXAMPLE\n" +
                "awsConfig.secretKey=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n" +
                "awsConfig.roleArn=arn:aws:iam::123456789012:role/LFRole\n" +
                "policyRefreshIntervalMs=60000\n" +
                "maxLfRetries=10\n" +
                "lfRetryBackoffMs=5000\n" +
                "deadLetterLogPath=/var/log/dead-letter.log\n";

        File propsFile = tempDir.resolve("config.properties").toFile();
        writeFile(propsFile, props);

        SyncConfig config = loader.load(propsFile.getAbsolutePath());

        assertNotNull(config);
        assertEquals("http://ranger:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("admin", config.getRangerConfig().getUsername());
        assertEquals("secret123", config.getRangerConfig().getPassword());
        assertEquals("us-east-1", config.getAwsConfig().getRegion());
        assertEquals("123456789012", config.getAwsConfig().getCatalogId());
        assertEquals(60000L, config.getPolicyRefreshIntervalMs());
        assertEquals(10, config.getMaxLfRetries());
    }

    @Test
    void loadFromProperties_withPrincipalMappings() throws IOException {
        String props =
                "rangerConfig.rangerAdminUrl=http://ranger:6080\n" +
                "awsConfig.region=us-east-1\n" +
                "principalMapping.userMappings.alice=arn:aws:iam::123456789012:user/alice\n" +
                "principalMapping.groupMappings.analysts=arn:aws:iam::123456789012:role/AnalystRole\n" +
                "principalMapping.roleMappings.admin=arn:aws:iam::123456789012:role/AdminRole\n";

        File propsFile = tempDir.resolve("config.properties").toFile();
        writeFile(propsFile, props);

        SyncConfig config = loader.load(propsFile.getAbsolutePath());

        assertNotNull(config.getPrincipalMapping());
        assertEquals("arn:aws:iam::123456789012:user/alice",
                config.getPrincipalMapping().getUserMappings().get("alice"));
        assertEquals("arn:aws:iam::123456789012:role/AnalystRole",
                config.getPrincipalMapping().getGroupMappings().get("analysts"));
        assertEquals("arn:aws:iam::123456789012:role/AdminRole",
                config.getPrincipalMapping().getRoleMappings().get("admin"));
    }

    @Test
    void environmentVariableOverrides_replaceFileValues() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  username: fileUser\n" +
                "  password: filePassword\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  accessKey: fileAccessKey\n" +
                "  secretKey: fileSecretKey\n";

        File yamlFile = tempDir.resolve("config.yaml").toFile();
        writeFile(yamlFile, yaml);

        envVars.put("RANGER_ADMIN_URL", "http://override-ranger:6080");
        envVars.put("RANGER_USERNAME", "envUser");
        envVars.put("RANGER_PASSWORD", "envPassword");
        envVars.put("AWS_REGION", "eu-west-1");
        envVars.put("AWS_ACCESS_KEY", "envAccessKey");
        envVars.put("AWS_SECRET_KEY", "envSecretKey");

        SyncConfig config = loader.load(yamlFile.getAbsolutePath());

        assertEquals("http://override-ranger:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("envUser", config.getRangerConfig().getUsername());
        assertEquals("envPassword", config.getRangerConfig().getPassword());
        assertEquals("eu-west-1", config.getAwsConfig().getRegion());
        assertEquals("envAccessKey", config.getAwsConfig().getAccessKey());
        assertEquals("envSecretKey", config.getAwsConfig().getSecretKey());
    }

    @Test
    void environmentVariableOverrides_numericFields() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  maxRetries: 3\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "maxLfRetries: 5\n";

        File yamlFile = tempDir.resolve("config.yaml").toFile();
        writeFile(yamlFile, yaml);

        envVars.put("RANGER_MAX_RETRIES", "10");
        envVars.put("MAX_LF_RETRIES", "20");
        envVars.put("POLICY_REFRESH_INTERVAL_MS", "120000");

        SyncConfig config = loader.load(yamlFile.getAbsolutePath());

        assertEquals(10, config.getRangerConfig().getMaxRetries());
        assertEquals(20, config.getMaxLfRetries());
        assertEquals(120000L, config.getPolicyRefreshIntervalMs());
    }

    @Test
    void environmentVariableOverrides_emptyEnvVarDoesNotOverride() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  username: fileUser\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n";

        File yamlFile = tempDir.resolve("config.yaml").toFile();
        writeFile(yamlFile, yaml);

        envVars.put("RANGER_USERNAME", "");

        SyncConfig config = loader.load(yamlFile.getAbsolutePath());

        assertEquals("fileUser", config.getRangerConfig().getUsername());
    }

    @Test
    void fileNotFound_throwsIOException() {
        assertThrows(IOException.class, () -> loader.load("/nonexistent/path/config.yaml"));
    }

    @Test
    void maskSensitiveValues_passwordAndSecretKeyMasked() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  username: admin\n" +
                "  password: superSecret\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n" +
                "  accessKey: AKIAIOSFODNN7EXAMPLE\n" +
                "  secretKey: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n";

        File yamlFile = tempDir.resolve("config.yaml").toFile();
        writeFile(yamlFile, yaml);

        SyncConfig config = loader.load(yamlFile.getAbsolutePath());
        Map<String, String> masked = loader.toMaskedMap(config);

        assertEquals("****", masked.get("rangerConfig.password"));
        assertEquals("****", masked.get("awsConfig.accessKey"));
        assertEquals("****", masked.get("awsConfig.secretKey"));
        // Non-sensitive values should not be masked
        assertEquals("http://ranger:6080", masked.get("rangerConfig.rangerAdminUrl"));
        assertEquals("admin", masked.get("rangerConfig.username"));
        assertEquals("us-east-1", masked.get("awsConfig.region"));
    }

    @Test
    void maskSensitiveValues_nullValuesReturnNull() {
        assertNull(ConfigLoader.mask(null));
        assertEquals("****", ConfigLoader.mask("anyValue"));
    }

    @Test
    void toMaskedMap_nullSubConfigs() {
        SyncConfig config = new SyncConfig(null, null, null, null, null, null, null);
        Map<String, String> masked = loader.toMaskedMap(config);

        assertNotNull(masked);
        assertFalse(masked.containsKey("rangerConfig.rangerAdminUrl"));
        assertFalse(masked.containsKey("awsConfig.region"));
        // Sync-level fields should still be present
        assertTrue(masked.containsKey("policyRefreshIntervalMs"));
    }

    @Test
    void environmentVariableOverrides_invalidNumericIgnored() throws IOException {
        String yaml =
                "rangerConfig:\n" +
                "  rangerAdminUrl: http://ranger:6080\n" +
                "  maxRetries: 3\n" +
                "awsConfig:\n" +
                "  region: us-east-1\n";

        File yamlFile = tempDir.resolve("config.yaml").toFile();
        writeFile(yamlFile, yaml);

        envVars.put("RANGER_MAX_RETRIES", "not-a-number");

        SyncConfig config = loader.load(yamlFile.getAbsolutePath());

        // Should keep the file value when env var is invalid
        assertEquals(3, config.getRangerConfig().getMaxRetries());
    }

    private void writeFile(File file, String content) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }
}
