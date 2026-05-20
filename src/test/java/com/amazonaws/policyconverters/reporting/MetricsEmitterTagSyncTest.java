package com.amazonaws.policyconverters.reporting;

import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.model.TagSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricsEmitterTagSyncTest {

    @Mock
    private CloudWatchClient cloudWatchClient;

    private MetricsEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new MetricsEmitter(cloudWatchClient, new ServerConfig(30, "INFO", "TestNamespace"));
    }

    // --- null result → no-op ---

    @Test
    void recordTagSync_nullResult_doesNotPublish() {
        emitter.recordTagSync(null);
        verifyNoInteractions(cloudWatchClient);
    }

    // --- success result ---

    @Test
    void recordTagSync_success_publishesTagSyncSuccess() {
        TagSyncResult result = TagSyncResult.success(500, 2, 1, 3, 1, 0);
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        assertNotNull(byName.get("TagSyncSuccess"), "Expected TagSyncSuccess metric");
        assertEquals(1.0, byName.get("TagSyncSuccess").value());
        assertEquals(StandardUnit.COUNT, byName.get("TagSyncSuccess").unit());
        assertNull(byName.get("TagSyncFailure"), "Should not have TagSyncFailure on success");
    }

    @Test
    void recordTagSync_success_publishesDuration() {
        TagSyncResult result = TagSyncResult.success(750, 0, 0, 0, 0, 0);
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        MetricDatum duration = byName.get("TagSyncDuration");
        assertNotNull(duration);
        assertEquals(750.0, duration.value());
        assertEquals(StandardUnit.MILLISECONDS, duration.unit());
    }

    @Test
    void recordTagSync_success_publishesCountMetrics() {
        TagSyncResult result = TagSyncResult.success(100, 2, 1, 3, 1, 0);
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        assertEquals(2.0, byName.get("TagsCreated").value());
        assertEquals(1.0, byName.get("TagsDeleted").value());
        assertEquals(3.0, byName.get("TagAttachmentsAdded").value());
        assertEquals(1.0, byName.get("TagAttachmentsRemoved").value());
    }

    @Test
    void recordTagSync_success_noPartialFailure_doesNotPublishPartialFailureMetric() {
        TagSyncResult result = TagSyncResult.success(100, 1, 0, 0, 0, 0);
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        assertNull(byName.get("TagSyncPartialFailure"), "Should not emit partial failure when failed=0");
    }

    @Test
    void recordTagSync_partialFailure_publishesBothSuccessAndPartialFailure() {
        TagSyncResult result = TagSyncResult.success(100, 1, 0, 0, 0, 2); // failed=2
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        assertNotNull(byName.get("TagSyncSuccess"), "Expected TagSyncSuccess");
        assertNotNull(byName.get("TagSyncPartialFailure"), "Expected TagSyncPartialFailure when failed>0");
        assertEquals(1.0, byName.get("TagSyncPartialFailure").value());
    }

    // --- failure result ---

    @Test
    void recordTagSync_failure_publishesTagSyncFailure() {
        TagSyncResult result = TagSyncResult.failure(200, new RuntimeException("LF down"));
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        assertNotNull(byName.get("TagSyncFailure"), "Expected TagSyncFailure metric");
        assertEquals(1.0, byName.get("TagSyncFailure").value());
        assertNull(byName.get("TagSyncSuccess"), "Should not have TagSyncSuccess on failure");
    }

    @Test
    void recordTagSync_failure_doesNotPublishPartialFailure() {
        TagSyncResult result = TagSyncResult.failure(100, new RuntimeException("err"));
        emitter.recordTagSync(result);

        Map<String, MetricDatum> byName = captureMetrics();
        assertNull(byName.get("TagSyncPartialFailure"), "Should not emit partial failure on full failure");
    }

    // --- all metrics have ServiceName dimension ---

    @Test
    void recordTagSync_success_allMetricsHaveServiceNameDimension() {
        TagSyncResult result = TagSyncResult.success(100, 1, 0, 0, 0, 0);
        emitter.recordTagSync(result);

        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());

        for (MetricDatum datum : captor.getValue().metricData()) {
            boolean hasServiceName = datum.dimensions().stream()
                    .anyMatch(d -> "ServiceName".equals(d.name()) && "conversion-server".equals(d.value()));
            assertTrue(hasServiceName, datum.metricName() + " missing ServiceName dimension");
        }
    }

    // --- single PutMetricData call ---

    @Test
    void recordTagSync_singlePutMetricDataCall() {
        TagSyncResult result = TagSyncResult.success(100, 1, 0, 0, 0, 0);
        emitter.recordTagSync(result);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    // --- CloudWatch error → no throw ---

    @Test
    void recordTagSync_cloudWatchError_doesNotThrow() {
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class)))
                .thenThrow(new RuntimeException("CloudWatch unavailable"));
        assertDoesNotThrow(() -> emitter.recordTagSync(TagSyncResult.success(100, 0, 0, 0, 0, 0)));
    }

    // --- helper ---

    private Map<String, MetricDatum> captureMetrics() {
        ArgumentCaptor<PutMetricDataRequest> captor = ArgumentCaptor.forClass(PutMetricDataRequest.class);
        verify(cloudWatchClient).putMetricData(captor.capture());
        return captor.getValue().metricData().stream()
                .collect(Collectors.toMap(MetricDatum::metricName, d -> d));
    }
}
