package com.amazonaws.policyconverters.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes operational metrics to CloudWatch after each sync cycle.
 * All metrics include a {@code ServiceName=conversion-server} dimension.
 * CloudWatch errors are caught and logged without failing the cycle.
 */
public class MetricsEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsEmitter.class);

    private static final String SERVICE_NAME_DIMENSION = "ServiceName";
    private static final String SERVICE_NAME_VALUE = "conversion-server";

    private final CloudWatchClient cloudWatchClient;
    private final String namespace;

    public MetricsEmitter(CloudWatchClient cloudWatchClient, ServerConfig serverConfig) {
        this.cloudWatchClient = cloudWatchClient;
        this.namespace = serverConfig.getMetricsNamespace();
    }

    /**
     * Records metrics for a successful sync cycle.
     * Publishes SyncCycleSuccess, SyncCycleDuration, PoliciesProcessed,
     * GrantsApplied, and RevocationsApplied in a single PutMetricData call.
     */
    public void recordSuccess(SyncCycleResult result) {
        Dimension serviceDimension = serviceDimension();
        List<MetricDatum> metrics = new ArrayList<>();

        metrics.add(datum("SyncCycleSuccess", 1.0, StandardUnit.COUNT, serviceDimension));
        metrics.add(datum("SyncCycleDuration", result.getDurationMs(), StandardUnit.MILLISECONDS, serviceDimension));
        metrics.addAll(commonMetrics(result, serviceDimension));

        publish(metrics);
    }

    /**
     * Records metrics for a failed sync cycle.
     * Publishes SyncCycleFailure, ErrorCount (with ErrorType dimension),
     * PoliciesProcessed, GrantsApplied, and RevocationsApplied in a single PutMetricData call.
     */
    public void recordFailure(SyncCycleResult result) {
        Dimension serviceDimension = serviceDimension();
        List<MetricDatum> metrics = new ArrayList<>();

        metrics.add(datum("SyncCycleFailure", 1.0, StandardUnit.COUNT, serviceDimension));

        String errorType = result.getErrorClass() != null ? result.getErrorClass() : "Unknown";
        Dimension errorTypeDimension = Dimension.builder()
                .name("ErrorType")
                .value(errorType)
                .build();
        metrics.add(MetricDatum.builder()
                .metricName("ErrorCount")
                .value(1.0)
                .unit(StandardUnit.COUNT)
                .dimensions(serviceDimension, errorTypeDimension)
                .build());

        metrics.addAll(commonMetrics(result, serviceDimension));

        publish(metrics);
    }

    private List<MetricDatum> commonMetrics(SyncCycleResult result, Dimension serviceDimension) {
        return List.of(
                datum("PoliciesProcessed", result.getPoliciesProcessed(), StandardUnit.COUNT, serviceDimension),
                datum("GrantsApplied", result.getGrantsApplied(), StandardUnit.COUNT, serviceDimension),
                datum("RevocationsApplied", result.getRevocationsApplied(), StandardUnit.COUNT, serviceDimension)
        );
    }

    private static MetricDatum datum(String name, double value, StandardUnit unit, Dimension... dimensions) {
        return MetricDatum.builder()
                .metricName(name)
                .value(value)
                .unit(unit)
                .dimensions(dimensions)
                .build();
    }

    private static Dimension serviceDimension() {
        return Dimension.builder()
                .name(SERVICE_NAME_DIMENSION)
                .value(SERVICE_NAME_VALUE)
                .build();
    }

    private void publish(List<MetricDatum> metrics) {
        try {
            cloudWatchClient.putMetricData(PutMetricDataRequest.builder()
                    .namespace(namespace)
                    .metricData(metrics)
                    .build());
        } catch (Exception e) {
            LOG.warn("Failed to publish CloudWatch metrics: {}", e.getMessage(), e);
        }
    }
}
