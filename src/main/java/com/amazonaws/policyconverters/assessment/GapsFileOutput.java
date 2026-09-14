package com.amazonaws.policyconverters.assessment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Envelope for the {@code --output-gaps-path} file.
 *
 * <p>Replaces the bare {@code PolicyGapSummary[]} array this file used to be, which had
 * nowhere to put the known-unmappable-action rollup. This is a breaking change for anything
 * parsing the old array — see the README migration note.
 */
public class GapsFileOutput {

    private final List<PolicyGapSummary> policies;
    private final List<KnownUnmappableActionSummary> knownUnmappableActions;

    @JsonCreator
    public GapsFileOutput(
            @JsonProperty("policies") List<PolicyGapSummary> policies,
            @JsonProperty("knownUnmappableActions")
                    List<KnownUnmappableActionSummary> knownUnmappableActions) {
        this.policies = policies != null
                ? Collections.unmodifiableList(policies) : Collections.emptyList();
        this.knownUnmappableActions = knownUnmappableActions != null
                ? Collections.unmodifiableList(knownUnmappableActions) : Collections.emptyList();
    }

    public List<PolicyGapSummary> getPolicies() { return policies; }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<KnownUnmappableActionSummary> getKnownUnmappableActions() {
        return knownUnmappableActions;
    }
}
