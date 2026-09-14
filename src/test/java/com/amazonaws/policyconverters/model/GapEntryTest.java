package com.amazonaws.policyconverters.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GapEntryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructAndGetters() {
        GapEntry entry = new GapEntry("42", "test-policy",
                GapEntry.GapType.DATA_MASKING, "db.table",
                "Data masking not supported", "Use LF cell-level security");

        assertEquals("42", entry.getPolicyId());
        assertEquals("test-policy", entry.getPolicyName());
        assertEquals(GapEntry.GapType.DATA_MASKING, entry.getGapType());
        assertEquals("db.table", entry.getResourcePath());
        assertEquals("Data masking not supported", entry.getDetails());
        assertEquals("Use LF cell-level security", entry.getRecommendation());
    }

    @Test
    void equalsAndHashCode() {
        GapEntry a = new GapEntry("1", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        GapEntry b = new GapEntry("1", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferent() {
        GapEntry a = new GapEntry("1", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        GapEntry b = new GapEntry("2", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        assertNotEquals(a, b);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        GapEntry original = new GapEntry("42", "my-policy",
                GapEntry.GapType.VALIDITY_SCHEDULE, "db.table.col",
                "Time-bound policy not supported in LF",
                "Implement external scheduler");
        String json = mapper.writeValueAsString(original);
        GapEntry deserialized = mapper.readValue(json, GapEntry.class);
        assertEquals(original, deserialized);
    }

    @Test
    void gapTypeFromValue() {
        for (GapEntry.GapType type : GapEntry.GapType.values()) {
            assertEquals(type, GapEntry.GapType.fromValue(type.getValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> GapEntry.GapType.fromValue("UNKNOWN"));
    }

    @Test
    void toStringContainsFields() {
        GapEntry entry = new GapEntry("42", "pol", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        String str = entry.toString();
        assertTrue(str.contains("42"));
        assertTrue(str.contains("DENY_POLICY"));
    }

    @Test
    void knownUnmappableActionGapTypeRoundTripsThroughJson() throws Exception {
        GapEntry entry = new GapEntry(
                "42", "hive-analyst", GapEntry.GapType.KNOWN_UNMAPPABLE_ACTION,
                null, "details", "recommendation", "hive", java.util.List.of("index"));

        // `mapper` is the existing field at GapEntryTest.java:10 — do not redeclare it.
        String json = mapper.writeValueAsString(entry);
        GapEntry parsed = mapper.readValue(json, GapEntry.class);

        assertEquals(entry, parsed);
        assertEquals("hive", parsed.getServiceType());
        assertEquals(java.util.List.of("index"), parsed.getAccessTypes());
    }

    @Test
    void accessTypesWithMultipleValuesRoundTripsThroughJsonInOrder() throws Exception {
        GapEntry entry = new GapEntry(
                "42", "hive-analyst", GapEntry.GapType.KNOWN_UNMAPPABLE_ACTION,
                null, "details", "recommendation", "hive",
                java.util.List.of("index", "lock", "refresh"));

        String json = mapper.writeValueAsString(entry);
        GapEntry parsed = mapper.readValue(json, GapEntry.class);

        assertEquals(entry, parsed);
        assertEquals(java.util.List.of("index", "lock", "refresh"), parsed.getAccessTypes());
    }

    @Test
    void legacySixArgConstructorLeavesActionFieldsNullAndOutOfJson() throws Exception {
        GapEntry entry = new GapEntry(
                "42", "mask-ssn", GapEntry.GapType.DATA_MASKING, "db/tbl", "d", "r");

        assertNull(entry.getServiceType());
        assertNull(entry.getAccessTypes());

        String json = mapper.writeValueAsString(entry);
        assertFalse(json.contains("serviceType"), "NON_NULL must keep the field out of unrelated gaps");
        assertFalse(json.contains("accessTypes"));
    }
}
