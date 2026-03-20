package org.apache.ranger.lakeformation.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GapReportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructAndGetters() {
        GapEntry entry = new GapEntry("1", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        Map<GapEntry.GapType, Integer> summary = new EnumMap<>(GapEntry.GapType.class);
        summary.put(GapEntry.GapType.DENY_POLICY, 1);
        GapReport report = new GapReport(Collections.singletonList(entry), summary, "2024-01-15T10:00:00Z");

        assertEquals(1, report.getEntries().size());
        assertEquals(entry, report.getEntries().get(0));
        assertEquals(summary, report.getSummary());
        assertEquals("2024-01-15T10:00:00Z", report.getGeneratedAt());
    }

    @Test
    void entriesAreImmutable() {
        GapEntry entry = new GapEntry("1", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        GapReport report = new GapReport(Collections.singletonList(entry),
                Collections.<GapEntry.GapType, Integer>emptyMap(), "2024-01-15T10:00:00Z");
        assertThrows(UnsupportedOperationException.class, () ->
                report.getEntries().add(new GapEntry("2", "q", GapEntry.GapType.DATA_MASKING, "r2", "d2", "rec2")));
    }

    @Test
    void computeSummary() {
        List<GapEntry> entries = Arrays.asList(
                new GapEntry("1", "p1", GapEntry.GapType.DENY_POLICY, "r", "d", "rec"),
                new GapEntry("2", "p2", GapEntry.GapType.DENY_POLICY, "r", "d", "rec"),
                new GapEntry("3", "p3", GapEntry.GapType.DATA_MASKING, "r", "d", "rec")
        );
        Map<GapEntry.GapType, Integer> summary = GapReport.computeSummary(entries);
        assertEquals(2, summary.get(GapEntry.GapType.DENY_POLICY).intValue());
        assertEquals(1, summary.get(GapEntry.GapType.DATA_MASKING).intValue());
        assertNull(summary.get(GapEntry.GapType.SECURITY_ZONE));
    }

    @Test
    void computeSummaryWithNull() {
        Map<GapEntry.GapType, Integer> summary = GapReport.computeSummary(null);
        assertTrue(summary.isEmpty());
    }

    @Test
    void equalsAndHashCode() {
        GapEntry entry = new GapEntry("1", "p", GapEntry.GapType.DENY_POLICY, "r", "d", "rec");
        Map<GapEntry.GapType, Integer> summary = new EnumMap<>(GapEntry.GapType.class);
        summary.put(GapEntry.GapType.DENY_POLICY, 1);
        GapReport a = new GapReport(Collections.singletonList(entry), summary, "2024-01-15T10:00:00Z");
        GapReport b = new GapReport(Collections.singletonList(entry), summary, "2024-01-15T10:00:00Z");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void jsonRoundTrip() throws Exception {
        List<GapEntry> entries = Arrays.asList(
                new GapEntry("1", "policy-a", GapEntry.GapType.DENY_POLICY, "db.tbl", "Deny not supported", "Use LF grants"),
                new GapEntry("2", "policy-b", GapEntry.GapType.DATA_MASKING, "db.tbl.col", "Masking not supported", "Use column-level")
        );
        Map<GapEntry.GapType, Integer> summary = GapReport.computeSummary(entries);
        GapReport original = new GapReport(entries, summary, "2024-01-15T10:30:00Z");

        String json = mapper.writeValueAsString(original);
        GapReport deserialized = mapper.readValue(json, GapReport.class);
        assertEquals(original, deserialized);
    }

    @Test
    void emptyReport() {
        GapReport report = new GapReport(null, null, null);
        assertTrue(report.getEntries().isEmpty());
        assertTrue(report.getSummary().isEmpty());
        assertNull(report.getGeneratedAt());
    }
}
