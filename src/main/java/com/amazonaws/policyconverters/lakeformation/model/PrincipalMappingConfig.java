package com.amazonaws.policyconverters.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for mapping Ranger principals (users, groups, roles)
 * to AWS IAM principal ARNs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrincipalMappingConfig {

    private final Map<String, String> userMappings;
    private final Map<String, String> groupMappings;
    private final Map<String, String> roleMappings;

    @JsonCreator
    public PrincipalMappingConfig(
            @JsonProperty("userMappings") Map<String, String> userMappings,
            @JsonProperty("groupMappings") Map<String, String> groupMappings,
            @JsonProperty("roleMappings") Map<String, String> roleMappings) {
        this.userMappings = userMappings != null
                ? Collections.unmodifiableMap(new HashMap<>(userMappings))
                : Collections.<String, String>emptyMap();
        this.groupMappings = groupMappings != null
                ? Collections.unmodifiableMap(new HashMap<>(groupMappings))
                : Collections.<String, String>emptyMap();
        this.roleMappings = roleMappings != null
                ? Collections.unmodifiableMap(new HashMap<>(roleMappings))
                : Collections.<String, String>emptyMap();
    }

    public Map<String, String> getUserMappings() {
        return userMappings;
    }

    public Map<String, String> getGroupMappings() {
        return groupMappings;
    }

    public Map<String, String> getRoleMappings() {
        return roleMappings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrincipalMappingConfig that = (PrincipalMappingConfig) o;
        return Objects.equals(userMappings, that.userMappings)
                && Objects.equals(groupMappings, that.groupMappings)
                && Objects.equals(roleMappings, that.roleMappings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userMappings, groupMappings, roleMappings);
    }

    @Override
    public String toString() {
        return "PrincipalMappingConfig{" +
                "userMappings=" + userMappings +
                ", groupMappings=" + groupMappings +
                ", roleMappings=" + roleMappings +
                '}';
    }
}
