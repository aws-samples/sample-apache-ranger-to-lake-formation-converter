package com.amazonaws.policyconverters.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// Feature: tag-metadata-sync
// Property 6: For any SyncCheckpoint with tag version and tag names,
//             serialize → deserialize → result is equivalent

@Tag("tag-metadata-sync")
class TagCheckpointRoundTripPropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Property 6: Tag checkpoint round-trip
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void tagCheckpointRoundTrip(
            @ForAll("serviceVersionMaps") Map<String, Long> serviceVersions,
            @ForAll("cedarTexts") String cedarPolicyText,
            @ForAll("tagVersions") long tagVersion,
            @ForAll("tagNameSets") Set<String> tagNames
    ) throws Exception {
        Path tempFile = Files.createTempFile("tag-checkpoint-pbt-", ".json");
        try {
            CheckpointStore store = new CheckpointStore(tempFile, objectMapper);

            store.save(serviceVersions, cedarPolicyText);
            store.saveTagState(tagVersion, tagNames);

            Optional<SyncCheckpoint> loaded = store.load();
            assertTrue(loaded.isPresent());

            SyncCheckpoint checkpoint = loaded.get();
            assertEquals(serviceVersions, checkpoint.getServiceVersions(),
                    "Per-service version map should survive round-trip");
            assertEquals(cedarPolicyText, checkpoint.getCedarPolicyText(),
                    "Cedar policy text should survive round-trip");
            assertEquals(tagVersion, checkpoint.getLastKnownTagVersion(),
                    "Tag version should survive round-trip");
            assertEquals(tagNames, checkpoint.getLastKnownRangerTagNames(),
                    "Tag names should survive round-trip");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // -----------------------------------------------------------------------
    // Property 5: Tag version incremental tracking
    // Sequence of increasing tag versions → checkpoint always stores the latest
    // -----------------------------------------------------------------------
    @Property(tries = 50)
    void tagVersionIncremental_checkpointStoresLatest(
            @ForAll("tagVersionSequences") long[] versions
    ) throws Exception {
        Path tempFile = Files.createTempFile("tag-version-pbt-", ".json");
        try {
            CheckpointStore store = new CheckpointStore(tempFile, objectMapper);
            store.save(Map.of("lakeformation", 1L), "");

            long lastVersion = 0L;
            for (long v : versions) {
                if (v > lastVersion) {
                    store.saveTagState(v, Set.of("PII"));
                    lastVersion = v;
                }
            }

            if (lastVersion > 0) {
                Optional<SyncCheckpoint> loaded = store.load();
                assertTrue(loaded.isPresent());
                assertEquals(lastVersion, loaded.get().getLastKnownTagVersion(),
                        "Checkpoint should contain the most recently saved tag version");
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Map<String, Long>> serviceVersionMaps() {
        Arbitrary<String> serviceTypes = Arbitraries.of("lakeformation", "hive", "presto", "trino");
        Arbitrary<Long> versions = Arbitraries.longs().between(0L, 100_000L);
        return Arbitraries.maps(serviceTypes, versions).ofMinSize(1).ofMaxSize(4);
    }

    @Provide
    Arbitrary<String> cedarTexts() {
        return Arbitraries.of(
                "",
                "permit(principal, action, resource);",
                "forbid(principal, action, resource);",
                "@source(\"lakeformation:42\")\npermit(principal, action, resource);"
        );
    }

    @Provide
    Arbitrary<Long> tagVersions() {
        return Arbitraries.longs().between(0L, 1_000_000L);
    }

    @Provide
    Arbitrary<Set<String>> tagNameSets() {
        Arbitrary<String> names = Arbitraries.of("PII", "SENSITIVE", "PUBLIC", "CONFIDENTIAL");
        return names.set().ofMinSize(0).ofMaxSize(4);
    }

    @Provide
    Arbitrary<long[]> tagVersionSequences() {
        return Arbitraries.longs().between(1L, 10_000L)
                .array(long[].class).ofMinSize(1).ofMaxSize(10);
    }
}
