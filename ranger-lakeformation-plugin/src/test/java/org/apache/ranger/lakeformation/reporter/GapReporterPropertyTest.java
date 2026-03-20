package org.apache.ranger.lakeformation.reporter;

import net.jqwik.api.*;
import org.apache.ranger.lakeformation.model.GapEntry;
import org.apache.ranger.lakeformation.model.GapEntry.GapType;
import org.apache.ranger.lakeformation.model.GapReport;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for GapReporter using jqwik.
 */
class GapReporterPropertyTest {

    // Feature: ranger-lakeformation-sync, Property 11: Gap report JSON round-trip
    // **Validates: Requirements 3.8, 3.9**
    @Property(tries = 100)
    void gapReportJsonRoundTripProducesEquivalentObject(
            @ForAll("gapEntryLists") List<GapEntry> entries
    ) throws IOException {
        GapReporter reporter = new GapReporter();
        for (GapEntry entry : entries) {
            reporter.recordGap(entry);
        }

        String json = reporter.toJson();
        GapReport deserialized = GapReporter.fromJson(json);
        GapReport original = reporter.getReport();

        assertEquals(original.getEntries(), deserialized.getEntries(),
                "Entries should be equivalent after JSON round-trip");
        assertEquals(original.getSummary(), deserialized.getSummary(),
                "Summary should be equivalent after JSON round-trip");
        assertNotNull(deserialized.getGeneratedAt(),
                "generatedAt should be preserved after JSON round-trip");
    }

    // Feature: ranger-lakeformation-sync, Property 12: Gap report summary accuracy
    // **Validates: Requirements 3.8**
    @Property(tries = 100)
    void gapReportSummaryCountMatchesActualEntryCount(
            @ForAll("gapEntryLists") List<GapEntry> entries
    ) {
        GapReporter reporter = new GapReporter();
        for (GapEntry entry : entries) {
            reporter.recordGap(entry);
        }

        GapReport report = reporter.getReport();

        // Compute expected counts from entries
        Map<GapType, Integer> expectedCounts = new EnumMap<>(GapType.class);
        for (GapEntry entry : entries) {
            Integer current = expectedCounts.get(entry.getGapType());
            expectedCounts.put(entry.getGapType(), current != null ? current + 1 : 1);
        }

        assertEquals(expectedCounts, report.getSummary(),
                "Summary counts per GapType should equal actual entry counts");

        // Also verify that GapTypes not present in entries are absent from summary
        for (GapType type : GapType.values()) {
            if (!expectedCounts.containsKey(type)) {
                assertNull(report.getSummary().get(type),
                        "GapType " + type + " should not appear in summary when no entries have it");
            }
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<List<GapEntry>> gapEntryLists() {
        return gapEntries().list().ofMinSize(1).ofMaxSize(30);
    }

    @Provide
    Arbitrary<GapEntry> gapEntries() {
        Arbitrary<String> policyIds = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
        Arbitrary<String> policyNames = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<GapType> gapTypes = Arbitraries.of(GapType.values());
        Arbitrary<String> resourcePaths = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
        Arbitrary<String> details = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
        Arbitrary<String> recommendations = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);

        return Combinators.combine(policyIds, policyNames, gapTypes, resourcePaths, details, recommendations)
                .as(GapEntry::new);
    }
}
