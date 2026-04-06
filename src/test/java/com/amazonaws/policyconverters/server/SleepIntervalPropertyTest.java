package com.amazonaws.policyconverters.server;

import net.jqwik.api.*;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Feature: conversion-server, Property 11: Sleep interval matches configuration

/**
 * Property-based test verifying that the actual sleep duration between
 * consecutive sync cycles matches the configured {@code policyRefreshIntervalMs}
 * within a ±50ms tolerance.
 * <p>
 * **Validates: Requirements 2.2**
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SleepIntervalPropertyTest {

    private static final long TOLERANCE_MS = 50;

    @Property(tries = 100)
    void sleepIntervalMatchesConfiguration(
            @ForAll("sleepIntervals") int sleepIntervalMs
    ) throws InterruptedException {
        MetricsEmitter metricsEmitter = mock(MetricsEmitter.class);
        ServerConfig serverConfig = new ServerConfig(5, "INFO", "TestNamespace");

        CountDownLatch firstCycleStarted = new CountDownLatch(1);
        CountDownLatch secondCycleStarted = new CountDownLatch(1);
        AtomicLong firstCycleTimestamp = new AtomicLong(0);
        AtomicLong secondCycleTimestamp = new AtomicLong(0);

        // Near-zero execution time executor: records timestamps at cycle start
        SyncCycleExecutor executor = () -> {
            if (firstCycleStarted.getCount() > 0) {
                firstCycleTimestamp.set(System.nanoTime());
                firstCycleStarted.countDown();
            } else if (secondCycleStarted.getCount() > 0) {
                secondCycleTimestamp.set(System.nanoTime());
                secondCycleStarted.countDown();
            }
            return SyncCycleResult.success(1, 1, 0, 0, 0);
        };

        ServerLifecycle lifecycle = new ServerLifecycle(
                executor, metricsEmitter, serverConfig, sleepIntervalMs);

        Thread runThread = new Thread(lifecycle::run, "run-loop");
        runThread.setDaemon(true);
        runThread.start();

        // Wait for both cycles to start
        assertTrue(firstCycleStarted.await(5, TimeUnit.SECONDS),
                "First cycle should start within 5 seconds");
        assertTrue(secondCycleStarted.await(sleepIntervalMs + 2000, TimeUnit.MILLISECONDS),
                "Second cycle should start within sleep interval + 2s buffer");

        // Shut down the lifecycle
        lifecycle.shutdown();
        runThread.join(2000);

        // Measure the gap between the two cycle starts
        long gapNanos = secondCycleTimestamp.get() - firstCycleTimestamp.get();
        long gapMs = TimeUnit.NANOSECONDS.toMillis(gapNanos);

        // The gap should be approximately sleepIntervalMs (cycle execution is near-zero).
        // Allow tolerance for scheduling jitter and the tiny cycle execution time.
        assertTrue(gapMs >= sleepIntervalMs - TOLERANCE_MS,
                "Gap between cycles (" + gapMs + "ms) should be >= configured interval ("
                        + sleepIntervalMs + "ms) minus tolerance (" + TOLERANCE_MS + "ms)");
        assertTrue(gapMs <= sleepIntervalMs + TOLERANCE_MS,
                "Gap between cycles (" + gapMs + "ms) should be <= configured interval ("
                        + sleepIntervalMs + "ms) plus tolerance (" + TOLERANCE_MS + "ms)");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<Integer> sleepIntervals() {
        return Arbitraries.integers().between(50, 300);
    }
}
