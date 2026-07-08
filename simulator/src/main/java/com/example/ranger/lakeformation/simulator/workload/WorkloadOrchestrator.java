package com.example.ranger.lakeformation.simulator.workload;

import java.time.Instant;
import java.util.*;

/**
 * Generates weighted-random batches of MutationOperation for one simulator cycle.
 *
 * Weight table (must sum to 100):
 *   CREATE  30%
 *   UPDATE  20%
 *   DISABLE 15%
 *   ENABLE  15%
 *   DELETE  10%
 *   (remaining 10% no-op)
 *
 * Generator selection is a second weighted draw across the List<GeneratorEntry>.
 * Batch size is 1–10, chosen uniformly at random.
 */
public class WorkloadOrchestrator {
    private static final int BATCH_MIN = 2;
    private static final int BATCH_MAX = 20;
    private static final int WEIGHT_CREATE  = 30;
    private static final int WEIGHT_UPDATE  = 50;
    private static final int WEIGHT_DISABLE = 65;
    private static final int WEIGHT_ENABLE  = 80;
    private static final int WEIGHT_DELETE  = 90;

    private final List<String> existingPolicyIds;
    private final Map<String, GeneratorEntry> policyIdToGenerator;
    // Resource node (db/table, catalog/schema/table, column, data-location, ...) each policy was
    // CREATEd with. UPDATE reuses this so a policy's resource stays fixed for its lifetime —
    // an UPDATE only varies principals/accesses/enabled, mirroring how real Ranger admins edit
    // an existing policy rather than repurposing it onto an entirely different table.
    private final Map<String, Object> policyIdToResource;
    private final List<GeneratorEntry> generators;
    private final int totalWeight;
    private final Random random;

    public WorkloadOrchestrator(List<String> existingPolicyIds,
                                List<GeneratorEntry> generators, Random random) {
        this.existingPolicyIds   = new ArrayList<>(existingPolicyIds);
        this.policyIdToGenerator = new HashMap<>();
        this.policyIdToResource  = new HashMap<>();
        this.generators          = List.copyOf(generators);
        this.totalWeight         = generators.stream().mapToInt(GeneratorEntry::weight).sum();
        this.random              = random;
    }

    public List<MutationOperation> generateBatch() {
        int batchSize = BATCH_MIN + random.nextInt(BATCH_MAX - BATCH_MIN + 1);
        List<MutationOperation> batch = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            MutationOperation op = pickOperation(random.nextInt(100));
            if (op != null) batch.add(op);
        }
        return batch;
    }

    public List<String> getExistingPolicyIds() {
        return Collections.unmodifiableList(existingPolicyIds);
    }

    private MutationOperation pickOperation(int roll) {
        if (roll < WEIGHT_CREATE) {
            GeneratorEntry entry = pickGenerator();
            String newId = entry.name() + "-sim-" + Long.toUnsignedString(random.nextLong(), 36);
            Map<String, Object> payload = entry.generator().generate(newId);
            existingPolicyIds.add(newId);
            policyIdToGenerator.put(newId, entry);
            if (payload != null && payload.get("resources") != null) {
                policyIdToResource.put(newId, payload.get("resources"));
            }
            return new MutationOperation.CreatePolicy(Instant.now(), newId, payload);
        }
        if (existingPolicyIds.isEmpty()) return null;
        if (roll < WEIGHT_UPDATE) {
            String id = randomFrom(existingPolicyIds);
            GeneratorEntry entry = policyIdToGenerator.getOrDefault(id, pickGenerator());
            Map<String, Object> payload = entry.generator().generate(id);
            // Pin the resource to the one this policy was created with, so an UPDATE only
            // changes principals/accesses/enabled — not which table/database it targets.
            payload = pinResource(id, payload);
            return new MutationOperation.UpdatePolicy(Instant.now(), id, payload);
        }
        if (roll < WEIGHT_DISABLE) {
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.DisablePolicy(Instant.now(), id);
        }
        if (roll < WEIGHT_ENABLE) {
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.EnablePolicy(Instant.now(), id);
        }
        if (roll < WEIGHT_DELETE) {
            String id = randomFrom(existingPolicyIds);
            existingPolicyIds.remove(id);
            policyIdToGenerator.remove(id);
            policyIdToResource.remove(id);
            return new MutationOperation.DeletePolicy(Instant.now(), id);
        }
        return null;
    }

    /**
     * Return a copy of {@code payload} with its {@code resources} node replaced by the resource
     * the given policy was originally created with. If no original resource is recorded (e.g. the
     * policy predates this run) or the payload has no resources, the payload is returned unchanged.
     */
    private Map<String, Object> pinResource(String id, Map<String, Object> payload) {
        Object originalResource = policyIdToResource.get(id);
        if (originalResource == null || payload == null || !payload.containsKey("resources")) {
            return payload;
        }
        Map<String, Object> pinned = new LinkedHashMap<>(payload);
        pinned.put("resources", originalResource);
        return pinned;
    }

    private GeneratorEntry pickGenerator() {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (GeneratorEntry entry : generators) {
            cumulative += entry.weight();
            if (roll < cumulative) return entry;
        }
        return generators.getLast();
    }

    private String randomFrom(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }
}
