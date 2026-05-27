package com.example.ranger.lakeformation.simulator.workload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates weighted-random batches of MutationOperation for one simulator cycle.
 *
 * Weight table (must sum to 100):
 *   CREATE  30%
 *   UPDATE  20%
 *   DISABLE 15%
 *   ENABLE  15%
 *   DELETE  10%
 *   (remaining 10% reserved — currently treated as no-op / empty batch entry)
 *
 * Batch size is 1–5, chosen uniformly at random.
 */
public class WorkloadOrchestrator {
    private static final int BATCH_MIN = 1;
    private static final int BATCH_MAX = 5;

    // Cumulative weight thresholds (exclusive upper bound)
    private static final int WEIGHT_CREATE  = 30;
    private static final int WEIGHT_UPDATE  = 50;   // 30+20
    private static final int WEIGHT_DISABLE = 65;   // 50+15
    private static final int WEIGHT_ENABLE  = 80;   // 65+15
    private static final int WEIGHT_DELETE  = 90;   // 80+10
    // 90–99 reserved → treated as no-op (not appended to batch)

    private final List<String> principalPool;
    private final List<String> existingPolicyIds;
    private final Random random;
    private final HivePolicyGenerator policyGenerator;

    public WorkloadOrchestrator(List<String> principalPool, List<String> existingPolicyIds, Random random) {
        this.principalPool = List.copyOf(principalPool);
        this.existingPolicyIds = new ArrayList<>(existingPolicyIds);
        this.random = random;
        this.policyGenerator = new HivePolicyGenerator(
                List.of("analytics", "staging", "default_sim"),
                List.of("events", "users", "orders", "products", "sessions"),
                this.principalPool,
                "lakeformation",
                random);
    }

    /**
     * Generate one batch for a cycle. Batch size is 1–5.
     * Returned list may be smaller than batch size if reserved slots are drawn.
     */
    public List<MutationOperation> generateBatch() {
        int batchSize = BATCH_MIN + random.nextInt(BATCH_MAX - BATCH_MIN + 1);
        List<MutationOperation> batch = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            int roll = random.nextInt(100);
            MutationOperation op = pickOperation(roll);
            if (op != null) {
                batch.add(op);
            }
        }
        return batch;
    }

    private MutationOperation pickOperation(int roll) {
        Instant now = Instant.now();
        if (roll < WEIGHT_CREATE) {
            String newId = "sim-policy-" + System.nanoTime();
            existingPolicyIds.add(newId);
            return new MutationOperation.CreatePolicy(now, newId, policyGenerator.generateTablePolicy(newId));
        } else if (roll < WEIGHT_UPDATE) {
            if (existingPolicyIds.isEmpty()) return null;
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.UpdatePolicy(now, id, policyGenerator.generateTablePolicy(id));
        } else if (roll < WEIGHT_DISABLE) {
            if (existingPolicyIds.isEmpty()) return null;
            return new MutationOperation.DisablePolicy(now, randomFrom(existingPolicyIds));
        } else if (roll < WEIGHT_ENABLE) {
            if (existingPolicyIds.isEmpty()) return null;
            return new MutationOperation.EnablePolicy(now, randomFrom(existingPolicyIds));
        } else if (roll < WEIGHT_DELETE) {
            if (existingPolicyIds.isEmpty()) return null;
            String id = randomFrom(existingPolicyIds);
            existingPolicyIds.remove(id);
            return new MutationOperation.DeletePolicy(now, id);
        }
        return null; // reserved range 90–99
    }

    private String randomFrom(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }

    /** Expose current known policy IDs (after mutations from generated batches). */
    public List<String> getExistingPolicyIds() {
        return Collections.unmodifiableList(existingPolicyIds);
    }
}
