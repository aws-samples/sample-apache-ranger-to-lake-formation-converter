package org.apache.ranger.lakeformation.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.apache.ranger.lakeformation.model.PrincipalMappingConfig;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for PrincipalMapper.
 * Uses jqwik to verify principal mapping round-trip and unmapped principal skipping.
 */
class PrincipalMapperPropertyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Feature: ranger-lakeformation-sync, Property 16: Principal mapping round-trip
    // **Validates: Requirements 6.5**
    @Property(tries = 100)
    void principalMappingConfigRoundTripThroughJson(
            @ForAll("principalMappingConfigs") PrincipalMappingConfig original
    ) throws Exception {
        String json = OBJECT_MAPPER.writeValueAsString(original);
        PrincipalMappingConfig deserialized = OBJECT_MAPPER.readValue(json, PrincipalMappingConfig.class);

        assertEquals(original, deserialized,
                "PrincipalMappingConfig should survive JSON round-trip");
        assertEquals(original.getUserMappings(), deserialized.getUserMappings());
        assertEquals(original.getGroupMappings(), deserialized.getGroupMappings());
        assertEquals(original.getRoleMappings(), deserialized.getRoleMappings());
    }

    // Feature: ranger-lakeformation-sync, Property 15: Unmapped principal skipping
    // **Validates: Requirements 6.3**
    @Property(tries = 100)
    void unmappedPrincipalsReturnEmptyAndMappedReturnArn(
            @ForAll("principalMappingConfigs") PrincipalMappingConfig config,
            @ForAll("unmappedNames") Set<String> unmappedNames
    ) {
        PrincipalMapper mapper = PrincipalMapper.fromConfig(config);

        // Verify mapped users return the correct ARN
        for (Map.Entry<String, String> entry : config.getUserMappings().entrySet()) {
            Optional<String> result = mapper.resolveUser(entry.getKey());
            assertTrue(result.isPresent(),
                    "Mapped user '" + entry.getKey() + "' should resolve");
            assertEquals(entry.getValue(), result.get());
        }

        // Verify mapped groups return the correct ARN
        for (Map.Entry<String, String> entry : config.getGroupMappings().entrySet()) {
            Optional<String> result = mapper.resolveGroup(entry.getKey());
            assertTrue(result.isPresent(),
                    "Mapped group '" + entry.getKey() + "' should resolve");
            assertEquals(entry.getValue(), result.get());
        }

        // Verify mapped roles return the correct ARN
        for (Map.Entry<String, String> entry : config.getRoleMappings().entrySet()) {
            Optional<String> result = mapper.resolveRole(entry.getKey());
            assertTrue(result.isPresent(),
                    "Mapped role '" + entry.getKey() + "' should resolve");
            assertEquals(entry.getValue(), result.get());
        }

        // Verify unmapped names return Optional.empty() for all principal types
        Set<String> allMappedNames = new HashSet<>();
        allMappedNames.addAll(config.getUserMappings().keySet());
        allMappedNames.addAll(config.getGroupMappings().keySet());
        allMappedNames.addAll(config.getRoleMappings().keySet());

        for (String unmapped : unmappedNames) {
            if (!config.getUserMappings().containsKey(unmapped)) {
                assertFalse(mapper.resolveUser(unmapped).isPresent(),
                        "Unmapped user '" + unmapped + "' should return empty");
            }
            if (!config.getGroupMappings().containsKey(unmapped)) {
                assertFalse(mapper.resolveGroup(unmapped).isPresent(),
                        "Unmapped group '" + unmapped + "' should return empty");
            }
            if (!config.getRoleMappings().containsKey(unmapped)) {
                assertFalse(mapper.resolveRole(unmapped).isPresent(),
                        "Unmapped role '" + unmapped + "' should return empty");
            }
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<PrincipalMappingConfig> principalMappingConfigs() {
        Arbitrary<Map<String, String>> mappingMap = mappingEntries();
        return Combinators.combine(mappingMap, mappingMap, mappingMap)
                .as(PrincipalMappingConfig::new);
    }

    @Provide
    Arbitrary<Set<String>> unmappedNames() {
        // Generate names with a prefix that won't collide with mapped names
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(10)
                .map(s -> "unmapped_" + s)
                .set()
                .ofMinSize(1)
                .ofMaxSize(5);
    }

    private Arbitrary<Map<String, String>> mappingEntries() {
        Arbitrary<String> principalName = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(15);

        Arbitrary<String> iamArn = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(10)
                .map(s -> "arn:aws:iam::123456789012:user/" + s);

        return Combinators.combine(principalName, iamArn)
                .as((k, v) -> new AbstractMap.SimpleEntry<>(k, v))
                .list()
                .ofMinSize(0)
                .ofMaxSize(5)
                .map(entries -> {
                    Map<String, String> map = new HashMap<>();
                    for (AbstractMap.SimpleEntry<String, String> e : entries) {
                        map.put(e.getKey(), e.getValue());
                    }
                    return map;
                });
    }
}
