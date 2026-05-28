package com.example.ranger.lakeformation.simulator.workload;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadOrchestratorTest {

    private static final long FIXED_SEED = 42L;

    // Build a 5-entry generator list; each lambda captures the service name in the payload
    private static List<GeneratorEntry> makeGenerators() {
        return List.of(
            new GeneratorEntry("hive",         id -> Map.of("service", "hive",         "name", id), 45),
            new GeneratorEntry("trino",        id -> Map.of("service", "trino",        "name", id), 25),
            new GeneratorEntry("datalocation", id -> Map.of("service", "lakeformation","name", id), 15),
            new GeneratorEntry("tag",          id -> Map.of("service", "cl_tag",       "name", id), 10),
            new GeneratorEntry("emrfs",        id -> Map.of("service", "emrfs",        "name", id),  5)
        );
    }

    private WorkloadOrchestrator orchestrator(List<String> policyIds, long seed) {
        return new WorkloadOrchestrator(policyIds, makeGenerators(), new Random(seed));
    }

    @Test
    void generateBatchReturnsBetweenZeroAndFiveOperations() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        List<MutationOperation> batch = orch.generateBatch();
        assertNotNull(batch);
        assertTrue(batch.size() >= 0 && batch.size() <= 5);
    }

    @Test
    void generateBatchIsDeterministicWithFixedSeed() {
        WorkloadOrchestrator o1 = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        WorkloadOrchestrator o2 = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        List<MutationOperation> b1 = o1.generateBatch();
        List<MutationOperation> b2 = o2.generateBatch();
        assertEquals(b1.size(), b2.size());
        for (int i = 0; i < b1.size(); i++) {
            assertEquals(b1.get(i).getClass(), b2.get(i).getClass(), "Same op type at index " + i);
        }
    }

    @Test
    void createPolicyAddsIdToExistingPolicyIds() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 1L);
        boolean foundCreate = false;
        for (int cycle = 0; cycle < 20 && !foundCreate; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.CreatePolicy create) {
                    assertTrue(orch.getExistingPolicyIds().contains(create.policyId()));
                    foundCreate = true;
                    break;
                }
            }
        }
        assertTrue(foundCreate, "Should generate at least one CreatePolicy in 20 cycles");
    }

    @Test
    void deletePolicyRemovesIdFromExistingPolicyIds() {
        WorkloadOrchestrator orch = orchestrator(
                new ArrayList<>(List.of("seed-1", "seed-2", "seed-3")), 7L);
        boolean foundDelete = false;
        for (int cycle = 0; cycle < 50 && !foundDelete; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.DeletePolicy delete) {
                    assertFalse(orch.getExistingPolicyIds().contains(delete.policyId()));
                    foundDelete = true;
                    break;
                }
            }
        }
        assertTrue(foundDelete, "Should generate at least one DeletePolicy in 50 cycles");
    }

    @Test
    void batchIsNeverNull() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 99L);
        for (int i = 0; i < 10; i++) {
            assertNotNull(orch.generateBatch());
        }
    }

    @Test
    void getExistingPolicyIdsReturnsUnmodifiableView() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(List.of("p1")), 0L);
        assertThrows(UnsupportedOperationException.class,
                () -> orch.getExistingPolicyIds().add("injected"));
    }

    @Test
    void allFiveGeneratorNamesFireAcrossManyIterations() {
        // Run 1000 batch cycles; collect the service prefix from every CreatePolicy policyId.
        // With weights [45,25,15,10,5] and CREATE at 30% of ops, expected emrfs creates ≈ 15
        // (0.30 * 5 ops/batch avg * 0.05 * 1000 cycles). Fixed seed ensures determinism.
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 77L);
        Set<String> observedPrefixes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.CreatePolicy create) {
                    // policyId format: "{entry.name()}-sim-{nanoTime}" → split on "-sim-"
                    String prefix = create.policyId().split("-sim-")[0];
                    observedPrefixes.add(prefix);
                }
            }
        }
        Set<String> expected = Set.of("hive", "trino", "datalocation", "tag", "emrfs");
        assertEquals(expected, observedPrefixes,
                "All five generator names must appear in CreatePolicy IDs over 1000 cycles");
    }
}
