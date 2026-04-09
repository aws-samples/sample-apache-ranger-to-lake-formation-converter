package com.amazonaws.policyconverters.reporting;

import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.model.GapReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class GapReporterTest {

    @Test
    void recordGapAndGetReport() {
        GapReporter reporter = new GapReporter();
        GapEntry entry = new GapEntry("1", "policy-a", GapType.DENY_POLICY,
                "db.table", "Deny not supported", "Use LF grants");
        reporter.recordGap(entry);

        GapReport report = reporter.getReport();
        assertEquals(1, report.getEntries().size());
        assertEquals(entry, report.getEntries().get(0));
        assertNotNull(report.getGeneratedAt());
        assertEquals(1, report.getSummary().get(GapType.DENY_POLICY).intValue());
    }

    @Test
    void emptyReporterProducesEmptyReport() {
        GapReporter reporter = new GapReporter();
        GapReport report = reporter.getReport();

        assertTrue(report.getEntries().isEmpty());
        assertTrue(report.getSummary().isEmpty());
        assertNotNull(report.getGeneratedAt());
    }

    @Test
    void recordGapRejectsNull() {
        GapReporter reporter = new GapReporter();
        assertThrows(IllegalArgumentException.class, () -> reporter.recordGap(null));
    }

    @Test
    void summaryCountsMultipleGapTypes() {
        GapReporter reporter = new GapReporter();
        reporter.recordGap(new GapEntry("1", "p1", GapType.DENY_POLICY, "r", "d", "rec"));
        reporter.recordGap(new GapEntry("2", "p2", GapType.DENY_POLICY, "r", "d", "rec"));
        reporter.recordGap(new GapEntry("3", "p3", GapType.DATA_MASKING, "r", "d", "rec"));
        reporter.recordGap(new GapEntry("4", "p4", GapType.VALIDITY_SCHEDULE, "r", "d", "rec"));

        GapReport report = reporter.getReport();
        assertEquals(4, report.getEntries().size());
        assertEquals(2, report.getSummary().get(GapType.DENY_POLICY).intValue());
        assertEquals(1, report.getSummary().get(GapType.DATA_MASKING).intValue());
        assertEquals(1, report.getSummary().get(GapType.VALIDITY_SCHEDULE).intValue());
        assertNull(report.getSummary().get(GapType.SECURITY_ZONE));
    }

    @Test
    void toJsonProducesValidJson() throws IOException {
        GapReporter reporter = new GapReporter();
        reporter.recordGap(new GapEntry("42", "my-policy", GapType.CUSTOM_CONDITION,
                "db.tbl", "IP-based condition not supported", "Remove condition"));

        String json = reporter.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"policyId\":\"42\""));
        assertTrue(json.contains("CUSTOM_CONDITION"));
    }

    @Test
    void fromJsonDeserializesCorrectly() throws IOException {
        GapReporter reporter = new GapReporter();
        reporter.recordGap(new GapEntry("10", "pol-x", GapType.TAG_BASED_POLICY,
                "db.tbl", "Tag-based policy", "Map LF-Tags manually"));
        reporter.recordGap(new GapEntry("11", "pol-y", GapType.DENY_EXCEPTION,
                "db.tbl.col", "Deny exception", "Review grants"));

        String json = reporter.toJson();
        GapReport deserialized = GapReporter.fromJson(json);

        assertEquals(2, deserialized.getEntries().size());
        assertEquals("10", deserialized.getEntries().get(0).getPolicyId());
        assertEquals(GapType.TAG_BASED_POLICY, deserialized.getEntries().get(0).getGapType());
        assertEquals("11", deserialized.getEntries().get(1).getPolicyId());
        assertEquals(GapType.DENY_EXCEPTION, deserialized.getEntries().get(1).getGapType());
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws IOException {
        GapReporter reporter = new GapReporter();
        reporter.recordGap(new GapEntry("1", "policy-a", GapType.DENY_POLICY,
                "db.table", "Deny not supported", "Use LF grants"));
        reporter.recordGap(new GapEntry("2", "policy-b", GapType.DATA_MASKING,
                "db.table.col", "Masking not supported", "Use column-level"));
        reporter.recordGap(new GapEntry("3", "policy-c", GapType.SECURITY_ZONE,
                "db", "Zone not supported", "Flatten zones"));

        String json = reporter.toJson();
        GapReport deserialized = GapReporter.fromJson(json);
        GapReport original = reporter.getReport();

        assertEquals(original.getEntries(), deserialized.getEntries());
        assertEquals(original.getSummary(), deserialized.getSummary());
        assertNotNull(deserialized.getGeneratedAt());
    }

    @Test
    void fromJsonRejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> GapReporter.fromJson(null));
    }

    @Test
    void fromJsonRejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> GapReporter.fromJson(""));
        assertThrows(IllegalArgumentException.class, () -> GapReporter.fromJson("   "));
    }

    @Test
    void fromJsonRejectsInvalidJson() {
        assertThrows(IOException.class, () -> GapReporter.fromJson("{invalid json}"));
    }

    @Test
    void generatedAtIsIso8601Format() {
        GapReporter reporter = new GapReporter();
        GapReport report = reporter.getReport();
        String ts = report.getGeneratedAt();
        // ISO-8601 format: yyyy-MM-ddTHH:mm:ssZ
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                "Timestamp should be ISO-8601 format, got: " + ts);
    }

    @Test
    void formatIso8601ProducesUtcTimestamp() {
        // Use a known date: 2024-01-15 10:30:00 UTC
        @SuppressWarnings("deprecation")
        Date date = new Date(Date.UTC(124, 0, 15, 10, 30, 0));
        String formatted = GapReporter.formatIso8601(date);
        assertEquals("2024-01-15T10:30:00Z", formatted);
    }

    @Test
    void multipleGetReportCallsReturnConsistentEntries() {
        GapReporter reporter = new GapReporter();
        reporter.recordGap(new GapEntry("1", "p", GapType.DENY_POLICY, "r", "d", "rec"));

        GapReport report1 = reporter.getReport();
        reporter.recordGap(new GapEntry("2", "q", GapType.DATA_MASKING, "r2", "d2", "rec2"));
        GapReport report2 = reporter.getReport();

        assertEquals(1, report1.getEntries().size());
        assertEquals(2, report2.getEntries().size());
    }

    @Test
    void allGapTypesCanBeRecordedAndSummarized() {
        GapReporter reporter = new GapReporter();
        for (GapType type : GapType.values()) {
            reporter.recordGap(new GapEntry("id-" + type.getValue(), "pol",
                    type, "resource", "details", "recommendation"));
        }

        GapReport report = reporter.getReport();
        assertEquals(GapType.values().length, report.getEntries().size());
        for (GapType type : GapType.values()) {
            assertEquals(1, report.getSummary().get(type).intValue(),
                    "Summary should have count 1 for " + type);
        }
    }
}
