package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.RetryConfig;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lakeformation.model.AlreadyExistsException;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsFailureEntry;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsRequestEntry;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.ColumnLFTag;
import software.amazon.awssdk.services.lakeformation.model.ConcurrentModificationException;
import software.amazon.awssdk.services.lakeformation.model.CreateLfTagRequest;
import software.amazon.awssdk.services.lakeformation.model.DataLakePrincipal;
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.DeleteLfTagRequest;
import software.amazon.awssdk.services.lakeformation.model.EntityNotFoundException;
import software.amazon.awssdk.services.lakeformation.model.GetResourceLfTagsRequest;
import software.amazon.awssdk.services.lakeformation.model.GetResourceLfTagsResponse;
import software.amazon.awssdk.services.lakeformation.model.AddLfTagsToResourceRequest;
import software.amazon.awssdk.services.lakeformation.model.RemoveLfTagsFromResourceRequest;
import software.amazon.awssdk.services.lakeformation.model.GrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.InvalidInputException;
import software.amazon.awssdk.services.lakeformation.model.LFTagPair;
import software.amazon.awssdk.services.lakeformation.model.ListLfTagsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListLfTagsResponse;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.RevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWildcard;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            try {
                lfClient.revokePermissions(request);
            } catch (InvalidInputException e) {
                if (e.getMessage() != null && e.getMessage().contains("No permissions revoked")) {
                    LOG.info("Revoke is a no-op for policyId={}: grantee has no permissions on resource",
                            op.getSourcePolicyId());
                } else {
                    throw e;
                }
            }
            return null;
        });
    }

    /** Maximum entries per BatchGrant/BatchRevoke API call. */
    private static final int MAX_BATCH_SIZE = 20;

    /**
     * Apply a batch of operations using BatchGrantPermissions and BatchRevokePermissions.
     * Operations are first consolidated (merging columns and permissions for the same
     * principal/resource), then split into grants and revokes, chunked into groups of
     * up to 20 (the LF API limit), and sent as batch calls. Partial failures from the
     * batch response are logged to the dead-letter log.
     *
     * @param operations     the list of LF permission operations to apply
     * @param deadLetterLogger logger for failed operations (may be null to skip dead-letter logging)
     * @return BatchResult summarizing successes and failures
     */
    public BatchResult applyBatch(List<LFPermissionOperation> operations, DeadLetterLogger deadLetterLogger) {
        // Consolidate operations before batching
        List<LFPermissionOperation> consolidated = consolidateOperations(operations);
        consolidated = resolveTableColumnConflicts(consolidated);
        LOG.info("Consolidated {} operations into {} entries", operations.size(), consolidated.size());

        List<LFPermissionOperation> grants = new ArrayList<>();
        List<LFPermissionOperation> revokes = new ArrayList<>();
        for (LFPermissionOperation op : consolidated) {
            if (op.getOperationType() == LFPermissionOperation.OperationType.GRANT) {
                grants.add(op);
            } else {
                revokes.add(op);
            }
        }

        List<String> succeededPolicies = new ArrayList<>();
        List<String> failedPolicies = new ArrayList<>();
        int appliedOps = 0;
        int failedOps = 0;

        // Revokes run first so TABLE_WITH_COLUMNS conflicts are cleared before TABLE grants land.
        // SyncService applies resolveTableColumnConflicts to currentOperations before diffing,
        // which produces explicit TABLE_WITH_COLUMNS REVOKEs alongside TABLE GRANTs when needed.
        for (int i = 0; i < revokes.size(); i += MAX_BATCH_SIZE) {
            List<LFPermissionOperation> chunk = revokes.subList(i, Math.min(i + MAX_BATCH_SIZE, revokes.size()));
            int[] result = executeBatchRevoke(chunk, deadLetterLogger, succeededPolicies, failedPolicies);
            appliedOps += result[0];
            failedOps += result[1];
        }

        // Process grants in chunks of MAX_BATCH_SIZE
        for (int i = 0; i < grants.size(); i += MAX_BATCH_SIZE) {
            List<LFPermissionOperation> chunk = grants.subList(i, Math.min(i + MAX_BATCH_SIZE, grants.size()));
            int[] result = executeBatchGrant(chunk, deadLetterLogger, succeededPolicies, failedPolicies);
            appliedOps += result[0];
            failedOps += result[1];
        }

        BatchResult batchResult = new BatchResult(
                succeededPolicies, failedPolicies, operations.size(), appliedOps, 0);
        LOG.info("Batch application complete: {}", batchResult);
        return batchResult;
    }

    /**
     * Consolidate operations that share the same principal, resource base (catalog, database,
     * table, data location, row filter), operation type, and grantable flag. Merges:
     * <ul>
     *   <li>Columns: multiple single-column ops become one multi-column op</li>
     *   <li>Permissions: multiple single-permission ops become one multi-permission op</li>
     * </ul>
     * This reduces the number of batch entries sent to the LF API.
     */
    static List<LFPermissionOperation> consolidateOperations(List<LFPermissionOperation> operations) {
        // Key: everything except columns and permissions
        Map<ConsolidationKey, MergedOp> merged = new LinkedHashMap<>();

        for (LFPermissionOperation op : operations) {
            ConsolidationKey key = new ConsolidationKey(op);
            merged.computeIfAbsent(key, k -> new MergedOp(op)).merge(op);
        }

        List<LFPermissionOperation> result = new ArrayList<>(merged.size());
        for (MergedOp m : merged.values()) {
            result.add(m.toOperation());
        }
        return result;
    }

    /**
     * Resolve TABLE vs TABLE_WITH_COLUMNS conflicts within a batch. LF rejects a
     * TABLE_WITH_COLUMNS grant when the same principal already holds (or is being
     * granted) a plain TABLE grant on the same (principal, catalogId, db, table).
     *
     * <p>For within-batch conflicts: merges column-level permissions into the
     * TABLE-level entry and removes the TABLE_WITH_COLUMNS entry.
     *
     * <p>Cross-cycle conflicts (TABLE grant already exists in LF from a prior
     * cycle) are handled in {@link #processBatchFailures} via promotion fallback.
     */
    public static List<LFPermissionOperation> resolveTableColumnConflicts(List<LFPermissionOperation> ops) {
        // Map conflictKey → index of the TABLE-level entry in working list
        Map<String, Integer> tableGrantIndex = new LinkedHashMap<>();
        List<LFPermissionOperation> working = new ArrayList<>(ops);

        for (int i = 0; i < working.size(); i++) {
            LFPermissionOperation op = working.get(i);
            LFResource r = op.getResource();
            if (r.getTableName() != null
                    && (r.getColumnNames() == null || r.getColumnNames().isEmpty())
                    && !r.isAllTables()
                    && r.getDataLocationPath() == null) {
                String key = op.getOperationType() + "|" + op.getPrincipalArn() + "|"
                        + r.getCatalogId() + "|" + r.getDatabaseName() + "|" + r.getTableName();
                tableGrantIndex.put(key, i);
            }
        }

        Set<Integer> removeIndices = new HashSet<>();
        for (int i = 0; i < working.size(); i++) {
            LFPermissionOperation op = working.get(i);
            LFResource r = op.getResource();
            if (r.getColumnNames() != null && !r.getColumnNames().isEmpty()) {
                String key = op.getOperationType() + "|" + op.getPrincipalArn() + "|"
                        + r.getCatalogId() + "|" + r.getDatabaseName() + "|" + r.getTableName();
                Integer tableIdx = tableGrantIndex.get(key);
                if (tableIdx != null) {
                    // Merge column-level permissions into the existing TABLE entry
                    LFPermissionOperation tableOp = working.get(tableIdx);
                    EnumSet<LFPermission> merged = EnumSet.copyOf(tableOp.getPermissions());
                    merged.addAll(op.getPermissions());
                    working.set(tableIdx, new LFPermissionOperation(
                            tableOp.getOperationType(), tableOp.getSourcePolicyId(),
                            tableOp.getPrincipalArn(), tableOp.getResource(),
                            merged, tableOp.isGrantable() || op.isGrantable()));
                    removeIndices.add(i);
                    LOG.debug("Resolved TABLE/TABLE_WITH_COLUMNS conflict: principal={}, db={}, table={}, merged perms={}",
                            op.getPrincipalArn(), r.getDatabaseName(), r.getTableName(), op.getPermissions());
                }
            }
        }

        if (removeIndices.isEmpty()) {
            return working;
        }
        List<LFPermissionOperation> result = new ArrayList<>(working.size() - removeIndices.size());
        for (int i = 0; i < working.size(); i++) {
            if (!removeIndices.contains(i)) {
                result.add(working.get(i));
            }
        }
        return result;
    }

    /**
     * Key for consolidation: groups operations by everything except columns and permissions.
     */
    private static final class ConsolidationKey {
        private final LFPermissionOperation.OperationType opType;
        private final String sourcePolicyId;
        private final String principalArn;
        private final String catalogId;
        private final String databaseName;
        private final String tableName;
        private final String dataLocationPath;
        private final String rowFilterExpression;
        private final boolean grantable;

        ConsolidationKey(LFPermissionOperation op) {
            this.opType = op.getOperationType();
            this.sourcePolicyId = op.getSourcePolicyId();
            this.principalArn = op.getPrincipalArn();
            LFResource r = op.getResource();
            this.catalogId = r.getCatalogId();
            this.databaseName = r.getDatabaseName();
            this.tableName = r.getTableName();
            this.dataLocationPath = r.getDataLocationPath();
            this.rowFilterExpression = r.getRowFilterExpression();
            this.grantable = op.isGrantable();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ConsolidationKey that = (ConsolidationKey) o;
            return grantable == that.grantable
                    && opType == that.opType
                    && Objects.equals(sourcePolicyId, that.sourcePolicyId)
                    && Objects.equals(principalArn, that.principalArn)
                    && Objects.equals(catalogId, that.catalogId)
                    && Objects.equals(databaseName, that.databaseName)
                    && Objects.equals(tableName, that.tableName)
                    && Objects.equals(dataLocationPath, that.dataLocationPath)
                    && Objects.equals(rowFilterExpression, that.rowFilterExpression);
        }

        @Override
        public int hashCode() {
            return Objects.hash(opType, sourcePolicyId, principalArn, catalogId, databaseName,
                    tableName, dataLocationPath, rowFilterExpression, grantable);
        }
    }

    /**
     * Accumulator for merging columns and permissions across operations with the same key.
     */
    private static final class MergedOp {
        private final LFPermissionOperation.OperationType opType;
        private final String sourcePolicyId;
        private final String principalArn;
        private final String catalogId;
        private final String databaseName;
        private final String tableName;
        private final String dataLocationPath;
        private final String rowFilterExpression;
        private final boolean grantable;
        private final Set<String> columns = new HashSet<>();
        private final EnumSet<LFPermission> permissions = EnumSet.noneOf(LFPermission.class);

        MergedOp(LFPermissionOperation op) {
            this.opType = op.getOperationType();
            this.sourcePolicyId = op.getSourcePolicyId();
            this.principalArn = op.getPrincipalArn();
            LFResource r = op.getResource();
            this.catalogId = r.getCatalogId();
            this.databaseName = r.getDatabaseName();
            this.tableName = r.getTableName();
            this.dataLocationPath = r.getDataLocationPath();
            this.rowFilterExpression = r.getRowFilterExpression();
            this.grantable = op.isGrantable();
        }

        void merge(LFPermissionOperation op) {
            permissions.addAll(op.getPermissions());
            if (op.getResource().getColumnNames() != null) {
                columns.addAll(op.getResource().getColumnNames());
            }
        }

        LFPermissionOperation toOperation() {
            Set<String> cols = columns.isEmpty() ? null : columns;
            LFResource resource = new LFResource(
                    catalogId, databaseName, tableName, cols,
                    rowFilterExpression, dataLocationPath);
            return new LFPermissionOperation(
                    opType, sourcePolicyId, principalArn, resource, permissions, grantable);
        }
    }

    /**
     * Execute a BatchGrantPermissions call for a chunk of up to 20 operations.
     * Returns [appliedCount, failedCount].
     */
    private int[] executeBatchGrant(List<LFPermissionOperation> ops, DeadLetterLogger deadLetterLogger,
                                    List<String> succeededPolicies, List<String> failedPolicies) {
        List<BatchPermissionsRequestEntry> entries = new ArrayList<>();
        Map<String, LFPermissionOperation> entryIdToOp = new LinkedHashMap<>();

        for (int i = 0; i < ops.size(); i++) {
            LFPermissionOperation op = ops.get(i);
            String entryId = "grant-" + i;
            entries.add(toBatchEntry(entryId, op));
            entryIdToOp.put(entryId, op);
        }

        BatchGrantPermissionsRequest request = BatchGrantPermissionsRequest.builder()
                .entries(entries)
                .build();

        LOG.info("Sending BatchGrantPermissions with {} entries", entries.size());
        BatchGrantPermissionsResponse response = lfClient.batchGrantPermissions(request);

        return processBatchFailures(response.failures(), entryIdToOp,
                "GRANT", deadLetterLogger, succeededPolicies, failedPolicies);
    }

    /**
     * Execute a BatchRevokePermissions call for a chunk of up to 20 operations.
     * Returns [appliedCount, failedCount].
     */
    private int[] executeBatchRevoke(List<LFPermissionOperation> ops, DeadLetterLogger deadLetterLogger,
                                     List<String> succeededPolicies, List<String> failedPolicies) {
        List<BatchPermissionsRequestEntry> entries = new ArrayList<>();
        Map<String, LFPermissionOperation> entryIdToOp = new LinkedHashMap<>();

        for (int i = 0; i < ops.size(); i++) {
            LFPermissionOperation op = ops.get(i);
            String entryId = "revoke-" + i;
            entries.add(toBatchEntry(entryId, op));
            entryIdToOp.put(entryId, op);
        }

        BatchRevokePermissionsRequest request = BatchRevokePermissionsRequest.builder()
                .entries(entries)
                .build();

        LOG.info("Sending BatchRevokePermissions with {} entries", entries.size());
        BatchRevokePermissionsResponse response = lfClient.batchRevokePermissions(request);

        return processBatchFailures(response.failures(), entryIdToOp,
                "REVOKE", deadLetterLogger, succeededPolicies, failedPolicies);
    }

    /**
     * Process batch response failures. Returns [appliedCount, failedCount].
     * Entries not in the failures list are considered successful.
     */
    private int[] processBatchFailures(List<BatchPermissionsFailureEntry> failures,
                                       Map<String, LFPermissionOperation> entryIdToOp,
                                       String operationType, DeadLetterLogger deadLetterLogger,
                                       List<String> succeededPolicies, List<String> failedPolicies) {
        // Collect failed entry IDs
        Set<String> failedEntryIds = new java.util.HashSet<>();
        if (failures != null) {
            for (BatchPermissionsFailureEntry failure : failures) {
                String entryId = failure.requestEntry().id();
                String errorMsg = failure.error() != null ? failure.error().errorMessage() : "Unknown error";

                // Treat idempotent revoke errors as no-op successes so the checkpoint
                // advances and the operation is not retried forever:
                // - "No permissions revoked": grantee already has no such permission
                // - "Permissions modification is invalid": column spec doesn't match the actual
                //   grant (e.g. revoking column-scoped SELECT when LF holds an all-columns grant
                //   after resolveTableColumnConflicts merged them into a TABLE-level entry)
                if ("REVOKE".equals(operationType) && errorMsg != null
                        && (errorMsg.contains("No permissions revoked")
                                || errorMsg.contains("Permissions modification is invalid"))) {
                    LFPermissionOperation op = entryIdToOp.get(entryId);
                    LOG.info("Revoke treated as no-op for policyId={}: {}",
                            op != null ? op.getSourcePolicyId() : entryId, errorMsg);
                    continue;
                }

                failedEntryIds.add(entryId);
                LFPermissionOperation op = entryIdToOp.get(entryId);
                LOG.error("Batch {} failed for entryId={}, policyId={}: {}",
                        operationType, entryId,
                        op != null ? op.getSourcePolicyId() : "unknown", errorMsg);

                if (deadLetterLogger != null && op != null) {
                    deadLetterLogger.logFailedOperation(op, errorMsg, 0);
                }
            }
        }

        int failedCount = failedEntryIds.size();
        int appliedCount = entryIdToOp.size() - failedCount;

        // Track succeeded/failed policy IDs
        for (Map.Entry<String, LFPermissionOperation> entry : entryIdToOp.entrySet()) {
            String policyId = entry.getValue().getSourcePolicyId() != null
                    ? entry.getValue().getSourcePolicyId() : "__unknown__";
            if (failedEntryIds.contains(entry.getKey())) {
                if (!failedPolicies.contains(policyId)) {
                    failedPolicies.add(policyId);
                }
            } else {
                if (!succeededPolicies.contains(policyId) && !failedPolicies.contains(policyId)) {
                    succeededPolicies.add(policyId);
                }
            }
        }

        return new int[]{appliedCount, failedCount};
    }

    /**
     * Convert an LFPermissionOperation to a BatchPermissionsRequestEntry.
     */
    private BatchPermissionsRequestEntry toBatchEntry(String entryId, LFPermissionOperation op) {
        Resource resource = buildResource(op.getResource());
        DataLakePrincipal principal = DataLakePrincipal.builder()
                .dataLakePrincipalIdentifier(op.getPrincipalArn())
                .build();
        Collection<Permission> permissions = toLfPermissions(op.getPermissions());

        BatchPermissionsRequestEntry.Builder builder = BatchPermissionsRequestEntry.builder()
                .id(entryId)
                .principal(principal)
                .resource(resource)
                .permissions(permissions);

        if (op.isGrantable()) {
            builder.permissionsWithGrantOption(permissions);
        }

        return builder.build();
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

    // ---------------------------------------------------------------
    // LF-Tag definition management
    // ---------------------------------------------------------------

    /**
     * Create an LF-Tag key with the given allowed values.
     * If the tag already exists, logs INFO and returns without error.
     */
    public void createLFTag(String catalogId, String tagKey, List<String> tagValues) {
        try {
            lfClient.createLFTag(CreateLfTagRequest.builder()
                    .catalogId(catalogId)
                    .tagKey(tagKey)
                    .tagValues(tagValues)
                    .build());
            LOG.info("Created LF-Tag: key={}", tagKey);
        } catch (AlreadyExistsException e) {
            LOG.info("LF-Tag already exists (external or prior run): key={}", tagKey);
        }
    }

    /**
     * Delete an LF-Tag key.
     * If the tag does not exist, logs INFO and returns without error.
     */
    public void deleteLFTag(String catalogId, String tagKey) {
        try {
            lfClient.deleteLFTag(DeleteLfTagRequest.builder()
                    .catalogId(catalogId)
                    .tagKey(tagKey)
                    .build());
            LOG.info("Deleted LF-Tag: key={}", tagKey);
        } catch (EntityNotFoundException e) {
            LOG.info("LF-Tag not found (already deleted): key={}", tagKey);
        }
    }

    /**
     * List all LF-Tag keys in the catalog. Paginates through all pages.
     */
    public List<String> listLFTagKeys(String catalogId) {
        List<String> keys = new ArrayList<>();
        String nextToken = null;
        do {
            ListLfTagsRequest.Builder reqBuilder = ListLfTagsRequest.builder()
                    .catalogId(catalogId)
                    .maxResults(1000);
            if (nextToken != null) {
                reqBuilder.nextToken(nextToken);
            }
            ListLfTagsResponse response = lfClient.listLFTags(reqBuilder.build());
            if (response.hasLfTags()) {
                for (LFTagPair pair : response.lfTags()) {
                    keys.add(pair.tagKey());
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null);
        return keys;
    }

    // ---------------------------------------------------------------
    // LF-Tag resource attachment management
    // ---------------------------------------------------------------

    /**
     * Get the LF-Tags currently attached to a resource.
     * Returns a map of tagKey → tagValue for all managed tags on the resource.
     * Throws on API failure — caller decides whether to abort reconciliation.
     */
    public Map<String, String> getResourceLFTags(LFResource resource, String catalogId) {
        GetResourceLfTagsResponse response = lfClient.getResourceLFTags(
                GetResourceLfTagsRequest.builder()
                        .catalogId(catalogId)
                        .resource(buildResource(resource))
                        .showAssignedLFTags(true)
                        .build());

        Map<String, String> result = new java.util.HashMap<>();

        // Collect database-level tags
        if (response.hasLfTagOnDatabase()) {
            for (LFTagPair pair : response.lfTagOnDatabase()) {
                String val = pair.hasTagValues() && !pair.tagValues().isEmpty()
                        ? pair.tagValues().get(0) : "true";
                result.put(pair.tagKey(), val);
            }
        }
        // Collect table-level tags
        if (response.hasLfTagsOnTable()) {
            for (LFTagPair pair : response.lfTagsOnTable()) {
                String val = pair.hasTagValues() && !pair.tagValues().isEmpty()
                        ? pair.tagValues().get(0) : "true";
                result.put(pair.tagKey(), val);
            }
        }
        // Collect column-level tags (first column's tags, since we track per-column resources)
        if (response.hasLfTagsOnColumns()) {
            for (ColumnLFTag col : response.lfTagsOnColumns()) {
                if (col.hasLfTags()) {
                    for (LFTagPair pair : col.lfTags()) {
                        String val = pair.hasTagValues() && !pair.tagValues().isEmpty()
                                ? pair.tagValues().get(0) : "true";
                        result.put(pair.tagKey(), val);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Attach LF-Tags (key → value) to a resource.
     */
    public void addLFTagsToResource(LFResource resource, Map<String, String> tags, String catalogId) {
        List<LFTagPair> lfTags = new ArrayList<>();
        for (Map.Entry<String, String> e : tags.entrySet()) {
            lfTags.add(LFTagPair.builder()
                    .catalogId(catalogId)
                    .tagKey(e.getKey())
                    .tagValues(e.getValue())
                    .build());
        }
        lfClient.addLFTagsToResource(AddLfTagsToResourceRequest.builder()
                .catalogId(catalogId)
                .resource(buildResource(resource))
                .lfTags(lfTags)
                .build());
        LOG.debug("Added LF-Tags to resource: resource={}, tags={}", resource, tags.keySet());
    }

    /**
     * Detach LF-Tag keys from a resource.
     */
    public void removeLFTagsFromResource(LFResource resource, List<String> tagKeys, String catalogId) {
        List<LFTagPair> lfTags = new ArrayList<>();
        for (String key : tagKeys) {
            lfTags.add(LFTagPair.builder()
                    .catalogId(catalogId)
                    .tagKey(key)
                    .tagValues("true")
                    .build());
        }
        lfClient.removeLFTagsFromResource(RemoveLfTagsFromResourceRequest.builder()
                .catalogId(catalogId)
                .resource(buildResource(resource))
                .lfTags(lfTags)
                .build());
        LOG.debug("Removed LF-Tags from resource: resource={}, tagKeys={}", resource, tagKeys);
    }

    /**
     * Build an AWS Lake Formation Resource from our LFResource model.
     */
    Resource buildResource(LFResource lfResource) {
        Resource.Builder builder = Resource.builder();

        if (lfResource.getDataLocationPath() != null) {
            // Data location resource: convert s3://bucket/path → arn:aws:s3:::bucket/path
            String s3Path = lfResource.getDataLocationPath();
            String resourceArn = s3Path.startsWith("s3://")
                    ? "arn:aws:s3:::" + s3Path.substring("s3://".length())
                    : s3Path;
            if (resourceArn.endsWith("/")) {
                resourceArn = resourceArn.substring(0, resourceArn.length() - 1);
            }
            builder.dataLocation(DataLocationResource.builder()
                    .catalogId(lfResource.getCatalogId())
                    .resourceArn(resourceArn)
                    .build());
        } else if (lfResource.isAllTables()) {
            // All tables wildcard — applies to all current and future tables in the database
            builder.table(TableResource.builder()
                    .catalogId(lfResource.getCatalogId())
                    .databaseName(lfResource.getDatabaseName())
                    .tableWildcard(TableWildcard.builder().build())
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
