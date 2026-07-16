package com.amazonaws.policyconverters.lakeformation;

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
    // Source policy IDs whose GRANT failed because the target Glue resource no longer exists
    // (deleted table/database). NOT failures in the retry/dead-letter sense — LF already removed
    // the permissions on deletion. Kept out of the checkpoint so they retry (self-healing if the
    // resource is recreated) but never dead-lettered. Surfaced via the MissingResourceGrants metric.
    private final List<String> missingResourcePolicyIds;
    private final int totalOperations;
    private final int appliedOperations;
    private final int rolledBackOperations;

    /** Backward-compatible overload: no missing-resource policies. */
    public BatchResult(List<String> succeededPolicyIds,
                       List<String> failedPolicyIds,
                       int totalOperations,
                       int appliedOperations,
                       int rolledBackOperations) {
        this(succeededPolicyIds, failedPolicyIds, Collections.emptyList(),
                totalOperations, appliedOperations, rolledBackOperations);
    }

    public BatchResult(List<String> succeededPolicyIds,
                       List<String> failedPolicyIds,
                       List<String> missingResourcePolicyIds,
                       int totalOperations,
                       int appliedOperations,
                       int rolledBackOperations) {
        this.succeededPolicyIds = succeededPolicyIds != null
                ? Collections.unmodifiableList(new ArrayList<>(succeededPolicyIds))
                : Collections.<String>emptyList();
        this.failedPolicyIds = failedPolicyIds != null
                ? Collections.unmodifiableList(new ArrayList<>(failedPolicyIds))
                : Collections.<String>emptyList();
        this.missingResourcePolicyIds = missingResourcePolicyIds != null
                ? Collections.unmodifiableList(new ArrayList<>(missingResourcePolicyIds))
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

    /**
     * Source policy IDs whose GRANT failed because the target Glue resource does not exist.
     * Distinct from {@link #getFailedPolicyIds()} — expected/benign (retried, not dead-lettered),
     * counted for the MissingResourceGrants metric.
     */
    public List<String> getMissingResourcePolicyIds() {
        return missingResourcePolicyIds;
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
                ", missingResourcePolicies=" + missingResourcePolicyIds.size() +
                ", totalOperations=" + totalOperations +
                ", appliedOperations=" + appliedOperations +
                ", rolledBackOperations=" + rolledBackOperations +
                '}';
    }
}
