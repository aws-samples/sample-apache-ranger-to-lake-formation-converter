package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.workload.MutationLog;
import com.example.ranger.lakeformation.simulator.workload.MutationOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
            rangerClient.createPolicy((Map<String, Object>) c.policyPayload());
        } else if (op instanceof MutationOperation.UpdatePolicy u) {
            rangerClient.updatePolicy(u.policyId(), (Map<String, Object>) u.policyPayload());
        } else if (op instanceof MutationOperation.DisablePolicy d) {
            disablePolicy(d.policyId());
        } else if (op instanceof MutationOperation.EnablePolicy e) {
            enablePolicy(e.policyId());
        } else if (op instanceof MutationOperation.DeletePolicy del) {
            rangerClient.deletePolicy(del.policyId());
        }
    }

    private void disablePolicy(String policyId) throws IOException, InterruptedException {
        // Fetch-modify-update: get current policy, set isEnabled=false, update
        var current = rangerClient.listPolicies("hive");   // simplified — in practice filter by ID
        LOG.debug("Disabling policy {}", policyId);
        // For the simulator, we'll PUT a minimal payload with isEnabled=false
        rangerClient.setPolicyEnabled(policyId, false, Map.of("id", policyId, "isEnabled", true));
    }

    private void enablePolicy(String policyId) throws IOException, InterruptedException {
        LOG.debug("Enabling policy {}", policyId);
        rangerClient.setPolicyEnabled(policyId, true, Map.of("id", policyId, "isEnabled", false));
    }
}
