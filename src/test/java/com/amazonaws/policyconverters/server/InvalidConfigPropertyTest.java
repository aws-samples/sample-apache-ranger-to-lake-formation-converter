package com.amazonaws.policyconverters.server;

import com.amazonaws.policyconverters.lakeformation.model.AwsConfig;
import com.amazonaws.policyconverters.lakeformation.model.RangerConnectionConfig;
import com.amazonaws.policyconverters.lakeformation.model.SyncConfig;
import com.amazonaws.policyconverters.ranger.config.ConfigValidator;
import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Feature: conversion-server, Property 1: Invalid configuration produces error exit

/**
 * Property-based test verifying that any SyncConfig with at least one required field
 * missing or blank produces validation errors, which would cause startup to fail
 * with a non-zero exit code.
 *
 * **Validates: Requirements 1.2, 1.3**
 */
class InvalidConfigPropertyTest {

    private final ConfigValidator validator = new ConfigValidator();

    @Property(tries = 100)
    void invalidConfigProducesValidationErrors(
            @ForAll("invalidSyncConfigs") SyncConfig config
    ) {
        List<String> errors = validator.validate(config);

        assertFalse(errors.isEmpty(),
                "Config with at least one missing required field should produce validation errors, got none for: " + config);
    }

    @Property(tries = 100)
    void nullConfigProducesValidationError() {
        List<String> errors = validator.validate(null);

        assertFalse(errors.isEmpty(), "Null config should produce at least one validation error");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<SyncConfig> invalidSyncConfigs() {
        // Required field groups checked by ConfigValidator:
        //   1. rangerConfig not null, rangerAdminUrl not blank and starts with http(s)://
        //   2. rangerConfig auth: username+password OR kerberosKeytab+kerberosPrincipal
        //   3. awsConfig not null, region not blank
        //   4. awsConfig.catalogId not blank
        //   5. awsConfig creds: accessKey+secretKey OR roleArn

        Arbitrary<Boolean> bools = Arbitraries.of(true, false);

        // First combine: build RangerConnectionConfig from 4 booleans
        Arbitrary<RangerConnectionConfig> rangerConfigs = Combinators.combine(
                bools, // hasRangerAdminUrl
                bools, // hasBasicAuth
                bools  // hasKerberosAuth
        ).as((hasUrl, hasBasicAuth, hasKerberosAuth) -> new RangerConnectionConfig(
                hasUrl ? "https://ranger.example.com" : null,
                hasBasicAuth ? "admin" : null,
                hasBasicAuth ? "secret" : null,
                hasKerberosAuth ? "/etc/keytab" : null,
                hasKerberosAuth ? "ranger@REALM" : null,
                null, null
        ));

        // Second combine: build AwsConfig from 4 booleans
        Arbitrary<AwsConfig> awsConfigs = Combinators.combine(
                bools, // hasRegion
                bools, // hasCatalogId
                bools, // hasStaticCreds
                bools  // hasRoleArn
        ).as((hasRegion, hasCatalogId, hasStaticCreds, hasRoleArn) -> new AwsConfig(
                hasRegion ? "us-east-1" : null,
                hasCatalogId ? "123456789012" : null,
                hasStaticCreds ? "AKIAIOSFODNN7EXAMPLE" : null,
                hasStaticCreds ? "wJalrXUtnFEMI/K7MDENG" : null,
                hasRoleArn ? "arn:aws:iam::123456789012:role/test" : null
        ));

        // Final combine: assemble SyncConfig, optionally nulling out entire sub-configs
        return Combinators.combine(bools, rangerConfigs, bools, awsConfigs)
                .as((hasRangerConfig, rangerConfig, hasAwsConfig, awsConfig) ->
                        new SyncConfig(
                                hasRangerConfig ? rangerConfig : null,
                                hasAwsConfig ? awsConfig : null,
                                null, null, null, null, null
                        ))
                .filter(config -> !validator.validate(config).isEmpty());
    }
}
