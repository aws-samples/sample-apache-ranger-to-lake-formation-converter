package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadOrchestratorTest {

    private static final List<String> PRINCIPALS = List.of("user:alice", "user:bob");
    private static final Map<String, List<String>> DB_TABLES = Map.of(
            "db1", List.of("t1", "t2"),
            "db2", List.of("t3"));
    private static final long FIXED_SEED = 42L;

    private WorkloadOrchestrator orchestrator(List<String> policyIds, long seed) {
        return new WorkloadOrchestrator(PRINCIPALS, policyIds, DB_TABLES, "hive", new Random(seed));
    }

    @Test
    void generateBatchReturnsBetweenZeroAndFiveOperations() {
        List<String> policyIds = new ArrayList<>(List.of("p1", "p2", "p3"));
        WorkloadOrchestrator orchestrator = orchestrator(policyIds, FIXED_SEED);

        List<MutationOperation> batch = orchestrator.generateBatch();

        assertNotNull(batch, "Batch must not be null");
        assertTrue(batch.size() >= 0, "Batch size must be >= 0");
        assertTrue(batch.size() <= 5, "Batch size must be <= 5");
    }

    @Test
    void generateBatchIsDeterministicWithFixedSeed() {
        List<String> policyIds1 = new ArrayList<>(List.of("p1", "p2", "p3"));
        List<String> policyIds2 = new ArrayList<>(List.of("p1", "p2", "p3"));

        WorkloadOrchestrator o1 = orchestrator(policyIds1, FIXED_SEED);
        WorkloadOrchestrator o2 = orchestrator(policyIds2, FIXED_SEED);

        List<MutationOperation> batch1 = o1.generateBatch();
        List<MutationOperation> batch2 = o2.generateBatch();

        assertEquals(batch1.size(), batch2.size(), "Deterministic seed must produce same batch size");
        for (int i = 0; i < batch1.size(); i++) {
            assertEquals(batch1.get(i).getClass(), batch2.get(i).getClass(),
                    "Same operation type at index " + i);
        }
    }

    @Test
    void createPolicyAddsIdToExistingPolicyIds() {
        // Use a seed that will definitely produce a CREATE (roll < 30).
        // Force by providing an orchestrator with empty policy list so only CREATE is valid.
        List<String> policyIds = new ArrayList<>();

        // With empty policy IDs, any non-CREATE roll (UPDATE/DISABLE/ENABLE/DELETE) returns null,
        // so we just need at least one CREATE to appear.
        // Use many iterations to ensure at least one CREATE fires.
        WorkloadOrchestrator orchestrator = orchestrator(policyIds, 1L);

        boolean foundCreate = false;
        for (int cycle = 0; cycle < 20 && !foundCreate; cycle++) {
            List<MutationOperation> batch = orchestrator.generateBatch();
            for (MutationOperation op : batch) {
                if (op instanceof MutationOperation.CreatePolicy create) {
                    assertTrue(orchestrator.getExistingPolicyIds().contains(create.policyId()),
                            "Created policy ID should be in existingPolicyIds");
                    foundCreate = true;
                    break;
                }
            }
        }
        assertTrue(foundCreate, "Should have generated at least one CreatePolicy in 20 cycles");
    }

    @Test
    void deletePolicyRemovesIdFromExistingPolicyIds() {
        // Seed the policy list, then drive until a DELETE fires.
        List<String> policyIds = new ArrayList<>(List.of("policy-seed-1", "policy-seed-2", "policy-seed-3"));
        WorkloadOrchestrator orchestrator = orchestrator(policyIds, 7L);

        boolean foundDelete = false;
        for (int cycle = 0; cycle < 50 && !foundDelete; cycle++) {
            List<MutationOperation> batch = orchestrator.generateBatch();
            for (MutationOperation op : batch) {
                if (op instanceof MutationOperation.DeletePolicy delete) {
                    assertFalse(orchestrator.getExistingPolicyIds().contains(delete.policyId()),
                            "Deleted policy ID should be removed from existingPolicyIds");
                    foundDelete = true;
                    break;
                }
            }
        }
        assertTrue(foundDelete, "Should have generated at least one DeletePolicy in 50 cycles");
    }

    @Test
    void batchIsNeverNull() {
        WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(PRINCIPALS, new ArrayList<>(), DB_TABLES, "hive", new Random());
        for (int i = 0; i < 10; i++) {
            assertNotNull(orchestrator.generateBatch(), "generateBatch() must never return null");
        }
    }

    @Test
    void getExistingPolicyIdsReturnsUnmodifiableView() {
        List<String> policyIds = new ArrayList<>(List.of("p1"));
        WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(PRINCIPALS, policyIds, DB_TABLES, "hive", new Random());
        assertThrows(UnsupportedOperationException.class,
                () -> orchestrator.getExistingPolicyIds().add("injected"),
                "getExistingPolicyIds() should return an unmodifiable list");
    }
}
