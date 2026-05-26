package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MutationLogTest {

    private static final Instant NOW = Instant.now();
    private static final String POLICY_ID = "policy-test-001";
    private static final Object PAYLOAD = "{}";

    @TempDir
    Path tempDir;

    @Test
    void appendAddsToGetEntries() throws IOException {
        Path logFile = tempDir.resolve("mutation.log");
        MutationLog log = new MutationLog(logFile);

        MutationOperation op = new MutationOperation.CreatePolicy(NOW, POLICY_ID, PAYLOAD);
        log.append(op);

        List<MutationOperation> entries = log.getEntries();
        assertEquals(1, entries.size());
        assertSame(op, entries.get(0));
    }

    @Test
    void appendWritesJsonLineToFile() throws IOException {
        Path logFile = tempDir.resolve("mutation.log");
        MutationLog log = new MutationLog(logFile);

        log.append(new MutationOperation.CreatePolicy(NOW, POLICY_ID, PAYLOAD));

        assertTrue(Files.exists(logFile), "Log file should be created");
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("{"), "Should be JSON: " + line);
        assertTrue(line.contains("CreatePolicy") || line.contains("type"), "Should contain type info: " + line);
    }

    @Test
    void multipleAppendsWriteMultipleLines() throws IOException {
        Path logFile = tempDir.resolve("mutation.log");
        MutationLog log = new MutationLog(logFile);

        log.append(new MutationOperation.CreatePolicy(NOW, "p1", PAYLOAD));
        log.append(new MutationOperation.UpdatePolicy(NOW, "p2", PAYLOAD));
        log.append(new MutationOperation.DisablePolicy(NOW, "p3"));

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(3, lines.size(), "Each append should write one line");

        List<MutationOperation> entries = log.getEntries();
        assertEquals(3, entries.size());
    }

    @Test
    void clearEmptiesInMemoryListButDoesNotAffectFile() throws IOException {
        Path logFile = tempDir.resolve("mutation.log");
        MutationLog log = new MutationLog(logFile);

        log.append(new MutationOperation.CreatePolicy(NOW, "p1", PAYLOAD));
        log.append(new MutationOperation.DeletePolicy(NOW, "p2"));

        log.clear();

        assertEquals(0, log.getEntries().size(), "In-memory list should be empty after clear()");

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size(), "File should still contain both lines after clear()");
    }

    @Test
    void getLogPathReturnsConfiguredPath() throws IOException {
        Path logFile = tempDir.resolve("mutation.log");
        MutationLog log = new MutationLog(logFile);

        assertEquals(logFile, log.getLogPath());
    }

    @Test
    void getEntriesReturnsUnmodifiableSnapshot() throws IOException {
        Path logFile = tempDir.resolve("mutation.log");
        MutationLog log = new MutationLog(logFile);

        log.append(new MutationOperation.EnablePolicy(NOW, "p1"));

        List<MutationOperation> snapshot = log.getEntries();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(
                new MutationOperation.DisablePolicy(NOW, "p2")));
    }
}
