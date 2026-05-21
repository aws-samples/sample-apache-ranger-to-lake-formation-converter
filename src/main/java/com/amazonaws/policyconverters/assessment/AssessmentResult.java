package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapReport;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The result of a one-time gap assessment run.
 * Contains policy-level convertibility counts, projected grant count, and the
 * full gap report with per-GapType breakdown.
 */
public class AssessmentResult {

    private final int totalPolicies;
    private final int fullyConvertible;
    private final int partiallyConvertible;
    private final int notConvertible;
    private final int projectedGrantCount;
    private final GapReport gapReport;

    @JsonCreator
    public AssessmentResult(
            @JsonProperty("totalPolicies") int totalPolicies,
            @JsonProperty("fullyConvertible") int fullyConvertible,
            @JsonProperty("partiallyConvertible") int partiallyConvertible,
            @JsonProperty("notConvertible") int notConvertible,
            @JsonProperty("projectedGrantCount") int projectedGrantCount,
            @JsonProperty("gapReport") GapReport gapReport) {
        this.totalPolicies = totalPolicies;
        this.fullyConvertible = fullyConvertible;
        this.partiallyConvertible = partiallyConvertible;
        this.notConvertible = notConvertible;
        this.projectedGrantCount = projectedGrantCount;
        this.gapReport = gapReport;
    }

    public int getTotalPolicies() {
        return totalPolicies;
    }

    public int getFullyConvertible() {
        return fullyConvertible;
    }

    public int getPartiallyConvertible() {
        return partiallyConvertible;
    }

    public int getNotConvertible() {
        return notConvertible;
    }

    public int getProjectedGrantCount() {
        return projectedGrantCount;
    }

    public GapReport getGapReport() {
        return gapReport;
    }
}
