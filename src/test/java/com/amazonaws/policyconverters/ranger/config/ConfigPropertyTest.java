package com.amazonaws.policyconverters.ranger.config;

import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import com.amazonaws.policyconverters.lakeformation.model.AwsConfig;
import com.amazonaws.policyconverters.lakeformation.model.PrincipalMappingConfig;
import com.amazonaws.policyconverters.lakeformation.model.RangerConnectionConfig;
import com.amazonaws.policyconverters.lakeformation.model.SyncConfig;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for configuration handling.
 * Uses jqwik to verify configuration loading, validation, and masking properties.
 */
class ConfigPropertyTest {

    // Feature: ranger-lakeformation-sync, Property 21: Environment variable override
    // **Validates: Requirements 9.2**
    @Property(tries = 100)
    void envVarOverridesFileValue(
            @ForAll("fileConfigValues") String fileValue,
            @ForAll("envOverrideValues") String envValue
    ) {
        // Build a base config with file values for all string fields
        RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                "http://" + fileValue, fileValue, fileValue, null, null, null, null
        );
        AwsConfig awsConfig = new AwsConfig(
                fileValue, fileValue, fileValue, fileValue, null
        );
        SyncConfig baseConfig = new SyncConfig(
                rangerConfig, awsConfig, null, null, null, null, null
        );

        // Map of env var name -> getter that extracts the overridden value
        Map<String, EnvVarMapping> stringMappings = new LinkedHashMap<>();
        stringMappings.put(ConfigLoader.ENV_RANGER_ADMIN_URL, c -> c.getRangerConfig().getRangerAdminUrl());
        stringMappings.put(ConfigLoader.ENV_RANGER_USERNAME, c -> c.getRangerConfig().getUsername());
        stringMappings.put(ConfigLoader.ENV_RANGER_PASSWORD, c -> c.getRangerConfig().getPassword());
        stringMappings.put(ConfigLoader.ENV_AWS_REGION, c -> c.getAwsConfig().getRegion());
        stringMappings.put(ConfigLoader.ENV_AWS_CATALOG_ID, c -> c.getAwsConfig().getCatalogId());
        stringMappings.put(ConfigLoader.ENV_AWS_ACCESS_KEY, c -> c.getAwsConfig().getAccessKey());
        stringMappings.put(ConfigLoader.ENV_AWS_SECRET_KEY, c -> c.getAwsConfig().getSecretKey());
        stringMappings.put(ConfigLoader.ENV_AWS_ROLE_ARN, c -> c.getAwsConfig().getRoleArn());
        stringMappings.put(ConfigLoader.ENV_DEAD_LETTER_LOG_PATH, SyncConfig::getDeadLetterLogPath);

        // For each env var, set only that one and verify it wins
        for (Map.Entry<String, EnvVarMapping> entry : stringMappings.entrySet()) {
            String envVarName = entry.getKey();
            EnvVarMapping getter = entry.getValue();

            // Create an env provider that returns envValue for this var only
            ConfigLoader loader = new ConfigLoader(name -> envVarName.equals(name) ? envValue : null);
            SyncConfig result = loader.applyEnvironmentOverrides(baseConfig);

            assertEquals(envValue, getter.get(result),
                    "Env var " + envVarName + " should override file value");
        }
    }

    // Feature: ranger-lakeformation-sync, Property 22: Configuration validation completeness
    // **Validates: Requirements 9.3**
    @Property(tries = 100)
    void validationReportsExactlyNErrorsForNMissingParams(
            @ForAll("configsWithMissingFields") ConfigWithExpectedErrors configWithErrors
    ) {
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = validator.validate(configWithErrors.config);

        assertEquals(configWithErrors.expectedErrorCount, errors.size(),
                "Expected " + configWithErrors.expectedErrorCount
                        + " errors but got " + errors.size() + ": " + errors);
    }

    // Feature: ranger-lakeformation-sync, Property 23: Sensitive value masking in logs
    // **Validates: Requirements 9.4**
    @Property(tries = 100)
    void sensitiveValuesAreMaskedInLogOutput(
            @ForAll("sensitiveConfigs") SyncConfig config
    ) {
        ConfigLoader loader = new ConfigLoader(name -> null);
        Map<String, String> maskedMap = loader.toMaskedMap(config);

        // Collect all raw sensitive values that are non-null
        List<String> sensitiveValues = new ArrayList<>();
        if (config.getRangerConfig() != null && config.getRangerConfig().getPassword() != null) {
            sensitiveValues.add(config.getRangerConfig().getPassword());
        }
        if (config.getAwsConfig() != null) {
            if (config.getAwsConfig().getAccessKey() != null) {
                sensitiveValues.add(config.getAwsConfig().getAccessKey());
            }
            if (config.getAwsConfig().getSecretKey() != null) {
                sensitiveValues.add(config.getAwsConfig().getSecretKey());
            }
        }

        // Verify the masked map keys for sensitive fields contain "****", not raw values
        String maskedPassword = maskedMap.get("rangerConfig.password");
        String maskedAccessKey = maskedMap.get("awsConfig.accessKey");
        String maskedSecretKey = maskedMap.get("awsConfig.secretKey");

        if (config.getRangerConfig() != null && config.getRangerConfig().getPassword() != null) {
            assertEquals("****", maskedPassword,
                    "rangerConfig.password should be masked");
        }
        if (config.getAwsConfig() != null && config.getAwsConfig().getAccessKey() != null) {
            assertEquals("****", maskedAccessKey,
                    "awsConfig.accessKey should be masked");
        }
        if (config.getAwsConfig() != null && config.getAwsConfig().getSecretKey() != null) {
            assertEquals("****", maskedSecretKey,
                    "awsConfig.secretKey should be masked");
        }

        // Additionally verify no raw sensitive value appears anywhere in the masked map values
        for (String rawSensitive : sensitiveValues) {
            if (rawSensitive.equals("****")) {
                continue; // Skip if the raw value happens to be the mask itself
            }
            for (Map.Entry<String, String> mapEntry : maskedMap.entrySet()) {
                if (mapEntry.getValue() != null) {
                    assertFalse(mapEntry.getValue().contains(rawSensitive),
                            "Raw sensitive value should not appear in masked map entry '"
                                    + mapEntry.getKey() + "'");
                }
            }
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> fileConfigValues() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> envOverrideValues() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    @Provide
    Arbitrary<ConfigWithExpectedErrors> configsWithMissingFields() {
        // We generate configs by selectively including/excluding required fields.
        // Required fields and their groupings:
        //   1. rangerConfig.rangerAdminUrl (must be present and start with http(s)://)
        //   2. rangerConfig auth: username+password OR kerberosKeytab+kerberosPrincipal
        //   3. awsConfig.region
        //   4. awsConfig.catalogId
        //   5. awsConfig creds: accessKey+secretKey OR roleArn
        return Combinators.combine(
                Arbitraries.of(true, false), // hasRangerAdminUrl
                Arbitraries.of(true, false), // hasBasicAuth
                Arbitraries.of(true, false), // hasKerberosAuth
                Arbitraries.of(true, false), // hasRegion
                Arbitraries.of(true, false), // hasCatalogId
                Arbitraries.of(true, false), // hasStaticCreds
                Arbitraries.of(true, false)  // hasRoleArn
        ).as((hasUrl, hasBasicAuth, hasKerberosAuth, hasRegion, hasCatalogId, hasStaticCreds, hasRoleArn) -> {
            int expectedErrors = 0;

            String url = hasUrl ? "https://ranger.example.com" : null;
            if (!hasUrl) expectedErrors++;

            String username = hasBasicAuth ? "admin" : null;
            String password = hasBasicAuth ? "secret" : null;
            String keytab = hasKerberosAuth ? "/etc/keytab" : null;
            String principal = hasKerberosAuth ? "ranger@REALM" : null;
            if (!hasBasicAuth && !hasKerberosAuth) expectedErrors++;

            RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                    url, username, password, keytab, principal, null, null
            );

            String region = hasRegion ? "us-east-1" : null;
            if (!hasRegion) expectedErrors++;

            String catalogId = hasCatalogId ? "123456789012" : null;
            if (!hasCatalogId) expectedErrors++;

            String accessKey = hasStaticCreds ? "AKIAIOSFODNN7EXAMPLE" : null;
            String secretKey = hasStaticCreds ? "wJalrXUtnFEMI/K7MDENG" : null;
            String roleArn = hasRoleArn ? "arn:aws:iam::123456789012:role/test" : null;
            if (!hasStaticCreds && !hasRoleArn) expectedErrors++;

            AwsConfig awsConfig = new AwsConfig(region, catalogId, accessKey, secretKey, roleArn);

            SyncConfig config = new SyncConfig(
                    rangerConfig, awsConfig, null, null, null, null, null
            );

            return new ConfigWithExpectedErrors(config, expectedErrors);
        });
    }

    @Provide
    Arbitrary<SyncConfig> sensitiveConfigs() {
        // Generate configs with random non-trivial sensitive values
        Arbitrary<String> sensitiveValue = Arbitraries.strings()
                .alpha()
                .ofMinLength(5)
                .ofMaxLength(30)
                .filter(s -> !"****".equals(s)); // Exclude the mask value itself

        Arbitrary<String> normalValue = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20);

        return Combinators.combine(normalValue, sensitiveValue, sensitiveValue, sensitiveValue)
                .as((normal, password, accessKey, secretKey) -> {
                    RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                            "https://" + normal, normal, password, null, null, null, null
                    );
                    AwsConfig awsConfig = new AwsConfig(
                            normal, normal, accessKey, secretKey, null
                    );
                    return new SyncConfig(
                            rangerConfig, awsConfig, null, null, null, null, null
                    );
                });
    }

    // --- Helper types ---

    @FunctionalInterface
    interface EnvVarMapping {
        String get(SyncConfig config);
    }

    static class ConfigWithExpectedErrors {
        final SyncConfig config;
        final int expectedErrorCount;

        ConfigWithExpectedErrors(SyncConfig config, int expectedErrorCount) {
            this.config = config;
            this.expectedErrorCount = expectedErrorCount;
        }
    }
}
