package com.amazonaws.policyconverters.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Represents a single gap entry documenting an unsupported Ranger policy feature
 * that cannot be represented in Lake Formation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GapEntry {

    /**
     * The type of unsupported feature encountered during policy conversion.
     */
    public enum GapType {
        DATA_MASKING("DATA_MASKING"),
        TAG_BASED_POLICY("TAG_BASED_POLICY"),
        DENY_POLICY("DENY_POLICY"),
        DENY_EXCEPTION("DENY_EXCEPTION"),
        VALIDITY_SCHEDULE("VALIDITY_SCHEDULE"),
        CUSTOM_CONDITION("CUSTOM_CONDITION"),
        SECURITY_ZONE("SECURITY_ZONE"),
        DELEGATED_ADMIN("DELEGATED_ADMIN"),
        WILDCARD_PATTERN("WILDCARD_PATTERN"),
        UNSUPPORTED_SERVICE_TYPE("UNSUPPORTED_SERVICE_TYPE"),
        UNSUPPORTED_ACTION("UNSUPPORTED_ACTION"),
        UNMAPPED_RESOURCE("UNMAPPED_RESOURCE"),
        SCHEMA_VALIDATION_FAILURE("SCHEMA_VALIDATION_FAILURE"),
        UNREGISTERED_S3_LOCATION("UNREGISTERED_S3_LOCATION"),
        CANNOT_VALIDATE_S3_LOCATION("CANNOT_VALIDATE_S3_LOCATION");

        private final String value;

        GapType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static GapType fromValue(String value) {
            for (GapType type : values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown GapType: " + value);
        }
    }

    private final String policyId;
    private final String policyName;
    private final GapType gapType;
    private final String resourcePath;
    private final String details;
    private final String recommendation;

    @JsonCreator
    public GapEntry(
            @JsonProperty("policyId") String policyId,
            @JsonProperty("policyName") String policyName,
            @JsonProperty("gapType") GapType gapType,
            @JsonProperty("resourcePath") String resourcePath,
            @JsonProperty("details") String details,
            @JsonProperty("recommendation") String recommendation) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.gapType = gapType;
        this.resourcePath = resourcePath;
        this.details = details;
        this.recommendation = recommendation;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public GapType getGapType() {
        return gapType;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public String getDetails() {
        return details;
    }

    public String getRecommendation() {
        return recommendation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GapEntry gapEntry = (GapEntry) o;
        return Objects.equals(policyId, gapEntry.policyId)
                && Objects.equals(policyName, gapEntry.policyName)
                && gapType == gapEntry.gapType
                && Objects.equals(resourcePath, gapEntry.resourcePath)
                && Objects.equals(details, gapEntry.details)
                && Objects.equals(recommendation, gapEntry.recommendation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyId, policyName, gapType, resourcePath, details, recommendation);
    }

    @Override
    public String toString() {
        return "GapEntry{" +
                "policyId='" + policyId + '\'' +
                ", policyName='" + policyName + '\'' +
                ", gapType=" + gapType +
                ", resourcePath='" + resourcePath + '\'' +
                ", details='" + details + '\'' +
                ", recommendation='" + recommendation + '\'' +
                '}';
    }
}
