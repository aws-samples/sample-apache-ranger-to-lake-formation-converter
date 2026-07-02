package com.example.ranger.lakeformation.simulator.alert;

import com.example.ranger.lakeformation.simulator.remediation.ReproductionBundle;
import com.example.ranger.lakeformation.simulator.validator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

class AlertEmitterTest {

    private ReproductionBundle minimalBundle() {
        return new ReproductionBundle(
                Instant.now(),
                1L,
                0L,
                List.of(),
                "{}",
                Set.of(),
                Set.of(),
                ValidationResult.pass()
        );
    }

    @Test
    void logAlertEmitterShouldNotThrow() {
        AlertEmitter emitter = new AlertEmitter.LogAlertEmitter();
        emitter.emit(ValidationResult.pass(), minimalBundle());
    }

    @Test
    void noOpAlertEmitterShouldNotThrow() {
        AlertEmitter emitter = new AlertEmitter.NoOpAlertEmitter();
        emitter.emit(ValidationResult.pass(), minimalBundle());
    }

    @Test
    void logAlertEmitterShouldNotThrowWhenBundleIsNull() {
        // Fail-loud alerts (e.g. PersistentMutationFailureException) have no reproduction
        // bundle. emit(result, null) must not NPE.
        AlertEmitter emitter = new AlertEmitter.LogAlertEmitter();
        emitter.emit(ValidationResult.pass(), null);
    }

    @Test
    void noOpAlertEmitterShouldNotThrowWhenBundleIsNull() {
        AlertEmitter emitter = new AlertEmitter.NoOpAlertEmitter();
        emitter.emit(ValidationResult.pass(), null);
    }
}
