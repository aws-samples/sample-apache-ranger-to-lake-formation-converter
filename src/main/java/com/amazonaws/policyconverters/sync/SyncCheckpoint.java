package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a persisted checkpoint of the sync service state.
 * Contains the Cedar policy text (the source of truth) and the
 * Ranger policy version it was derived from. On restart, the Cedar
 * text is re-parsed and converted to LF operations to seed the
 * diff computation, avoiding a redundant bulk re-grant.
 * <p>
 * Supports per-service version tracking via {@code serviceVersions}.
 * Legacy checkpoints without {@code serviceVersions} are backward-compatible:
 * the single {@code policyVersion} is treated as the version for "lakeformation".
 */
public class SyncCheckpoint {

    private final long policyVersion;
    private final Map<String, Long> serviceVersions;
    private final String timestamp;
    private final String cedarPolicyText;
    private final Long lastKnownTagVersion;
    private final Set<String> lastKnownRangerTagNames;
    private final List<S3AccessGrantOperation> s3AgOperations;

    @JsonCreator
    public SyncCheckpoint(
            @JsonProperty("policyVersion") long policyVersion,
            @JsonProperty("serviceVersions") Map<String, Long> serviceVersions,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("cedarPolicyText") String cedarPolicyText,
            @JsonProperty("lastKnownTagVersion") Long lastKnownTagVersion,
            @JsonProperty("lastKnownRangerTagNames") Set<String> lastKnownRangerTagNames,
            @JsonProperty("s3AgOperations") List<S3AccessGrantOperation> s3AgOperations) {
        this.policyVersion = policyVersion;
        this.serviceVersions = serviceVersions != null
                ? Map.copyOf(serviceVersions)
                : Map.of("lakeformation", policyVersion);
        this.timestamp = timestamp;
        this.cedarPolicyText = cedarPolicyText != null ? cedarPolicyText : "";
        this.lastKnownTagVersion = lastKnownTagVersion;
        this.lastKnownRangerTagNames = lastKnownRangerTagNames != null
                ? Collections.unmodifiableSet(new HashSet<>(lastKnownRangerTagNames))
                : Collections.emptySet();
        this.s3AgOperations = s3AgOperations != null
                ? Collections.unmodifiableList(new ArrayList<>(s3AgOperations))
                : Collections.emptyList();
    }

    /**
     * Backward-compatible constructor without S3 AG operations.
     */
    public SyncCheckpoint(
            long policyVersion,
            Map<String, Long> serviceVersions,
            String timestamp,
            String cedarPolicyText,
            Long lastKnownTagVersion,
            Set<String> lastKnownRangerTagNames) {
        this(policyVersion, serviceVersions, timestamp, cedarPolicyText,
                lastKnownTagVersion, lastKnownRangerTagNames, null);
    }

    /**
     * Backward-compatible constructor without tag fields.
     */
    public SyncCheckpoint(
            long policyVersion,
            Map<String, Long> serviceVersions,
            String timestamp,
            String cedarPolicyText) {
        this(policyVersion, serviceVersions, timestamp, cedarPolicyText, null, null, null);
    }

    /**
     * Legacy constructor for backward compatibility.
     * Creates a checkpoint with a single "lakeformation" service version entry.
     */
    public SyncCheckpoint(long policyVersion, String timestamp, String cedarPolicyText) {
        this(policyVersion, null, timestamp, cedarPolicyText, null, null, null);
    }

    public long getPolicyVersion() {
        return policyVersion;
    }

    public Map<String, Long> getServiceVersions() {
        return serviceVersions;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getCedarPolicyText() {
        return cedarPolicyText;
    }

    public Long getLastKnownTagVersion() {
        return lastKnownTagVersion;
    }

    public Set<String> getLastKnownRangerTagNames() {
        return lastKnownRangerTagNames;
    }

    public List<S3AccessGrantOperation> getS3AgOperations() {
        return s3AgOperations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncCheckpoint that = (SyncCheckpoint) o;
        return policyVersion == that.policyVersion
                && Objects.equals(serviceVersions, that.serviceVersions)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(cedarPolicyText, that.cedarPolicyText)
                && Objects.equals(lastKnownTagVersion, that.lastKnownTagVersion)
                && Objects.equals(lastKnownRangerTagNames, that.lastKnownRangerTagNames)
                && Objects.equals(s3AgOperations, that.s3AgOperations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyVersion, serviceVersions, timestamp, cedarPolicyText,
                lastKnownTagVersion, lastKnownRangerTagNames, s3AgOperations);
    }

    @Override
    public String toString() {
        int policyLen = cedarPolicyText != null ? cedarPolicyText.length() : 0;
        return "SyncCheckpoint{policyVersion=" + policyVersion
                + ", serviceVersions=" + serviceVersions
                + ", timestamp='" + timestamp + '\''
                + ", cedarPolicyTextLength=" + policyLen
                + ", lastKnownTagVersion=" + lastKnownTagVersion
                + ", lastKnownRangerTagNames=" + lastKnownRangerTagNames
                + ", s3AgOperations=" + s3AgOperations + '}';
    }
}
