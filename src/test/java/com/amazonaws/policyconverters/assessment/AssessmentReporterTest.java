package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.model.GapReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentReporterTest {

    @Test
    void report_printsHeaderAndCounts() {
        AssessmentResult result = buildResult(10, 7, 2, 1, 25, List.of(), null, List.of());
        AssessmentConfig config = configConsoleOnly();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, config, new PrintStream(baos));
        String output = baos.toString();

        assertTrue(output.contains("Apache Ranger → Lake Formation Assessment"),
                "Missing header");
        assertTrue(output.contains("10"), "Missing total policy count");
        assertTrue(output.contains("25"), "Missing projected grant count");
    }

    @Test
    void report_withGaps_printsGapSummary() {
        GapEntry gap = new GapEntry("42", "policy-42", GapType.DATA_MASKING,
                "db.table", "has masking item", "remove masking");
        AssessmentResult result = buildResult(3, 1, 1, 1, 5, List.of(gap), null, List.of());
        AssessmentConfig config = configConsoleOnly();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, config, new PrintStream(baos));
        String output = baos.toString();

        assertTrue(output.contains("DATA_MASKING"), "Missing DATA_MASKING in output");
    }

    @Test
    void report_writesJsonFile(@TempDir Path tempDir) throws IOException {
        AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of(), null, List.of());
        AssessmentConfig config = AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .outputDir(tempDir)
                .consoleOnly(false)
                .build();

        new AssessmentReporter().report(result, config, new PrintStream(new ByteArrayOutputStream()));

        long jsonFiles = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("assessment-report-"))
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .count();
        assertEquals(1, jsonFiles, "Expected exactly one JSON report file");
    }

    @Test
    void report_consoleOnly_doesNotWriteFile(@TempDir Path tempDir) throws IOException {
        AssessmentResult result = buildResult(1, 1, 0, 0, 2, List.of(), null, List.of());
        AssessmentConfig config = AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .outputDir(tempDir)
                .consoleOnly(true)
                .build();

        new AssessmentReporter().report(result, config, new PrintStream(new ByteArrayOutputStream()));

        long fileCount = Files.list(tempDir).count();
        assertEquals(0, fileCount, "Expected no files written in console-only mode");
    }

    @Test
    void assessmentConfig_buildsWithoutRangerAdminUrl() {
        // Should not throw — validation moves to AssessmentMain per subcommand
        AssessmentConfig config = AssessmentConfig.builder()
                .consoleOnly(true)
                .build();
        assertNotNull(config);
    }

    @Test
    void report_alwaysPrintsPreambleWithSourceAndServices() {
        AssessedService svc1 = AssessedService.assessed("hive_prod", "hive", 16);
        AssessedService svc2 = AssessedService.skipped("yarn_prod", "yarn", "unsupported service type");
        AssessmentResult result = buildResult(16, 16, 0, 0, 5, List.of(),
                "file:export.json", List.of(svc1, svc2));
        AssessmentConfig config = AssessmentConfig.builder().consoleOnly(true).build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, config, new PrintStream(baos));
        String output = baos.toString();

        assertTrue(output.contains("Source:"), "Missing Source line");
        assertTrue(output.contains("file:export.json"), "Missing source label");
        assertTrue(output.contains("hive_prod"), "Missing assessed service");
        assertTrue(output.contains("yarn_prod"), "Missing skipped service");
        assertTrue(output.contains("skipped"), "Missing skipped status");
    }

    @Test
    void report_withWarnings_printsBannerBeforeHeader() {
        AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of(), null, List.of(),
                List.of("No principal mapping configured. Ranger usernames are passed through as-is."));
        AssessmentConfig config = configConsoleOnly();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, config, new PrintStream(baos));
        String output = baos.toString();

        int warningIdx = output.indexOf("⚠");
        int headerIdx = output.indexOf("=== Apache Ranger");
        assertTrue(warningIdx >= 0, "Warning banner (⚠) must appear in output");
        assertTrue(warningIdx < headerIdx, "Warning banner must appear before the report header");
    }

    @Test
    void report_withoutWarnings_noBannerInOutput() {
        AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of(), null, List.of(), List.of());
        AssessmentConfig config = configConsoleOnly();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, config, new PrintStream(baos));
        String output = baos.toString();

        assertFalse(output.contains("⚠"), "No warning banner expected when warnings list is empty");
    }

    @Test
    void printsKnownUnmappableActionRollupAsItsOwnSection() {
        AssessmentResult result = resultWithKnownUnmappable(List.of(
                new KnownUnmappableActionSummary("hive", "index", 1204, List.of("1", "2")),
                new KnownUnmappableActionSummary("hive", "lock", 1198, List.of("3")),
                new KnownUnmappableActionSummary("trino", "grant", 12, List.of("4"))));

        String output = capture(result);

        assertTrue(output.contains("Known-unmappable access types"));
        assertTrue(output.contains("hive: index"));
        assertTrue(output.contains("1204"));
        assertTrue(output.contains("trino: grant"));
    }

    @Test
    void knownUnmappableActionsAreNotListedInThePerPolicyBreakdown() {
        // The whole point: one policy per line for 1204 policies is what we are removing.
        AssessmentResult result = resultWithKnownUnmappable(List.of(
                new KnownUnmappableActionSummary("hive", "index", 1204, List.of("1"))));

        String output = capture(result);

        assertFalse(output.contains("Policies with gaps"),
                "no actionable gaps means no per-policy breakdown");
    }

    @Test
    void printsBothKnownUnmappableRollupAndPerPolicyBreakdownWhenBothPresent() {
        GapEntry actionableGap = new GapEntry("42", "policy-42", GapType.DATA_MASKING,
                "db.table", "has masking item", "remove masking");
        GapEntry collapsedKnownUnmappable = new GapEntry(null, null, GapType.KNOWN_UNMAPPABLE_ACTION,
                null, "hive: index appears in 1204 policies", "no action needed", "hive", "index");
        List<GapEntry> entries = List.of(actionableGap, collapsedKnownUnmappable);
        GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
        AssessmentResult result = new AssessmentResult(3, 1, 1, 1, 5, gapReport,
                "ranger-admin:http://localhost:6080", List.of(), List.of(), List.of(), List.of(),
                List.of(new KnownUnmappableActionSummary("hive", "index", 1204, List.of("1"))));

        String output = capture(result);

        assertTrue(output.contains("Known-unmappable access types"), "Missing rollup section");
        assertTrue(output.contains("Policies with gaps"), "Missing per-policy breakdown");
        assertTrue(output.contains("DATA_MASKING"), "Missing actionable gap in per-policy breakdown");
    }

    @Test
    void gapsFileIsAnObjectWithPoliciesAndKnownUnmappableRollup(@TempDir Path tmp)
            throws Exception {
        Path gapsPath = tmp.resolve("gaps.json");
        GapEntry gap = new GapEntry("42", "policy-42", GapType.DATA_MASKING,
                "db.table", "has masking item", "remove masking");
        PolicyGapSummary policySummary = new PolicyGapSummary("42", "policy-42",
                PolicyGapSummary.Convertibility.PARTIALLY_CONVERTIBLE, List.of(gap));
        GapReport gapReport = new GapReport(List.of(gap), GapReport.computeSummary(List.of(gap)),
                "2024-01-01T00:00:00Z");
        AssessmentResult result = new AssessmentResult(1, 0, 1, 0, 1, gapReport,
                "ranger-admin:http://localhost:6080", List.of(), List.of(), List.of(),
                List.of(policySummary),
                List.of(new KnownUnmappableActionSummary("hive", "index", 1204, List.of("1"))));

        new AssessmentReporter().report(result, configWithGapsPath(gapsPath),
                new PrintStream(new ByteArrayOutputStream()));

        JsonNode root = new ObjectMapper().readTree(gapsPath.toFile());
        assertTrue(root.isObject(), "gaps file is now an object, not a bare array");
        assertTrue(root.get("policies").isArray());
        assertEquals(1, root.get("policies").size());
        assertEquals("index", root.get("knownUnmappableActions").get(0).get("accessType").asText());
        assertEquals(1204,
                root.get("knownUnmappableActions").get(0).get("occurrenceCount").asInt());
    }

    @Test
    void gapsFileOmitsRollupKeyWhenThereAreNoKnownUnmappableActions(@TempDir Path tmp)
            throws Exception {
        Path gapsPath = tmp.resolve("gaps.json");
        AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of(), null, List.of());

        new AssessmentReporter().report(result, configWithGapsPath(gapsPath),
                new PrintStream(new ByteArrayOutputStream()));

        JsonNode root = new ObjectMapper().readTree(gapsPath.toFile());
        assertFalse(root.has("knownUnmappableActions"));
    }

    // ---- helpers ----

    private AssessmentResult buildResult(int total, int fully, int partial, int notConv,
                                         int grants, List<GapEntry> entries,
                                         String source, List<AssessedService> services) {
        return buildResult(total, fully, partial, notConv, grants, entries, source, services, List.of());
    }

    private AssessmentResult buildResult(int total, int fully, int partial, int notConv,
                                         int grants, List<GapEntry> entries,
                                         String source, List<AssessedService> services,
                                         List<String> warnings) {
        GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
        String resolvedSource = source != null ? source : "ranger-admin:http://localhost:6080";
        List<AssessedService> resolvedServices = services != null ? services : List.of();
        return new AssessmentResult(total, fully, partial, notConv, grants, gapReport,
                resolvedSource, resolvedServices, warnings);
    }

    private AssessmentConfig configConsoleOnly() {
        return AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .consoleOnly(true)
                .build();
    }

    private AssessmentConfig configWithGapsPath(Path gapsPath) {
        return AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .consoleOnly(true)
                .gapsOutputPath(gapsPath)
                .build();
    }

    /**
     * Builds an AssessmentResult whose collapsed gap report contains one
     * KNOWN_UNMAPPABLE_ACTION entry per rollup row (policyId == null, as the real
     * collapsed report produces them) alongside the rollup itself.
     */
    private AssessmentResult resultWithKnownUnmappable(List<KnownUnmappableActionSummary> rollup) {
        List<GapEntry> entries = new java.util.ArrayList<>();
        for (KnownUnmappableActionSummary row : rollup) {
            entries.add(new GapEntry(null, null, GapType.KNOWN_UNMAPPABLE_ACTION, null,
                    row.getServiceType() + ": " + row.getAccessType() + " appears in "
                            + row.getOccurrenceCount() + " policies",
                    "no action needed", row.getServiceType(), row.getAccessType()));
        }
        GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
        return new AssessmentResult(5, 5, 0, 0, 10, gapReport,
                "ranger-admin:http://localhost:6080", List.of(), List.of(), List.of(), List.of(), rollup);
    }

    private String capture(AssessmentResult result) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, configConsoleOnly(), new PrintStream(baos));
        return baos.toString();
    }
}
