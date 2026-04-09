package com.amazonaws.policyconverters.reporting;
import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.model.SyncCycleResult;

import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Feature: conversion-server, Property 8: ServiceName dimension invariant

/**
 * Property-based test verifying that every MetricDatum published by MetricsEmitter
 * includes a ServiceName dimension with value "conversion-server".
 * <p>
 * **Validates: Requirements 6.7**
 */
class ServiceNameDimensionPropertyTest {

    @Property(tries = 100)
    void allSuccessMetricsIncludeServiceNameDimension(
            @ForAll("successResults") SyncCycleResult result
    ) {
        CloudWatchClient mockClient = mock(CloudWatchClient.class);
        ServerConfig config = new ServerConfig(30, "INFO", "RangerLFSync");
        MetricsEmitter emitter = new MetricsEmitter(mockClient, config);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockClient).putMetricData(captor.capture());

        List<MetricDatum> metrics = captor.getValue().metricData();
        assertFalse(metrics.isEmpty(), "Expected at least one metric datum");

        for (MetricDatum datum : metrics) {
            boolean hasServiceName = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceName".equals(d.name())
                            && "conversion-server".equals(d.value()));
            assertTrue(hasServiceName,
                    "MetricDatum '" + datum.metricName()
                            + "' is missing ServiceName=conversion-server dimension. "
                            + "Dimensions: " + datum.dimensions());
        }
    }

    @Property(tries = 100)
    void allFailureMetricsIncludeServiceNameDimension(
            @ForAll("failureResults") SyncCycleResult result
    ) {
        CloudWatchClient mockClient = mock(CloudWatchClient.class);
        ServerConfig config = new ServerConfig(30, "INFO", "RangerLFSync");
        MetricsEmitter emitter = new MetricsEmitter(mockClient, config);

        emitter.recordFailure(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockClient).putMetricData(captor.capture());

        List<MetricDatum> metrics = captor.getValue().metricData();
        assertFalse(metrics.isEmpty(), "Expected at least one metric datum");

        for (MetricDatum datum : metrics) {
            boolean hasServiceName = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceName".equals(d.name())
                            && "conversion-server".equals(d.value()));
            assertTrue(hasServiceName,
                    "MetricDatum '" + datum.metricName()
                            + "' is missing ServiceName=conversion-server dimension. "
                            + "Dimensions: " + datum.dimensions());
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
}
