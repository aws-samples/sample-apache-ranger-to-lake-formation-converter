package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * One row of the known-unmappable-action rollup: a Ranger access type that a service
 * adapter deliberately does not map because Lake Formation has no equivalent, together
 * with how many policies used it.
 *
 * <p>Aggregated per {@code (serviceType, accessType)} rather than per policy — a single
 * unmappable access type routinely appears in thousands of policies, and one entry each
 * would swamp every output the tool produces.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnownUnmappableActionSummary {

    /** Upper bound on {@code samplePolicyIds}; the count is exact regardless. */
    public static final int SAMPLE_LIMIT = 10;

    private final String serviceType;
    private final String accessType;
    private final int occurrenceCount;
    private final List<String> samplePolicyIds;

    @JsonCreator
    public KnownUnmappableActionSummary(
            @JsonProperty("serviceType") String serviceType,
            @JsonProperty("accessType") String accessType,
            @JsonProperty("occurrenceCount") int occurrenceCount,
            @JsonProperty("samplePolicyIds") List<String> samplePolicyIds) {
        this.serviceType = serviceType;
        this.accessType = accessType;
        this.occurrenceCount = occurrenceCount;
        this.samplePolicyIds = samplePolicyIds != null
                ? Collections.unmodifiableList(samplePolicyIds) : Collections.emptyList();
    }

    public String getServiceType()          { return serviceType; }
    public String getAccessType()           { return accessType; }
    public int getOccurrenceCount()         { return occurrenceCount; }
    public List<String> getSamplePolicyIds() { return samplePolicyIds; }
}
