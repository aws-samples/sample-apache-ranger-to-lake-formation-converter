package com.amazonaws.policyconverters.config;

import com.amazonaws.policyconverters.lakeformation.PermissionFilter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Configuration for the reverse-sync feature that retrieves actual LakeFormation
 * permissions and reconciles them against the Cedar-authoritative desired state.
 *
 * Deserialized from the {@code reverseSync} section of {@code server-config.yaml}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReverseSyncConfig {

    private final boolean enabled;
    private final String catalogId;
    private final boolean reportOnly;
    private final boolean dryRun;
    private final PermissionFilter filter;
    private final long periodicIntervalMs;

    @JsonCreator
    public ReverseSyncConfig(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("catalogId") String catalogId,
            @JsonProperty("reportOnly") Boolean reportOnly,
            @JsonProperty("dryRun") Boolean dryRun,
            @JsonProperty("filter") PermissionFilter filter,
            @JsonProperty("exclusionFilter") PermissionFilter exclusionFilter,
            @JsonProperty("periodicIntervalMs") Long periodicIntervalMs) {
        this.enabled = enabled != null ? enabled : false;
        this.catalogId = catalogId;
        this.reportOnly = reportOnly != null ? reportOnly : false;
        this.dryRun = dryRun != null ? dryRun : false;
        // exclusionFilter from YAML maps to filter; explicit filter takes precedence
        this.filter = filter != null ? filter : exclusionFilter;
        this.periodicIntervalMs = periodicIntervalMs != null ? periodicIntervalMs : 0L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCatalogId() {
        return catalogId;
    }

    public boolean isReportOnly() {
        return reportOnly;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public PermissionFilter getFilter() {
        return filter;
    }

    public long getPeriodicIntervalMs() {
        return periodicIntervalMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReverseSyncConfig that = (ReverseSyncConfig) o;
        return enabled == that.enabled
                && reportOnly == that.reportOnly
                && dryRun == that.dryRun
                && periodicIntervalMs == that.periodicIntervalMs
                && Objects.equals(catalogId, that.catalogId)
                && Objects.equals(filter, that.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, catalogId, reportOnly, dryRun, filter, periodicIntervalMs);
    }

    @Override
    public String toString() {
        return "ReverseSyncConfig{" +
                "enabled=" + enabled +
                ", catalogId='" + catalogId + '\'' +
                ", reportOnly=" + reportOnly +
                ", dryRun=" + dryRun +
                ", filter=" + filter +
                ", periodicIntervalMs=" + periodicIntervalMs +
                '}';
    }
}
