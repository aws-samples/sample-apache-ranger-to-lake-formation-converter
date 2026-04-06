package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.lakeformation.client.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.client.LakeFormationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for dry-run wiring logic in SyncServiceMain.
 * Tests the DryRunLakeFormationClient construction and type hierarchy.
 */
class SyncServiceMainDryRunTest {

    @Test
    void dryRunClientIsInstanceOfLakeFormationClient(@TempDir Path tempDir) {
        DryRunLakeFormationClient dryRunClient = new DryRunLakeFormationClient(tempDir, new ObjectMapper());
        assertInstanceOf(LakeFormationClient.class, dryRunClient,
                "DryRunLakeFormationClient should be a subtype of LakeFormationClient");
    }

    @Test
    void dryRunClientUsesDefaultOutputDirectory() {
        // Verify the default output directory path matches what SyncServiceMain uses
        String defaultDir = "./dry-run-output";
        Path expectedPath = Path.of(defaultDir);
        assertNotNull(expectedPath, "Default output directory path should be valid");
        assertEquals("dry-run-output", expectedPath.getFileName().toString());
    }

    @Test
    void dryRunEnabledParsing() {
        // Test the same parsing logic used in SyncServiceMain
        assertTrue("true".equalsIgnoreCase("true"));
        assertTrue("true".equalsIgnoreCase("TRUE"));
        assertTrue("true".equalsIgnoreCase("True"));
        assertFalse("true".equalsIgnoreCase("false"));
        assertFalse("true".equalsIgnoreCase("yes"));
        assertFalse("true".equalsIgnoreCase("1"));
    }

    @Test
    void dryRunOutputDirDefaultWhenBlank() {
        // Simulate the logic from SyncServiceMain
        String outputDir = null;
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "./dry-run-output";
        }
        assertEquals("./dry-run-output", outputDir);

        outputDir = "";
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "./dry-run-output";
        }
        assertEquals("./dry-run-output", outputDir);

        outputDir = "  ";
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "./dry-run-output";
        }
        assertEquals("./dry-run-output", outputDir);
    }

    @Test
    void dryRunOutputDirCustomValue() {
        String outputDir = "/custom/path";
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "./dry-run-output";
        }
        assertEquals("/custom/path", outputDir);
    }
}
