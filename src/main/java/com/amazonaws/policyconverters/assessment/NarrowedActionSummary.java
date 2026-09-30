package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * One row of the narrowed-action rollup: a Ranger access type that a service adapter
 * successfully mapped but had to narrow to a weaker Lake Formation permission, together with
 * how many policies used it, what it was narrowed to, and why.
 *
 * <p>Aggregated per {@code (serviceType, accessType)} for the same reason as
 * {@link KnownUnmappableActionSummary}: a narrowed access type can appear in many policies, and
 * one entry each would swamp every output the tool produces. Unlike a known-unmappable access
 * type, a policy affected only by narrowing still produces a grant and is fully convertible —
 * this rollup is the operator's only trace of the narrowing in that case, since the policy does
 * not appear in the gaps file's per-policy {@code policies} array.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NarrowedActionSummary {

    /** Upper bound on {@code samplePolicyIds}; the count is exact regardless. */
    public static final int SAMPLE_LIMIT = 10;

    private final String serviceType;
    private final String accessType;
    private final int occurrenceCount;
    private final List<String> samplePolicyIds;
    private final String narrowedTo;
    private final String reason;

    @JsonCreator
    public NarrowedActionSummary(
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("accessType") String accessType,
            @JsonProperty("occurrenceCount") int occurrenceCount,
            @JsonProperty("samplePolicyIds") List<String> samplePolicyIds,
            @JsonProperty("narrowedTo") String narrowedTo,
            @JsonProperty("reason") String reason) {
        this.serviceType = serviceType;
        this.accessType = accessType;
        this.occurrenceCount = occurrenceCount;
        this.samplePolicyIds = samplePolicyIds != null
                ? Collections.unmodifiableList(samplePolicyIds) : Collections.emptyList();
        this.narrowedTo = narrowedTo;
        this.reason = reason;
    }

    public String getServiceType()          { return serviceType; }
    public String getAccessType()           { return accessType; }
    public int getOccurrenceCount()         { return occurrenceCount; }
    public List<String> getSamplePolicyIds() { return samplePolicyIds; }
    public String getNarrowedTo()           { return narrowedTo; }
    public String getReason()               { return reason; }
}
