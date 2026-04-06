package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Top-level configuration for the Ranger-LakeFormation sync utility.
 * Composes Ranger connection, AWS, and principal mapping configs
 * with sync-specific settings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SyncConfig {

    private static final long DEFAULT_POLICY_REFRESH_INTERVAL_MS = 30000L;
    private static final int DEFAULT_MAX_LF_RETRIES = 5;
    private static final long DEFAULT_LF_RETRY_BACKOFF_MS = 2000L;

    private final RangerConnectionConfig rangerConfig;
    private final AwsConfig awsConfig;
    private final PrincipalMappingConfig principalMapping;
    private final long policyRefreshIntervalMs;
    private final int maxLfRetries;
    private final long lfRetryBackoffMs;
    private final String deadLetterLogPath;

    @JsonCreator
    public SyncConfig(
            @JsonProperty("rangerConfig") RangerConnectionConfig rangerConfig,
            @JsonProperty("awsConfig") AwsConfig awsConfig,
            @JsonProperty("principalMapping") PrincipalMappingConfig principalMapping,
            @JsonProperty("policyRefreshIntervalMs") Long policyRefreshIntervalMs,
            @JsonProperty("maxLfRetries") Integer maxLfRetries,
            @JsonProperty("lfRetryBackoffMs") Long lfRetryBackoffMs,
            @JsonProperty("deadLetterLogPath") String deadLetterLogPath) {
        this.rangerConfig = rangerConfig;
        this.awsConfig = awsConfig;
        this.principalMapping = principalMapping;
        this.policyRefreshIntervalMs = policyRefreshIntervalMs != null
                ? policyRefreshIntervalMs : DEFAULT_POLICY_REFRESH_INTERVAL_MS;
        this.maxLfRetries = maxLfRetries != null
                ? maxLfRetries : DEFAULT_MAX_LF_RETRIES;
        this.lfRetryBackoffMs = lfRetryBackoffMs != null
                ? lfRetryBackoffMs : DEFAULT_LF_RETRY_BACKOFF_MS;
        this.deadLetterLogPath = deadLetterLogPath;
    }

    public RangerConnectionConfig getRangerConfig() {
        return rangerConfig;
    }

    public AwsConfig getAwsConfig() {
        return awsConfig;
    }

    public PrincipalMappingConfig getPrincipalMapping() {
        return principalMapping;
    }

    public long getPolicyRefreshIntervalMs() {
        return policyRefreshIntervalMs;
    }

    public int getMaxLfRetries() {
        return maxLfRetries;
    }

    public long getLfRetryBackoffMs() {
        return lfRetryBackoffMs;
    }

    public String getDeadLetterLogPath() {
        return deadLetterLogPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncConfig that = (SyncConfig) o;
        return policyRefreshIntervalMs == that.policyRefreshIntervalMs
                && maxLfRetries == that.maxLfRetries
                && lfRetryBackoffMs == that.lfRetryBackoffMs
                && Objects.equals(rangerConfig, that.rangerConfig)
                && Objects.equals(awsConfig, that.awsConfig)
                && Objects.equals(principalMapping, that.principalMapping)
                && Objects.equals(deadLetterLogPath, that.deadLetterLogPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rangerConfig, awsConfig, principalMapping,
                policyRefreshIntervalMs, maxLfRetries, lfRetryBackoffMs, deadLetterLogPath);
    }

    @Override
    public String toString() {
        return "SyncConfig{" +
                "rangerConfig=" + rangerConfig +
                ", awsConfig=" + awsConfig +
                ", principalMapping=" + principalMapping +
                ", policyRefreshIntervalMs=" + policyRefreshIntervalMs +
                ", maxLfRetries=" + maxLfRetries +
                ", lfRetryBackoffMs=" + lfRetryBackoffMs +
                ", deadLetterLogPath='" + deadLetterLogPath + '\'' +
                '}';
    }
}
