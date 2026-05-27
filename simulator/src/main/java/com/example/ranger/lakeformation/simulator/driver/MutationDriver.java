package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.workload.MutationLog;
import com.example.ranger.lakeformation.simulator.workload.MutationOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a batch of MutationOperations to Ranger via RangerPolicyClient
 * and records each operation to MutationLog.
 */
public class MutationDriver {
    private static final Logger LOG = LoggerFactory.getLogger(MutationDriver.class);

    private final RangerPolicyClient rangerClient;
    private final MutationLog mutationLog;
    private final ObjectMapper mapper = new ObjectMapper();
    // Maps internal simulator IDs (sim-policy-{nanoTime}) to Ranger-assigned numeric IDs
    private final Map<String, String> internalToRangerIdMap = new HashMap<>();

    public MutationDriver(RangerPolicyClient rangerClient, MutationLog mutationLog) {
        this.rangerClient = rangerClient;
        this.mutationLog = mutationLog;
    }

    /**
     * Apply each operation in the batch to Ranger and log it.
     * Failed operations are logged and skipped — they do not abort the batch.
     */
    public void applyBatch(List<MutationOperation> batch) {
        for (MutationOperation op : batch) {
            try {
                applyOne(op);
                mutationLog.append(op);
            } catch (IOException | InterruptedException e) {
                LOG.warn("Failed to apply mutation {}: {}", op.getClass().getSimpleName(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyOne(MutationOperation op) throws IOException, InterruptedException {
        if (op instanceof MutationOperation.CreatePolicy c) {
            String rangerNumericId = rangerClient.createPolicy((Map<String, Object>) c.policyPayload());
            internalToRangerIdMap.put(c.policyId(), rangerNumericId);
            LOG.debug("Created policy {} → Ranger ID {}", c.policyId(), rangerNumericId);
        } else if (op instanceof MutationOperation.UpdatePolicy u) {
            String rangerNumericId = internalToRangerIdMap.getOrDefault(u.policyId(), u.policyId());
            rangerClient.updatePolicy(rangerNumericId, (Map<String, Object>) u.policyPayload());
        } else if (op instanceof MutationOperation.DisablePolicy d) {
            String rangerNumericId = internalToRangerIdMap.get(d.policyId());
            if (rangerNumericId == null) {
                LOG.warn("No Ranger ID mapped for internal ID {}; skipping disable", d.policyId());
            } else {
                togglePolicy(rangerNumericId, false);
            }
        } else if (op instanceof MutationOperation.EnablePolicy e) {
            String rangerNumericId = internalToRangerIdMap.get(e.policyId());
            if (rangerNumericId == null) {
                LOG.warn("No Ranger ID mapped for internal ID {}; skipping enable", e.policyId());
            } else {
                togglePolicy(rangerNumericId, true);
            }
        } else if (op instanceof MutationOperation.DeletePolicy del) {
            String rangerNumericId = internalToRangerIdMap.get(del.policyId());
            if (rangerNumericId == null) {
                LOG.warn("No Ranger ID mapped for internal ID {}; skipping delete", del.policyId());
            } else {
                rangerClient.deletePolicy(rangerNumericId);
                internalToRangerIdMap.remove(del.policyId());
            }
        }
    }

    private void togglePolicy(String rangerNumericId, boolean enabled) throws IOException, InterruptedException {
        JsonNode current = rangerClient.getPolicy(rangerNumericId);
        @SuppressWarnings("unchecked")
        Map<String, Object> updated = mapper.convertValue(current, Map.class);
        updated.put("isEnabled", enabled);
        rangerClient.updatePolicy(rangerNumericId, updated);
        LOG.debug("{} policy Ranger ID {}", enabled ? "Enabled" : "Disabled", rangerNumericId);
    }
}
