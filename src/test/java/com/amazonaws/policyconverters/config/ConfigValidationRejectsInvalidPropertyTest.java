package com.amazonaws.policyconverters.config;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 6: Configuration Validation Rejects Invalid Configs

/**
 * Property-based test verifying that {@link ConfigValidator} rejects invalid
 * {@link SyncConfig} objects containing malformed {@code rangerServices} entries.
 *
 * Generates configs with:
 * <ul>
 *   <li>Duplicate serviceType+serviceInstanceName pairs</li>
 *   <li>Unknown serviceType values</li>
 *   <li>Missing/blank serviceInstanceName</li>
 *   <li>Presto/Trino entries without gdcCatalogName</li>
 * </ul>
 *
 * <b>Validates: Requirements 6.4, 6.5, 6.6, 6.7</b>
 */
@Tag("multi-ranger-plugin-support")
class ConfigValidationRejectsInvalidPropertyTest {

    private final ConfigValidator validator = new ConfigValidator();

    // ---- helpers to build a valid base SyncConfig wrapping rangerServices ----

    private SyncConfig buildConfig(List<RangerServiceConfig> rangerServices) {
        RangerConnectionConfig ranger = new RangerConnectionConfig(
                "https://ranger.example.com", "admin", "admin",
                null, null, null, null);
        AwsConfig aws = new AwsConfig("us-east-1", "123456789012", null, null, null);
        PrincipalMappingConfig principal = new PrincipalMappingConfig(
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        return new SyncConfig(ranger, aws, principal,
                30000L, 5, 2000L,
                "dead-letter.log", "checkpoint.json",
                60, rangerServices);
    }

    // ---- Property: duplicate serviceType+serviceInstanceName → errors ----

    /**
     * Validates: Requirements 6.4
     */
    @Property(tries = 100)
    void duplicateServiceTypeAndInstanceNamePairProducesError(
            @ForAll("validServiceTypes") String serviceType,
            @ForAll("nonBlankAlpha") String instanceName
    ) {
        String gdcCatalog = requiresCatalog(serviceType) ? "awsdatacatalog" : null;
        RangerServiceConfig entry = new RangerServiceConfig(serviceType, instanceName, null, gdcCatalog);

        // Two identical entries → duplicate
        List<RangerServiceConfig> services = List.of(entry, entry);
        SyncConfig config = buildConfig(services);

        List<String> errors = validator.validate(config);

        assertFalse(errors.isEmpty(),
                "Duplicate serviceType+serviceInstanceName should produce validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate")),
                "Error list should mention 'duplicate': " + errors);
    }

    // ---- Property: unknown serviceType → errors ----

    /**
     * Validates: Requirements 6.5
     */
    @Property(tries = 100)
    void unknownServiceTypeProducesError(
            @ForAll("unknownServiceTypes") String unknownType,
            @ForAll("nonBlankAlpha") String instanceName
    ) {
        RangerServiceConfig entry = new RangerServiceConfig(unknownType, instanceName, null, null);
        List<RangerServiceConfig> services = List.of(entry);
        SyncConfig config = buildConfig(services);

        List<String> errors = validator.validate(config);

        assertFalse(errors.isEmpty(),
                "Unknown serviceType '" + unknownType + "' should produce validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown serviceType")),
                "Error list should mention 'unknown serviceType': " + errors);
    }

    // ---- Property: missing/blank serviceInstanceName → errors ----

    /**
     * Validates: Requirements 6.6
     */
    @Property(tries = 100)
    void missingOrBlankInstanceNameProducesError(
            @ForAll("validServiceTypes") String serviceType,
            @ForAll("blankStrings") String blankName
    ) {
        String gdcCatalog = requiresCatalog(serviceType) ? "awsdatacatalog" : null;
        RangerServiceConfig entry = new RangerServiceConfig(serviceType, blankName, null, gdcCatalog);
        List<RangerServiceConfig> services = List.of(entry);
        SyncConfig config = buildConfig(services);

        List<String> errors = validator.validate(config);

        assertFalse(errors.isEmpty(),
                "Missing/blank serviceInstanceName should produce validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("serviceInstanceName")),
                "Error list should mention 'serviceInstanceName': " + errors);
    }

    // ---- Property: presto/trino without gdcCatalogName → errors ----

    /**
     * Validates: Requirements 6.7
     */
    @Property(tries = 100)
    void prestoTrinoWithoutGdcCatalogNameProducesError(
            @ForAll("catalogRequiredTypes") String serviceType,
            @ForAll("nonBlankAlpha") String instanceName,
            @ForAll("blankStrings") String blankCatalog
    ) {
        RangerServiceConfig entry = new RangerServiceConfig(serviceType, instanceName, null, blankCatalog);
        List<RangerServiceConfig> services = List.of(entry);
        SyncConfig config = buildConfig(services);

        List<String> errors = validator.validate(config);

        assertFalse(errors.isEmpty(),
                "Presto/Trino without gdcCatalogName should produce validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("gdcCatalogName")),
                "Error list should mention 'gdcCatalogName': " + errors);
    }

    // ---- Arbitraries ----

    @Provide
    Arbitrary<String> validServiceTypes() {
        return Arbitraries.of("lakeformation", "hive", "presto", "trino");
    }

    @Provide
    Arbitrary<String> catalogRequiredTypes() {
        return Arbitraries.of("presto", "trino");
    }

    @Provide
    Arbitrary<String> unknownServiceTypes() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12)
                .filter(s -> !List.of("lakeformation", "hive", "presto", "trino")
                        .contains(s.toLowerCase()))
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> nonBlankAlpha() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15);
    }

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of(null, "", "   ", "\t", " \n ");
    }

    // ---- Utility ----

    private boolean requiresCatalog(String serviceType) {
        return "presto".equals(serviceType) || "trino".equals(serviceType);
    }
}
