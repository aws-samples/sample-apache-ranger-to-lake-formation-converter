package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase1DriftValidatorTest {

    private Phase1DriftValidator validator;

    private static final SimulatorPermission PERM_A = new SimulatorPermission(
            "arn:aws:iam::123:role/alice", "TABLE", "mydb.events", "SELECT", false);
    private static final SimulatorPermission PERM_B = new SimulatorPermission(
            "arn:aws:iam::123:role/alice", "TABLE", "mydb.orders", "INSERT", false);
    private static final SimulatorPermission PERM_EXTRA = new SimulatorPermission(
            "arn:aws:iam::123:role/alice", "DATABASE", "mydb", "DESCRIBE", false);

    @BeforeEach
    void setUp() {
        validator = new Phase1DriftValidator();
    }

    // 1. Empty checkpoint + empty actual → PASS
    @Test
    void emptyCheckpointAndEmptyActualReturnsPass() {
        ValidationResult result = validator.validate(Set.of());

        assertFalse(result.isViolation());
        assertEquals(ValidationResult.Outcome.PASS, result.outcome());
        assertTrue(result.overGrants().isEmpty());
        assertTrue(result.underGrants().isEmpty());
    }

    // 2. Checkpoint equals actual → PASS
    @Test
    void checkpointEqualsActualReturnsPass() {
        validator.updateCheckpoint(Set.of(PERM_A, PERM_B));

        ValidationResult result = validator.validate(Set.of(PERM_A, PERM_B));

        assertFalse(result.isViolation());
        assertEquals(ValidationResult.Outcome.PASS, result.outcome());
    }

    // 3. Actual has extra permission (over-grant) → TRANSIENT_VIOLATION, overGrants = {extra}
    @Test
    void actualHasExtraPermissionProducesOverGrant() {
        validator.updateCheckpoint(Set.of(PERM_A));

        ValidationResult result = validator.validate(Set.of(PERM_A, PERM_EXTRA));

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
        assertEquals(Set.of(PERM_EXTRA), result.overGrants());
        assertTrue(result.underGrants().isEmpty());
    }

    // 4. Actual missing permission (under-grant) → TRANSIENT_VIOLATION, underGrants = {missing}
    @Test
    void actualMissingPermissionProducesUnderGrant() {
        validator.updateCheckpoint(Set.of(PERM_A, PERM_B));

        ValidationResult result = validator.validate(Set.of(PERM_A));

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
        assertTrue(result.overGrants().isEmpty());
        assertEquals(Set.of(PERM_B), result.underGrants());
    }

    // 5. updateCheckpoint updates the stored checkpoint for future calls
    @Test
    void updateCheckpointUpdatesStoredCheckpointForFutureCalls() {
        validator.updateCheckpoint(Set.of(PERM_A));
        // First call: PERM_B is extra
        ValidationResult firstResult = validator.validate(Set.of(PERM_A, PERM_B));
        assertTrue(firstResult.isViolation());

        // Update checkpoint to current actual
        validator.updateCheckpoint(Set.of(PERM_A, PERM_B));

        // Second call: actual matches new checkpoint → PASS
        ValidationResult secondResult = validator.validate(Set.of(PERM_A, PERM_B));
        assertFalse(secondResult.isViolation());
    }

    // 6. getCheckpoint() returns an unmodifiable copy (mutating the returned set does not affect internal state)
    @Test
    void getCheckpointReturnsUnmodifiableCopy() {
        validator.updateCheckpoint(Set.of(PERM_A));

        Set<SimulatorPermission> checkpoint = validator.getCheckpoint();
        assertThrows(UnsupportedOperationException.class,
                () -> checkpoint.add(PERM_B),
                "getCheckpoint() should return an unmodifiable set");

        // Internal state should still equal original checkpoint
        assertEquals(Set.of(PERM_A), validator.getCheckpoint());
    }
}
