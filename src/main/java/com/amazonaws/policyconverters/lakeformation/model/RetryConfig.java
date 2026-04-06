package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Configuration for retry behavior with exponential backoff.
 * Used by BulkExtractor, LakeFormationClient, and CatalogResolver.
 */
public class RetryConfig {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_INITIAL_BACKOFF_MS = 1000L;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    private static final long DEFAULT_MAX_BACKOFF_MS = 30000L;

    private final int maxRetries;
    private final long initialBackoffMs;
    private final double backoffMultiplier;
    private final long maxBackoffMs;

    /**
     * Create a RetryConfig with default values.
     */
    public RetryConfig() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_BACKOFF_MS,
                DEFAULT_BACKOFF_MULTIPLIER, DEFAULT_MAX_BACKOFF_MS);
    }

    @JsonCreator
    public RetryConfig(
            @JsonProperty("maxRetries") int maxRetries,
            @JsonProperty("initialBackoffMs") long initialBackoffMs,
            @JsonProperty("backoffMultiplier") double backoffMultiplier,
            @JsonProperty("maxBackoffMs") long maxBackoffMs) {
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxBackoffMs = maxBackoffMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RetryConfig that = (RetryConfig) o;
        return maxRetries == that.maxRetries
                && initialBackoffMs == that.initialBackoffMs
                && Double.compare(that.backoffMultiplier, backoffMultiplier) == 0
                && maxBackoffMs == that.maxBackoffMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRetries, initialBackoffMs, backoffMultiplier, maxBackoffMs);
    }

    @Override
    public String toString() {
        return "RetryConfig{" +
                "maxRetries=" + maxRetries +
                ", initialBackoffMs=" + initialBackoffMs +
                ", backoffMultiplier=" + backoffMultiplier +
                ", maxBackoffMs=" + maxBackoffMs +
                '}';
    }
}
