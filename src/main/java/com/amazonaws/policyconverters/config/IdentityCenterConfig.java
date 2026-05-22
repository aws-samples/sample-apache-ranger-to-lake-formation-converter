package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdentityCenterConfig {

    private final String identityStoreId;
    private final String region;
    private final String accountId;
    private final int cacheTtlMinutes;

    @JsonCreator
    public IdentityCenterConfig(
            @JsonProperty("identityStoreId") String identityStoreId,
            @JsonProperty("region") String region,
            @JsonProperty("accountId") String accountId,
            @JsonProperty("cacheTtlMinutes") Integer cacheTtlMinutes) {
        this.identityStoreId = identityStoreId;
        this.region = region;
        this.accountId = accountId;
        this.cacheTtlMinutes = cacheTtlMinutes != null ? cacheTtlMinutes : 60;
    }

    public String getIdentityStoreId() {
        return identityStoreId;
    }

    public String getRegion() {
        return region;
    }

    public String getAccountId() {
        return accountId;
    }

    public int getCacheTtlMinutes() {
        return cacheTtlMinutes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdentityCenterConfig that = (IdentityCenterConfig) o;
        return cacheTtlMinutes == that.cacheTtlMinutes
                && Objects.equals(identityStoreId, that.identityStoreId)
                && Objects.equals(region, that.region)
                && Objects.equals(accountId, that.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identityStoreId, region, accountId, cacheTtlMinutes);
    }

    @Override
    public String toString() {
        return "IdentityCenterConfig{" +
                "identityStoreId='" + identityStoreId + '\'' +
                ", region='" + region + '\'' +
                ", accountId='" + accountId + '\'' +
                ", cacheTtlMinutes=" + cacheTtlMinutes +
                '}';
    }
}
