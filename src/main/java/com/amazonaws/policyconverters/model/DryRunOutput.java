package com.amazonaws.policyconverters.model;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * JSON envelope for dry-run output files. Contains a timestamp, sequence number,
 * and the list of LF permission operations that would have been applied.
 */
public class DryRunOutput {

    private final String timestamp;
    private final int sequenceNumber;
    private final List<LFPermissionOperation> operations;

    @JsonCreator
    public DryRunOutput(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("sequenceNumber") int sequenceNumber,
            @JsonProperty("operations") List<LFPermissionOperation> operations) {
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.operations = operations;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public List<LFPermissionOperation> getOperations() {
        return operations;
    }
}
