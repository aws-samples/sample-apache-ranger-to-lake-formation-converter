package com.amazonaws.policyconverters.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.List;
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
        KNOWN_UNMAPPABLE_ACTION("KNOWN_UNMAPPABLE_ACTION"),
        NARROWED_ACTION("NARROWED_ACTION"),
        UNMAPPED_RESOURCE("UNMAPPED_RESOURCE"),
        UNRESOLVED_PRINCIPAL("UNRESOLVED_PRINCIPAL"),
        CATALOG_NOT_IN_SCOPE("CATALOG_NOT_IN_SCOPE"),
        SCHEMA_VALIDATION_FAILURE("SCHEMA_VALIDATION_FAILURE"),
        UNREGISTERED_S3_LOCATION("UNREGISTERED_S3_LOCATION"),
        CANNOT_VALIDATE_S3_LOCATION("CANNOT_VALIDATE_S3_LOCATION"),
        EXCLUDES_PATTERN("EXCLUDES_PATTERN");

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
    /** Ranger service type; populated only for action-related gap types, else null. */
    private final String serviceType;
    /** Ranger access type(s); populated only for action-related gap types, else null. */
    private final List<String> accessTypes;

    public GapEntry(
            String policyId,
            String policyName,
            GapType gapType,
            String resourcePath,
            String details,
            String recommendation) {
        this(policyId, policyName, gapType, resourcePath, details, recommendation, null, null);
    }

    @JsonCreator
    public GapEntry(
            @JsonProperty("policyId") String policyId,
            @JsonProperty("policyName") String policyName,
            @JsonProperty("gapType") GapType gapType,
            @JsonProperty("resourcePath") String resourcePath,
            @JsonProperty("details") String details,
            @JsonProperty("recommendation") String recommendation,
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("accessTypes") List<String> accessTypes) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.gapType = gapType;
        this.resourcePath = resourcePath;
        this.details = details;
        this.recommendation = recommendation;
        this.serviceType = serviceType;
        // null stays null (so @JsonInclude(NON_NULL) omits the field for the fifteen gap
        // types that don't use it); a non-null list is copied and made unmodifiable.
        this.accessTypes = accessTypes == null ? null : Collections.unmodifiableList(accessTypes);
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

    public String getServiceType() {
        return serviceType;
    }

    public List<String> getAccessTypes() {
        return accessTypes;
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
                && Objects.equals(recommendation, gapEntry.recommendation)
                && Objects.equals(serviceType, gapEntry.serviceType)
                && Objects.equals(accessTypes, gapEntry.accessTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policyId, policyName, gapType, resourcePath, details, recommendation,
                serviceType, accessTypes);
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
                ", serviceType='" + serviceType + '\'' +
                ", accessTypes=" + accessTypes +
                '}';
    }
}
