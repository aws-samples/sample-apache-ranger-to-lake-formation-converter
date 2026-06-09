package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssessedService {

    private final String name;
    private final String serviceType;
    private final String status;
    private final int policiesScanned;
    private final String skipReason;

    @JsonCreator
    public AssessedService(
            @JsonProperty("name") String name,
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("status") String status,
            @JsonProperty("policiesScanned") int policiesScanned,
            @JsonProperty("skipReason") String skipReason) {
        this.name = name;
        this.serviceType = serviceType;
        this.status = status;
        this.policiesScanned = policiesScanned;
        this.skipReason = skipReason;
    }

    public static AssessedService assessed(String name, String serviceType, int policiesScanned) {
        return new AssessedService(name, serviceType, "assessed", policiesScanned, null);
    }

    public static AssessedService skipped(String name, String serviceType, String skipReason) {
        return new AssessedService(name, serviceType, "skipped", 0, skipReason);
    }

    public String getName()           { return name; }
    public String getServiceType()    { return serviceType; }
    public String getStatus()         { return status; }
    public int getPoliciesScanned()   { return policiesScanned; }
    public String getSkipReason()     { return skipReason; }
    public boolean isSkipped()        { return "skipped".equals(status); }
}
