package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Top-level configuration for the Ranger-LakeFormation sync utility.
 * Composes Ranger connection, AWS, and principal mapping configs
 * with sync-specific settings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncConfig {

    private static final long DEFAULT_POLICY_REFRESH_INTERVAL_MS = 30000L;
    private static final int DEFAULT_MAX_LF_RETRIES = 5;
    private static final long DEFAULT_LF_RETRY_BACKOFF_MS = 2000L;
    private static final int DEFAULT_WILDCARD_REFRESH_INTERVAL_SECONDS = 0;

    private final RangerConnectionConfig rangerConfig;
    private final AwsConfig awsConfig;
    private final PrincipalMappingConfig principalMapping;
    private final long policyRefreshIntervalMs;
    private final int maxLfRetries;
    private final long lfRetryBackoffMs;
    private final String deadLetterLogPath;
    private final String checkpointPath;
    private final int wildcardRefreshIntervalSeconds;
    private final List<RangerServiceConfig> rangerServices;
    private final TagSyncConfig tagSync;
    private final S3AccessGrantsConfig s3AccessGrants;
    private final ReverseSyncConfig reverseSyncConfig;

    /**
     * Backward-compatible constructor without checkpointPath, wildcardRefreshIntervalSeconds,
     * or rangerServices.
     */
    public SyncConfig(
            RangerConnectionConfig rangerConfig,
            AwsConfig awsConfig,
            PrincipalMappingConfig principalMapping,
            Long policyRefreshIntervalMs,
            Integer maxLfRetries,
            Long lfRetryBackoffMs,
            String deadLetterLogPath) {
        this(rangerConfig, awsConfig, principalMapping, policyRefreshIntervalMs,
                maxLfRetries, lfRetryBackoffMs, deadLetterLogPath, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor without rangerServices.
     */
    public SyncConfig(
            RangerConnectionConfig rangerConfig,
            AwsConfig awsConfig,
            PrincipalMappingConfig principalMapping,
            Long policyRefreshIntervalMs,
            Integer maxLfRetries,
            Long lfRetryBackoffMs,
            String deadLetterLogPath,
            String checkpointPath,
            Integer wildcardRefreshIntervalSeconds) {
        this(rangerConfig, awsConfig, principalMapping, policyRefreshIntervalMs,
                maxLfRetries, lfRetryBackoffMs, deadLetterLogPath, checkpointPath,
                wildcardRefreshIntervalSeconds, null, null, null, null);
    }

    /**
     * Backward-compatible constructor without tagSync.
     */
    public SyncConfig(
            RangerConnectionConfig rangerConfig,
            AwsConfig awsConfig,
            PrincipalMappingConfig principalMapping,
            Long policyRefreshIntervalMs,
            Integer maxLfRetries,
            Long lfRetryBackoffMs,
            String deadLetterLogPath,
            String checkpointPath,
            Integer wildcardRefreshIntervalSeconds,
            List<RangerServiceConfig> rangerServices) {
        this(rangerConfig, awsConfig, principalMapping, policyRefreshIntervalMs,
                maxLfRetries, lfRetryBackoffMs, deadLetterLogPath, checkpointPath,
                wildcardRefreshIntervalSeconds, rangerServices, null, null, null);
    }

    /**
     * Backward-compatible constructor without s3AccessGrants.
     */
    public SyncConfig(
            RangerConnectionConfig rangerConfig,
            AwsConfig awsConfig,
            PrincipalMappingConfig principalMapping,
            Long policyRefreshIntervalMs,
            Integer maxLfRetries,
            Long lfRetryBackoffMs,
            String deadLetterLogPath,
            String checkpointPath,
            Integer wildcardRefreshIntervalSeconds,
            List<RangerServiceConfig> rangerServices,
            TagSyncConfig tagSync) {
        this(rangerConfig, awsConfig, principalMapping, policyRefreshIntervalMs,
                maxLfRetries, lfRetryBackoffMs, deadLetterLogPath, checkpointPath,
                wildcardRefreshIntervalSeconds, rangerServices, tagSync, null, null);
    }

    /**
     * Backward-compatible constructor without reverseSyncConfig.
     */
    public SyncConfig(
            RangerConnectionConfig rangerConfig,
            AwsConfig awsConfig,
            PrincipalMappingConfig principalMapping,
            Long policyRefreshIntervalMs,
            Integer maxLfRetries,
            Long lfRetryBackoffMs,
            String deadLetterLogPath,
            String checkpointPath,
            Integer wildcardRefreshIntervalSeconds,
            List<RangerServiceConfig> rangerServices,
            TagSyncConfig tagSync,
            S3AccessGrantsConfig s3AccessGrants) {
        this(rangerConfig, awsConfig, principalMapping, policyRefreshIntervalMs,
                maxLfRetries, lfRetryBackoffMs, deadLetterLogPath, checkpointPath,
                wildcardRefreshIntervalSeconds, rangerServices, tagSync, s3AccessGrants, null);
    }

    @JsonCreator
    public SyncConfig(
            @JsonProperty("rangerConfig") RangerConnectionConfig rangerConfig,
            @JsonProperty("awsConfig") AwsConfig awsConfig,
            @JsonProperty("principalMapping") PrincipalMappingConfig principalMapping,
            @JsonProperty("policyRefreshIntervalMs") Long policyRefreshIntervalMs,
            @JsonProperty("maxLfRetries") Integer maxLfRetries,
            @JsonProperty("lfRetryBackoffMs") Long lfRetryBackoffMs,
            @JsonProperty("deadLetterLogPath") String deadLetterLogPath,
            @JsonProperty("checkpointPath") String checkpointPath,
            @JsonProperty("wildcardRefreshIntervalSeconds") Integer wildcardRefreshIntervalSeconds,
            @JsonProperty("rangerServices") List<RangerServiceConfig> rangerServices,
            @JsonProperty("tagSync") TagSyncConfig tagSync,
            @JsonProperty("s3AccessGrants") S3AccessGrantsConfig s3AccessGrants,
            @JsonProperty("reverseSync") ReverseSyncConfig reverseSyncConfig) {
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
        this.checkpointPath = checkpointPath;
        this.wildcardRefreshIntervalSeconds = wildcardRefreshIntervalSeconds != null
                ? wildcardRefreshIntervalSeconds : DEFAULT_WILDCARD_REFRESH_INTERVAL_SECONDS;
        this.rangerServices = rangerServices;
        this.tagSync = tagSync != null ? tagSync : new TagSyncConfig(false, null, 0L);
        this.s3AccessGrants = s3AccessGrants;
        this.reverseSyncConfig = reverseSyncConfig != null ? reverseSyncConfig : new ReverseSyncConfig(null, null, null, null, null, null, null);
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

    public String getCheckpointPath() {
        return checkpointPath;
    }

    public int getWildcardRefreshIntervalSeconds() {
        return wildcardRefreshIntervalSeconds;
    }

    public List<RangerServiceConfig> getRangerServices() {
        return rangerServices;
    }

    public TagSyncConfig getTagSync() {
        return tagSync;
    }

    public S3AccessGrantsConfig getS3AccessGrants() {
        return s3AccessGrants;
    }

    public ReverseSyncConfig getReverseSyncConfig() {
        return reverseSyncConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncConfig that = (SyncConfig) o;
        return policyRefreshIntervalMs == that.policyRefreshIntervalMs
                && maxLfRetries == that.maxLfRetries
                && lfRetryBackoffMs == that.lfRetryBackoffMs
                && wildcardRefreshIntervalSeconds == that.wildcardRefreshIntervalSeconds
                && Objects.equals(rangerConfig, that.rangerConfig)
                && Objects.equals(awsConfig, that.awsConfig)
                && Objects.equals(principalMapping, that.principalMapping)
                && Objects.equals(deadLetterLogPath, that.deadLetterLogPath)
                && Objects.equals(checkpointPath, that.checkpointPath)
                && Objects.equals(rangerServices, that.rangerServices)
                && Objects.equals(tagSync, that.tagSync)
                && Objects.equals(s3AccessGrants, that.s3AccessGrants)
                && Objects.equals(reverseSyncConfig, that.reverseSyncConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rangerConfig, awsConfig, principalMapping,
                policyRefreshIntervalMs, maxLfRetries, lfRetryBackoffMs,
                deadLetterLogPath, checkpointPath, wildcardRefreshIntervalSeconds,
                rangerServices, tagSync, s3AccessGrants, reverseSyncConfig);
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
                ", checkpointPath='" + checkpointPath + '\'' +
                ", wildcardRefreshIntervalSeconds=" + wildcardRefreshIntervalSeconds +
                ", rangerServices=" + rangerServices +
                ", tagSync=" + tagSync +
                ", s3AccessGrants=" + s3AccessGrants +
                ", reverseSyncConfig=" + reverseSyncConfig +
                '}';
    }
}
