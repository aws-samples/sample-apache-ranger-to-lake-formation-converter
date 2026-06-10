package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapReport;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

public class AssessmentResult {

    private final String source;
    private final List<AssessedService> services;
    private final int totalPolicies;
    private final int fullyConvertible;
    private final int partiallyConvertible;
    private final int notConvertible;
    private final int projectedGrantCount;
    private final GapReport gapReport;
    private final List<String> warnings;

    @JsonCreator
    public AssessmentResult(
            @JsonProperty("totalPolicies") int totalPolicies,
            @JsonProperty("fullyConvertible") int fullyConvertible,
            @JsonProperty("partiallyConvertible") int partiallyConvertible,
            @JsonProperty("notConvertible") int notConvertible,
            @JsonProperty("projectedGrantCount") int projectedGrantCount,
            @JsonProperty("gapReport") GapReport gapReport,
            @JsonProperty("source") String source,
            @JsonProperty("services") List<AssessedService> services,
            @JsonProperty("warnings") List<String> warnings) {
        this.totalPolicies = totalPolicies;
        this.fullyConvertible = fullyConvertible;
        this.partiallyConvertible = partiallyConvertible;
        this.notConvertible = notConvertible;
        this.projectedGrantCount = projectedGrantCount;
        this.gapReport = gapReport;
        this.source = source;
        this.services = services != null ? Collections.unmodifiableList(services) : Collections.emptyList();
        this.warnings = warnings != null ? Collections.unmodifiableList(warnings) : Collections.emptyList();
    }

    public String getSource()                    { return source; }
    public List<AssessedService> getServices()   { return services; }
    public int getTotalPolicies()                { return totalPolicies; }
    public int getFullyConvertible()             { return fullyConvertible; }
    public int getPartiallyConvertible()         { return partiallyConvertible; }
    public int getNotConvertible()               { return notConvertible; }
    public int getProjectedGrantCount()          { return projectedGrantCount; }
    public GapReport getGapReport()              { return gapReport; }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> getWarnings()            { return warnings; }
}
