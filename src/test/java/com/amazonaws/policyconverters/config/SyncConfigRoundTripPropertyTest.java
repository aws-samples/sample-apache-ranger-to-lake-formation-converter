package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 5: Configuration Round-Trip

/**
 * Property-based test for SyncConfig YAML round-trip serialization.
 * Generates random SyncConfig objects with rangerServices lists,
 * serializes to YAML, deserializes back, and asserts equality.
 *
 * **Validates: Requirements 6.1**
 */
@Tag("multi-ranger-plugin-support")
class SyncConfigRoundTripPropertyTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Property(tries = 100)
    void syncConfigWithRangerServicesRoundTrips(
            @ForAll("validSyncConfigs") SyncConfig original
    ) throws Exception {
        String yaml = yamlMapper.writeValueAsString(original);
        SyncConfig deserialized = yamlMapper.readValue(yaml, SyncConfig.class);

        assertEquals(original, deserialized,
                "SyncConfig should survive YAML round-trip");
    }

    @Property(tries = 100)
    void rangerServicesListPreservedAfterRoundTrip(
            @ForAll("validSyncConfigs") SyncConfig original
    ) throws Exception {
        String yaml = yamlMapper.writeValueAsString(original);
        SyncConfig deserialized = yamlMapper.readValue(yaml, SyncConfig.class);

        assertEquals(original.getRangerServices(), deserialized.getRangerServices(),
                "rangerServices list should survive YAML round-trip");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<SyncConfig> validSyncConfigs() {
        return Combinators.combine(
                rangerConnectionConfigs(),
                awsConfigs(),
                principalMappingConfigs(),
                Arbitraries.longs().between(1000L, 120000L),
                Arbitraries.integers().between(1, 20),
                Arbitraries.longs().between(100L, 30000L),
                rangerServiceConfigLists()
        ).as((ranger, aws, principal, refreshInterval, maxRetries, backoff, services) ->
                new SyncConfig(
                        ranger, aws, principal,
                        refreshInterval, maxRetries, backoff,
                        "dead-letter.log", "checkpoint.json",
                        60, services
                )
        );
    }

    private Arbitrary<RangerConnectionConfig> rangerConnectionConfigs() {
        Arbitrary<String> urls = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .map(s -> "https://" + s + ".example.com");
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);

        return Combinators.combine(urls, names, names)
                .as((url, user, pass) -> new RangerConnectionConfig(
                        url, user, pass, null, null, null, null
                ));
    }

    private Arbitrary<AwsConfig> awsConfigs() {
        Arbitrary<String> regions = Arbitraries.of("us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1");
        Arbitrary<String> catalogIds = Arbitraries.strings().numeric().ofLength(12);

        return Combinators.combine(regions, catalogIds)
                .as((region, catalogId) -> new AwsConfig(
                        region, catalogId, null, null, null
                ));
    }

    private Arbitrary<PrincipalMappingConfig> principalMappingConfigs() {
        Arbitrary<Map<String, String>> mappings = Arbitraries.maps(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
                        .map(s -> "arn:aws:iam::123456789012:role/" + s)
        ).ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(mappings, mappings, mappings)
                .as(PrincipalMappingConfig::new);
    }

    private Arbitrary<List<RangerServiceConfig>> rangerServiceConfigLists() {
        return rangerServiceConfigs().list().ofMinSize(1).ofMaxSize(5);
    }

    private Arbitrary<RangerServiceConfig> rangerServiceConfigs() {
        Arbitrary<String> serviceTypes = Arbitraries.of("lakeformation", "hive", "presto", "trino");
        Arbitrary<String> instanceNames = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15);

        return Combinators.combine(serviceTypes, instanceNames)
                .as((type, name) -> {
                    String gdcCatalog = ("presto".equals(type) || "trino".equals(type))
                            ? "awsdatacatalog" : null;
                    return new RangerServiceConfig(type, name, null, gdcCatalog);
                });
    }
}
