package com.amazonaws.policyconverters.lakeformation.client;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for DryRunLakeFormationClient monotonic sequence numbering.
 *
 * Feature: dry-run-integration-tests, Property 3: Monotonic sequence numbering
 * **Validates: Requirements 1.3**
 */
class DryRunSequencePropertyTest {

    private static final LFPermissionOperation FIXED_OP = new LFPermissionOperation(
            OperationType.GRANT, "test-policy", "arn:aws:iam::123456789012:role/test",
            new LFResource("123456789012", "testdb", null, null, null),
            Set.of(LFPermission.SELECT), false);

    /**
     * Property 3: For any N calls (1–20) to applyBatch() on a single
     * DryRunLakeFormationClient instance, the output directory contains
     * exactly N files named dry-run-001.json through dry-run-N.json
     * with consecutive sequence numbers 1..N.
     */
    @Property(tries = 100)
    void monotonicSequenceNumbering(
            @ForAll @IntRange(min = 1, max = 20) int n
    ) throws Exception {
        Path runDir = Files.createTempDirectory("dryrun-sequence-");
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            DryRunLakeFormationClient client = new DryRunLakeFormationClient(runDir, objectMapper);

            // Call applyBatch N times
            for (int i = 0; i < n; i++) {
                client.applyBatch(List.of(FIXED_OP), null);
            }

            // List files in the output directory
            File[] files = runDir.toFile().listFiles();
            assertNotNull(files, "Output directory should contain files");
            assertEquals(n, files.length, "Output directory should contain exactly N files");

            // Sort filenames for deterministic ordering
            String[] filenames = Arrays.stream(files).map(File::getName).sorted().toArray(String[]::new);

            // Assert filenames are dry-run-001.json through dry-run-N.json
            for (int i = 1; i <= n; i++) {
                String expected = String.format("dry-run-%03d.json", i);
                assertEquals(expected, filenames[i - 1],
                        "File " + i + " should be named " + expected);
            }

            // Deserialize each file and verify sequenceNumber matches 1..N
            for (int i = 1; i <= n; i++) {
                String filename = String.format("dry-run-%03d.json", i);
                File outputFile = runDir.resolve(filename).toFile();
                DryRunOutput output = objectMapper.readValue(outputFile, DryRunOutput.class);
                assertEquals(i, output.getSequenceNumber(),
                        "File " + filename + " should have sequenceNumber " + i);
            }
        } finally {
            // Cleanup temp files
            File[] files = runDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            runDir.toFile().delete();
        }
    }
}
