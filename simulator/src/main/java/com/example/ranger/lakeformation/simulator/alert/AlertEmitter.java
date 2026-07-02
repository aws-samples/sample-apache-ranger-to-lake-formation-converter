package com.example.ranger.lakeformation.simulator.alert;

import com.example.ranger.lakeformation.simulator.remediation.ReproductionBundle;
import com.example.ranger.lakeformation.simulator.validator.ValidationResult;

public interface AlertEmitter {

    void emit(ValidationResult result, ReproductionBundle bundle);

    /** Always logs to SLF4J. */
    class LogAlertEmitter implements AlertEmitter {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(LogAlertEmitter.class);

        @Override
        public void emit(ValidationResult result, ReproductionBundle bundle) {
            // bundle may be null for alerts that have no reproduction bundle (e.g. a
            // fail-loud PersistentMutationFailureException abort).
            Object detectedAt = bundle != null ? bundle.detectedAt() : "n/a";
            LOG.warn("SIMULATOR ALERT [{}]: {} — bundle: {}",
                    result.outcome(), result.description(), detectedAt);
        }
    }

    /** Discards all alerts. Useful for unit tests. */
    class NoOpAlertEmitter implements AlertEmitter {
        @Override
        public void emit(ValidationResult result, ReproductionBundle bundle) { /* no-op */ }
    }
}
