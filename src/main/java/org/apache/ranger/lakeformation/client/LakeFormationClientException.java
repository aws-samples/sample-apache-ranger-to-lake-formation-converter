package org.apache.ranger.lakeformation.client;

/**
 * Exception thrown when a Lake Formation API operation fails
 * after exhausting retries or due to a non-retryable error.
 */
public class LakeFormationClientException extends Exception {

    public LakeFormationClientException(String message) {
        super(message);
    }

    public LakeFormationClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
