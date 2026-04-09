package com.amazonaws.policyconverters.app;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import com.amazonaws.policyconverters.config.ServerConfig;

// Feature: conversion-server, Property 6: Cycle result log completeness

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.*;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Property-based test verifying that ServerLifecycle logs contain all required
 * fields for both successful and failed sync cycles.
 * <p>
 * For success: cycle number, duration, policiesProcessed, grantsApplied,
 * revocationsApplied, policiesSkipped.
 * For failure: cycle number, error class name, error message.
 * <p>
 * **Validates: Requirements 5.2, 5.3**
 */
class CycleLogCompletenessPropertyTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger lifecycleLogger;

    private void setUpAppender() {
        lifecycleLogger = (Logger) LoggerFactory.getLogger(ServerLifecycle.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        lifecycleLogger.addAppender(listAppender);
    }

    private void tearDownAppender() {
        if (lifecycleLogger != null && listAppender != null) {
            lifecycleLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Property(tries = 100)
    void successCycleLogContainsAllRequiredFields(
            @ForAll("successResults") SyncCycleResult result
    ) {
        setUpAppender();
        try {
            MetricsEmitter metricsEmitter = mock(MetricsEmitter.class);
            ServerConfig config = new ServerConfig(30, "INFO", "TestNS");
            SyncCycleExecutor executor = () -> result;
            ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, config, 1000);

            lifecycle.executeCycle();

            List<ILoggingEvent> infoLogs = listAppender.list.stream()
                    .filter(e -> e.getLevel().toString().equals("INFO"))
                    .collect(Collectors.toList());

            // Find the completion log (not the "starting at" log)
            List<ILoggingEvent> completionLogs = infoLogs.stream()
                    .filter(e -> e.getFormattedMessage().contains("completed successfully"))
                    .collect(Collectors.toList());

            assertFalse(completionLogs.isEmpty(),
                    "Expected at least one INFO completion log, got: " + formatLogs(infoLogs));

            String logMessage = completionLogs.get(0).getFormattedMessage();

            // Verify cycle number is present (cycle counter starts at 1 for first call)
            assertTrue(logMessage.contains(String.valueOf(lifecycle.getCycleCount())),
                    "Log should contain cycle number, got: " + logMessage);

            // Verify duration in ms
            assertTrue(logMessage.contains("durationMs="),
                    "Log should contain durationMs, got: " + logMessage);

            // Verify policiesProcessed count
            assertTrue(logMessage.contains("policiesProcessed=" + result.getPoliciesProcessed()),
                    "Log should contain policiesProcessed=" + result.getPoliciesProcessed() + ", got: " + logMessage);

            // Verify grantsApplied count
            assertTrue(logMessage.contains("grantsApplied=" + result.getGrantsApplied()),
                    "Log should contain grantsApplied=" + result.getGrantsApplied() + ", got: " + logMessage);

            // Verify revocationsApplied count
            assertTrue(logMessage.contains("revocationsApplied=" + result.getRevocationsApplied()),
                    "Log should contain revocationsApplied=" + result.getRevocationsApplied() + ", got: " + logMessage);

            // Verify policiesSkipped count
            assertTrue(logMessage.contains("policiesSkipped=" + result.getPoliciesSkipped()),
                    "Log should contain policiesSkipped=" + result.getPoliciesSkipped() + ", got: " + logMessage);
        } finally {
            tearDownAppender();
        }
    }

    @Property(tries = 100)
    void failureCycleLogContainsAllRequiredFields(
            @ForAll("failureResults") SyncCycleResult result
    ) {
        setUpAppender();
        try {
            MetricsEmitter metricsEmitter = mock(MetricsEmitter.class);
            ServerConfig config = new ServerConfig(30, "INFO", "TestNS");
            SyncCycleExecutor executor = () -> result;
            ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, config, 1000);

            lifecycle.executeCycle();

            List<ILoggingEvent> errorLogs = listAppender.list.stream()
                    .filter(e -> e.getLevel().toString().equals("ERROR"))
                    .collect(Collectors.toList());

            assertFalse(errorLogs.isEmpty(),
                    "Expected at least one ERROR log for failure cycle");

            ILoggingEvent errorEvent = errorLogs.get(0);
            String logMessage = errorEvent.getFormattedMessage();

            // Verify cycle number
            assertTrue(logMessage.contains(String.valueOf(lifecycle.getCycleCount())),
                    "Error log should contain cycle number, got: " + logMessage);

            // Verify error class name
            assertTrue(logMessage.contains(result.getErrorClass()),
                    "Error log should contain error class '" + result.getErrorClass() + "', got: " + logMessage);

            // Verify error message
            assertTrue(logMessage.contains(result.getErrorMessage()),
                    "Error log should contain error message '" + result.getErrorMessage() + "', got: " + logMessage);
        } finally {
            tearDownAppender();
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<SyncCycleResult> successResults() {
        Arbitrary<Long> durations = Arbitraries.longs().between(0, 100_000);
        Arbitrary<Integer> policiesProcessed = Arbitraries.integers().between(0, 1_000);
        Arbitrary<Integer> grantsApplied = Arbitraries.integers().between(0, 500);
        Arbitrary<Integer> revocationsApplied = Arbitraries.integers().between(0, 500);
        Arbitrary<Integer> policiesSkipped = Arbitraries.integers().between(0, 100);

        return Combinators.combine(durations, policiesProcessed, grantsApplied, revocationsApplied, policiesSkipped)
                .as(SyncCycleResult::success);
    }

    @Provide
    Arbitrary<SyncCycleResult> failureResults() {
        Arbitrary<Long> durations = Arbitraries.longs().between(0, 100_000);
        Arbitrary<Throwable> exceptions = Arbitraries.of(
                new RuntimeException("connection refused"),
                new IllegalStateException("invalid state"),
                new NullPointerException("null reference"),
                new IllegalArgumentException("bad argument"),
                new UnsupportedOperationException("not supported")
        );

        return Combinators.combine(durations, exceptions)
                .as(SyncCycleResult::failure);
    }

    private String formatLogs(List<ILoggingEvent> events) {
        return events.stream()
                .map(e -> "[" + e.getLevel() + "] " + e.getFormattedMessage())
                .collect(Collectors.joining("\n"));
    }
}
