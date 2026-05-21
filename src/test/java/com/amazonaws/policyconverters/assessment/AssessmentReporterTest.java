package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.model.GapReport;
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
        AssessmentResult result = buildResult(10, 7, 2, 1, 25, List.of());
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
        AssessmentResult result = buildResult(3, 1, 1, 1, 5, List.of(gap));
        AssessmentConfig config = configConsoleOnly();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new AssessmentReporter().report(result, config, new PrintStream(baos));
        String output = baos.toString();

        assertTrue(output.contains("DATA_MASKING"), "Missing DATA_MASKING in output");
    }

    @Test
    void report_writesJsonFile(@TempDir Path tempDir) throws IOException {
        AssessmentResult result = buildResult(5, 5, 0, 0, 10, List.of());
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
        AssessmentResult result = buildResult(1, 1, 0, 0, 2, List.of());
        AssessmentConfig config = AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .outputDir(tempDir)
                .consoleOnly(true)
                .build();

        new AssessmentReporter().report(result, config, new PrintStream(new ByteArrayOutputStream()));

        long fileCount = Files.list(tempDir).count();
        assertEquals(0, fileCount, "Expected no files written in console-only mode");
    }

    // ---- helpers ----

    private AssessmentResult buildResult(int total, int fully, int partial, int notConv,
                                         int grants, List<GapEntry> entries) {
        GapReport gapReport = new GapReport(entries, GapReport.computeSummary(entries), "2024-01-01T00:00:00Z");
        return new AssessmentResult(total, fully, partial, notConv, grants, gapReport);
    }

    private AssessmentConfig configConsoleOnly() {
        return AssessmentConfig.builder()
                .rangerAdminUrl("http://localhost:6080")
                .consoleOnly(true)
                .build();
    }
}
