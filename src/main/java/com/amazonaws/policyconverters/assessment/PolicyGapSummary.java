package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapEntry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Per-policy gap summary for the gaps output file. Groups all actionable gap
 * entries for a single Ranger policy under one object alongside its
 * convertibility classification.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyGapSummary {

    public enum Convertibility {
        PARTIALLY_CONVERTIBLE,
        NOT_CONVERTIBLE
    }

    private final String policyId;
    private final String policyName;
    private final Convertibility convertibility;
    private final List<GapEntry> gaps;

    public PolicyGapSummary(
            @JsonProperty("policyId") String policyId,
            @JsonProperty("policyName") String policyName,
            @JsonProperty("convertibility") Convertibility convertibility,
            @JsonProperty("gaps") List<GapEntry> gaps) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.convertibility = convertibility;
        this.gaps = gaps != null ? Collections.unmodifiableList(gaps) : Collections.emptyList();
    }

    public String getPolicyId()             { return policyId; }
    public String getPolicyName()           { return policyName; }
    public Convertibility getConvertibility() { return convertibility; }
    public List<GapEntry> getGaps()         { return gaps; }
}
