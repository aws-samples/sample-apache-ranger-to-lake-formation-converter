package com.amazonaws.policyconverters.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a persisted checkpoint of the sync service state.
 * Contains the Cedar policy text (the source of truth) and the
 * Ranger policy version it was derived from. On restart, the Cedar
 * text is re-parsed and converted to LF operations to seed the
 * diff computation, avoiding a redundant bulk re-grant.
 */
public class SyncCheckpoint {

    private final long policyVersion;
    private final String timestamp;
    private final String cedarPolicyText;

    @JsonCreator
    public SyncCheckpoint(
            @JsonProperty("policyVersion") long policyVersion,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("cedarPolicyText") String cedarPolicyText) {
        this.policyVersion = policyVersion;
        this.timestamp = timestamp;
        this.cedarPolicyText = cedarPolicyText != null ? cedarPolicyText : "";
    }

    public long getPolicyVersion() {
        return policyVersion;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getCedarPolicyText() {
        return cedarPolicyText;
    }

    @Override
    public String toString() {
        int policyLen = cedarPolicyText != null ? cedarPolicyText.length() : 0;
        return "SyncCheckpoint{policyVersion=" + policyVersion
                + ", timestamp='" + timestamp + '\''
                + ", cedarPolicyTextLength=" + policyLen + '}';
    }
}
