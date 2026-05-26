package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.workload.MutationLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MutationDriverTest {

    @TempDir
    Path tempDir;

    /**
     * Smoke test: constructing a MutationDriver and calling applyBatch with an empty list
     * should not throw and should not attempt any network calls.
     */
    @Test
    void applyBatch_emptyBatch_doesNotThrow() {
        // Dummy URL — no connection is made because the batch is empty
        RangerPolicyClient rangerClient = new RangerPolicyClient("http://localhost:1", "u", "p");
        MutationLog mutationLog = new MutationLog(tempDir.resolve("mutation.log"));
        MutationDriver driver = new MutationDriver(rangerClient, mutationLog);

        assertDoesNotThrow(() -> driver.applyBatch(List.of()));
    }
}
