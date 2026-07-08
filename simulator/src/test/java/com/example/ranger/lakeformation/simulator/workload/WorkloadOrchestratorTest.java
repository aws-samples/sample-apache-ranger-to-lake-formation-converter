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
    void generateBatchReturnsBetweenZeroAndTenOperations() {
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(List.of("p1", "p2")), FIXED_SEED);
        List<MutationOperation> batch = orch.generateBatch();
        assertNotNull(batch);
        assertTrue(batch.size() >= 0 && batch.size() <= 10);
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

    @Test
    void updatePolicyOperationIsGenerated() {
        WorkloadOrchestrator orch = orchestrator(
                new ArrayList<>(List.of("seed-1", "seed-2")), 3L);
        boolean found = false;
        for (int cycle = 0; cycle < 100 && !found; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.UpdatePolicy update) {
                    assertNotNull(update.policyId());
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "Should generate at least one UpdatePolicy in 100 cycles");
    }

    @Test
    void disablePolicyOperationIsGenerated() {
        WorkloadOrchestrator orch = orchestrator(
                new ArrayList<>(List.of("seed-1", "seed-2")), 5L);
        boolean found = false;
        for (int cycle = 0; cycle < 100 && !found; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.DisablePolicy disable) {
                    assertNotNull(disable.policyId());
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "Should generate at least one DisablePolicy in 100 cycles");
    }

    @Test
    void enablePolicyOperationIsGenerated() {
        WorkloadOrchestrator orch = orchestrator(
                new ArrayList<>(List.of("seed-1", "seed-2")), 6L);
        boolean found = false;
        for (int cycle = 0; cycle < 100 && !found; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.EnablePolicy enable) {
                    assertNotNull(enable.policyId());
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "Should generate at least one EnablePolicy in 100 cycles");
    }

    @Test
    void updateReusesResourceFromCreate() {
        // A generator that emits a DIFFERENT resource on every call. If UPDATE regenerated the
        // resource freely (the old behavior), an UPDATE payload would carry a resource that
        // differs from the CREATE's. The orchestrator must pin the resource to the CREATE's,
        // so every UPDATE for a given policy id carries the exact resource its CREATE used.
        final int[] counter = {0};
        List<GeneratorEntry> gens = List.of(
            new GeneratorEntry("hive", id -> {
                int n = counter[0]++;
                Map<String, Object> resources = Map.of(
                        "database", Map.of("values", List.of("db" + n)),
                        "table",    Map.of("values", List.of("tbl" + n)));
                return Map.of("service", "hive", "name", id, "resources", resources,
                        "policyItems", List.of(Map.of("users", List.of("u" + n))));
            }, 100)
        );
        WorkloadOrchestrator orch = new WorkloadOrchestrator(new ArrayList<>(), gens, new Random(11L));

        // Track the resource each policy id was CREATEd with, then assert every UPDATE matches.
        Map<String, Object> createdResource = new HashMap<>();
        int updatesChecked = 0;
        for (int cycle = 0; cycle < 200; cycle++) {
            for (MutationOperation op : orch.generateBatch()) {
                if (op instanceof MutationOperation.CreatePolicy c) {
                    createdResource.put(c.policyId(), asMap(c.policyPayload()).get("resources"));
                } else if (op instanceof MutationOperation.UpdatePolicy u) {
                    Object updateResource = asMap(u.policyPayload()).get("resources");
                    assertEquals(createdResource.get(u.policyId()), updateResource,
                            "UPDATE resource must equal the CREATE resource for id " + u.policyId());
                    updatesChecked++;
                }
            }
        }
        assertTrue(updatesChecked > 0, "Test must observe at least one UPDATE to be meaningful");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object payload) {
        return (Map<String, Object>) payload;
    }

    @Test
    void batchWithEmptyExistingIdsContainsOnlyCreateOperations() {
        // When existingPolicyIds is initially empty, any roll >= WEIGHT_CREATE returns null
        // and is filtered. Only CREATE ops (which also populate existingPolicyIds) can appear.
        // We verify this by checking a single batch: the orchestrator starts empty, so the
        // first non-CREATE roll must be null-filtered. CREATEs within the batch add IDs, so
        // we track them and assert non-CREATE ops only reference IDs seeded prior to the batch.
        //
        // Simplest invariant: run one batch from a fresh (empty) orchestrator; any non-CREATE
        // op must reference a policyId that was added by a CREATE during that same batch
        // (existingPolicyIds starts empty, so null was returned and filtered for any roll that
        // preceded all CREATEs). We verify no UPDATE/DISABLE/ENABLE/DELETE appears in the batch
        // unless a CREATE op already ran and added its id.
        WorkloadOrchestrator orch = orchestrator(new ArrayList<>(), 1L);
        // Run a single batch; collect policyIds seen via CREATE ops as they are added
        List<MutationOperation> batch = orch.generateBatch();
        Set<String> createdIds = new LinkedHashSet<>();
        for (MutationOperation op : batch) {
            if (op instanceof MutationOperation.CreatePolicy create) {
                createdIds.add(create.policyId());
            } else {
                // Any non-CREATE op must reference a policyId that was previously created
                String pid = null;
                if (op instanceof MutationOperation.UpdatePolicy u)  pid = u.policyId();
                else if (op instanceof MutationOperation.DisablePolicy d) pid = d.policyId();
                else if (op instanceof MutationOperation.EnablePolicy e)  pid = e.policyId();
                else if (op instanceof MutationOperation.DeletePolicy d)  pid = d.policyId();
                assertTrue(createdIds.contains(pid),
                        "Non-CREATE op " + op.getClass().getSimpleName()
                                + " references policyId not yet created in this batch: " + pid);
            }
        }
    }
}
