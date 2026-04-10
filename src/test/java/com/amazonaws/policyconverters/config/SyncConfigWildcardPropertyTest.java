package com.amazonaws.policyconverters.config;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for wildcard refresh interval configuration validation.
 *
 * **Validates: Requirements 1.1, 1.3, 1.5**
 */
@Tag("Feature: wildcard-pattern-refresh, Property 1: Configuration validation classifies all integers correctly")
class SyncConfigWildcardPropertyTest {

    private static final String NEGATIVE_ERROR_MESSAGE =
            "Invalid parameter: wildcardRefreshIntervalSeconds must be >= 0";

    /**
     * Property 1: Configuration validation classifies all integers correctly.
     *
     * For any integer value provided as wildcardRefreshIntervalSeconds, the configuration
     * system SHALL: accept and store positive values as-is, treat zero as disabled
     * (stored as 0), and reject negative values with a validation error.
     *
     * **Validates: Requirements 1.1, 1.3, 1.5**
     */
    @Property(tries = 200)
    void configValidationClassifiesAllIntegersCorrectly(@ForAll int intervalValue) {
        SyncConfig config = buildValidConfigWithInterval(intervalValue);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = validator.validate(config);

        if (intervalValue > 0) {
            // Positive values: stored as-is, no validation error for this field
            assertEquals(intervalValue, config.getWildcardRefreshIntervalSeconds(),
                    "Positive value should be stored as-is");
            assertFalse(errors.stream().anyMatch(e -> e.contains("wildcardRefreshIntervalSeconds")),
                    "Positive value should not produce a wildcardRefreshIntervalSeconds error");
        } else if (intervalValue == 0) {
            // Zero: treated as disabled, stored as 0, no validation error
            assertEquals(0, config.getWildcardRefreshIntervalSeconds(),
                    "Zero should be stored as 0 (disabled)");
            assertFalse(errors.stream().anyMatch(e -> e.contains("wildcardRefreshIntervalSeconds")),
                    "Zero should not produce a wildcardRefreshIntervalSeconds error");
        } else {
            // Negative values: produce validation error
            assertEquals(intervalValue, config.getWildcardRefreshIntervalSeconds(),
                    "Negative value should still be stored in the config object");
            assertTrue(errors.contains(NEGATIVE_ERROR_MESSAGE),
                    "Negative value should produce the expected validation error message");
        }
    }

    @Property(tries = 100)
    void positiveValuesStoredAsIs(@ForAll @IntRange(min = 1) int positiveValue) {
        SyncConfig config = buildValidConfigWithInterval(positiveValue);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = validator.validate(config);

        assertEquals(positiveValue, config.getWildcardRefreshIntervalSeconds());
        assertTrue(errors.isEmpty(),
                "Valid config with positive interval should have no errors, got: " + errors);
    }

    @Property(tries = 100)
    void negativeValuesProduceValidationError(@ForAll @IntRange(min = Integer.MIN_VALUE, max = -1) int negativeValue) {
        SyncConfig config = buildValidConfigWithInterval(negativeValue);
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = validator.validate(config);

        assertTrue(errors.contains(NEGATIVE_ERROR_MESSAGE),
                "Negative value " + negativeValue + " should produce validation error, got: " + errors);
    }

    /**
     * Build a SyncConfig that is valid in all respects except for the
     * wildcardRefreshIntervalSeconds field, which is set to the given value.
     */
    private SyncConfig buildValidConfigWithInterval(int intervalSeconds) {
        RangerConnectionConfig rangerConfig = new RangerConnectionConfig(
                "https://ranger:6080", "admin", "secret",
                null, null, null, null);
        AwsConfig awsConfig = new AwsConfig(
                "us-east-1", "123456789012", "AKID", "SK", null);
        return new SyncConfig(
                rangerConfig, awsConfig, null,
                null, null, null, null,
                null, intervalSeconds);
    }
}
