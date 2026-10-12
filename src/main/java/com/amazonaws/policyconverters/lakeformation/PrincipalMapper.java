package com.amazonaws.policyconverters.lakeformation;

import java.util.Optional;

/**
 * Maps Ranger principals (users, groups, roles) to AWS principal ARNs
 * for use as Lake Formation {@code dataLakePrincipalIdentifier} values.
 * Returns {@link java.util.Optional#empty()} when a principal cannot be resolved.
 */
public interface PrincipalMapper {
    Optional<String> resolveUser(String rangerUser);
    Optional<String> resolveGroup(String rangerGroup);
    Optional<String> resolveRole(String rangerRole);

    /** The kind of Ranger principal being resolved. */
    enum PrincipalType {
        USER, GROUP, ROLE;

        /** Lower-case name used in log lines, gap text and metric dimensions. */
        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Why a principal could not be resolved. The distinction matters: a principal that genuinely
     * no longer exists means a stale Ranger policy, whereas a failed directory lookup means the
     * assessment could not do its job and its numbers are untrustworthy.
     */
    enum UnresolvedCause {
        /** The directory was queried and confirmed the principal does not exist. */
        NOT_FOUND("not found in directory",
                "Stale Ranger policy — consider pruning it, or re-create the principal."),
        /** No mapping is configured for this principal, so it was never looked up. */
        NO_MAPPING("no mapping configured",
                "Add the principal to principalMapping (or enable a mapper that can resolve it) "
                        + "and re-run; real access is otherwise under-reported."),
        /** The directory lookup itself failed — throttling, permissions, misconfiguration. */
        LOOKUP_FAILED("directory lookup failed",
                "The assessment could not determine this principal's identity, so its grant counts "
                        + "are unreliable. Check the log for the underlying error and re-run.");

        private final String description;
        private final String recommendation;

        UnresolvedCause(String description, String recommendation) {
            this.description = description;
            this.recommendation = recommendation;
        }

        public String getDescription()    { return description; }
        public String getRecommendation() { return recommendation; }
    }

    /**
     * Outcome of resolving one principal: either an ARN, or the reason there isn't one.
     * {@code cause} is only meaningful when {@code arn} is empty.
     */
    record Resolution(Optional<String> arn, UnresolvedCause cause) {
        public static Resolution of(String arn) {
            return new Resolution(Optional.of(arn), null);
        }

        public static Resolution unresolved(UnresolvedCause cause) {
            return new Resolution(Optional.empty(), cause);
        }

        public boolean isResolved() {
            return arn.isPresent();
        }
    }

    /**
     * Resolves a principal of any type, reporting why when it fails.
     *
     * <p>The default delegates to the three type-specific methods and reports
     * {@link UnresolvedCause#NO_MAPPING}, which is correct for mappers that only consult local
     * configuration. Mappers that query a directory should override this to distinguish a
     * confirmed absence from a failed lookup — those two outcomes mean very different things to
     * an operator, and collapsing them into a bare empty {@code Optional} loses the distinction.
     */
    default Resolution resolve(PrincipalType type, String name) {
        Optional<String> arn = switch (type) {
            case USER -> resolveUser(name);
            case GROUP -> resolveGroup(name);
            case ROLE -> resolveRole(name);
        };
        return arn.map(Resolution::of).orElseGet(() -> Resolution.unresolved(UnresolvedCause.NO_MAPPING));
    }
}
