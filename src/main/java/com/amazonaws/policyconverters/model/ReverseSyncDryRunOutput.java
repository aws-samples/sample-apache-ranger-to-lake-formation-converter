package com.amazonaws.policyconverters.model;

import com.amazonaws.policyconverters.model.DriftReport;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Extended dry-run output for reverse-sync operations. Includes the standard
 * timestamp, sequence number, and operations list from {@link DryRunOutput},
 * plus a {@link DriftReport} summarising the detected drift.
 */
public class ReverseSyncDryRunOutput extends DryRunOutput {

    private final DriftReport driftSummary;

    @JsonCreator
    public ReverseSyncDryRunOutput(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("sequenceNumber") int sequenceNumber,
            @JsonProperty("operations") List<LFPermissionOperation> operations,
            @JsonProperty("driftSummary") DriftReport driftSummary) {
        super(timestamp, sequenceNumber, operations);
        this.driftSummary = driftSummary;
    }

    @JsonProperty("driftSummary")
    public DriftReport getDriftSummary() {
        return driftSummary;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReverseSyncDryRunOutput that = (ReverseSyncDryRunOutput) o;
        return getSequenceNumber() == that.getSequenceNumber()
                && Objects.equals(getTimestamp(), that.getTimestamp())
                && Objects.equals(getOperations(), that.getOperations())
                && Objects.equals(driftSummary, that.driftSummary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getSequenceNumber(), getOperations(), driftSummary);
    }

    @Override
    public String toString() {
        return "ReverseSyncDryRunOutput{" +
                "timestamp='" + getTimestamp() + '\'' +
                ", sequenceNumber=" + getSequenceNumber() +
                ", operations=" + getOperations() +
                ", driftSummary=" + driftSummary +
                '}';
    }
}
