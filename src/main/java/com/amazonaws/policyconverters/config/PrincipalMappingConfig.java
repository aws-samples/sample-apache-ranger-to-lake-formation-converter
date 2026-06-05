package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for mapping Ranger principals (users, groups, roles)
 * to AWS IAM principal ARNs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PrincipalMappingConfig {

    private final Map<String, String> userMappings;
    private final Map<String, String> groupMappings;
    private final Map<String, String> roleMappings;
    private final PrincipalMapperType type;
    private final IdentityCenterConfig idcConfig;
    private final List<PrincipalMappingConfig> delegates;

    // Backward-compat 3-arg — unchanged, chains to 5-arg
    public PrincipalMappingConfig(
            Map<String, String> userMappings,
            Map<String, String> groupMappings,
            Map<String, String> roleMappings) {
        this(userMappings, groupMappings, roleMappings, null, null);
    }

    // Backward-compat 5-arg — loses @JsonCreator, chains to new 6-arg
    public PrincipalMappingConfig(
            Map<String, String> userMappings,
            Map<String, String> groupMappings,
            Map<String, String> roleMappings,
            PrincipalMapperType type,
            IdentityCenterConfig idcConfig) {
        this(userMappings, groupMappings, roleMappings, type, idcConfig, null);
    }

    // New canonical @JsonCreator — 6-arg, adds delegates
    @JsonCreator
    public PrincipalMappingConfig(
            @JsonProperty("userMappings")  Map<String, String> userMappings,
            @JsonProperty("groupMappings") Map<String, String> groupMappings,
            @JsonProperty("roleMappings")  Map<String, String> roleMappings,
            @JsonProperty("type")          PrincipalMapperType type,
            @JsonProperty("idcConfig")     IdentityCenterConfig idcConfig,
            @JsonProperty("delegates")     List<PrincipalMappingConfig> delegates) {
        this.userMappings  = userMappings  != null ? Collections.unmodifiableMap(new HashMap<>(userMappings))  : Collections.<String,String>emptyMap();
        this.groupMappings = groupMappings != null ? Collections.unmodifiableMap(new HashMap<>(groupMappings)) : Collections.<String,String>emptyMap();
        this.roleMappings  = roleMappings  != null ? Collections.unmodifiableMap(new HashMap<>(roleMappings))  : Collections.<String,String>emptyMap();
        this.type          = (type != null) ? type : PrincipalMapperType.STATIC;
        this.idcConfig     = idcConfig;
        this.delegates     = delegates != null
                ? Collections.unmodifiableList(new ArrayList<>(delegates))
                : null;  // null → absent from JSON (NON_NULL); getDelegates() returns empty list
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

    public PrincipalMapperType getType() {
        return type;
    }

    public IdentityCenterConfig getIdcConfig() {
        return idcConfig;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<PrincipalMappingConfig> getDelegates() {
        return delegates != null ? delegates : Collections.emptyList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrincipalMappingConfig that = (PrincipalMappingConfig) o;
        return Objects.equals(userMappings, that.userMappings)
                && Objects.equals(groupMappings, that.groupMappings)
                && Objects.equals(roleMappings, that.roleMappings)
                && type == that.type
                && Objects.equals(idcConfig, that.idcConfig)
                && Objects.equals(getDelegates(), that.getDelegates());
    }

    @Override
    public int hashCode() {
        return Objects.hash(userMappings, groupMappings, roleMappings, type, idcConfig, getDelegates());
    }

    @Override
    public String toString() {
        return "PrincipalMappingConfig{" +
                "userMappings=" + userMappings +
                ", groupMappings=" + groupMappings +
                ", roleMappings=" + roleMappings +
                ", type=" + type +
                ", idcConfig=" + idcConfig +
                ", delegates=" + delegates +
                '}';
    }
}
