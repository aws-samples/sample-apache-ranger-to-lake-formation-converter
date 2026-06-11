package com.amazonaws.policyconverters.s3accessgrants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single S3 Access Grants operation to apply.
 *
 * <p>{@code grantId} is {@code null} when produced by the converter (logical state),
 * and populated only when records come back from {@code listGrants()} (live API state).
 */
public record S3AccessGrantOperation(
    @JsonProperty("type") OperationType type,
    @JsonProperty("principalArn") String principalArn,
    @JsonProperty("s3Prefix") String s3Prefix,
    @JsonProperty("permission") S3AccessGrantPermission permission,
    /** Null when produced by the converter; populated from listGrants() live state. */
    @JsonProperty("grantId") String grantId,
    /** Null when read from listGrants(); populated by the converter (format: "serviceType:policyId"). */
    @JsonProperty("sourcePolicyId") String sourcePolicyId
) {
    @JsonCreator
    public S3AccessGrantOperation {}
}
