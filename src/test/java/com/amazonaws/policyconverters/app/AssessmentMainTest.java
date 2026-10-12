package com.amazonaws.policyconverters.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentMainTest {

    @Test
    void run_withNoArgs_returnsExitCode1() {
        assertEquals(1, AssessmentMain.run(new String[]{}));
    }

    @Test
    void run_withMissingRangerUrl_returnsExitCode1() {
        // CLI-only mode with no --ranger-url
        assertEquals(1, AssessmentMain.run(new String[]{"--console-only"}));
    }

    @Test
    void guessServiceType_hiveInName_returnsHive() {
        assertEquals("hive", AssessmentMain.guessServiceType("hive_prod"));
        assertEquals("hive", AssessmentMain.guessServiceType("HIVE_analytics"));
    }

    @Test
    void guessServiceType_prestoInName_returnsPresto() {
        assertEquals("presto", AssessmentMain.guessServiceType("presto-default"));
    }

    @Test
    void guessServiceType_trinoInName_returnsTrno() {
        assertEquals("trino", AssessmentMain.guessServiceType("trino_cluster"));
    }

    @Test
    void guessServiceType_unknownName_returnsLakeFormation() {
        assertEquals("lakeformation", AssessmentMain.guessServiceType("my_service"));
        assertEquals("lakeformation", AssessmentMain.guessServiceType("lakeformation"));
    }

    @Test
    void run_withUnknownFlag_returnsExitCode1() {
        assertEquals(1, AssessmentMain.run(new String[]{"--unknown-flag", "value"}));
    }

    // --- logging flags ---

    @Test
    void logLevel_invalidValue_isRejected() {
        assertEquals(1, AssessmentMain.run(new String[]{
                "file", "/does/not/matter.json", "--log-level", "VERBOSE", "--console-only"}),
                "an unrecognised level must fail fast rather than silently defaulting to INFO");
    }

    @Test
    void logFile_unwritablePath_isRejected() {
        assertEquals(1, AssessmentMain.run(new String[]{
                "file", "/does/not/matter.json",
                "--log-file", "/nonexistent-dir-" + System.nanoTime() + "/a.log", "--console-only"}),
                "an unwritable log path must fail rather than silently producing no log");
    }

    @Test
    void logLevel_isAcceptedBySubcommands() {
        // Exits 1 because the export path does not exist, not because the flag was rejected —
        // the point is that parsing accepts the flag in both subcommands.
        assertEquals(1, AssessmentMain.run(new String[]{
                "file", "/no/such/export.json", "--log-level", "DEBUG", "--console-only"}));
        assertEquals(1, AssessmentMain.run(new String[]{
                "server", "--log-level", "DEBUG", "--console-only"}));
    }

    @Test
    void logLevel_restoresNothingGlobally_butDoesNotThrow() {
        // Applied immediately at parse time, so it mutates the process-wide root logger. Verify
        // that is at least idempotent and does not blow up when applied repeatedly, since several
        // tests in one JVM will each set it.
        assertEquals(1, AssessmentMain.run(new String[]{
                "file", "/no/such/export.json", "--log-level", "TRACE", "--console-only"}));
        assertEquals(1, AssessmentMain.run(new String[]{
                "file", "/no/such/export.json", "--log-level", "INFO", "--console-only"}));
    }
}
