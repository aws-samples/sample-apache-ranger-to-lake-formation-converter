package org.apache.ranger.lakeformation.client;

import org.apache.ranger.lakeformation.model.LFPermission;
import org.apache.ranger.lakeformation.model.LFPermissionOperation;
import org.apache.ranger.lakeformation.model.LFResource;
import org.apache.ranger.lakeformation.model.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.ConcurrentModificationException;
import software.amazon.awssdk.services.lakeformation.model.GrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.LakeFormationException;
import software.amazon.awssdk.services.lakeformation.model.RevokePermissionsRequest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
