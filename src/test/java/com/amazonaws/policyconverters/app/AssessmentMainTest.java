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
}
