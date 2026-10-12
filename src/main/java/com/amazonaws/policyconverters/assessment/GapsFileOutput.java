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
    private final List<NarrowedActionSummary> narrowedActions;
    private final List<UnresolvedPrincipalSummary> unresolvedPrincipals;

    public GapsFileOutput(
            List<PolicyGapSummary> policies,
            List<KnownUnmappableActionSummary> knownUnmappableActions) {
        this(policies, knownUnmappableActions, Collections.emptyList());
    }

    @JsonCreator
    public GapsFileOutput(
            @JsonProperty("policies") List<PolicyGapSummary> policies,
            @JsonProperty("knownUnmappableActions")
                    List<KnownUnmappableActionSummary> knownUnmappableActions,
            @JsonProperty("narrowedActions")
                    List<NarrowedActionSummary> narrowedActions,
            @JsonProperty("unresolvedPrincipals")
                    List<UnresolvedPrincipalSummary> unresolvedPrincipals) {
        this.policies = policies != null
                ? Collections.unmodifiableList(policies) : Collections.emptyList();
        this.knownUnmappableActions = knownUnmappableActions != null
                ? Collections.unmodifiableList(knownUnmappableActions) : Collections.emptyList();
        this.unresolvedPrincipals = unresolvedPrincipals != null
                ? Collections.unmodifiableList(unresolvedPrincipals) : Collections.emptyList();
        this.narrowedActions = narrowedActions != null
                ? Collections.unmodifiableList(narrowedActions) : Collections.emptyList();
    }

    /** Convenience overload for callers with no unresolved-principal rollup. */
    public GapsFileOutput(List<PolicyGapSummary> policies,
                          List<KnownUnmappableActionSummary> knownUnmappableActions,
                          List<NarrowedActionSummary> narrowedActions) {
        this(policies, knownUnmappableActions, narrowedActions, Collections.emptyList());
    }

    public List<PolicyGapSummary> getPolicies() { return policies; }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<KnownUnmappableActionSummary> getKnownUnmappableActions() {
        return knownUnmappableActions;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<UnresolvedPrincipalSummary> getUnresolvedPrincipals() {
        return unresolvedPrincipals;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<NarrowedActionSummary> getNarrowedActions() {
        return narrowedActions;
    }
}
