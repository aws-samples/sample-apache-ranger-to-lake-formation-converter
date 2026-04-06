package com.amazonaws.policyconverters.lakeformation.client;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.RetryConfig;
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
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wrapper around the AWS Lake Formation SDK that handles grant/revoke
 * operations with retry logic for ConcurrentModificationException.
 * Throttling retries are handled internally by the AWS SDK v2 retry policy.
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
     * Retries on ConcurrentModificationException with exponential backoff.
     * Throttling is handled by the AWS SDK's built-in retry policy.
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
     * Retries on ConcurrentModificationException with exponential backoff.
     * Throttling is handled by the AWS SDK's built-in retry policy.
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
     * Apply a batch of operations atomically per policy.
     * Operations are grouped by source policy ID and applied sequentially.
     * If any operation for a policy fails after retries, all previously applied
     * operations for that policy are rolled back. Failed operations are written
     * to the dead-letter log.
     *
     * @param operations  the list of LF permission operations to apply
     * @param deadLetterLogger logger for failed operations (may be null to skip dead-letter logging)
     * @return BatchResult summarizing successes and failures
     */
    public BatchResult applyBatch(List<LFPermissionOperation> operations, DeadLetterLogger deadLetterLogger) {
        // Group operations by source policy ID, preserving insertion order
        Map<String, List<LFPermissionOperation>> byPolicy = new LinkedHashMap<>();
        for (LFPermissionOperation op : operations) {
            String policyId = op.getSourcePolicyId() != null ? op.getSourcePolicyId() : "__unknown__";
            if (!byPolicy.containsKey(policyId)) {
                byPolicy.put(policyId, new ArrayList<LFPermissionOperation>());
            }
            byPolicy.get(policyId).add(op);
        }

        List<String> succeededPolicies = new ArrayList<>();
        List<String> failedPolicies = new ArrayList<>();
        int totalOps = operations.size();
        int appliedOps = 0;
        int rolledBackOps = 0;

        for (Map.Entry<String, List<LFPermissionOperation>> entry : byPolicy.entrySet()) {
            String policyId = entry.getKey();
            List<LFPermissionOperation> policyOps = entry.getValue();
            List<LFPermissionOperation> appliedForPolicy = new ArrayList<>();
            boolean policyFailed = false;
            String failureError = null;

            for (LFPermissionOperation op : policyOps) {
                try {
                    applyOperation(op);
                    appliedForPolicy.add(op);
                } catch (LakeFormationClientException e) {
                    policyFailed = true;
                    failureError = e.getMessage();
                    LOG.error("Operation failed for policyId={}, triggering rollback of {} applied operations: {}",
                            policyId, appliedForPolicy.size(), e.getMessage());

                    // Write the failed operation to dead-letter log
                    if (deadLetterLogger != null) {
                        deadLetterLogger.logFailedOperation(op, e.getMessage(), retryConfig.getMaxRetries());
                    }
                    break;
                }
            }

            if (policyFailed) {
                // Rollback all previously applied operations for this policy
                int rolledBack = rollbackOperations(appliedForPolicy, policyId);
                rolledBackOps += rolledBack;
                failedPolicies.add(policyId);

                // Also log remaining unapplied operations to dead-letter if they exist
                if (deadLetterLogger != null) {
                    int failedIdx = appliedForPolicy.size() + 1; // +1 for the one that failed
                    for (int i = failedIdx; i < policyOps.size(); i++) {
                        deadLetterLogger.logFailedOperation(policyOps.get(i),
                                "Skipped due to earlier failure in policy batch: " + failureError, 0);
                    }
                }
            } else {
                appliedOps += appliedForPolicy.size();
                succeededPolicies.add(policyId);
            }
        }

        BatchResult result = new BatchResult(succeededPolicies, failedPolicies, totalOps, appliedOps, rolledBackOps);
        LOG.info("Batch application complete: {}", result);
        return result;
    }

    /**
     * Apply a single operation (grant or revoke) using the appropriate method.
     */
    private void applyOperation(LFPermissionOperation op) throws LakeFormationClientException {
        if (op.getOperationType() == LFPermissionOperation.OperationType.GRANT) {
            grantPermission(op);
        } else {
            revokePermission(op);
        }
    }

    /**
     * Rollback previously applied operations by reversing them.
     * Grants are reversed with revokes, and revokes are reversed with grants.
     * Rollback failures are logged but do not propagate.
     *
     * @return the number of operations successfully rolled back
     */
    private int rollbackOperations(List<LFPermissionOperation> appliedOps, String policyId) {
        int rolledBack = 0;
        // Reverse in opposite order of application
        for (int i = appliedOps.size() - 1; i >= 0; i--) {
            LFPermissionOperation original = appliedOps.get(i);
            try {
                if (original.getOperationType() == LFPermissionOperation.OperationType.GRANT) {
                    // Reverse a grant by revoking
                    LFPermissionOperation reversal = new LFPermissionOperation(
                            LFPermissionOperation.OperationType.REVOKE,
                            original.getSourcePolicyId(),
                            original.getPrincipalArn(),
                            original.getResource(),
                            original.getPermissions(),
                            original.isGrantable());
                    revokePermission(reversal);
                } else {
                    // Reverse a revoke by granting
                    LFPermissionOperation reversal = new LFPermissionOperation(
                            LFPermissionOperation.OperationType.GRANT,
                            original.getSourcePolicyId(),
                            original.getPrincipalArn(),
                            original.getResource(),
                            original.getPermissions(),
                            original.isGrantable());
                    grantPermission(reversal);
                }
                rolledBack++;
            } catch (LakeFormationClientException e) {
                LOG.error("Rollback failed for policyId={}, operation={}: {}",
                        policyId, original, e.getMessage());
            }
        }
        LOG.info("Rolled back {}/{} operations for policyId={}", rolledBack, appliedOps.size(), policyId);
        return rolledBack;
    }

    /**
     * Execute an operation with retry logic for ConcurrentModificationException.
     * Throttling is handled by the AWS SDK's built-in retry policy.
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
            } catch (Exception e) {
                LOG.error("{} failed with error for policyId={}: {}",
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

        if (lfResource.getDataLocationPath() != null) {
            // Data location resource (S3 path)
            builder.dataLocation(DataLocationResource.builder()
                    .catalogId(lfResource.getCatalogId())
                    .resourceArn(lfResource.getDataLocationPath())
                    .build());
        } else if (lfResource.getTableName() == null) {
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
     * Functional interface for retryable operations.
     */
    @FunctionalInterface
    private interface RetryableAction {
        Void execute() throws Exception;
    }
}
