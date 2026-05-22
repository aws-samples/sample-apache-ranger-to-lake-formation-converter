package com.amazonaws.policyconverters.reporting;
import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.model.WildcardRefreshResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
class MetricsEmitterTest {

    @Mock
    private CloudWatchClient cloudWatchClient;

    private MetricsEmitter emitter;

    @BeforeEach
    void setUp() {
        ServerConfig config = new ServerConfig(30, "INFO", "TestNamespace");
        emitter = new MetricsEmitter(cloudWatchClient, config);
    }

    @Test
    void recordSuccess_publishesCorrectMetrics() {
        SyncCycleResult result = SyncCycleResult.success(1500, 10, 3, 2, 1);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        PutMetricDataRequest request = captor.getValue();
        assertEquals("TestNamespace", request.namespace());

        Map<String, MetricDatum> byName = request.metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        assertEquals(5, byName.size());

        MetricDatum success = byName.get("SyncCycleSuccess");
        assertEquals(1.0, success.value());
        assertEquals(StandardUnit.COUNT, success.unit());

        MetricDatum duration = byName.get("SyncCycleDuration");
        assertEquals(1500.0, duration.value());
        assertEquals(StandardUnit.MILLISECONDS, duration.unit());

        MetricDatum policies = byName.get("PoliciesProcessed");
        assertEquals(10.0, policies.value());

        MetricDatum grants = byName.get("GrantsApplied");
        assertEquals(3.0, grants.value());

        MetricDatum revocations = byName.get("RevocationsApplied");
        assertEquals(2.0, revocations.value());
    }

    @Test
    void recordSuccess_allMetricsHaveServiceNameDimension() {
        SyncCycleResult result = SyncCycleResult.success(100, 1, 0, 0, 0);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceName = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceName".equals(d.name()) && "conversion-server".equals(d.value()));
            assertTrue(hasServiceName, "Metric " + datum.metricName() + " missing ServiceName dimension");
        }
    }

    @Test
    void recordFailure_publishesCorrectMetrics() {
        RuntimeException error = new RuntimeException("connection refused");
        SyncCycleResult result = SyncCycleResult.failure(500, error);

        emitter.recordFailure(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        PutMetricDataRequest request = captor.getValue();
        assertEquals("TestNamespace", request.namespace());

        Map<String, MetricDatum> byName = request.metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        assertEquals(5, byName.size());

        MetricDatum failure = byName.get("SyncCycleFailure");
        assertEquals(1.0, failure.value());
        assertEquals(StandardUnit.COUNT, failure.unit());

        MetricDatum errorCount = byName.get("ErrorCount");
        assertEquals(1.0, errorCount.value());
        boolean hasErrorType = errorCount.dimensions().stream()
                .anyMatch(d -> "ErrorType".equals(d.name())
                        && "java.lang.RuntimeException".equals(d.value()));
        assertTrue(hasErrorType, "ErrorCount missing ErrorType dimension");

        assertNotNull(byName.get("PoliciesProcessed"));
        assertNotNull(byName.get("GrantsApplied"));
        assertNotNull(byName.get("RevocationsApplied"));
    }

    @Test
    void recordFailure_errorCountHasServiceNameDimension() {
        SyncCycleResult result = SyncCycleResult.failure(100, new RuntimeException("err"));

        emitter.recordFailure(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceName = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceName".equals(d.name()) && "conversion-server".equals(d.value()));
            assertTrue(hasServiceName, "Metric " + datum.metricName() + " missing ServiceName dimension");
        }
    }

    @Test
    void recordSuccess_singlePutMetricDataCall() {
        SyncCycleResult result = SyncCycleResult.success(100, 5, 2, 1, 0);

        emitter.recordSuccess(result);

        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void recordFailure_singlePutMetricDataCall() {
        SyncCycleResult result = SyncCycleResult.failure(100, new RuntimeException("err"));

        emitter.recordFailure(result);

        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void cloudWatchError_doesNotThrow() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));

        SyncCycleResult result = SyncCycleResult.success(100, 1, 0, 0, 0);

        assertDoesNotThrow(() -> emitter.recordSuccess(result));
    }

    @Test
    void cloudWatchError_onFailure_doesNotThrow() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));

        SyncCycleResult result = SyncCycleResult.failure(100, new RuntimeException("err"));

        assertDoesNotThrow(() -> emitter.recordFailure(result));
    }

    @Test
    void usesConfiguredNamespace() {
        ServerConfig config = new ServerConfig(30, "INFO", "CustomNamespace");
        MetricsEmitter customEmitter = new MetricsEmitter(cloudWatchClient, config);

        customEmitter.recordSuccess(SyncCycleResult.success(100, 1, 0, 0, 0));

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());
        assertEquals("CustomNamespace", captor.getValue().namespace());
    }

    @Test
    void usesDefaultNamespace() {
        ServerConfig config = new ServerConfig(null, null, null);
        MetricsEmitter defaultEmitter = new MetricsEmitter(cloudWatchClient, config);

        defaultEmitter.recordSuccess(SyncCycleResult.success(100, 1, 0, 0, 0));

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());
        assertEquals("RangerLFSync", captor.getValue().namespace());
    }

    @Test
    void recordSuccess_zeroCounts() {
        SyncCycleResult result = SyncCycleResult.success(50, 0, 0, 0, 0);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        Map<String, MetricDatum> byName = captor.getValue().metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        assertEquals(0.0, byName.get("PoliciesProcessed").value());
        assertEquals(0.0, byName.get("GrantsApplied").value());
        assertEquals(0.0, byName.get("RevocationsApplied").value());
    }

    // --- Wildcard Refresh Metrics Tests (Requirement 6.4) ---

    @Test
    void recordWildcardRefresh_success_publishesCorrectMetrics() {
        WildcardRefreshResult result = WildcardRefreshResult.success(2500, 5, 3, 1, 4);

        emitter.recordWildcardRefresh(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        PutMetricDataRequest request = captor.getValue();
        assertEquals("TestNamespace", request.namespace());

        Map<String, MetricDatum> byName = request.metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        assertEquals(3, byName.size());

        MetricDatum success = byName.get("WildcardRefreshSuccess");
        assertNotNull(success, "Expected WildcardRefreshSuccess metric");
        assertEquals(1.0, success.value());
        assertEquals(StandardUnit.COUNT, success.unit());

        MetricDatum duration = byName.get("WildcardRefreshDuration");
        assertNotNull(duration, "Expected WildcardRefreshDuration metric");
        assertEquals(2500.0, duration.value());
        assertEquals(StandardUnit.MILLISECONDS, duration.unit());

        MetricDatum deltaOps = byName.get("WildcardRefreshDeltaOperations");
        assertNotNull(deltaOps, "Expected WildcardRefreshDeltaOperations metric");
        assertEquals(4.0, deltaOps.value()); // newGrants(3) + revocations(1)
        assertEquals(StandardUnit.COUNT, deltaOps.unit());
    }

    @Test
    void recordWildcardRefresh_failure_publishesCorrectMetrics() {
        WildcardRefreshResult result = WildcardRefreshResult.failure(800, new RuntimeException("Glue timeout"));

        emitter.recordWildcardRefresh(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        PutMetricDataRequest request = captor.getValue();
        assertEquals("TestNamespace", request.namespace());

        Map<String, MetricDatum> byName = request.metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        assertEquals(3, byName.size());

        MetricDatum failure = byName.get("WildcardRefreshFailure");
        assertNotNull(failure, "Expected WildcardRefreshFailure metric");
        assertEquals(1.0, failure.value());
        assertEquals(StandardUnit.COUNT, failure.unit());

        MetricDatum duration = byName.get("WildcardRefreshDuration");
        assertNotNull(duration, "Expected WildcardRefreshDuration metric");
        assertEquals(800.0, duration.value());
        assertEquals(StandardUnit.MILLISECONDS, duration.unit());

        MetricDatum deltaOps = byName.get("WildcardRefreshDeltaOperations");
        assertNotNull(deltaOps, "Expected WildcardRefreshDeltaOperations metric");
        assertEquals(0.0, deltaOps.value()); // failure: newGrants(0) + revocations(0)
        assertEquals(StandardUnit.COUNT, deltaOps.unit());
    }

    @Test
    void recordWildcardRefresh_allMetricsHaveServiceNameDimension() {
        WildcardRefreshResult result = WildcardRefreshResult.success(100, 2, 1, 0, 1);

        emitter.recordWildcardRefresh(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceName = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceName".equals(d.name()) && "conversion-server".equals(d.value()));
            assertTrue(hasServiceName, "Metric " + datum.metricName() + " missing ServiceName dimension");
        }
    }

    @Test
    void recordWildcardRefresh_singlePutMetricDataCall() {
        WildcardRefreshResult result = WildcardRefreshResult.success(100, 1, 0, 0, 1);

        emitter.recordWildcardRefresh(result);

        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void recordWildcardRefresh_cloudWatchError_doesNotThrow() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));

        WildcardRefreshResult result = WildcardRefreshResult.success(100, 1, 0, 0, 1);

        assertDoesNotThrow(() -> emitter.recordWildcardRefresh(result));
    }

    // --- ServiceType Dimension Tests (Requirement 7.7) ---

    @Test
    void recordSuccess_withServiceType_addsServiceTypeDimensionToCommonMetrics() {
        SyncCycleResult result = SyncCycleResult.success(1500, 10, 3, 2, 1);

        emitter.recordSuccess(result, "hive");

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        Map<String, MetricDatum> byName = captor.getValue().metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        // Common metrics should have ServiceType dimension
        for (String metricName : List.of("PoliciesProcessed", "GrantsApplied", "RevocationsApplied")) {
            MetricDatum datum = byName.get(metricName);
            assertNotNull(datum, "Expected " + metricName + " metric");
            boolean hasServiceType = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceType".equals(d.name()) && "hive".equals(d.value()));
            assertTrue(hasServiceType, metricName + " missing ServiceType dimension");
        }

        // SyncCycleSuccess and SyncCycleDuration should NOT have ServiceType dimension
        for (String metricName : List.of("SyncCycleSuccess", "SyncCycleDuration")) {
            MetricDatum datum = byName.get(metricName);
            assertNotNull(datum, "Expected " + metricName + " metric");
            boolean hasServiceType = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceType".equals(d.name()));
            assertFalse(hasServiceType, metricName + " should not have ServiceType dimension");
        }
    }

    @Test
    void recordSuccess_withNullServiceType_behavesLikeNoServiceType() {
        SyncCycleResult result = SyncCycleResult.success(100, 5, 1, 0, 0);

        emitter.recordSuccess(result, null);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceType = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceType".equals(d.name()));
            assertFalse(hasServiceType, datum.metricName() + " should not have ServiceType when null");
        }
    }

    @Test
    void recordFailure_withServiceType_addsServiceTypeDimensionToCommonMetrics() {
        SyncCycleResult result = SyncCycleResult.failure(500, new RuntimeException("err"));

        emitter.recordFailure(result, "presto");

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        Map<String, MetricDatum> byName = captor.getValue().metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));

        // Common metrics should have ServiceType dimension
        for (String metricName : List.of("PoliciesProcessed", "GrantsApplied", "RevocationsApplied")) {
            MetricDatum datum = byName.get(metricName);
            assertNotNull(datum, "Expected " + metricName + " metric");
            boolean hasServiceType = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceType".equals(d.name()) && "presto".equals(d.value()));
            assertTrue(hasServiceType, metricName + " missing ServiceType dimension");
        }

        // SyncCycleFailure and ErrorCount should NOT have ServiceType dimension
        MetricDatum failure = byName.get("SyncCycleFailure");
        assertFalse(failure.dimensions().stream().anyMatch(d -> "ServiceType".equals(d.name())),
                "SyncCycleFailure should not have ServiceType dimension");
    }

    @Test
    void recordFailure_withNullServiceType_behavesLikeNoServiceType() {
        SyncCycleResult result = SyncCycleResult.failure(100, new RuntimeException("err"));

        emitter.recordFailure(result, null);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceType = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceType".equals(d.name()));
            assertFalse(hasServiceType, datum.metricName() + " should not have ServiceType when null");
        }
    }

    @Test
    void recordPluginFetchFailure_publishesMetricWithServiceTypeDimension() {
        emitter.recordPluginFetchFailure("hive");

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        PutMetricDataRequest request = captor.getValue();
        assertEquals("TestNamespace", request.namespace());
        assertEquals(1, request.metricData().size());

        MetricDatum datum = request.metricData().get(0);
        assertEquals("PluginFetchFailure", datum.metricName());
        assertEquals(1.0, datum.value());
        assertEquals(StandardUnit.COUNT, datum.unit());

        boolean hasServiceName = datum.dimensions().stream()
                .anyMatch(d -> "ServiceName".equals(d.name()) && "conversion-server".equals(d.value()));
        assertTrue(hasServiceName, "PluginFetchFailure missing ServiceName dimension");

        boolean hasServiceType = datum.dimensions().stream()
                .anyMatch(d -> "ServiceType".equals(d.name()) && "hive".equals(d.value()));
        assertTrue(hasServiceType, "PluginFetchFailure missing ServiceType dimension");
    }

    @Test
    void recordPluginFetchFailure_withNullServiceType_usesUnknown() {
        emitter.recordPluginFetchFailure(null);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        MetricDatum datum = captor.getValue().metricData().get(0);
        boolean hasUnknown = datum.dimensions().stream()
                .anyMatch(d -> "ServiceType".equals(d.name()) && "unknown".equals(d.value()));
        assertTrue(hasUnknown, "PluginFetchFailure should use 'unknown' for null serviceType");
    }

    @Test
    void recordPluginFetchFailure_cloudWatchError_doesNotThrow() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));

        assertDoesNotThrow(() -> emitter.recordPluginFetchFailure("trino"));
    }

    @Test
    void recordSuccess_withServiceType_backwardCompatible_existingMethodStillWorks() {
        // Verify the original no-arg overload still works without ServiceType
        SyncCycleResult result = SyncCycleResult.success(100, 5, 1, 0, 0);

        emitter.recordSuccess(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceType = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceType".equals(d.name()));
            assertFalse(hasServiceType, datum.metricName() + " should not have ServiceType in backward-compat mode");
        }
    }

    // --- recordUnmappedPrincipal tests ---

    @Test
    void recordUnmappedPrincipal_publishesCorrectMetric() {
        emitter.recordUnmappedPrincipal("user");

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        PutMetricDataRequest request = captor.getValue();
        assertEquals("TestNamespace", request.namespace());
        assertEquals(1, request.metricData().size());

        MetricDatum datum = request.metricData().get(0);
        assertEquals("UnmappedPrincipal", datum.metricName());
        assertEquals(1.0, datum.value());
        assertEquals(StandardUnit.COUNT, datum.unit());

        boolean hasServiceName = datum.dimensions().stream()
                .anyMatch(d -> "ServiceName".equals(d.name()) && "conversion-server".equals(d.value()));
        assertTrue(hasServiceName, "UnmappedPrincipal missing ServiceName dimension");

        boolean hasPrincipalType = datum.dimensions().stream()
                .anyMatch(d -> "PrincipalType".equals(d.name()) && "user".equals(d.value()));
        assertTrue(hasPrincipalType, "UnmappedPrincipal missing PrincipalType=user dimension");
    }

    @Test
    void recordUnmappedPrincipal_withGroupType_hasPrincipalTypeDimension() {
        emitter.recordUnmappedPrincipal("group");

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        MetricDatum datum = captor.getValue().metricData().get(0);
        assertEquals("UnmappedPrincipal", datum.metricName());

        boolean hasPrincipalType = datum.dimensions().stream()
                .anyMatch(d -> "PrincipalType".equals(d.name()) && "group".equals(d.value()));
        assertTrue(hasPrincipalType, "UnmappedPrincipal missing PrincipalType=group dimension");
    }

    @Test
    void recordUnmappedPrincipal_nullInput_publishesWithNullString() {
        emitter.recordUnmappedPrincipal(null);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        MetricDatum datum = captor.getValue().metricData().get(0);
        assertEquals("UnmappedPrincipal", datum.metricName());
        assertEquals(1.0, datum.value());

        boolean hasNullString = datum.dimensions().stream()
                .anyMatch(d -> "PrincipalType".equals(d.name()) && "null".equals(d.value()));
        assertTrue(hasNullString, "UnmappedPrincipal should use 'null' string for null principalType");
    }

    @Test
    void recordUnmappedPrincipal_cloudWatchError_doesNotThrow() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));

        assertDoesNotThrow(() -> emitter.recordUnmappedPrincipal("role"));
    }
}
