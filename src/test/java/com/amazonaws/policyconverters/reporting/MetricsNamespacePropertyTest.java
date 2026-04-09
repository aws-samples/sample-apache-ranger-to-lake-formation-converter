package com.amazonaws.policyconverters.reporting;
import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.model.SyncCycleResult;

import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Feature: conversion-server, Property 9: Metrics namespace configurability

/**
 * Property-based test verifying that MetricsEmitter uses the configured namespace
 * from ServerConfig for all PutMetricData calls.
 * <p>
 * **Validates: Requirements 6.1**
 */
class MetricsNamespacePropertyTest {

    @Property(tries = 100)
    void putMetricDataUsesConfiguredNamespaceOnSuccess(
            @ForAll("namespaces") String namespace,
            @ForAll("successResults") SyncCycleResult result
    ) {
        CloudWatchClient mockClient = mock(CloudWatchClient.class);
        ServerConfig config = new ServerConfig(30, "INFO", namespace);
        MetricsEmitter emitter = new MetricsEmitter(mockClient, config);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockClient).putMetricData(captor.capture());

        assertEquals(namespace, captor.getValue().namespace(),
                "PutMetricData namespace should match the configured namespace");
    }

    @Property(tries = 100)
    void putMetricDataUsesConfiguredNamespaceOnFailure(
            @ForAll("namespaces") String namespace,
            @ForAll("failureResults") SyncCycleResult result
    ) {
        CloudWatchClient mockClient = mock(CloudWatchClient.class);
        ServerConfig config = new ServerConfig(30, "INFO", namespace);
        MetricsEmitter emitter = new MetricsEmitter(mockClient, config);

        emitter.recordFailure(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(mockClient).putMetricData(captor.capture());

        assertEquals(namespace, captor.getValue().namespace(),
                "PutMetricData namespace should match the configured namespace");
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> namespaces() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(50);
    }

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
