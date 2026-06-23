package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * AWS configuration for Lake Formation and Glue Data Catalog access.
 * Supports both static credentials (access key / secret key) and
 * IAM role assumption via STS AssumeRole.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsConfig {

    private final String region;
    private final String catalogId;
    private final String accessKey;
    private final String secretKey;
    private final String roleArn;
    private final String profile;

    @JsonCreator
    public AwsConfig(
            @JsonProperty("region") String region,
            @JsonProperty("catalogId") String catalogId,
            @JsonProperty("accessKey") String accessKey,
            @JsonProperty("secretKey") String secretKey,
            @JsonProperty("roleArn") String roleArn) {
        this(region, catalogId, accessKey, secretKey, roleArn, null);
    }

    public AwsConfig(
            String region,
            String catalogId,
            String accessKey,
            String secretKey,
            String roleArn,
            String profile) {
        this.region = region;
        this.catalogId = catalogId;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.roleArn = roleArn;
        this.profile = profile;
    }

    public String getRegion() {
        return region;
    }

    public String getCatalogId() {
        return catalogId;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public String getProfile() {
        return profile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AwsConfig that = (AwsConfig) o;
        return Objects.equals(region, that.region)
                && Objects.equals(catalogId, that.catalogId)
                && Objects.equals(accessKey, that.accessKey)
                && Objects.equals(secretKey, that.secretKey)
                && Objects.equals(roleArn, that.roleArn)
                && Objects.equals(profile, that.profile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(region, catalogId, accessKey, secretKey, roleArn, profile);
    }

    @Override
    public String toString() {
        return "AwsConfig{" +
                "region='" + region + '\'' +
                ", catalogId='" + catalogId + '\'' +
                ", accessKey='" + (accessKey != null ? "****" : "null") + '\'' +
                ", secretKey='" + (secretKey != null ? "****" : "null") + '\'' +
                ", roleArn='" + roleArn + '\'' +
                ", profile='" + profile + '\'' +
                '}';
    }
}
