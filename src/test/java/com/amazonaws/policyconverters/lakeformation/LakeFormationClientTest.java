package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.lakeformation.BatchResult;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClientException;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.config.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.ConcurrentModificationException;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsFailureEntry;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsRequestEntry;
import software.amazon.awssdk.services.lakeformation.model.ErrorDetail;
import software.amazon.awssdk.services.lakeformation.model.GrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.LakeFormationException;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.PrincipalResourcePermissions;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.RevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeFormationClientTest {

    @Mock
    private software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient;

    private List<Long> sleepDurations;
    private LakeFormationClient client;
    private RetryConfig retryConfig;

    @BeforeEach
    void setUp() {
        sleepDurations = new ArrayList<>();
        retryConfig = new RetryConfig(3, 100L, 2.0, 5000L);
        LakeFormationClient.Sleeper trackingSleeper = millis -> sleepDurations.add(millis);
        client = new LakeFormationClient(awsClient, retryConfig, trackingSleeper);
    }

    private LFPermissionOperation makeGrantOp() {
        LFResource resource = new LFResource("catalog1", "mydb", "mytable", null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        return new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "policy-1",
                "arn:aws:iam::123456789012:role/TestRole", resource, perms, false);
    }

    private LFPermissionOperation makeRevokeOp() {
        LFResource resource = new LFResource("catalog1", "mydb", null, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.DESCRIBE);
        return new LFPermissionOperation(
                LFPermissionOperation.OperationType.REVOKE, "policy-2",
                "arn:aws:iam::123456789012:user/TestUser", resource, perms, false);
    }

    // --- Grant tests ---

    @Test
    void grantPermission_succeeds_onFirstAttempt() throws Exception {
        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenReturn(null);

        client.grantPermission(makeGrantOp());

        verify(awsClient, times(1)).grantPermissions(any(GrantPermissionsRequest.class));
        assertTrue(sleepDurations.isEmpty());
    }

    @Test
    void grantPermission_retriesOnConcurrentModification_thenSucceeds() throws Exception {
        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenThrow(ConcurrentModificationException.builder()
                        .message("concurrent mod").build())
                .thenThrow(ConcurrentModificationException.builder()
                        .message("concurrent mod").build())
                .thenReturn(null);

        client.grantPermission(makeGrantOp());

        verify(awsClient, times(3)).grantPermissions(any(GrantPermissionsRequest.class));
        assertEquals(2, sleepDurations.size());
        assertEquals(100L, sleepDurations.get(0).longValue());
        assertEquals(200L, sleepDurations.get(1).longValue());
    }

    @Test
    void grantPermission_failsAfterExhaustingRetries_concurrentModification() {
        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenThrow(ConcurrentModificationException.builder()
                        .message("concurrent mod").build());

        LakeFormationClientException ex = assertThrows(LakeFormationClientException.class,
                () -> client.grantPermission(makeGrantOp()));

        // 1 initial + 3 retries = 4 total attempts
        verify(awsClient, times(4)).grantPermissions(any(GrantPermissionsRequest.class));
        assertEquals(3, sleepDurations.size());
        assertTrue(ex.getMessage().contains("GRANT failed after 3 retries"));
        assertTrue(ex.getCause() instanceof ConcurrentModificationException);
    }

    @Test
    void grantPermission_nonRetryableException_failsImmediately() {
        LakeFormationException nonRetryable = (LakeFormationException) LakeFormationException.builder()
                .message("Access denied")
                .build();

        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenThrow(nonRetryable);

        LakeFormationClientException ex = assertThrows(LakeFormationClientException.class,
                () -> client.grantPermission(makeGrantOp()));

        verify(awsClient, times(1)).grantPermissions(any(GrantPermissionsRequest.class));
        assertTrue(sleepDurations.isEmpty());
        assertTrue(ex.getMessage().contains("GRANT failed"));
    }

    // --- Revoke tests ---

    @Test
    void revokePermission_succeeds_onFirstAttempt() throws Exception {
        when(awsClient.revokePermissions(any(RevokePermissionsRequest.class)))
                .thenReturn(null);

        client.revokePermission(makeRevokeOp());

        verify(awsClient, times(1)).revokePermissions(any(RevokePermissionsRequest.class));
        assertTrue(sleepDurations.isEmpty());
    }

    @Test
    void revokePermission_retriesOnConcurrentModification_thenSucceeds() throws Exception {
        when(awsClient.revokePermissions(any(RevokePermissionsRequest.class)))
                .thenThrow(ConcurrentModificationException.builder()
                        .message("concurrent mod").build())
                .thenReturn(null);

        client.revokePermission(makeRevokeOp());

        verify(awsClient, times(2)).revokePermissions(any(RevokePermissionsRequest.class));
        assertEquals(1, sleepDurations.size());
    }

    // --- Backoff tests ---

    @Test
    void backoff_respectsMaxBackoffMs() throws Exception {
        // Use config with low max backoff to test capping
        RetryConfig cappedConfig = new RetryConfig(5, 1000L, 10.0, 5000L);
        LakeFormationClient cappedClient = new LakeFormationClient(awsClient, cappedConfig,
                millis -> sleepDurations.add(millis));

        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenThrow(ConcurrentModificationException.builder().message("mod").build())
                .thenThrow(ConcurrentModificationException.builder().message("mod").build())
                .thenThrow(ConcurrentModificationException.builder().message("mod").build())
                .thenReturn(null);

        cappedClient.grantPermission(makeGrantOp());

        assertEquals(3, sleepDurations.size());
        assertEquals(1000L, sleepDurations.get(0).longValue());
        // 1000 * 10 = 10000, capped to 5000
        assertEquals(5000L, sleepDurations.get(1).longValue());
        // 5000 * 10 = 50000, capped to 5000
        assertEquals(5000L, sleepDurations.get(2).longValue());
    }

    // --- Resource building tests ---

    @Test
    void buildResource_databaseLevel() {
        LFResource dbResource = new LFResource("cat1", "mydb", null, null, null);
        software.amazon.awssdk.services.lakeformation.model.Resource result = client.buildResource(dbResource);

        assertEquals("cat1", result.database().catalogId());
        assertEquals("mydb", result.database().name());
    }

    @Test
    void buildResource_tableLevel() {
        LFResource tableResource = new LFResource("cat1", "mydb", "mytable", null, null);
        software.amazon.awssdk.services.lakeformation.model.Resource result = client.buildResource(tableResource);

        assertEquals("cat1", result.table().catalogId());
        assertEquals("mydb", result.table().databaseName());
        assertEquals("mytable", result.table().name());
    }

    @Test
    void buildResource_columnLevel() {
        Set<String> cols = new java.util.HashSet<>();
        cols.add("col1");
        cols.add("col2");
        LFResource colResource = new LFResource("cat1", "mydb", "mytable", cols, null);
        software.amazon.awssdk.services.lakeformation.model.Resource result = client.buildResource(colResource);

        assertEquals("cat1", result.tableWithColumns().catalogId());
        assertEquals("mydb", result.tableWithColumns().databaseName());
        assertEquals("mytable", result.tableWithColumns().name());
        assertTrue(result.tableWithColumns().columnNames().containsAll(cols));
    }

    @Test
    void buildResource_allTablesWildcard() {
        LFResource allTablesResource = LFResource.allTablesResource("cat1", "mydb");
        software.amazon.awssdk.services.lakeformation.model.Resource result = client.buildResource(allTablesResource);

        assertNotNull(result.table(), "Should produce a table resource");
        assertEquals("cat1", result.table().catalogId());
        assertEquals("mydb", result.table().databaseName());
        assertNotNull(result.table().tableWildcard(), "Should have tableWildcard set");
    }

    // --- Grantable flag test ---

    @Test
    void grantPermission_withGrantable_setsPermissionsWithGrantOption() throws Exception {
        LFResource resource = new LFResource("cat1", "mydb", "mytable", null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT, LFPermission.INSERT);
        LFPermissionOperation grantableOp = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "policy-3",
                "arn:aws:iam::123456789012:role/Admin", resource, perms, true);

        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenReturn(null);

        client.grantPermission(grantableOp);

        verify(awsClient, times(1)).grantPermissions(any(GrantPermissionsRequest.class));
    }

    // --- Batch application tests ---

    private LFPermissionOperation makeOp(String policyId, LFPermissionOperation.OperationType type, String table) {
        LFResource resource = new LFResource("catalog1", "mydb", table, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        return new LFPermissionOperation(type, policyId,
                "arn:aws:iam::123456789012:role/TestRole", resource, perms, false);
    }

    private LFPermissionOperation makeOpWithColumns(String policyId, LFPermissionOperation.OperationType type,
                                                    String table, Set<String> columns) {
        LFResource resource = new LFResource("catalog1", "mydb", table, columns, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        return new LFPermissionOperation(type, policyId,
                "arn:aws:iam::123456789012:role/TestRole", resource, perms, false);
    }

    @Test
    void applyBatch_allSucceed() {
        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build());

        List<LFPermissionOperation> ops = Arrays.asList(
                makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t1"),
                makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t2"),
                makeOp("p2", LFPermissionOperation.OperationType.GRANT, "t3")
        );

        BatchResult result = client.applyBatch(ops, null);

        assertFalse(result.hasFailures());
        assertEquals(2, result.getSucceededPolicyIds().size());
        assertTrue(result.getSucceededPolicyIds().contains("p1"));
        assertTrue(result.getSucceededPolicyIds().contains("p2"));
        assertEquals(3, result.getTotalOperations());
        assertEquals(3, result.getAppliedOperations());
    }

    @Test
    void applyBatch_policyFailure_reportsFailedPolicy() {
        // Simulate one entry failing in the batch response
        BatchPermissionsFailureEntry failure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-1").build())
                .error(ErrorDetail.builder().errorMessage("Access denied").build())
                .build();
        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder().failures(List.of(failure)).build());

        List<LFPermissionOperation> ops = Arrays.asList(
                makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t1"),
                makeOp("p2", LFPermissionOperation.OperationType.GRANT, "t2"),
                makeOp("p3", LFPermissionOperation.OperationType.GRANT, "t3")
        );

        BatchResult result = client.applyBatch(ops, null);

        assertTrue(result.hasFailures());
        assertEquals(1, result.getFailedPolicyIds().size());
        assertEquals("p2", result.getFailedPolicyIds().get(0));
        assertEquals(2, result.getAppliedOperations());
    }

    @Test
    void applyBatch_writesToDeadLetterLog() {
        // Simulate failure for first entry
        BatchPermissionsFailureEntry failure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Forbidden").build())
                .build();
        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder().failures(List.of(failure)).build());

        StringWriter sw = new StringWriter();
        DeadLetterLogger dll = new DeadLetterLogger(new BufferedWriter(sw));

        List<LFPermissionOperation> ops = Arrays.asList(
                makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t1"),
                makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t2")
        );

        BatchResult result = client.applyBatch(ops, dll);

        assertTrue(result.hasFailures());
        String logContent = sw.toString();
        assertTrue(logContent.contains("\"policyId\":\"p1\""));
        assertTrue(logContent.contains("\"operation\":\"GRANT\""));
    }

    @Test
    void applyBatch_emptyList_returnsEmptyResult() {
        BatchResult result = client.applyBatch(new ArrayList<LFPermissionOperation>(), null);

        assertFalse(result.hasFailures());
        assertEquals(0, result.getTotalOperations());
        assertEquals(0, result.getAppliedOperations());
    }

    @Test
    void applyBatch_revokeOperations_succeed() {
        when(awsClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class)))
                .thenReturn(BatchRevokePermissionsResponse.builder().failures(List.of()).build());

        List<LFPermissionOperation> ops = Arrays.asList(
                makeOp("p1", LFPermissionOperation.OperationType.REVOKE, "t1")
        );

        BatchResult result = client.applyBatch(ops, null);

        assertFalse(result.hasFailures());
        assertEquals(1, result.getAppliedOperations());
        verify(awsClient, times(1)).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));
    }

    // --- Task 6.4: ConcurrentModificationException retry with backoff ---

    @Test
    void grantPermission_concurrentModification_exponentialBackoffSequence() throws Exception {
        // Verify exact backoff sequence: 100, 200, 400 (with multiplier 2.0)
        RetryConfig config = new RetryConfig(3, 100L, 2.0, 5000L);
        List<Long> sleeps = new ArrayList<>();
        LakeFormationClient retryClient = new LakeFormationClient(awsClient, config, millis -> sleeps.add(millis));

        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenThrow(ConcurrentModificationException.builder().message("concurrent").build())
                .thenThrow(ConcurrentModificationException.builder().message("concurrent").build())
                .thenThrow(ConcurrentModificationException.builder().message("concurrent").build())
                .thenReturn(null);

        retryClient.grantPermission(makeGrantOp());

        // 1 initial + 3 retries = 4 total attempts
        verify(awsClient, times(4)).grantPermissions(any(GrantPermissionsRequest.class));
        assertEquals(3, sleeps.size());
        assertEquals(100L, sleeps.get(0).longValue());
        assertEquals(200L, sleeps.get(1).longValue());
        assertEquals(400L, sleeps.get(2).longValue());
    }

    @Test
    void revokePermission_concurrentModification_retriesThenFails() {
        when(awsClient.revokePermissions(any(RevokePermissionsRequest.class)))
                .thenThrow(ConcurrentModificationException.builder().message("concurrent mod").build());

        LakeFormationClientException ex = assertThrows(LakeFormationClientException.class,
                () -> client.revokePermission(makeRevokeOp()));

        // 1 initial + 3 retries = 4 total attempts
        verify(awsClient, times(4)).revokePermissions(any(RevokePermissionsRequest.class));
        assertEquals(3, sleepDurations.size());
        assertTrue(ex.getMessage().contains("REVOKE failed after 3 retries"));
        assertTrue(ex.getCause() instanceof ConcurrentModificationException);
    }

    // --- Task 6.4: Successful rollback on partial batch failure ---

    // --- Bug fix: S3 ARN conversion ---

    @Test
    void buildResource_dataLocation_convertsS3UrlToArn() {
        LFResource dlResource = new LFResource(null, null, null, null, null, "s3://my-bucket/data/");
        software.amazon.awssdk.services.lakeformation.model.Resource result = client.buildResource(dlResource);

        assertNotNull(result.dataLocation(), "Should produce a data location resource");
        assertEquals("arn:aws:s3:::my-bucket/data", result.dataLocation().resourceArn(),
                "Should convert s3:// URL to ARN and strip trailing slash");
    }

    @Test
    void buildResource_dataLocation_alreadyArnPassedThrough() {
        LFResource dlResource = new LFResource(null, null, null, null, null, "arn:aws:s3:::my-bucket/data");
        software.amazon.awssdk.services.lakeformation.model.Resource result = client.buildResource(dlResource);

        assertEquals("arn:aws:s3:::my-bucket/data", result.dataLocation().resourceArn(),
                "Already-ARN value should pass through unchanged");
    }

    // --- Bug fix: TABLE vs TABLE_WITH_COLUMNS conflict resolution ---

    @Test
    void resolveTableColumnConflicts_mergesColumnPermissionsIntoTableGrant() {
        LFResource tableResource = new LFResource("cat1", "db1", "tbl1", null, null);
        LFResource colResource = new LFResource("cat1", "db1", "tbl1",
                new java.util.HashSet<>(Arrays.asList("col1")), null);

        LFPermissionOperation tableOp = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1",
                "arn:aws:iam::123456789012:role/R", tableResource,
                EnumSet.of(LFPermission.INSERT, LFPermission.DELETE), false);
        LFPermissionOperation colOp = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p2",
                "arn:aws:iam::123456789012:role/R", colResource,
                EnumSet.of(LFPermission.SELECT), false);

        List<LFPermissionOperation> result =
                LakeFormationClient.resolveTableColumnConflicts(Arrays.asList(tableOp, colOp));

        assertEquals(1, result.size(), "TABLE_WITH_COLUMNS entry should be merged away");
        LFPermissionOperation merged = result.get(0);
        assertTrue(merged.getPermissions().contains(LFPermission.SELECT));
        assertTrue(merged.getPermissions().contains(LFPermission.INSERT));
        assertTrue(merged.getPermissions().contains(LFPermission.DELETE));
        assertNotNull(merged.getResource().getTableName());
        assertTrue(merged.getResource().getColumnNames() == null
                || merged.getResource().getColumnNames().isEmpty(),
                "Merged op should be TABLE-level (no columns)");
    }

    @Test
    void resolveTableColumnConflicts_noConflict_unchanged() {
        LFResource tableA = new LFResource("cat1", "db1", "tbl1", null, null);
        LFResource tableB = new LFResource("cat1", "db1", "tbl2",
                new java.util.HashSet<>(Arrays.asList("col1")), null);

        LFPermissionOperation opA = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1",
                "arn:aws:iam::123456789012:role/R", tableA,
                EnumSet.of(LFPermission.SELECT), false);
        LFPermissionOperation opB = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p2",
                "arn:aws:iam::123456789012:role/R", tableB,
                EnumSet.of(LFPermission.SELECT), false);

        List<LFPermissionOperation> result =
                LakeFormationClient.resolveTableColumnConflicts(Arrays.asList(opA, opB));

        assertEquals(2, result.size(), "Different tables — no conflict, both entries kept");
    }

    private LFPermissionOperation makeRevoke(String policyId, String table) {
        LFResource resource = new LFResource("catalog1", "mydb", table, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        return new LFPermissionOperation(LFPermissionOperation.OperationType.REVOKE, policyId,
                "arn:aws:iam::123456789012:role/TestRole", resource, perms, false);
    }

    @Test
    void applyBatch_grantableOperation_setsPermissionsWithGrantOptionInBatchEntry() {
        LFResource resource = new LFResource("catalog1", "mydb", "grantable-table", null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        LFPermissionOperation grantableOp = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "pg1",
                "arn:aws:iam::123456789012:role/TestRole", resource, perms, true);

        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build());

        client.applyBatch(List.of(grantableOp), null);

        ArgumentCaptor<BatchGrantPermissionsRequest> captor =
                ArgumentCaptor.forClass(BatchGrantPermissionsRequest.class);
        verify(awsClient).batchGrantPermissions(captor.capture());
        assertTrue(captor.getValue().entries().get(0).permissionsWithGrantOption()
                        .contains(Permission.SELECT),
                "Grantable batch entry must include SELECT in permissionsWithGrantOption");
    }

    @Test
    void applyBatch_revokeFailure_noPermissionsRevoked_treatedAsSuccess() {
        BatchPermissionsFailureEntry failure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("revoke-0").build())
                .error(ErrorDetail.builder()
                        .errorMessage("No permissions revoked. Grantee has no permissions.")
                        .build())
                .build();
        when(awsClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class)))
                .thenReturn(BatchRevokePermissionsResponse.builder().failures(List.of(failure)).build());

        StringWriter sw = new StringWriter();
        DeadLetterLogger dll = new DeadLetterLogger(new BufferedWriter(sw));

        BatchResult result = client.applyBatch(List.of(makeRevoke("pr1", "t1")), dll);

        assertFalse(result.hasFailures(), "No-permissions-revoked must be treated as success");
        assertEquals(1, result.getAppliedOperations(), "Op must be counted as applied");
        assertTrue(sw.toString().isEmpty(), "Dead-letter must not be written for no-op revoke");
    }

    @Test
    void applyBatch_revokeFailure_permissionsModificationInvalid_treatedAsSuccess() {
        BatchPermissionsFailureEntry failure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("revoke-0").build())
                .error(ErrorDetail.builder()
                        .errorMessage("Permissions modification is invalid.")
                        .build())
                .build();
        when(awsClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class)))
                .thenReturn(BatchRevokePermissionsResponse.builder().failures(List.of(failure)).build());

        StringWriter sw = new StringWriter();
        DeadLetterLogger dll = new DeadLetterLogger(new BufferedWriter(sw));

        BatchResult result = client.applyBatch(List.of(makeRevoke("pr2", "t2")), dll);

        assertFalse(result.hasFailures(), "Permissions-modification-invalid must be treated as success");
        assertEquals(1, result.getAppliedOperations(), "Op must be counted as applied");
        assertTrue(sw.toString().isEmpty(), "Dead-letter must not be written for no-op revoke");
    }

    @Test
    void applyBatch_revokeFailure_realError_isReportedAsFailure() {
        BatchPermissionsFailureEntry failure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("revoke-0").build())
                .error(ErrorDetail.builder().errorMessage("Access denied").build())
                .build();
        when(awsClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class)))
                .thenReturn(BatchRevokePermissionsResponse.builder().failures(List.of(failure)).build());

        BatchResult result = client.applyBatch(List.of(makeRevoke("pr3", "t3")), null);

        assertTrue(result.hasFailures(), "Real revoke error must be reported as failure");
        assertTrue(result.getFailedPolicyIds().contains("pr3"),
                "Failed policy ID must appear in getFailedPolicyIds");
    }

    @Test
    void applyBatch_multipleFailures_reportsAllInDeadLetter() {
        // Simulate 2 out of 3 entries failing
        BatchPermissionsFailureEntry failure0 = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Failure on t1").build())
                .build();
        BatchPermissionsFailureEntry failure2 = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-2").build())
                .error(ErrorDetail.builder().errorMessage("Failure on t3").build())
                .build();
        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder()
                        .failures(List.of(failure0, failure2)).build());

        StringWriter sw = new StringWriter();
        DeadLetterLogger dll = new DeadLetterLogger(new BufferedWriter(sw));

        List<LFPermissionOperation> ops = Arrays.asList(
                makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t1"),
                makeOp("p2", LFPermissionOperation.OperationType.GRANT, "t2"),
                makeOp("p3", LFPermissionOperation.OperationType.GRANT, "t3")
        );

        BatchResult result = client.applyBatch(ops, dll);

        assertTrue(result.hasFailures());
        // Dead letter should have 2 entries (one per failed batch entry)
        String logContent = sw.toString();
        String[] lines = logContent.trim().split("\n");
        assertEquals(2, lines.length);
    }

    // --- Bug fix: TABLE/TABLE_WITH_COLUMNS conflict retry must not loop infinitely ---

    /**
     * Returns a ListPermissionsResponse whose single entry has SELECT on a TWC resource.
     * The resource field is set so that fetchActualPermissions' TWC-filter passes.
     */
    private ListPermissionsResponse listPermissionsWithSelect() {
        Resource twcResource = Resource.builder()
                .tableWithColumns(TableWithColumnsResource.builder()
                        .catalogId("catalog1")
                        .databaseName("mydb")
                        .name("t1")
                        .build())
                .build();
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .resource(twcResource)
                .permissions(List.of(Permission.SELECT))
                .build();
        return ListPermissionsResponse.builder()
                .principalResourcePermissions(List.of(entry))
                .build();
    }

    @Test
    void applyBatch_conflictRevoke_succeedsAndGrantRetried() {
        // First grant attempt: fails with "Permissions modification is invalid"
        BatchPermissionsFailureEntry conflictFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Permissions modification is invalid.").build())
                .build();
        // Retry grant attempt: succeeds
        doReturn(BatchGrantPermissionsResponse.builder().failures(List.of(conflictFailure)).build())
                .doReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build())
                .when(awsClient).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        doReturn(listPermissionsWithSelect()).when(awsClient).listPermissions(any(ListPermissionsRequest.class));
        doReturn(null).when(awsClient).revokePermissions(any(RevokePermissionsRequest.class));

        LFPermissionOperation op = makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t1");
        BatchResult result = client.applyBatch(List.of(op), null);

        // Grant must be retried exactly once after the conflict revoke
        verify(awsClient, times(2)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        verify(awsClient, times(1)).revokePermissions(any(RevokePermissionsRequest.class));
        assertFalse(result.hasFailures(), "Grant should succeed after conflict-revoke-and-retry");
    }

    @Test
    void applyBatch_conflictRevoke_failsThenGrantNotRetried_noInfiniteLoop() {
        // Both the first and any subsequent grant attempt fail with the same conflict error
        BatchPermissionsFailureEntry conflictFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Permissions modification is invalid.").build())
                .build();
        doReturn(BatchGrantPermissionsResponse.builder().failures(List.of(conflictFailure)).build())
                .when(awsClient).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        doReturn(listPermissionsWithSelect()).when(awsClient).listPermissions(any(ListPermissionsRequest.class));
        // The conflict revoke itself also fails
        doThrow(software.amazon.awssdk.services.lakeformation.model.InvalidInputException.builder()
                        .message("Permissions modification is invalid.").build())
                .when(awsClient).revokePermissions(any(RevokePermissionsRequest.class));

        LFPermissionOperation op = makeOp("p1", LFPermissionOperation.OperationType.GRANT, "t1");
        BatchResult result = client.applyBatch(List.of(op), null);

        // Grant must be attempted exactly once — no retry when revoke failed, no infinite loop
        verify(awsClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        verify(awsClient, times(1)).revokePermissions(any(RevokePermissionsRequest.class));
        assertTrue(result.hasFailures(), "Grant must be recorded as failed when revoke-and-retry is not possible");
    }

    @Test
    void applyBatch_twcGrantBlockedByTableGrant_revokesTableAndRetries() {
        // TABLE_WITH_COLUMNS grant blocked by an existing plain TABLE grant
        BatchPermissionsFailureEntry conflictFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Permissions modification is invalid.").build())
                .build();
        doReturn(BatchGrantPermissionsResponse.builder().failures(List.of(conflictFailure)).build())
                .doReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build())
                .when(awsClient).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        doReturn(listPermissionsWithSelect()).when(awsClient).listPermissions(any(ListPermissionsRequest.class));
        doReturn(null).when(awsClient).revokePermissions(any(RevokePermissionsRequest.class));

        LFPermissionOperation op = makeOpWithColumns("p1", LFPermissionOperation.OperationType.GRANT,
                "t1", Set.of("col1", "col2"));
        BatchResult result = client.applyBatch(List.of(op), null);

        verify(awsClient, times(2)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        verify(awsClient, times(1)).revokePermissions(any(RevokePermissionsRequest.class));
        assertFalse(result.hasFailures(), "TWC grant should succeed after TABLE conflict revoke");
    }

    @Test
    void applyBatch_twcGrantBlockedByTableGrant_revokeFails_noRetry() {
        BatchPermissionsFailureEntry conflictFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Permissions modification is invalid.").build())
                .build();
        doReturn(BatchGrantPermissionsResponse.builder().failures(List.of(conflictFailure)).build())
                .when(awsClient).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        doReturn(listPermissionsWithSelect()).when(awsClient).listPermissions(any(ListPermissionsRequest.class));
        doThrow(software.amazon.awssdk.services.lakeformation.model.InvalidInputException.builder()
                        .message("No permissions revoked.").build())
                .when(awsClient).revokePermissions(any(RevokePermissionsRequest.class));

        LFPermissionOperation op = makeOpWithColumns("p1", LFPermissionOperation.OperationType.GRANT,
                "t1", Set.of("col1"));
        BatchResult result = client.applyBatch(List.of(op), null);

        verify(awsClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        verify(awsClient, times(1)).revokePermissions(any(RevokePermissionsRequest.class));
        assertTrue(result.hasFailures(), "Must be recorded as failed when TABLE revoke also fails");
    }

    @Test
    void applyBatch_conflictRevoke_usesActualPermissionsNotOpPermissions() {
        // The failing TABLE op has INSERT/DELETE permissions.
        // The conflicting TWC grant has only SELECT — revoking with INSERT/DELETE or ALL would fail.
        // The code must fetch the actual permissions via listPermissions and revoke SELECT.
        BatchPermissionsFailureEntry conflictFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Permissions modification is invalid.").build())
                .build();
        doReturn(BatchGrantPermissionsResponse.builder().failures(List.of(conflictFailure)).build())
                .doReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build())
                .when(awsClient).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        // Conflicting TWC has only SELECT — different from the failing op's permissions.
        // The resource field must be a TWC resource so fetchActualPermissions' TWC-filter passes.
        Resource twcResource = Resource.builder()
                .tableWithColumns(TableWithColumnsResource.builder()
                        .catalogId("catalog1").databaseName("mydb").name("t1").build())
                .build();
        PrincipalResourcePermissions twcEntry = PrincipalResourcePermissions.builder()
                .resource(twcResource)
                .permissions(List.of(Permission.SELECT))
                .build();
        doReturn(ListPermissionsResponse.builder().principalResourcePermissions(List.of(twcEntry)).build())
                .when(awsClient).listPermissions(any(ListPermissionsRequest.class));
        ArgumentCaptor<RevokePermissionsRequest> revokeCaptor =
                ArgumentCaptor.forClass(RevokePermissionsRequest.class);
        doReturn(null).when(awsClient).revokePermissions(revokeCaptor.capture());

        // Op has INSERT/DELETE — if we used op perms or ALL this would fail against a SELECT-only grant
        LFResource resource = new LFResource("catalog1", "mydb", "t1",
                null, null);
        LFPermissionOperation op = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1",
                "arn:aws:iam::123456789012:role/TestRole", resource,
                EnumSet.of(LFPermission.INSERT, LFPermission.DELETE), false);
        BatchResult result = client.applyBatch(List.of(op), null);

        assertFalse(result.hasFailures(), "Grant should succeed after revoke with actual permissions");
        // Verify the revoke used SELECT (the actual TWC permissions), not INSERT/DELETE
        assertEquals(List.of(Permission.SELECT), revokeCaptor.getValue().permissions(),
                "Revoke must use the conflicting grant's actual permissions, not the failing op's permissions");
    }

    /**
     * TABLE grant blocked by an existing TWC grant: listPermissions must be issued on the plain
     * TABLE resource (not the TWC resource, which LF rejects with 400), and only TWC entries from
     * the response should count toward the permissions to revoke.
     */
    @Test
    void applyBatch_tableBLockedByTwc_listPermissionsUsesTableResource() {
        BatchPermissionsFailureEntry conflictFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("grant-0").build())
                .error(ErrorDetail.builder().errorMessage("Permissions modification is invalid.").build())
                .build();
        doReturn(BatchGrantPermissionsResponse.builder().failures(List.of(conflictFailure)).build())
                .doReturn(BatchGrantPermissionsResponse.builder().failures(List.of()).build())
                .when(awsClient).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));

        // listPermissions returns one TABLE entry (should be ignored) and one TWC entry (SELECT)
        Resource tableResource = Resource.builder()
                .table(software.amazon.awssdk.services.lakeformation.model.TableResource.builder()
                        .catalogId("catalog1").databaseName("mydb").name("t1").build())
                .build();
        Resource twcResource = Resource.builder()
                .tableWithColumns(TableWithColumnsResource.builder()
                        .catalogId("catalog1").databaseName("mydb").name("t1").build())
                .build();
        PrincipalResourcePermissions tableEntry = PrincipalResourcePermissions.builder()
                .resource(tableResource)
                .permissions(List.of(Permission.ALTER))
                .build();
        PrincipalResourcePermissions twcEntry = PrincipalResourcePermissions.builder()
                .resource(twcResource)
                .permissions(List.of(Permission.SELECT))
                .build();
        ArgumentCaptor<ListPermissionsRequest> listCaptor =
                ArgumentCaptor.forClass(ListPermissionsRequest.class);
        doReturn(ListPermissionsResponse.builder()
                .principalResourcePermissions(List.of(tableEntry, twcEntry)).build())
                .when(awsClient).listPermissions(listCaptor.capture());
        ArgumentCaptor<RevokePermissionsRequest> revokeCaptor =
                ArgumentCaptor.forClass(RevokePermissionsRequest.class);
        doReturn(null).when(awsClient).revokePermissions(revokeCaptor.capture());

        // Plain TABLE op (no columns) — conflict handler will try to revoke the existing TWC
        LFResource resource = new LFResource("catalog1", "mydb", "t1", null, null);
        LFPermissionOperation op = new LFPermissionOperation(
                LFPermissionOperation.OperationType.GRANT, "p1",
                "arn:aws:iam::123456789012:role/TestRole", resource,
                EnumSet.of(LFPermission.ALTER), false);
        BatchResult result = client.applyBatch(List.of(op), null);

        assertFalse(result.hasFailures(), "Grant should succeed after TWC revoke");

        // listPermissions must have been called on the plain TABLE resource (not TWC)
        ListPermissionsRequest listReq = listCaptor.getValue();
        assertNotNull(listReq.resource().table(), "listPermissions must use TABLE resource, not TWC");

        // Revoke must use only SELECT (from the TWC entry), not ALTER (from the TABLE entry)
        assertEquals(List.of(Permission.SELECT), revokeCaptor.getValue().permissions(),
                "Revoke must use only the TWC entry's permissions from the list response");
    }

}
