package org.apache.ranger.lakeformation.client;

import org.apache.ranger.lakeformation.model.LFPermission;
import org.apache.ranger.lakeformation.model.LFPermissionOperation;
import org.apache.ranger.lakeformation.model.LFResource;
import org.apache.ranger.lakeformation.model.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lakeformation.model.ConcurrentModificationException;
import software.amazon.awssdk.services.lakeformation.model.GrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.RevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.DataLakePrincipal;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wrapper around the AWS Lake Formation SDK that handles grant/revoke
 * operations with retry logic for ConcurrentModificationException and throttling.
 */
public class LakeFormationClient {

    private static final Logger LOG = LoggerFactory.getLogger(LakeFormationClient.class);

    private final software.amazon.awssdk.services.lakeformation.LakeFormationClient lfClient;
    private final RetryConfig retryConfig;
    private final Sleeper sleeper;

    /**
     * Abstraction for Thread.sleep to allow testing without real delays.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public LakeFormationClient(
            software.amazon.awssdk.services.lakeformation.LakeFormationClient lfClient,
            RetryConfig retryConfig) {
        this(lfClient, retryConfig, Thread::sleep);
    }

    public LakeFormationClient(
            software.amazon.awssdk.services.lakeformation.LakeFormationClient lfClient,
            RetryConfig retryConfig,
            Sleeper sleeper) {
        this.lfClient = lfClient;
        this.retryConfig = retryConfig;
        this.sleeper = sleeper;
    }

    /**
     * Grant permissions on a Lake Formation resource.
     * Retries on ConcurrentModificationException and throttling with exponential backoff.
     *
     * @param op the permission operation describing the grant
     * @throws LakeFormationClientException if the operation fails after exhausting retries
     */
    public void grantPermission(LFPermissionOperation op) throws LakeFormationClientException {
        LOG.info("Granting permissions: policyId={}, principal={}, resource={}, permissions={}",
                op.getSourcePolicyId(), op.getPrincipalArn(), op.getResource(), op.getPermissions());

        Resource resource = buildResource(op.getResource());
        DataLakePrincipal principal = DataLakePrincipal.builder()
                .dataLakePrincipalIdentifier(op.getPrincipalArn())
                .build();
        Collection<Permission> permissions = toLfPermissions(op.getPermissions());

        GrantPermissionsRequest.Builder requestBuilder = GrantPermissionsRequest.builder()
                .principal(principal)
                .resource(resource)
                .permissions(permissions);

        if (op.isGrantable()) {
            requestBuilder.permissionsWithGrantOption(permissions);
        }

        GrantPermissionsRequest request = requestBuilder.build();

        executeWithRetry("GRANT", op, () -> {
            lfClient.grantPermissions(request);
            return null;
        });
    }

    /**
     * Revoke permissions on a Lake Formation resource.
     * Retries on ConcurrentModificationException and throttling with exponential backoff.
     *
     * @param op the permission operation describing the revoke
     * @throws LakeFormationClientException if the operation fails after exhausting retries
     */
    public void revokePermission(LFPermissionOperation op) throws LakeFormationClientException {
        LOG.info("Revoking permissions: policyId={}, principal={}, resource={}, permissions={}",
                op.getSourcePolicyId(), op.getPrincipalArn(), op.getResource(), op.getPermissions());

        Resource resource = buildResource(op.getResource());
        DataLakePrincipal principal = DataLakePrincipal.builder()
                .dataLakePrincipalIdentifier(op.getPrincipalArn())
                .build();
        Collection<Permission> permissions = toLfPermissions(op.getPermissions());

        RevokePermissionsRequest.Builder requestBuilder = RevokePermissionsRequest.builder()
                .principal(principal)
                .resource(resource)
                .permissions(permissions);

        if (op.isGrantable()) {
            requestBuilder.permissionsWithGrantOption(permissions);
        }

        RevokePermissionsRequest request = requestBuilder.build();

        executeWithRetry("REVOKE", op, () -> {
            lfClient.revokePermissions(request);
            return null;
        });
    }

    /**
     * Execute an operation with retry logic for ConcurrentModificationException and throttling.
     */
    private void executeWithRetry(String operationType, LFPermissionOperation op, RetryableAction action)
            throws LakeFormationClientException {
        int attempt = 0;
        long backoffMs = retryConfig.getInitialBackoffMs();

        while (true) {
            try {
                action.execute();
                LOG.debug("{} succeeded for policyId={} on attempt {}",
                        operationType, op.getSourcePolicyId(), attempt + 1);
                return;
            } catch (ConcurrentModificationException e) {
                attempt++;
                if (attempt > retryConfig.getMaxRetries()) {
                    LOG.error("{} failed after {} retries due to ConcurrentModificationException: policyId={}, resource={}",
                            operationType, retryConfig.getMaxRetries(), op.getSourcePolicyId(), op.getResource());
                    throw new LakeFormationClientException(
                            operationType + " failed after " + retryConfig.getMaxRetries()
                                    + " retries for policyId=" + op.getSourcePolicyId(), e);
                }
                LOG.warn("{} attempt {} failed with ConcurrentModificationException for policyId={}, retrying in {}ms",
                        operationType, attempt, op.getSourcePolicyId(), backoffMs);
                doSleep(backoffMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (software.amazon.awssdk.services.lakeformation.model.LakeFormationException e) {
                if (isThrottlingException(e)) {
                    attempt++;
                    if (attempt > retryConfig.getMaxRetries()) {
                        LOG.error("{} failed after {} retries due to throttling: policyId={}, resource={}",
                                operationType, retryConfig.getMaxRetries(), op.getSourcePolicyId(), op.getResource());
                        throw new LakeFormationClientException(
                                operationType + " failed after " + retryConfig.getMaxRetries()
                                        + " retries (throttled) for policyId=" + op.getSourcePolicyId(), e);
                    }
                    LOG.warn("{} attempt {} throttled for policyId={}, retrying in {}ms",
                            operationType, attempt, op.getSourcePolicyId(), backoffMs);
                    doSleep(backoffMs);
                    backoffMs = nextBackoff(backoffMs);
                } else {
                    LOG.error("{} failed with non-retryable error for policyId={}: {}",
                            operationType, op.getSourcePolicyId(), e.getMessage());
                    throw new LakeFormationClientException(
                            operationType + " failed for policyId=" + op.getSourcePolicyId(), e);
                }
            } catch (Exception e) {
                LOG.error("{} failed with unexpected error for policyId={}: {}",
                        operationType, op.getSourcePolicyId(), e.getMessage());
                throw new LakeFormationClientException(
                        operationType + " failed for policyId=" + op.getSourcePolicyId(), e);
            }
        }
    }

    /**
     * Build an AWS Lake Formation Resource from our LFResource model.
     */
    Resource buildResource(LFResource lfResource) {
        Resource.Builder builder = Resource.builder();

        if (lfResource.getTableName() == null) {
            // Database-level resource
            builder.database(DatabaseResource.builder()
                    .catalogId(lfResource.getCatalogId())
                    .name(lfResource.getDatabaseName())
                    .build());
        } else if (lfResource.getColumnNames() != null && !lfResource.getColumnNames().isEmpty()) {
            // Column-level resource
            builder.tableWithColumns(TableWithColumnsResource.builder()
                    .catalogId(lfResource.getCatalogId())
                    .databaseName(lfResource.getDatabaseName())
                    .name(lfResource.getTableName())
                    .columnNames(lfResource.getColumnNames())
                    .build());
        } else {
            // Table-level resource
            builder.table(TableResource.builder()
                    .catalogId(lfResource.getCatalogId())
                    .databaseName(lfResource.getDatabaseName())
                    .name(lfResource.getTableName())
                    .build());
        }

        return builder.build();
    }

    /**
     * Convert our LFPermission enums to AWS SDK Permission enums.
     */
    List<Permission> toLfPermissions(Set<LFPermission> permissions) {
        return permissions.stream()
                .map(p -> Permission.fromValue(p.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Compute the next backoff duration, capped at maxBackoffMs.
     */
    private long nextBackoff(long currentBackoffMs) {
        long next = (long) (currentBackoffMs * retryConfig.getBackoffMultiplier());
        return Math.min(next, retryConfig.getMaxBackoffMs());
    }

    /**
     * Sleep for the given duration, wrapping InterruptedException.
     */
    private void doSleep(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Retry sleep interrupted");
        }
    }

    /**
     * Check if an exception is a throttling error.
     */
    private boolean isThrottlingException(software.amazon.awssdk.services.lakeformation.model.LakeFormationException e) {
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        return "ThrottlingException".equals(errorCode)
                || "Throttling".equals(errorCode)
                || "TooManyRequestsException".equals(errorCode)
                || "RequestLimitExceeded".equals(errorCode);
    }

    /**
     * Functional interface for retryable operations.
     */
    @FunctionalInterface
    private interface RetryableAction {
        Void execute() throws Exception;
    }
}
