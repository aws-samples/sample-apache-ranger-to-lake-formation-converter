package com.amazonaws.policyconverters.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

// Feature: multi-ranger-plugin-support, Property 10: Checkpoint Round-Trip with Per-Service Versions
// Feature: multi-ranger-plugin-support, Property 11: Legacy Checkpoint Backward Compatibility

/**
 * Property-based tests for checkpoint persistence round-trip and legacy backward compatibility.
 *
 * Property 10: For any map of service types to policy versions and any Cedar policy text,
 * saving a checkpoint and loading it back SHALL produce an equivalent per-service version map
 * and identical Cedar policy text.
 *
 * Property 11: For any legacy checkpoint JSON containing a single policyVersion field
 * (no serviceVersions), loading it SHALL produce a service version map with exactly one entry:
 * {"lakeformation": policyVersion}.
 */
@Tag("multi-ranger-plugin-support")
class CheckpointRoundTripPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Property 10: Checkpoint Round-Trip with Per-Service Versions
    // **Validates: Requirements 10.1, 10.2, 9.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void checkpointRoundTripPreservesServiceVersionsAndCedarText(
            @ForAll("serviceVersionMaps") Map<String, Long> serviceVersions,
            @ForAll("cedarTexts") String cedarPolicyText
    ) throws Exception {
        Path tempFile = Files.createTempFile("checkpoint-pbt-", ".json");
        try {
            CheckpointStore store = new CheckpointStore(tempFile, objectMapper);

            store.save(serviceVersions, cedarPolicyText);

            Optional<SyncCheckpoint> loaded = store.load();

            assertTrue(loaded.isPresent(), "Checkpoint should be loadable after save");

            SyncCheckpoint checkpoint = loaded.get();
            assertEquals(serviceVersions, checkpoint.getServiceVersions(),
                    "Per-service version map should survive round-trip");
            assertEquals(cedarPolicyText, checkpoint.getCedarPolicyText(),
                    "Cedar policy text should survive round-trip");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // -----------------------------------------------------------------------
    // Property 11: Legacy Checkpoint Backward Compatibility
    // **Validates: Requirements 10.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void legacyCheckpointDeserializesToSingleLakeFormationEntry(
            @ForAll("policyVersions") long policyVersion,
            @ForAll("timestamps") String timestamp,
            @ForAll("cedarTexts") String cedarPolicyText
    ) throws Exception {
        // Build legacy JSON with only policyVersion (no serviceVersions field)
        String legacyJson = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("policyVersion", policyVersion)
                        .put("timestamp", timestamp)
                        .put("cedarPolicyText", cedarPolicyText)
        );

        SyncCheckpoint checkpoint = objectMapper.readValue(legacyJson, SyncCheckpoint.class);

        assertEquals(Map.of("lakeformation", policyVersion), checkpoint.getServiceVersions(),
                "Legacy checkpoint should produce {\"lakeformation\": policyVersion}");
        assertEquals(policyVersion, checkpoint.getPolicyVersion(),
                "Legacy policyVersion field should be preserved");
        assertEquals(cedarPolicyText, checkpoint.getCedarPolicyText(),
                "Cedar policy text should be preserved from legacy checkpoint");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Map<String, Long>> serviceVersionMaps() {
        Arbitrary<String> serviceTypes = Arbitraries.of(
                "lakeformation", "hive", "presto", "trino");
        Arbitrary<Long> versions = Arbitraries.longs().between(0L, 100_000L);

        return Arbitraries.maps(serviceTypes, versions)
                .ofMinSize(1)
                .ofMaxSize(4);
    }

    @Provide
    Arbitrary<String> cedarTexts() {
        return Arbitraries.of(
                "",
                "permit(principal, action, resource);",
                "permit(principal == User::\"alice\", action == Action::\"SELECT\", resource);",
                "forbid(principal, action, resource) when { resource.tags.contains(\"pii\") };",
                "@source(\"lakeformation:42\")\npermit(principal, action, resource);\n"
                        + "@source(\"hive:7\")\npermit(principal, action, resource);"
        );
    }

    @Provide
    Arbitrary<Long> policyVersions() {
        return Arbitraries.longs().between(0L, 100_000L);
    }

    @Provide
    Arbitrary<String> timestamps() {
        return Arbitraries.of(
                "2025-01-15T10:30:00Z",
                "2025-06-01T00:00:00Z",
                "2024-12-31T23:59:59Z"
        );
    }
}
