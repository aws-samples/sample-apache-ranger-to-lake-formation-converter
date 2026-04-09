package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the conversion server run-loop: continuously executes sync cycles
 * at the configured poll interval and coordinates graceful shutdown.
 * <p>
 * The run-loop delegates each cycle to a {@link SyncCycleExecutor}, measures
 * duration, logs outcomes, and emits CloudWatch metrics via {@link MetricsEmitter}.
 * <p>
 * Shutdown is coordinated via a {@link CountDownLatch}: when {@link #shutdown()}
 * is called, the {@code running} flag is set to {@code false} and the method
 * waits for the current cycle to finish (up to the configured timeout).
 */
public class ServerLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ServerLifecycle.class);

    private final SyncCycleExecutor executor;
    private final MetricsEmitter metricsEmitter;
    private final int shutdownTimeoutSeconds;
    private final long sleepIntervalMs;

    private volatile boolean running = true;
    private volatile CountDownLatch cycleInProgress = new CountDownLatch(0);
    private final AtomicLong cycleCounter = new AtomicLong(0);

    /**
     * Creates a new ServerLifecycle.
     *
     * @param executor              executes a single sync cycle
     * @param metricsEmitter        publishes CloudWatch metrics per cycle
     * @param serverConfig          server-level configuration (shutdown timeout)
     * @param sleepIntervalMs       milliseconds to sleep between cycles
     */
    public ServerLifecycle(SyncCycleExecutor executor,
                           MetricsEmitter metricsEmitter,
                           ServerConfig serverConfig,
                           long sleepIntervalMs) {
        this.executor = executor;
        this.metricsEmitter = metricsEmitter;
        this.shutdownTimeoutSeconds = serverConfig.getShutdownTimeoutSeconds();
        this.sleepIntervalMs = sleepIntervalMs;
    }

    /**
     * Runs the main loop: execute cycles, sleep, repeat until shutdown.
     * This method blocks the calling thread until {@link #shutdown()} is called.
     */
    public void run() {
        LOG.info("ServerLifecycle started, sleepIntervalMs={}, shutdownTimeoutSeconds={}",
                sleepIntervalMs, shutdownTimeoutSeconds);

        while (running) {
            cycleInProgress = new CountDownLatch(1);
            try {
                executeCycle();
            } finally {
                cycleInProgress.countDown();
            }

            if (running) {
                try {
                    Thread.sleep(sleepIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.info("Sleep interrupted, checking running flag");
                }
            }
        }

        LOG.info("ServerLifecycle run-loop exited");
    }

    /**
     * Executes a single sync cycle: logs start, delegates to the executor,
     * logs the outcome, and emits metrics.
     */
    void executeCycle() {
        long cycleNumber = cycleCounter.incrementAndGet();
        Instant startTime = Instant.now();

        LOG.info("Sync cycle {} starting at {}", cycleNumber, startTime);

        long startMs = System.currentTimeMillis();
        try {
            SyncCycleResult result = executor.execute();
            long durationMs = System.currentTimeMillis() - startMs;

            // Build a result with the measured duration if the executor didn't set it
            SyncCycleResult measured = result.isSuccess()
                    ? SyncCycleResult.success(durationMs,
                        result.getPoliciesProcessed(),
                        result.getGrantsApplied(),
                        result.getRevocationsApplied(),
                        result.getPoliciesSkipped())
                    : SyncCycleResult.failure(durationMs, result.getError());

            if (measured.isSuccess()) {
                LOG.info("Sync cycle {} completed successfully: durationMs={}, policiesProcessed={}, "
                        + "grantsApplied={}, revocationsApplied={}, policiesSkipped={}",
                        cycleNumber, measured.getDurationMs(),
                        measured.getPoliciesProcessed(), measured.getGrantsApplied(),
                        measured.getRevocationsApplied(), measured.getPoliciesSkipped());
                metricsEmitter.recordSuccess(measured);
            } else {
                LOG.error("Sync cycle {} failed: errorClass={}, errorMessage={}",
                        cycleNumber, measured.getErrorClass(), measured.getErrorMessage(),
                        measured.getError());
                metricsEmitter.recordFailure(measured);
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            SyncCycleResult failureResult = SyncCycleResult.failure(durationMs, e);

            LOG.error("Sync cycle {} failed: errorClass={}, errorMessage={}",
                    cycleNumber, e.getClass().getName(), e.getMessage(), e);
            metricsEmitter.recordFailure(failureResult);
        }
    }

    /**
     * Initiates graceful shutdown. Sets the running flag to false and waits
     * for the current cycle to complete up to the configured timeout.
     *
     * @return {@code true} if the current cycle completed within the timeout,
     *         {@code false} if the timeout was exceeded
     */
    public boolean shutdown() {
        LOG.info("Shutdown requested");
        running = false;

        try {
            boolean completed = cycleInProgress.await(shutdownTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                LOG.warn("Shutdown timeout exceeded ({}s), current cycle did not complete in time",
                        shutdownTimeoutSeconds);
            } else {
                LOG.info("Graceful shutdown completed, current cycle finished");
            }
            return completed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Shutdown interrupted while waiting for current cycle");
            return false;
        }
    }

    /**
     * Returns the current cycle count (for testing/monitoring).
     */
    long getCycleCount() {
        return cycleCounter.get();
    }

    /**
     * Returns whether the lifecycle is still running (for testing).
     */
    boolean isRunning() {
        return running;
    }
}
