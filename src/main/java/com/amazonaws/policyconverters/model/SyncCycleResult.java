package com.amazonaws.policyconverters.model;

import java.util.Objects;

/**
 * Immutable value object representing the outcome of a single sync cycle.
 * Use the static factory methods {@link #success} and {@link #failure} to create instances.
 */
public final class SyncCycleResult {

    private final boolean success;
    private final long durationMs;
    private final int policiesProcessed;
    private final int grantsApplied;
    private final int revocationsApplied;
    private final int policiesSkipped;
    private final String errorClass;
    private final String errorMessage;
    private final Throwable error;

    private SyncCycleResult(boolean success, long durationMs, int policiesProcessed,
                            int grantsApplied, int revocationsApplied, int policiesSkipped,
                            String errorClass, String errorMessage, Throwable error) {
        this.success = success;
        this.durationMs = durationMs;
        this.policiesProcessed = policiesProcessed;
        this.grantsApplied = grantsApplied;
        this.revocationsApplied = revocationsApplied;
        this.policiesSkipped = policiesSkipped;
        this.errorClass = errorClass;
        this.errorMessage = errorMessage;
        this.error = error;
    }

    /**
     * Creates a successful cycle result.
     */
    public static SyncCycleResult success(long durationMs, int policiesProcessed,
                                          int grantsApplied, int revocationsApplied,
                                          int policiesSkipped) {
        return new SyncCycleResult(true, durationMs, policiesProcessed,
                grantsApplied, revocationsApplied, policiesSkipped,
                null, null, null);
    }

    /**
     * Creates a failed cycle result.
     */
    public static SyncCycleResult failure(long durationMs, Throwable error) {
        return new SyncCycleResult(false, durationMs, 0, 0, 0, 0,
                error.getClass().getName(), error.getMessage(), error);
    }

    public boolean isSuccess() {
        return success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getPoliciesProcessed() {
        return policiesProcessed;
    }

    public int getGrantsApplied() {
        return grantsApplied;
    }

    public int getRevocationsApplied() {
        return revocationsApplied;
    }

    public int getPoliciesSkipped() {
        return policiesSkipped;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Throwable getError() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncCycleResult that = (SyncCycleResult) o;
        return success == that.success
                && durationMs == that.durationMs
                && policiesProcessed == that.policiesProcessed
                && grantsApplied == that.grantsApplied
                && revocationsApplied == that.revocationsApplied
                && policiesSkipped == that.policiesSkipped
                && Objects.equals(errorClass, that.errorClass)
                && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, durationMs, policiesProcessed,
                grantsApplied, revocationsApplied, policiesSkipped,
                errorClass, errorMessage);
    }

    @Override
    public String toString() {
        if (success) {
            return "SyncCycleResult{success=true"
                    + ", durationMs=" + durationMs
                    + ", policiesProcessed=" + policiesProcessed
                    + ", grantsApplied=" + grantsApplied
                    + ", revocationsApplied=" + revocationsApplied
                    + ", policiesSkipped=" + policiesSkipped
                    + '}';
        }
        return "SyncCycleResult{success=false"
                + ", durationMs=" + durationMs
                + ", errorClass='" + errorClass + '\''
                + ", errorMessage='" + errorMessage + '\''
                + '}';
    }
}
