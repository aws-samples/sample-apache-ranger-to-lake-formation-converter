package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ValidationResultTest {

    private static final SimulatorPermission SAMPLE_PERMISSION = new SimulatorPermission(
            "arn:aws:iam::123456789012:role/MyRole",
            "TABLE",
            "arn:aws:glue:us-east-1:123456789012:table/mydb/mytable",
            "SELECT",
            false
    );

    @Test
    void passFactoryReturnsPassOutcomeWithEmptySetsAndCorrectDescription() {
        ValidationResult result = ValidationResult.pass();

        assertEquals(ValidationResult.Outcome.PASS, result.outcome());
        assertTrue(result.overGrants().isEmpty(), "overGrants should be empty for PASS");
        assertTrue(result.underGrants().isEmpty(), "underGrants should be empty for PASS");
        assertEquals("All permissions match", result.description());
    }

    @Test
    void passResultIsNotAViolation() {
        ValidationResult result = ValidationResult.pass();

        assertFalse(result.isViolation());
    }

    @Test
    void resultWithOverGrantsIsAViolation() {
        ValidationResult result = new ValidationResult(
                ValidationResult.Outcome.PERSISTENT_VIOLATION,
                Set.of(SAMPLE_PERMISSION),
                Set.of(),
                "Over-granted permission found"
        );

        assertTrue(result.isViolation());
        assertFalse(result.overGrants().isEmpty());
    }

    @Test
    void resultWithUnderGrantsIsAViolation() {
        ValidationResult result = new ValidationResult(
                ValidationResult.Outcome.TRANSIENT_VIOLATION,
                Set.of(),
                Set.of(SAMPLE_PERMISSION),
                "Under-granted permission found"
        );

        assertTrue(result.isViolation());
        assertFalse(result.underGrants().isEmpty());
    }

    @Test
    void transientViolationIsAlsoAViolation() {
        ValidationResult result = new ValidationResult(
                ValidationResult.Outcome.TRANSIENT_VIOLATION,
                Set.of(),
                Set.of(SAMPLE_PERMISSION),
                "Transient mismatch"
        );

        assertTrue(result.isViolation());
        assertEquals(ValidationResult.Outcome.TRANSIENT_VIOLATION, result.outcome());
    }
}
