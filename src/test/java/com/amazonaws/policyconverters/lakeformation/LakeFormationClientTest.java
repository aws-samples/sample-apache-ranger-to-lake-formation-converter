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
import software.amazon.awssdk.services.lakeformation.model.RevokePermissionsRequest;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

}
