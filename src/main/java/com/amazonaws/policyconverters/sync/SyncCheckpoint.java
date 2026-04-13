package com.amazonaws.policyconverters.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

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

    @JsonCreator
    public SyncCheckpoint(
            @JsonProperty("policyVersion") long policyVersion,
            @JsonProperty("serviceVersions") Map<String, Long> serviceVersions,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("cedarPolicyText") String cedarPolicyText) {
        this.policyVersion = policyVersion;
        this.serviceVersions = serviceVersions != null
                ? Map.copyOf(serviceVersions)
                : Map.of("lakeformation", policyVersion);
        this.timestamp = timestamp;
        this.cedarPolicyText = cedarPolicyText != null ? cedarPolicyText : "";
    }

    /**
     * Legacy constructor for backward compatibility.
     * Creates a checkpoint with a single "lakeformation" service version entry.
     */
    public SyncCheckpoint(long policyVersion, String timestamp, String cedarPolicyText) {
        this(policyVersion, null, timestamp, cedarPolicyText);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncCheckpoint that = (SyncCheckpoint) o;
        return policyVersion == that.policyVersion
                && Objects.equals(serviceVersions, that.serviceVersions)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(cedarPolicyText, that.cedarPolicyText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyVersion, serviceVersions, timestamp, cedarPolicyText);
    }

    @Override
    public String toString() {
        int policyLen = cedarPolicyText != null ? cedarPolicyText.length() : 0;
        return "SyncCheckpoint{policyVersion=" + policyVersion
                + ", serviceVersions=" + serviceVersions
                + ", timestamp='" + timestamp + '\''
                + ", cedarPolicyTextLength=" + policyLen + '}';
    }
}
