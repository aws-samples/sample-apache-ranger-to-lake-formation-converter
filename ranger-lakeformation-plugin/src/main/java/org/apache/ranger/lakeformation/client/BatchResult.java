package org.apache.ranger.lakeformation.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the result of applying a batch of LF permission operations.
 * Tracks which policies succeeded, which failed, and the total operation counts.
 */
public class BatchResult {

    private final List<String> succeededPolicyIds;
    private final List<String> failedPolicyIds;
    private final int totalOperations;
    private final int appliedOperations;
    private final int rolledBackOperations;

    public BatchResult(List<String> succeededPolicyIds,
                       List<String> failedPolicyIds,
                       int totalOperations,
                       int appliedOperations,
                       int rolledBackOperations) {
        this.succeededPolicyIds = succeededPolicyIds != null
                ? Collections.unmodifiableList(new ArrayList<>(succeededPolicyIds))
                : Collections.<String>emptyList();
        this.failedPolicyIds = failedPolicyIds != null
                ? Collections.unmodifiableList(new ArrayList<>(failedPolicyIds))
                : Collections.<String>emptyList();
        this.totalOperations = totalOperations;
        this.appliedOperations = appliedOperations;
        this.rolledBackOperations = rolledBackOperations;
    }

    public List<String> getSucceededPolicyIds() {
        return succeededPolicyIds;
    }

    public List<String> getFailedPolicyIds() {
        return failedPolicyIds;
    }

    public int getTotalOperations() {
        return totalOperations;
    }

    public int getAppliedOperations() {
        return appliedOperations;
    }

    public int getRolledBackOperations() {
        return rolledBackOperations;
    }

    public boolean hasFailures() {
        return !failedPolicyIds.isEmpty();
    }

    @Override
    public String toString() {
        return "BatchResult{" +
                "succeededPolicies=" + succeededPolicyIds.size() +
                ", failedPolicies=" + failedPolicyIds.size() +
                ", totalOperations=" + totalOperations +
                ", appliedOperations=" + appliedOperations +
                ", rolledBackOperations=" + rolledBackOperations +
                '}';
    }
}
