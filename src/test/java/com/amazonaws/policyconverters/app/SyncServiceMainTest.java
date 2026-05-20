package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.ConfigLoader;
import com.amazonaws.policyconverters.config.SyncConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SyncServiceMain entry point.
 * Tests configuration loading/validation and credential provider building.
 */
class SyncServiceMainTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAndValidateConfig_validYaml_succeeds() throws Exception {
        File configFile = createValidYamlConfig();
        SyncConfig config = SyncServiceMain.loadAndValidateConfig(configFile.getAbsolutePath(), cleanEnvLoader());

        assertNotNull(config);
        assertNotNull(config.getRangerConfig());
        assertNotNull(config.getAwsConfig());
        assertEquals("https://ranger.example.com:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("us-east-1", config.getAwsConfig().getRegion());
        assertEquals("123456789012", config.getAwsConfig().getCatalogId());
    }

    @Test
    void loadAndValidateConfig_invalidConfig_throwsIllegalState() throws Exception {
        // Config missing required fields
        File configFile = tempDir.resolve("bad-config.yaml").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("rangerConfig:\n");
            writer.write("  rangerAdminUrl: \"\"\n");
        }

        assertThrows(IllegalStateException.class,
                () -> SyncServiceMain.loadAndValidateConfig(configFile.getAbsolutePath()));
    }

    @Test
    void loadAndValidateConfig_missingFile_throwsIOException() {
        assertThrows(IOException.class,
                () -> SyncServiceMain.loadAndValidateConfig("/nonexistent/path/config.yaml"));
    }

    @Test
    void buildCredentialsProvider_staticCredentials_returnsStaticProvider() {
        AwsConfig awsConfig = new AwsConfig("us-east-1", "123456789012",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null);

        AwsCredentialsProvider provider = SyncServiceMain.buildCredentialsProvider(awsConfig);

        assertNotNull(provider);
        assertTrue(provider instanceof StaticCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_roleArnOnly_returnsStsProvider() {
        AwsConfig awsConfig = new AwsConfig("us-east-1", "123456789012",
                null, null, "arn:aws:iam::123456789012:role/TestRole");

        AwsCredentialsProvider provider = SyncServiceMain.buildCredentialsProvider(awsConfig);

        assertNotNull(provider);
        assertTrue(provider instanceof StsAssumeRoleCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_staticCredsAndRoleArn_returnsStsProvider() {
        AwsConfig awsConfig = new AwsConfig("us-east-1", "123456789012",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "arn:aws:iam::123456789012:role/TestRole");

        AwsCredentialsProvider provider = SyncServiceMain.buildCredentialsProvider(awsConfig);

        assertNotNull(provider);
        assertTrue(provider instanceof StsAssumeRoleCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_noCredentials_returnsDefaultProvider() {
        AwsConfig awsConfig = new AwsConfig("us-east-1", "123456789012",
                null, null, null);

        AwsCredentialsProvider provider = SyncServiceMain.buildCredentialsProvider(awsConfig);

        assertNotNull(provider);
        assertTrue(provider instanceof DefaultCredentialsProvider);
    }

    @Test
    void buildCredentialsProvider_emptyStrings_returnsDefaultProvider() {
        AwsConfig awsConfig = new AwsConfig("us-east-1", "123456789012",
                "", "", "");

        AwsCredentialsProvider provider = SyncServiceMain.buildCredentialsProvider(awsConfig);

        assertNotNull(provider);
        assertTrue(provider instanceof DefaultCredentialsProvider);
    }

    @Test
    void loadAndValidateConfig_validProperties_succeeds() throws Exception {
        File configFile = createValidPropertiesConfig();
        SyncConfig config = SyncServiceMain.loadAndValidateConfig(configFile.getAbsolutePath(), cleanEnvLoader());

        assertNotNull(config);
        assertEquals("https://ranger.example.com:6080", config.getRangerConfig().getRangerAdminUrl());
        assertEquals("us-west-2", config.getAwsConfig().getRegion());
    }

    private File createValidYamlConfig() throws IOException {
        File configFile = tempDir.resolve("sync-config.yaml").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("rangerConfig:\n");
            writer.write("  rangerAdminUrl: \"https://ranger.example.com:6080\"\n");
            writer.write("  username: \"admin\"\n");
            writer.write("  password: \"admin123\"\n");
            writer.write("awsConfig:\n");
            writer.write("  region: \"us-east-1\"\n");
            writer.write("  catalogId: \"123456789012\"\n");
            writer.write("  accessKey: \"AKIAIOSFODNN7EXAMPLE\"\n");
            writer.write("  secretKey: \"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"\n");
            writer.write("principalMapping:\n");
            writer.write("  userMappings:\n");
            writer.write("    alice: \"arn:aws:iam::123456789012:user/alice\"\n");
            writer.write("  groupMappings: {}\n");
            writer.write("  roleMappings: {}\n");
        }
        return configFile;
    }

    /** ConfigLoader that ignores host environment variables so tests are environment-independent. */
    private static ConfigLoader cleanEnvLoader() {
        return new ConfigLoader(name -> null);
    }

    private File createValidPropertiesConfig() throws IOException {
        File configFile = tempDir.resolve("sync-config.properties").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("rangerConfig.rangerAdminUrl=https://ranger.example.com:6080\n");
            writer.write("rangerConfig.username=admin\n");
            writer.write("rangerConfig.password=admin123\n");
            writer.write("awsConfig.region=us-west-2\n");
            writer.write("awsConfig.catalogId=987654321098\n");
            writer.write("awsConfig.accessKey=AKIAIOSFODNN7EXAMPLE\n");
            writer.write("awsConfig.secretKey=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n");
        }
        return configFile;
    }
}
