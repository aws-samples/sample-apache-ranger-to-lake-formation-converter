package com.amazonaws.policyconverters.s3accessgrants;

/**
 * Represents a single S3 Access Grants operation to apply.
 *
 * <p>{@code grantId} is {@code null} when produced by the converter (logical state),
 * and populated only when records come back from {@code listGrants()} (live API state).
 */
public record S3AccessGrantOperation(
    OperationType type,
    String principalArn,
    String s3Prefix,
    S3AccessGrantPermission permission,
    /** Null when produced by the converter; populated from listGrants() live state. */
    String grantId
) {}
