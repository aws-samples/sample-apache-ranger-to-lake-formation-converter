package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * One row of the unresolved-principal rollup: a Ranger principal that could not be mapped to an
 * AWS identity, why, and which policies name it.
 *
 * <p>Aggregated per principal rather than per policy. A single departed user or unmapped group
 * typically appears across many policies, and one entry each would swamp the report.
 *
 * <p>The {@code cause} is what makes this actionable rather than merely informative: a principal
 * confirmed absent from the directory means a stale Ranger policy, whereas a failed directory
 * lookup means the assessment's own grant counts are unreliable. Those need different responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnresolvedPrincipalSummary {

    /** Upper bound on {@code samplePolicyIds}; {@code policyCount} stays exact. */
    public static final int SAMPLE_LIMIT = 10;

    private final String principal;
    private final String cause;
    private final String causeDescription;
    private final String recommendation;
    private final int policyCount;
    private final List<String> samplePolicyIds;

    @JsonCreator
    public UnresolvedPrincipalSummary(
            @JsonProperty("principal") String principal,
            @JsonProperty("cause") String cause,
            @JsonProperty("causeDescription") String causeDescription,
            @JsonProperty("recommendation") String recommendation,
            @JsonProperty("policyCount") int policyCount,
            @JsonProperty("samplePolicyIds") List<String> samplePolicyIds) {
        this.principal = principal;
        this.cause = cause;
        this.causeDescription = causeDescription;
        this.recommendation = recommendation;
        this.policyCount = policyCount;
        this.samplePolicyIds = samplePolicyIds != null
                ? Collections.unmodifiableList(samplePolicyIds) : Collections.emptyList();
    }

    public static UnresolvedPrincipalSummary of(String principal, PrincipalMapper.UnresolvedCause cause,
                                                int policyCount, List<String> samplePolicyIds) {
        return new UnresolvedPrincipalSummary(principal, cause.name(), cause.getDescription(),
                cause.getRecommendation(), policyCount, samplePolicyIds);
    }

    public String getPrincipal()            { return principal; }
    public String getCause()                { return cause; }
    public String getCauseDescription()     { return causeDescription; }
    public String getRecommendation()       { return recommendation; }
    public int getPolicyCount()             { return policyCount; }
    public List<String> getSamplePolicyIds() { return samplePolicyIds; }
}
