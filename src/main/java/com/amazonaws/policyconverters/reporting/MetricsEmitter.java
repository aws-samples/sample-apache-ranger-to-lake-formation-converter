package com.amazonaws.policyconverters.reporting;

import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.model.TagSyncResult;
import com.amazonaws.policyconverters.model.WildcardRefreshResult;
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
    private static final String SERVICE_TYPE_DIMENSION = "ServiceType";

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
     * Records metrics for a successful sync cycle with a ServiceType dimension.
     * Publishes the same metrics as {@link #recordSuccess(SyncCycleResult)} but adds
     * a {@code ServiceType} dimension to PoliciesProcessed, GrantsApplied, and RevocationsApplied.
     *
     * @param result      the sync cycle result
     * @param serviceType the Ranger service type (e.g., "hive", "presto")
     */
    public void recordSuccess(SyncCycleResult result, String serviceType) {
        Dimension serviceDimension = serviceDimension();
        List<MetricDatum> metrics = new ArrayList<>();

        metrics.add(datum("SyncCycleSuccess", 1.0, StandardUnit.COUNT, serviceDimension));
        metrics.add(datum("SyncCycleDuration", result.getDurationMs(), StandardUnit.MILLISECONDS, serviceDimension));

        if (serviceType != null) {
            Dimension serviceTypeDimension = serviceTypeDimension(serviceType);
            metrics.addAll(commonMetrics(result, serviceDimension, serviceTypeDimension));
        } else {
            metrics.addAll(commonMetrics(result, serviceDimension));
        }

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

    /**
     * Records metrics for a failed sync cycle with a ServiceType dimension.
     * Publishes the same metrics as {@link #recordFailure(SyncCycleResult)} but adds
     * a {@code ServiceType} dimension to PoliciesProcessed, GrantsApplied, and RevocationsApplied.
     *
     * @param result      the sync cycle result
     * @param serviceType the Ranger service type (e.g., "hive", "presto")
     */
    public void recordFailure(SyncCycleResult result, String serviceType) {
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

        if (serviceType != null) {
            Dimension serviceTypeDimension = serviceTypeDimension(serviceType);
            metrics.addAll(commonMetrics(result, serviceDimension, serviceTypeDimension));
        } else {
            metrics.addAll(commonMetrics(result, serviceDimension));
        }

        publish(metrics);
    }

    /**
     * Records a metric when a Ranger plugin fails to fetch policies.
     * Publishes PluginFetchFailure count with a ServiceType dimension
     * so alarms can be configured per service in CloudWatch.
     *
     * @param serviceType the Ranger service type that failed (e.g., "hive", "presto")
     */
    public void recordPluginFetchFailure(String serviceType) {
        Dimension serviceDimension = serviceDimension();
        Dimension serviceTypeDimension = serviceTypeDimension(
                serviceType != null ? serviceType : "unknown");
        List<MetricDatum> metrics = List.of(
                MetricDatum.builder()
                        .metricName("PluginFetchFailure")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                        .dimensions(serviceDimension, serviceTypeDimension)
                        .build()
        );
        publish(metrics);
    }

    private List<MetricDatum> commonMetrics(SyncCycleResult result, Dimension serviceDimension) {
        return List.of(
                datum("PoliciesProcessed", result.getPoliciesProcessed(), StandardUnit.COUNT, serviceDimension),
                datum("GrantsApplied", result.getGrantsApplied(), StandardUnit.COUNT, serviceDimension),
                datum("RevocationsApplied", result.getRevocationsApplied(), StandardUnit.COUNT, serviceDimension)
        );
    }

    private List<MetricDatum> commonMetrics(SyncCycleResult result, Dimension serviceDimension,
                                            Dimension serviceTypeDimension) {
        return List.of(
                datum("PoliciesProcessed", result.getPoliciesProcessed(), StandardUnit.COUNT,
                        serviceDimension, serviceTypeDimension),
                datum("GrantsApplied", result.getGrantsApplied(), StandardUnit.COUNT,
                        serviceDimension, serviceTypeDimension),
                datum("RevocationsApplied", result.getRevocationsApplied(), StandardUnit.COUNT,
                        serviceDimension, serviceTypeDimension)
        );
    }

    /**
     * Records metrics for a wildcard refresh cycle.
     * Publishes WildcardRefreshSuccess or WildcardRefreshFailure (count=1),
     * WildcardRefreshDuration (milliseconds), and WildcardRefreshDeltaOperations
     * (count of newGrants + revocations) in a single PutMetricData call.
     */
    public void recordWildcardRefresh(WildcardRefreshResult result) {
        Dimension serviceDimension = serviceDimension();
        List<MetricDatum> metrics = new ArrayList<>();

        if (result.isSuccess()) {
            metrics.add(datum("WildcardRefreshSuccess", 1.0, StandardUnit.COUNT, serviceDimension));
        } else {
            metrics.add(datum("WildcardRefreshFailure", 1.0, StandardUnit.COUNT, serviceDimension));
        }

        metrics.add(datum("WildcardRefreshDuration", result.getDurationMs(), StandardUnit.MILLISECONDS, serviceDimension));
        metrics.add(datum("WildcardRefreshDeltaOperations",
                result.getNewGrants() + result.getRevocations(), StandardUnit.COUNT, serviceDimension));

        publish(metrics);
    }

    /**
     * Records metrics for a tag metadata sync cycle.
     * Emits TagSyncSuccess or TagSyncFailure, TagSyncDuration, individual count metrics,
     * and TagSyncPartialFailure when the cycle succeeded overall but had individual failures.
     */
    public void recordTagSync(TagSyncResult result) {
        if (result == null) return;
        Dimension serviceDimension = serviceDimension();
        List<MetricDatum> metrics = new ArrayList<>();

        if (result.isSuccess()) {
            metrics.add(datum("TagSyncSuccess", 1.0, StandardUnit.COUNT, serviceDimension));
        } else {
            metrics.add(datum("TagSyncFailure", 1.0, StandardUnit.COUNT, serviceDimension));
        }

        metrics.add(datum("TagSyncDuration", result.getDurationMs(), StandardUnit.MILLISECONDS, serviceDimension));
        metrics.add(datum("TagsCreated", result.getTagsCreated(), StandardUnit.COUNT, serviceDimension));
        metrics.add(datum("TagsDeleted", result.getTagsDeleted(), StandardUnit.COUNT, serviceDimension));
        metrics.add(datum("TagAttachmentsAdded", result.getAttachmentsAdded(), StandardUnit.COUNT, serviceDimension));
        metrics.add(datum("TagAttachmentsRemoved", result.getAttachmentsRemoved(), StandardUnit.COUNT, serviceDimension));

        if (result.isSuccess() && result.getFailed() > 0) {
            metrics.add(datum("TagSyncPartialFailure", 1.0, StandardUnit.COUNT, serviceDimension));
        }

        publish(metrics);
    }

    /**
     * Records a metric when a Ranger access type cannot be mapped.
     * Publishes UnmappedAccessType count with an AccessType dimension
     * so alarms can be configured in CloudWatch.
     *
     * @param accessType the unmapped Ranger access type
     */
    public void recordUnmappedAccessType(String accessType) {
        Dimension serviceDimension = serviceDimension();
        Dimension accessTypeDimension = Dimension.builder()
                .name("AccessType")
                .value(accessType != null ? accessType : "null")
                .build();
        List<MetricDatum> metrics = List.of(
                MetricDatum.builder()
                        .metricName("UnmappedAccessType")
                        .value(1.0)
                        .unit(StandardUnit.COUNT)
                        .dimensions(serviceDimension, accessTypeDimension)
                        .build()
        );
        publish(metrics);
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

    private static Dimension serviceTypeDimension(String serviceType) {
        return Dimension.builder()
                .name(SERVICE_TYPE_DIMENSION)
                .value(serviceType)
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
