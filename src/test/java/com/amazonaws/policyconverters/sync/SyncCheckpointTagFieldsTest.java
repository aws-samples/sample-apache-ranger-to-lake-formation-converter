package com.amazonaws.policyconverters.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SyncCheckpointTagFieldsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Backward compatibility: old JSON without tag fields ---

    @Test
    void deserialize_legacyJson_withoutTagFields_tagVersionIsNull() throws Exception {
        String json = "{\"policyVersion\":42,\"timestamp\":\"2025-01-01T00:00:00Z\"," +
                "\"cedarPolicyText\":\"permit(principal,action,resource);\"}";
        SyncCheckpoint checkpoint = objectMapper.readValue(json, SyncCheckpoint.class);
        assertNull(checkpoint.getLastKnownTagVersion());
        assertNotNull(checkpoint.getLastKnownRangerTagNames());
        assertTrue(checkpoint.getLastKnownRangerTagNames().isEmpty());
    }

    @Test
    void deserialize_legacyJson_preservesExistingFields() throws Exception {
        String json = "{\"policyVersion\":42,\"timestamp\":\"2025-01-01T00:00:00Z\"," +
                "\"cedarPolicyText\":\"permit(principal,action,resource);\"}";
        SyncCheckpoint checkpoint = objectMapper.readValue(json, SyncCheckpoint.class);
        assertEquals(42L, checkpoint.getPolicyVersion());
        assertEquals("2025-01-01T00:00:00Z", checkpoint.getTimestamp());
        assertEquals("permit(principal,action,resource);", checkpoint.getCedarPolicyText());
    }

    @Test
    void deserialize_withTagVersion_parsesCorrectly() throws Exception {
        String json = "{\"policyVersion\":10,\"timestamp\":\"2025-01-01T00:00:00Z\"," +
                "\"cedarPolicyText\":\"\",\"lastKnownTagVersion\":7," +
                "\"lastKnownRangerTagNames\":[\"PII\",\"SENSITIVE\"]}";
        SyncCheckpoint checkpoint = objectMapper.readValue(json, SyncCheckpoint.class);
        assertEquals(7L, checkpoint.getLastKnownTagVersion());
        assertEquals(Set.of("PII", "SENSITIVE"), checkpoint.getLastKnownRangerTagNames());
    }

    // --- Round-trip ---

    @Test
    void roundTrip_withTagFields() throws Exception {
        SyncCheckpoint original = new SyncCheckpoint(
                10L, Map.of("lakeformation", 10L), "2025-01-01T00:00:00Z",
                "permit(principal,action,resource);",
                5L, Set.of("PII", "SENSITIVE"));
        String json = objectMapper.writeValueAsString(original);
        SyncCheckpoint loaded = objectMapper.readValue(json, SyncCheckpoint.class);
        assertEquals(original, loaded);
    }

    @Test
    void roundTrip_withNullTagVersion() throws Exception {
        SyncCheckpoint original = new SyncCheckpoint(
                10L, Map.of("lakeformation", 10L), "2025-01-01T00:00:00Z",
                "permit(principal,action,resource);",
                null, null);
        String json = objectMapper.writeValueAsString(original);
        SyncCheckpoint loaded = objectMapper.readValue(json, SyncCheckpoint.class);
        assertNull(loaded.getLastKnownTagVersion());
        assertTrue(loaded.getLastKnownRangerTagNames().isEmpty());
    }

    // --- 4-arg backward-compat constructor ---

    @Test
    void fourArgConstructor_tagFieldsAreNull() {
        SyncCheckpoint checkpoint = new SyncCheckpoint(
                5L, Map.of("lakeformation", 5L), "2025-01-01T00:00:00Z", "");
        assertNull(checkpoint.getLastKnownTagVersion());
        assertTrue(checkpoint.getLastKnownRangerTagNames().isEmpty());
    }

    // --- CheckpointStore.saveTagState ---

    @Test
    void saveTagState_updatesTagFieldsPreservingOtherFields(@TempDir Path tempDir) throws Exception {
        Path checkpointPath = tempDir.resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(checkpointPath, objectMapper);

        store.save(Map.of("lakeformation", 42L), "permit(principal,action,resource);");

        store.saveTagState(7L, Set.of("PII", "SENSITIVE"));

        Optional<SyncCheckpoint> loaded = store.load();
        assertTrue(loaded.isPresent());
        SyncCheckpoint checkpoint = loaded.get();

        assertEquals(42L, checkpoint.getPolicyVersion());
        assertEquals("permit(principal,action,resource);", checkpoint.getCedarPolicyText());
        assertEquals(7L, checkpoint.getLastKnownTagVersion());
        assertEquals(Set.of("PII", "SENSITIVE"), checkpoint.getLastKnownRangerTagNames());
    }

    @Test
    void saveTagState_whenNoCheckpointExists_createsEntryWithTagFields(@TempDir Path tempDir) throws Exception {
        Path checkpointPath = tempDir.resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(checkpointPath, objectMapper);

        store.saveTagState(3L, Set.of("PUBLIC"));

        Optional<SyncCheckpoint> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(3L, loaded.get().getLastKnownTagVersion());
        assertEquals(Set.of("PUBLIC"), loaded.get().getLastKnownRangerTagNames());
    }

    @Test
    void saveTagState_doesNotChangeServiceVersions(@TempDir Path tempDir) throws Exception {
        Path checkpointPath = tempDir.resolve("checkpoint.json");
        CheckpointStore store = new CheckpointStore(checkpointPath, objectMapper);

        Map<String, Long> versions = Map.of("hive", 100L, "trino", 50L);
        store.save(versions, "");
        store.saveTagState(9L, Set.of("PII"));

        Optional<SyncCheckpoint> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(versions, loaded.get().getServiceVersions());
    }

    // --- lastKnownRangerTagNames is immutable ---

    @Test
    void lastKnownRangerTagNames_isImmutable() throws Exception {
        SyncCheckpoint checkpoint = objectMapper.readValue(
                "{\"policyVersion\":1,\"timestamp\":\"T\",\"cedarPolicyText\":\"\"," +
                        "\"lastKnownTagVersion\":1,\"lastKnownRangerTagNames\":[\"PII\"]}",
                SyncCheckpoint.class);
        assertThrows(UnsupportedOperationException.class,
                () -> checkpoint.getLastKnownRangerTagNames().add("NEW"));
    }

    // --- equals / hashCode include tag fields ---

    @Test
    void equals_includesTagVersion() {
        SyncCheckpoint a = new SyncCheckpoint(1L, null, "T", "", 5L, Set.of("PII"));
        SyncCheckpoint b = new SyncCheckpoint(1L, null, "T", "", 5L, Set.of("PII"));
        SyncCheckpoint c = new SyncCheckpoint(1L, null, "T", "", 9L, Set.of("PII"));
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void equals_includesTagNames() {
        SyncCheckpoint a = new SyncCheckpoint(1L, null, "T", "", 5L, Set.of("PII"));
        SyncCheckpoint b = new SyncCheckpoint(1L, null, "T", "", 5L, Set.of("SENSITIVE"));
        assertNotEquals(a, b);
    }
}
