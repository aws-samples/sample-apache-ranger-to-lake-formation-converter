package com.amazonaws.policyconverters.app;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import com.amazonaws.policyconverters.config.ServerConfig;

import net.jqwik.api.*;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Feature: conversion-server, Property 10: Graceful shutdown waits for current cycle

/**
 * Property-based test verifying that graceful shutdown waits for the current
 * sync cycle to complete before returning, for any cycle duration less than
 * the configured shutdown timeout.
 * <p>
 * **Validates: Requirements 2.4**
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GracefulShutdownPropertyTest {

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    @Property(tries = 100)
    void shutdownWaitsForCurrentCycleToComplete(
            @ForAll("cycleDurations") int cycleDurationMs
    ) throws InterruptedException {
        MetricsEmitter metricsEmitter = mock(MetricsEmitter.class);
        ServerConfig serverConfig = new ServerConfig(SHUTDOWN_TIMEOUT_SECONDS, "INFO", "TestNamespace");

        CountDownLatch cycleStarted = new CountDownLatch(1);
        AtomicBoolean cycleCompleted = new AtomicBoolean(false);

        SyncCycleExecutor executor = () -> {
            cycleStarted.countDown();
            try {
                Thread.sleep(cycleDurationMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cycleCompleted.set(true);
            return SyncCycleResult.success(cycleDurationMs, 1, 0, 0, 0);
        };

        ServerLifecycle lifecycle = new ServerLifecycle(
                executor, metricsEmitter, serverConfig, 60_000);

        // Start the run loop in a separate thread
        Thread runThread = new Thread(lifecycle::run, "run-loop");
        runThread.setDaemon(true);
        runThread.start();

        // Wait for the cycle to start
        assertTrue(cycleStarted.await(5, TimeUnit.SECONDS),
                "Cycle should start within 5 seconds");

        // Call shutdown and verify it returns true (cycle completed within timeout)
        boolean shutdownResult = lifecycle.shutdown();
        assertTrue(shutdownResult,
                "shutdown() should return true for cycle duration " + cycleDurationMs
                        + "ms (well within " + SHUTDOWN_TIMEOUT_SECONDS + "s timeout)");

        // Verify the cycle actually completed
        assertTrue(cycleCompleted.get(),
                "Cycle executor should have run to completion before shutdown returned");

        // Verify metrics were emitted for the completed cycle
        verify(metricsEmitter, atLeastOnce()).recordSuccess(any(SyncCycleResult.class));

        // Clean up
        runThread.join(2000);
        assertFalse(runThread.isAlive(), "Run thread should have exited after shutdown");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Integer> cycleDurations() {
        return Arbitraries.integers().between(10, 500);
    }
}
