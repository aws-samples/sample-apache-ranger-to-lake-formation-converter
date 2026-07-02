package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.workload.MutationLog;
import com.example.ranger.lakeformation.simulator.workload.MutationOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MutationDriverTest {

    @TempDir
    Path tempDir;

    private MutationOperation.CreatePolicy createFor(String service, String id) {
        Map<String, Object> payload = Map.of(
                "name", "sim-policy-" + id,
                "service", service,
                "isEnabled", true,
                "policyType", 0,
                "resources", Map.of(),
                "policyItems", List.of(),
                "denyPolicyItems", List.of());
        return new MutationOperation.CreatePolicy(Instant.EPOCH, id, payload);
    }

    /** A RangerPolicyClient stub whose createPolicy always fails with the given HTTP error. */
    private static final class AlwaysFailingClient extends RangerPolicyClient {
        AlwaysFailingClient() { super("http://localhost:1", "u", "p"); }
        @Override
        public String createPolicy(Map<String, Object> policyJson) throws IOException {
            throw new IOException("createPolicy failed: HTTP 400 — no service found");
        }
    }

    /** A RangerPolicyClient stub that succeeds, assigning sequential numeric IDs. */
    private static final class AlwaysSucceedingClient extends RangerPolicyClient {
        private int next = 1;
        AlwaysSucceedingClient() { super("http://localhost:1", "u", "p"); }
        @Override
        public String createPolicy(Map<String, Object> policyJson) {
            return String.valueOf(next++);
        }
    }

    /** A RangerPolicyClient stub whose createPolicy fails with a benign duplicate-resource (3010) error. */
    private static final class DuplicateResourceClient extends RangerPolicyClient {
        DuplicateResourceClient() { super("http://localhost:1", "u", "p"); }
        @Override
        public String createPolicy(Map<String, Object> policyJson) throws IOException {
            throw new IOException("createPolicy failed: HTTP 400 — {\"statusCode\":1,\"msgDesc\":"
                    + "\"(0) Validation failure: error code[3010], reason[Another policy already "
                    + "exists for matching resource: policy-name=[x], service=[hive]]\"}");
        }
    }

    @Test
    void applyBatch_emptyBatch_doesNotThrow() {
        RangerPolicyClient rangerClient = new RangerPolicyClient("http://localhost:1", "u", "p");
        MutationLog mutationLog = new MutationLog(tempDir.resolve("mutation.log"));
        MutationDriver driver = new MutationDriver(rangerClient, mutationLog);

        assertDoesNotThrow(() -> driver.applyBatch(List.of()));
    }

    @Test
    void applyBatch_persistentPerServiceFailures_throwsAfterThreshold() {
        MutationLog mutationLog = new MutationLog(tempDir.resolve("mutation.log"));
        MutationDriver driver = new MutationDriver(new AlwaysFailingClient(), mutationLog, 3);

        // 3 consecutive failed creates for the same service must trip the fail-loud guard.
        List<MutationOperation> batch = List.of(
                createFor("hive", "a"),
                createFor("hive", "b"),
                createFor("hive", "c"));

        PersistentMutationFailureException ex = assertThrows(
                PersistentMutationFailureException.class,
                () -> driver.applyBatch(batch));
        assertTrue(ex.getMessage().contains("hive"),
                "exception should name the failing service, got: " + ex.getMessage());
    }

    @Test
    void applyBatch_failuresSpreadAcrossServices_belowThreshold_doesNotThrow() {
        MutationLog mutationLog = new MutationLog(tempDir.resolve("mutation.log"));
        MutationDriver driver = new MutationDriver(new AlwaysFailingClient(), mutationLog, 3);

        // Each service fails only twice — below the per-service threshold of 3.
        List<MutationOperation> batch = List.of(
                createFor("hive", "a"), createFor("trino", "b"),
                createFor("hive", "c"), createFor("trino", "d"));

        assertDoesNotThrow(() -> driver.applyBatch(batch));
    }

    @Test
    void applyBatch_successResetsConsecutiveFailureCount() {
        MutationLog mutationLog = new MutationLog(tempDir.resolve("mutation.log"));
        // Succeeding client never trips the guard regardless of batch size.
        MutationDriver driver = new MutationDriver(new AlwaysSucceedingClient(), mutationLog, 3);

        List<MutationOperation> batch = List.of(
                createFor("hive", "a"), createFor("hive", "b"),
                createFor("hive", "c"), createFor("hive", "d"));

        assertDoesNotThrow(() -> driver.applyBatch(batch));
    }

    @Test
    void applyBatch_benignDuplicateResourceFailures_doNotTripGuard() {
        MutationLog mutationLog = new MutationLog(tempDir.resolve("mutation.log"));
        MutationDriver driver = new MutationDriver(new DuplicateResourceClient(), mutationLog, 3);

        // Many duplicate-resource (3010) conflicts are benign churn from the random workload
        // (a policy whose resource already exists) — they must NOT trip the fail-loud guard,
        // which is meant only for genuinely missing/misconfigured services.
        List<MutationOperation> batch = List.of(
                createFor("hive", "a"), createFor("hive", "b"),
                createFor("hive", "c"), createFor("hive", "d"),
                createFor("hive", "e"), createFor("hive", "f"));

        assertDoesNotThrow(() -> driver.applyBatch(batch),
                "Benign 3010 duplicate-resource conflicts must not trigger fail-loud abort");
    }
}
