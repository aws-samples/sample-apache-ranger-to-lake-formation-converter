package com.example.ranger.lakeformation.simulator.remediation;

import com.example.ranger.lakeformation.simulator.validator.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BundleWriterTest {

    @TempDir
    Path tempDir;

    private ReproductionBundle minimalBundle() {
        return new ReproductionBundle(
                Instant.parse("2024-03-15T10:30:00Z"),
                /* violationDetectedAfterCycle */ 42L,
                /* lastSuccessfulCycle */ 41L,
                List.of(),
                "{}",
                Set.of(),
                Set.of(),
                ValidationResult.pass()
        );
    }

    @Test
    void writeShouldCreateSubdirectoryNamedWithTimestamp() throws IOException {
        BundleWriter writer = new BundleWriter(tempDir);
        Path bundleDir = writer.write(minimalBundle());

        assertTrue(bundleDir.getFileName().toString().startsWith("violation_"),
                "Directory should start with 'violation_'");
        assertTrue(Files.isDirectory(bundleDir), "Bundle path should be a directory");
    }

    @Test
    void writeShouldCreateMutationsJson() throws IOException {
        BundleWriter writer = new BundleWriter(tempDir);
        Path bundleDir = writer.write(minimalBundle());

        Path mutationsFile = bundleDir.resolve("mutations.json");
        assertTrue(Files.exists(mutationsFile), "mutations.json should exist");
    }

    @Test
    void writeShouldCreateLfActualAndLfExpectedJson() throws IOException {
        BundleWriter writer = new BundleWriter(tempDir);
        Path bundleDir = writer.write(minimalBundle());

        assertTrue(Files.exists(bundleDir.resolve("lf-actual.json")), "lf-actual.json should exist");
        assertTrue(Files.exists(bundleDir.resolve("lf-expected.json")), "lf-expected.json should exist");
    }

    @Test
    void diffJsonShouldContainOverGrantsAndUnderGrantsKeys() throws IOException {
        BundleWriter writer = new BundleWriter(tempDir);
        Path bundleDir = writer.write(minimalBundle());

        Path diffFile = bundleDir.resolve("diff.json");
        assertTrue(Files.exists(diffFile), "diff.json should exist");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(diffFile.toFile());
        assertTrue(root.has("overGrants"), "diff.json should contain 'overGrants'");
        assertTrue(root.has("underGrants"), "diff.json should contain 'underGrants'");
    }

    @Test
    void readmeShouldBeHumanReadable() throws IOException {
        BundleWriter writer = new BundleWriter(tempDir);
        Path bundleDir = writer.write(minimalBundle());

        Path readmeFile = bundleDir.resolve("README.txt");
        assertTrue(Files.exists(readmeFile), "README.txt should exist");

        String content = Files.readString(readmeFile);
        assertTrue(content.contains("REPRODUCTION BUNDLE"),
                "README.txt should contain 'REPRODUCTION BUNDLE'");
    }

    @Test
    void cycleSequenceJsonShouldContainCycleNumbers() throws IOException {
        BundleWriter writer = new BundleWriter(tempDir);
        Path bundleDir = writer.write(minimalBundle());

        Path cycleFile = bundleDir.resolve("cycle-sequence.json");
        assertTrue(Files.exists(cycleFile), "cycle-sequence.json should exist");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(cycleFile.toFile());
        assertTrue(root.has("violationDetectedAfterCycle"),
                "cycle-sequence.json should contain 'violationDetectedAfterCycle'");
        assertTrue(root.has("lastSuccessfulCycle"),
                "cycle-sequence.json should contain 'lastSuccessfulCycle'");
        assertEquals(42L, root.get("violationDetectedAfterCycle").asLong());
        assertEquals(41L, root.get("lastSuccessfulCycle").asLong());
    }
}
