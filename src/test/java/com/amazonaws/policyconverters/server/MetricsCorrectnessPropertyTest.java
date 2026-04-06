package com.amazonaws.policyconverters.server;

import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Feature: conversion-server, Property 7: Metrics correctness per cycle result

/**
 * Property-based test verifying that MetricsEmitter publishes the correct set of
 * metrics for any randomly generated SyncCycleResult (success or failure).
 * <p>
 * **Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6**
 */
class MetricsCorrectnessPropertyTest {

    @Property(tries = 100)
    void successResultPublishesCorrectMetrics(
            @ForAll("successResults") SyncCycleResult result
    ) {
        CloudWatchClient mockClient = mock(CloudWatchClient.class);
        ServerConfig config = new ServerConfig(30, "INFO", "RangerLFSync");
        MetricsEmitter emitter = new MetricsEmitter(mockClient, config);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockClient).putMetricData(captor.capture());

        Map<String, MetricDatum> byName = captor.getValue().metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        // Requirement 6.2: SyncCycleSuccess=1 and SyncCycleDuration=durationMs
        assertTrue(byName.containsKey("SyncCycleSuccess"), "Missing SyncCycleSuccess metric");
        assertEquals(1.0, byName.get("SyncCycleSuccess").value());
        assertEquals(StandardUnit.COUNT, byName.get("SyncCycleSuccess").unit());

        assertTrue(byName.containsKey("SyncCycleDuration"), "Missing SyncCycleDuration metric");
        assertEquals((double) result.getDurationMs(), byName.get("SyncCycleDuration").value());
        assertEquals(StandardUnit.MILLISECONDS, byName.get("SyncCycleDuration").unit());

        // Requirement 6.4: PoliciesProcessed
        assertTrue(byName.containsKey("PoliciesProcessed"), "Missing PoliciesProcessed metric");
        assertEquals((double) result.getPoliciesProcessed(), byName.get("PoliciesProcessed").value());

        // Requirement 6.5: GrantsApplied and RevocationsApplied
        assertTrue(byName.containsKey("GrantsApplied"), "Missing GrantsApplied metric");
        assertEquals((double) result.getGrantsApplied(), byName.get("GrantsApplied").value());

        assertTrue(byName.containsKey("RevocationsApplied"), "Missing RevocationsApplied metric");
        assertEquals((double) result.getRevocationsApplied(), byName.get("RevocationsApplied").value());

        // Should not contain failure-specific metrics
        assertFalse(byName.containsKey("SyncCycleFailure"), "Success should not have SyncCycleFailure");
        assertFalse(byName.containsKey("ErrorCount"), "Success should not have ErrorCount");
    }

    @Property(tries = 100)
    void failureResultPublishesCorrectMetrics(
            @ForAll("failureResults") SyncCycleResult result
    ) {
        CloudWatchClient mockClient = mock(CloudWatchClient.class);
        ServerConfig config = new ServerConfig(30, "INFO", "RangerLFSync");
        MetricsEmitter emitter = new MetricsEmitter(mockClient, config);

        emitter.recordFailure(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockClient).putMetricData(captor.capture());

        Map<String, MetricDatum> byName = captor.getValue().metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        // Requirement 6.3: SyncCycleFailure=1
        assertTrue(byName.containsKey("SyncCycleFailure"), "Missing SyncCycleFailure metric");
        assertEquals(1.0, byName.get("SyncCycleFailure").value());
        assertEquals(StandardUnit.COUNT, byName.get("SyncCycleFailure").unit());

        // Requirement 6.6: ErrorCount=1 with ErrorType dimension
        assertTrue(byName.containsKey("ErrorCount"), "Missing ErrorCount metric");
        assertEquals(1.0, byName.get("ErrorCount").value());
        MetricDatum errorCount = byName.get("ErrorCount");
        boolean hasErrorType = errorCount.dimensions().stream()
                .anyMatch(d -> "ErrorType".equals(d.name())
                        && result.getErrorClass().equals(d.value()));
        assertTrue(hasErrorType,
                "ErrorCount must have ErrorType dimension matching error class: " + result.getErrorClass());

        // Requirement 6.4, 6.5: PoliciesProcessed, GrantsApplied, RevocationsApplied = 0 on failure
        assertTrue(byName.containsKey("PoliciesProcessed"), "Missing PoliciesProcessed metric");
        assertEquals(0.0, byName.get("PoliciesProcessed").value(),
                "PoliciesProcessed should be 0 on failure");

        assertTrue(byName.containsKey("GrantsApplied"), "Missing GrantsApplied metric");
        assertEquals(0.0, byName.get("GrantsApplied").value(),
                "GrantsApplied should be 0 on failure");

        assertTrue(byName.containsKey("RevocationsApplied"), "Missing RevocationsApplied metric");
        assertEquals(0.0, byName.get("RevocationsApplied").value(),
                "RevocationsApplied should be 0 on failure");

        // Should not contain success-specific metrics
        assertFalse(byName.containsKey("SyncCycleSuccess"), "Failure should not have SyncCycleSuccess");
        assertFalse(byName.containsKey("SyncCycleDuration"), "Failure should not have SyncCycleDuration");
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
}
