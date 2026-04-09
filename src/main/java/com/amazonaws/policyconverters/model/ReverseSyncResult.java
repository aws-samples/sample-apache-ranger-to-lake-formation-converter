package com.amazonaws.policyconverters.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Summary result of a single reverse-sync cycle, containing the drift report
 * and counts of successful and failed corrective operations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReverseSyncResult {

    private final DriftReport driftReport;
    private final int successfulGrants;
    private final int successfulRevokes;
    private final int failedOperations;
    private final long durationMs;

    @JsonCreator
    public ReverseSyncResult(
            @JsonProperty("driftReport") DriftReport driftReport,
            @JsonProperty("successfulGrants") int successfulGrants,
            @JsonProperty("successfulRevokes") int successfulRevokes,
            @JsonProperty("failedOperations") int failedOperations,
            @JsonProperty("durationMs") long durationMs) {
        this.driftReport = driftReport;
        this.successfulGrants = successfulGrants;
        this.successfulRevokes = successfulRevokes;
        this.failedOperations = failedOperations;
        this.durationMs = durationMs;
    }

    public DriftReport getDriftReport() {
        return driftReport;
    }

    public int getSuccessfulGrants() {
        return successfulGrants;
    }

    public int getSuccessfulRevokes() {
        return successfulRevokes;
    }

    public int getFailedOperations() {
        return failedOperations;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReverseSyncResult that = (ReverseSyncResult) o;
        return successfulGrants == that.successfulGrants
                && successfulRevokes == that.successfulRevokes
                && failedOperations == that.failedOperations
                && durationMs == that.durationMs
                && Objects.equals(driftReport, that.driftReport);
    }

    @Override
    public int hashCode() {
        return Objects.hash(driftReport, successfulGrants, successfulRevokes, failedOperations, durationMs);
    }

    @Override
    public String toString() {
        return "ReverseSyncResult{" +
                "driftReport=" + driftReport +
                ", successfulGrants=" + successfulGrants +
                ", successfulRevokes=" + successfulRevokes +
                ", failedOperations=" + failedOperations +
                ", durationMs=" + durationMs +
                '}';
    }
}
