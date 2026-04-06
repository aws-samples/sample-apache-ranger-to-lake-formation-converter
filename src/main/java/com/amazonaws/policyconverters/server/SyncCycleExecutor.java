package com.amazonaws.policyconverters.server;

/**
 * Functional interface representing a single sync cycle execution.
 * Returns a {@link SyncCycleResult} describing the outcome.
 * <p>
 * This abstraction decouples {@link ServerLifecycle} from the full
 * {@code SyncService} wiring, making the run-loop independently testable.
 */
@FunctionalInterface
public interface SyncCycleExecutor {

    /**
     * Executes a single sync cycle and returns the result.
     *
     * @return the outcome of the sync cycle
     */
    SyncCycleResult execute();
}
