package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.app.AssessmentMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessmentMainTest {

    @Test
    void run_noArgs_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{}));
    }

    @Test
    void run_unknownSubcommand_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{"--ranger-url", "http://localhost"}));
    }

    @Test
    void run_serverSubcommand_missingRangerUrl_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{"server", "--console-only"}));
    }

    @Test
    void run_fileSubcommand_nonExistentFile_exits1() {
        assertEquals(1, AssessmentMain.run(new String[]{"file", "/nonexistent/path.json"}));
    }

    @Test
    void run_fileSubcommand_withServicesFlag_exits1WithSpecificMessage(@TempDir Path tempDir)
            throws IOException {
        Path file = tempDir.resolve("export.json");
        Files.writeString(file, "{\"policies\":[]}", StandardCharsets.UTF_8);

        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBytes));
        try {
            int code = AssessmentMain.run(
                    new String[]{"file", file.toString(), "--services", "hive_prod"});
            assertEquals(1, code);
            String err = errBytes.toString();
            assertTrue(err.contains("--services is not supported in file mode"),
                    "Expected specific --services error message, got: " + err);
        } finally {
            System.setErr(origErr);
        }
    }
}
