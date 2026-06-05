package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.config.RetryConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsFailureEntry;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsRequestEntry;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.ErrorDetail;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for Bug 2: "Permissions modification is invalid" on REVOKE retried forever.
 *
 * Scenario:
 *   Cycle 1 — Ranger policy produces both TABLE-level and COLUMN-level SELECT grants.
 *             resolveTableColumnConflicts (inside applyBatch) merges them, but the
 *             checkpoint (previousOperations) stores the raw cedarToLFConverter output:
 *             [tableGrant, colGrant].
 *
 *   Cycle 2 — COLUMN resource gone from current ops. diff generates REVOKE for colGrant.
 *             LF rejects with "Permissions modification is invalid" because the actual
 *             stored grant is table-level (not column-scoped).
 *
 *   Before fix: this REVOKE failure added the shared sourcePolicyId to failedPolicies,
 *               which also evicted tableGrant from the next previousOperations snapshot.
 *               Cycle 3 saw tableGrant as "new" and re-issued the grant — indefinitely.
 *
 *   After fix:  "Permissions modification is invalid" is treated as a no-op revoke.
 *               failedPolicies stays empty. previousOperations advances to [tableGrant].
 *               Cycle 3 produces no diff → no API calls.
 */
@ExtendWith(MockitoExtension.class)
class ColumnRevokeIdempotencyTest {

    private static final String ALICE_ARN = "arn:aws:iam::123456789012:role/alice";
    private static final String CATALOG_ID = "123456789012";
    // Both ops share the same sourcePolicyId — key condition for triggering the infinite-retry bug.
    private static final String SHARED_POLICY_ID = "policy-42";

    @Mock
    private software.amazon.awssdk.services.lakeformation.LakeFormationClient awsSdkClient;
    @Mock
    private RangerToCedarConverter rangerToCedarConverter;
    @Mock
    private CedarToLFConverter cedarToLFConverter;
    @Mock
    private CedarPolicySet cedarPolicySet;

    private SyncService syncService;

    @BeforeEach
    void setUp() {
        RetryConfig retryConfig = new RetryConfig(1, 0L, 1.0, 0L);
        LakeFormationClient realLfClient = new LakeFormationClient(awsSdkClient, retryConfig);

        syncService = new SyncService(
                new RangerPlugin(), rangerToCedarConverter, cedarToLFConverter,
                realLfClient, new GapReporter(), null);
        syncService.start(new SyncConfig(null, null, null, null, null, null, null));

        lenient().when(cedarPolicySet.getPermitCount()).thenReturn(1);
        lenient().when(cedarPolicySet.getForbidCount()).thenReturn(0);
        lenient().when(cedarPolicySet.toCedarString()).thenReturn("");
        when(rangerToCedarConverter.convert(anyList())).thenReturn(cedarPolicySet);
    }

    @Test
    void columnRevokeAfterTableMerge_treatedAsNoOp_checkpointAdvances() {
        LFResource tableResource = new LFResource(CATALOG_ID, "db1", "tbl1", null, null);
        LFResource colResource = new LFResource(
                CATALOG_ID, "db1", "tbl1",
                new HashSet<>(Collections.singletonList("col1")), null);

        LFPermissionOperation tableGrant = new LFPermissionOperation(
                OperationType.GRANT, SHARED_POLICY_ID, ALICE_ARN,
                tableResource, EnumSet.of(LFPermission.SELECT), false);
        LFPermissionOperation colGrant = new LFPermissionOperation(
                OperationType.GRANT, SHARED_POLICY_ID, ALICE_ARN,
                colResource, EnumSet.of(LFPermission.SELECT), false);

        // Cycle 1: both TABLE and COLUMN grant. Cycle 2+: TABLE only.
        when(cedarToLFConverter.convert(any(CedarPolicySet.class)))
                .thenReturn(Arrays.asList(tableGrant, colGrant))  // cycle 1
                .thenReturn(Collections.singletonList(tableGrant)); // cycle 2 and 3

        // --- Cycle 1: grant batch succeeds, checkpoint = [tableGrant, colGrant] ---
        when(awsSdkClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder()
                        .failures(Collections.emptyList()).build());

        syncService.onPoliciesUpdated(makeServicePolicies(1L));

        verify(awsSdkClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        verify(awsSdkClient, never()).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));

        List<LFPermissionOperation> afterCycle1 = syncService.getPreviousOperations();
        assertEquals(2, afterCycle1.size(),
                "Checkpoint after cycle 1 must hold both TABLE and COLUMN ops");

        // --- Cycle 2: COLUMN gone, diff emits REVOKE for colGrant.
        //     LF rejects with "Permissions modification is invalid". ---
        BatchPermissionsFailureEntry invalidRevokeFailure = BatchPermissionsFailureEntry.builder()
                .requestEntry(BatchPermissionsRequestEntry.builder().id("revoke-0").build())
                .error(ErrorDetail.builder()
                        .errorMessage("Permissions modification is invalid")
                        .build())
                .build();
        when(awsSdkClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class)))
                .thenReturn(BatchRevokePermissionsResponse.builder()
                        .failures(Collections.singletonList(invalidRevokeFailure)).build());

        syncService.onPoliciesUpdated(makeServicePolicies(2L));

        verify(awsSdkClient, times(1)).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));
        // No new grant in cycle 2 — TABLE entry is unchanged.
        verify(awsSdkClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));

        // With the fix: failedPolicies is empty → previousOperations = [tableGrant].
        // Without the fix: policy-42 in failedPolicies → tableGrant evicted →
        //                  previousOperations = [] → cycle 3 sees tableGrant as new.
        List<LFPermissionOperation> afterCycle2 = syncService.getPreviousOperations();
        assertEquals(1, afterCycle2.size(),
                "Checkpoint after cycle 2 must contain only the TABLE op. "
                + "With the fix, failedPolicies stays empty so the TABLE grant (same "
                + "sourcePolicyId) is NOT evicted from the snapshot.");
        assertNull(afterCycle2.get(0).getResource().getColumnNames(),
                "Surviving op must be the TABLE-level grant (no column restriction)");

        // --- Cycle 3: same TABLE-only state in both checkpoint and converter.
        //     computeDiff produces no delta → no API calls.
        //     This is the regression guard: without the fix batchGrantPermissions would
        //     be called a second time because tableGrant was evicted from the checkpoint. ---
        syncService.onPoliciesUpdated(makeServicePolicies(3L));

        verify(awsSdkClient, times(1)).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));
        verify(awsSdkClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
    }

    private static ServicePolicies makeServicePolicies(long version) {
        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(version);
        sp.setPolicies(Collections.emptyList());
        return sp;
    }
}
