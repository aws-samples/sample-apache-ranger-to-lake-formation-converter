package com.example.ranger.lakeformation.simulator.validator;

import java.util.Set;

public record ValidationResult(
    Outcome outcome,
    Set<SimulatorPermission> overGrants,    // permissions in LF actual but not expected
    Set<SimulatorPermission> underGrants,   // permissions in expected but not in LF actual
    String description
) {
    public enum Outcome { PASS, TRANSIENT_VIOLATION, PERSISTENT_VIOLATION }

    public static ValidationResult pass() {
        return new ValidationResult(Outcome.PASS, Set.of(), Set.of(), "All permissions match");
    }

    public boolean isViolation() {
        return outcome != Outcome.PASS;
    }
}
