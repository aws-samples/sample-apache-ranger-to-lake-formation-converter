package com.example.ranger.lakeformation.simulator.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 2 validator: compares LF actual permissions against independently-computed expected permissions.
 * Uses ExpectedPermissionsComputer (zero imports from main pipeline) as the oracle.
 */
public class Phase2CorrectnessValidator {
    private static final Logger LOG = LoggerFactory.getLogger(Phase2CorrectnessValidator.class);

    private final ExpectedPermissionsComputer computer;

    public Phase2CorrectnessValidator(ExpectedPermissionsComputer computer) {
        this.computer = computer;
    }

    /**
     * Validate actual permissions against the expected permissions computed from current Ranger policies.
     *
     * @param actual         current LF permissions (from ListPermissions + S3AG scans)
     * @param rangerPolicies list of raw Ranger policy JSON nodes (from Ranger REST API)
     * @return PASS if actual equals expected; TRANSIENT_VIOLATION if they differ
     */
    public ValidationResult validate(Set<SimulatorPermission> actual, List<JsonNode> rangerPolicies) {
        Set<SimulatorPermission> expected = computer.compute(rangerPolicies);

        Set<SimulatorPermission> overGrants = new HashSet<>(actual);
        overGrants.removeAll(expected);

        Set<SimulatorPermission> underGrants = new HashSet<>(expected);
        underGrants.removeAll(actual);

        if (overGrants.isEmpty() && underGrants.isEmpty()) {
            LOG.info("Phase2: all {} permissions correct", actual.size());
            return ValidationResult.pass();
        }

        String desc = String.format(
                "Phase2 mismatch: %d over-grants (in LF but not expected), %d under-grants (expected but not in LF)",
                overGrants.size(), underGrants.size());
        LOG.warn(desc);
        LOG.warn("Over-grants: {}", overGrants);
        LOG.warn("Under-grants: {}", underGrants);
        return new ValidationResult(
                ValidationResult.Outcome.TRANSIENT_VIOLATION,
                Set.copyOf(overGrants),
                Set.copyOf(underGrants),
                desc);
    }
}
