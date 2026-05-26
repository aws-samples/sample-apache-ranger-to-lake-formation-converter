package com.example.ranger.lakeformation.simulator.validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Phase 1 validator: detects drift between two consecutive LF permission snapshots.
 * Unexpected additions → over-grants. Unexpected removals → under-grants.
 * "Expected" at phase 1 means the checkpoint from the previous cycle (not the Ranger spec).
 */
public class Phase1DriftValidator {
    private static final Logger LOG = LoggerFactory.getLogger(Phase1DriftValidator.class);

    private Set<SimulatorPermission> checkpoint = new HashSet<>();

    /**
     * Update the checkpoint to the given snapshot (called after each successful cycle).
     */
    public void updateCheckpoint(Set<SimulatorPermission> snapshot) {
        this.checkpoint = new HashSet<>(snapshot);
    }

    /**
     * Validate the current actual permissions against the stored checkpoint.
     *
     * @param actual current LF permissions (from ListPermissions scan)
     * @return PASS if actual equals checkpoint; TRANSIENT_VIOLATION if they differ
     */
    public ValidationResult validate(Set<SimulatorPermission> actual) {
        Set<SimulatorPermission> overGrants = new HashSet<>(actual);
        overGrants.removeAll(checkpoint);

        Set<SimulatorPermission> underGrants = new HashSet<>(checkpoint);
        underGrants.removeAll(actual);

        if (overGrants.isEmpty() && underGrants.isEmpty()) {
            LOG.debug("Phase1: no drift detected");
            return ValidationResult.pass();
        }

        String desc = String.format(
                "Phase1 drift: %d over-grants, %d under-grants",
                overGrants.size(), underGrants.size());
        LOG.warn(desc);
        return new ValidationResult(
                ValidationResult.Outcome.TRANSIENT_VIOLATION,
                Set.copyOf(overGrants),
                Set.copyOf(underGrants),
                desc);
    }

    public Set<SimulatorPermission> getCheckpoint() {
        return Set.copyOf(checkpoint);
    }
}
