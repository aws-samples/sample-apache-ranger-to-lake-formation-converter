package com.amazonaws.policyconverters.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Configuration for a single Ranger service instance within the multi-service
 * sync pipeline. Each entry in the {@code rangerServices} list maps to one
 * Ranger plugin that fetches policies from Ranger Admin.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RangerServiceConfig {

    private final String serviceType;
    private final String serviceInstanceName;
    private final String serviceDefPath;
    private final String gdcCatalogName;

    @JsonCreator
    public RangerServiceConfig(
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("serviceInstanceName") String serviceInstanceName,
            @JsonProperty("serviceDefPath") String serviceDefPath,
            @JsonProperty("gdcCatalogName") String gdcCatalogName) {
        this.serviceType = serviceType;
        this.serviceInstanceName = serviceInstanceName;
        this.serviceDefPath = serviceDefPath;
        this.gdcCatalogName = gdcCatalogName;
    }

    public String getServiceType() {
        return serviceType;
    }

    public String getServiceInstanceName() {
        return serviceInstanceName;
    }

    public String getServiceDefPath() {
        return serviceDefPath;
    }

    public String getGdcCatalogName() {
        return gdcCatalogName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RangerServiceConfig that = (RangerServiceConfig) o;
        return Objects.equals(serviceType, that.serviceType)
                && Objects.equals(serviceInstanceName, that.serviceInstanceName)
                && Objects.equals(serviceDefPath, that.serviceDefPath)
                && Objects.equals(gdcCatalogName, that.gdcCatalogName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceType, serviceInstanceName, serviceDefPath, gdcCatalogName);
    }

    @Override
    public String toString() {
        return "RangerServiceConfig{" +
                "serviceType='" + serviceType + '\'' +
                ", serviceInstanceName='" + serviceInstanceName + '\'' +
                ", serviceDefPath='" + serviceDefPath + '\'' +
                ", gdcCatalogName='" + gdcCatalogName + '\'' +
                '}';
    }
}
