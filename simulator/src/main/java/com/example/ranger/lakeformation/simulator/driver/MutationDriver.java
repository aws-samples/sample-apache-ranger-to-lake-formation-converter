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

    /**
     * Default number of consecutive per-service mutation failures tolerated before
     * {@link #applyBatch} fails loudly by throwing {@link PersistentMutationFailureException}.
     */
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    private final RangerPolicyClient rangerClient;
    private final MutationLog mutationLog;
    private final ObjectMapper mapper = new ObjectMapper();
    // Maps internal simulator IDs (sim-policy-{nanoTime}) to Ranger-assigned numeric IDs
    private final Map<String, String> internalToRangerIdMap = new HashMap<>();
    // Per-service count of consecutive create/update failures. Reset to 0 on any success
    // for that service. When a service exceeds the threshold, applyBatch fails loudly so a
    // missing/misconfigured service cannot masquerade as "all permissions correct".
    private final Map<String, Integer> consecutiveFailuresByService = new HashMap<>();
    private final int failureThreshold;

    public MutationDriver(RangerPolicyClient rangerClient, MutationLog mutationLog) {
        this(rangerClient, mutationLog, DEFAULT_FAILURE_THRESHOLD);
    }

    public MutationDriver(RangerPolicyClient rangerClient, MutationLog mutationLog, int failureThreshold) {
        this.rangerClient = rangerClient;
        this.mutationLog = mutationLog;
        this.failureThreshold = failureThreshold;
    }

    /**
     * Apply each operation in the batch to Ranger and log it.
     * <p>
     * Individual failures are logged and skipped — they do not abort the batch. However,
     * if any single Ranger service accumulates {@code failureThreshold} consecutive
     * create/update failures, {@link PersistentMutationFailureException} is thrown so the
     * run fails loudly rather than silently reporting a service whose policies never land.
     */
    public void applyBatch(List<MutationOperation> batch) {
        for (MutationOperation op : batch) {
            String service = serviceOf(op);
            try {
                applyOne(op);
                mutationLog.append(op);
                // Success clears the consecutive-failure streak for this service.
                if (service != null) {
                    consecutiveFailuresByService.put(service, 0);
                }
            } catch (IOException | InterruptedException e) {
                LOG.warn("Failed to apply mutation {}: {}", op.getClass().getSimpleName(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // Only count failures that indicate a genuinely missing/misconfigured service
                // toward the fail-loud guard. Benign churn from the random workload (a duplicate
                // resource, or updating an already-deleted policy) must NOT trip it.
                if (service != null && isServiceConfigFailure(e.getMessage())) {
                    int failures = consecutiveFailuresByService.merge(service, 1, Integer::sum);
                    if (failures >= failureThreshold) {
                        throw new PersistentMutationFailureException(service, failures, e.getMessage());
                    }
                } else if (service != null) {
                    // Benign failure — do not accumulate, but also do not reset a real streak here;
                    // a subsequent success is what clears it.
                    consecutiveFailuresByService.putIfAbsent(service, 0);
                }
            }
        }
    }

    /**
     * True if the error message indicates the target Ranger service is missing or the policy is
     * invalid for that service definition — i.e. a configuration problem that would cause EVERY
     * mutation for the service to fail. Distinguishes these from benign, self-correcting workload
     * churn (duplicate-resource conflicts, updates to already-deleted policies).
     * <ul>
     *   <li>3007 / "no service found" — the service instance is not installed</li>
     *   <li>3022 / "Invalid access type" — the policy uses access types the servicedef rejects</li>
     * </ul>
     * Benign (excluded): 3010 "Another policy already exists for matching resource"; HTTP 404 on
     * update/enable/disable of a policy that was already deleted.
     */
    static boolean isServiceConfigFailure(String message) {
        if (message == null) return false;
        return message.contains("error code[3007]")
                || message.contains("no service found")
                || message.contains("error code[3022]")
                || message.contains("Invalid access type");
    }

    /**
     * Returns the Ranger service name carried by a create/update operation's payload,
     * or null for operations (disable/enable/delete) that reference a policy by ID only.
     */
    @SuppressWarnings("unchecked")
    private String serviceOf(MutationOperation op) {
        Object payload = null;
        if (op instanceof MutationOperation.CreatePolicy c) {
            payload = c.policyPayload();
        } else if (op instanceof MutationOperation.UpdatePolicy u) {
            payload = u.policyPayload();
        }
        if (payload instanceof Map<?, ?> map) {
            Object service = ((Map<String, Object>) map).get("service");
            return service != null ? service.toString() : null;
        }
        return null;
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
