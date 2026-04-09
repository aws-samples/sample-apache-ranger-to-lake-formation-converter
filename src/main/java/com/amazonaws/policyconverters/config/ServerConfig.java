package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.Set;

/**
 * Configuration for the conversion server process.
 * Parsed from the {@code server} YAML section alongside {@code SyncConfig}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {

    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final String DEFAULT_METRICS_NAMESPACE = "RangerLFSync";

    private static final Set<String> ALLOWED_LOG_LEVELS = Set.of(
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR"
    );

    private final int shutdownTimeoutSeconds;
    private final String logLevel;
    private final String metricsNamespace;

    @JsonCreator
    public ServerConfig(
            @JsonProperty("shutdownTimeoutSeconds") Integer shutdownTimeoutSeconds,
            @JsonProperty("logLevel") String logLevel,
            @JsonProperty("metricsNamespace") String metricsNamespace) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds != null
                ? shutdownTimeoutSeconds : DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
        this.logLevel = validateLogLevel(logLevel);
        this.metricsNamespace = metricsNamespace != null
                ? metricsNamespace : DEFAULT_METRICS_NAMESPACE;
    }

    private static String validateLogLevel(String logLevel) {
        if (logLevel == null) {
            return DEFAULT_LOG_LEVEL;
        }
        String upper = logLevel.toUpperCase();
        if (ALLOWED_LOG_LEVELS.contains(upper)) {
            return upper;
        }
        // Invalid log level — fall back to INFO
        return DEFAULT_LOG_LEVEL;
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public String getMetricsNamespace() {
        return metricsNamespace;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServerConfig that = (ServerConfig) o;
        return shutdownTimeoutSeconds == that.shutdownTimeoutSeconds
                && Objects.equals(logLevel, that.logLevel)
                && Objects.equals(metricsNamespace, that.metricsNamespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shutdownTimeoutSeconds, logLevel, metricsNamespace);
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "shutdownTimeoutSeconds=" + shutdownTimeoutSeconds +
                ", logLevel='" + logLevel + '\'' +
                ", metricsNamespace='" + metricsNamespace + '\'' +
                '}';
    }
}
