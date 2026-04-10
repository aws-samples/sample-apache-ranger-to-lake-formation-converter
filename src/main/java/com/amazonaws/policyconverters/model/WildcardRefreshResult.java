package com.amazonaws.policyconverters.model;

import java.util.Objects;

/**
 * Immutable value object representing the outcome of a single wildcard refresh cycle.
 * Use the static factory methods {@link #success} and {@link #failure} to create instances.
 */
public final class WildcardRefreshResult {

    private final boolean success;
    private final long durationMs;
    private final int policiesEvaluated;
    private final int newGrants;
    private final int revocations;
    private final int unchanged;
    private final Exception error;

    private WildcardRefreshResult(boolean success, long durationMs, int policiesEvaluated,
                                  int newGrants, int revocations, int unchanged,
                                  Exception error) {
        this.success = success;
        this.durationMs = durationMs;
        this.policiesEvaluated = policiesEvaluated;
        this.newGrants = newGrants;
        this.revocations = revocations;
        this.unchanged = unchanged;
        this.error = error;
    }

    /**
     * Creates a successful wildcard refresh result.
     */
    public static WildcardRefreshResult success(long durationMs, int policiesEvaluated,
                                                int newGrants, int revocations, int unchanged) {
        return new WildcardRefreshResult(true, durationMs, policiesEvaluated,
                newGrants, revocations, unchanged, null);
    }

    /**
     * Creates a failed wildcard refresh result.
     */
    public static WildcardRefreshResult failure(long durationMs, Exception error) {
        return new WildcardRefreshResult(false, durationMs, 0, 0, 0, 0, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getPoliciesEvaluated() {
        return policiesEvaluated;
    }

    public int getNewGrants() {
        return newGrants;
    }

    public int getRevocations() {
        return revocations;
    }

    public int getUnchanged() {
        return unchanged;
    }

    public Exception getError() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WildcardRefreshResult that = (WildcardRefreshResult) o;
        return success == that.success
                && durationMs == that.durationMs
                && policiesEvaluated == that.policiesEvaluated
                && newGrants == that.newGrants
                && revocations == that.revocations
                && unchanged == that.unchanged
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, durationMs, policiesEvaluated,
                newGrants, revocations, unchanged, error);
    }

    @Override
    public String toString() {
        if (success) {
            return "WildcardRefreshResult{success=true"
                    + ", durationMs=" + durationMs
                    + ", policiesEvaluated=" + policiesEvaluated
                    + ", newGrants=" + newGrants
                    + ", revocations=" + revocations
                    + ", unchanged=" + unchanged
                    + '}';
        }
        return "WildcardRefreshResult{success=false"
                + ", durationMs=" + durationMs
                + ", error=" + (error != null ? error.getClass().getName() + ": " + error.getMessage() : "null")
                + '}';
    }
}
