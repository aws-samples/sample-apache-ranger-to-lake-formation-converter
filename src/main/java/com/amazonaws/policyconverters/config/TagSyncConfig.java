package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TagSyncConfig {

    private final boolean enabled;
    private final String tagServiceName;
    private final long tagSyncIntervalMs;

    @JsonCreator
    public TagSyncConfig(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("tagServiceName") String tagServiceName,
            @JsonProperty("tagSyncIntervalMs") Long tagSyncIntervalMs) {
        this.enabled = enabled != null && enabled;
        this.tagServiceName = tagServiceName;
        this.tagSyncIntervalMs = tagSyncIntervalMs != null ? tagSyncIntervalMs : 0L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTagServiceName() {
        return tagServiceName;
    }

    public long getTagSyncIntervalMs() {
        return tagSyncIntervalMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TagSyncConfig that = (TagSyncConfig) o;
        return enabled == that.enabled
                && tagSyncIntervalMs == that.tagSyncIntervalMs
                && Objects.equals(tagServiceName, that.tagServiceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, tagServiceName, tagSyncIntervalMs);
    }

    @Override
    public String toString() {
        return "TagSyncConfig{" +
                "enabled=" + enabled +
                ", tagServiceName='" + tagServiceName + '\'' +
                ", tagSyncIntervalMs=" + tagSyncIntervalMs +
                '}';
    }
}
