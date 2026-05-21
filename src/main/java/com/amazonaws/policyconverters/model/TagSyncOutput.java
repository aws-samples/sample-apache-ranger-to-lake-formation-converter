package com.amazonaws.policyconverters.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * JSON envelope for dry-run tag-sync output files. Written by DryRunLakeFormationClient
 * after each tag sync operation to make tag sync observable in integration tests.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TagSyncOutput {

    private final String timestamp;
    private final int sequenceNumber;
    private final String operationType;
    private final String tagKey;
    private final List<String> tagValues;
    private final Map<String, String> resource;

    @JsonCreator
    public TagSyncOutput(
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("sequenceNumber") int sequenceNumber,
            @JsonProperty("operationType") String operationType,
            @JsonProperty("tagKey") String tagKey,
            @JsonProperty("tagValues") List<String> tagValues,
            @JsonProperty("resource") Map<String, String> resource) {
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.operationType = operationType;
        this.tagKey = tagKey;
        this.tagValues = tagValues;
        this.resource = resource;
    }

    public String getTimestamp() { return timestamp; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getOperationType() { return operationType; }
    public String getTagKey() { return tagKey; }
    public List<String> getTagValues() { return tagValues; }
    public Map<String, String> getResource() { return resource; }
}
