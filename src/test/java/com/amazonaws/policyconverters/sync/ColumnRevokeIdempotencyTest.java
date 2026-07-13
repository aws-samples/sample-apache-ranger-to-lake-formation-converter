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
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsRequest;

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
 * Regression test for Bug 2 ("Permissions modification is invalid" on REVOKE retried forever),
 * now guarded at its root cause by the TABLE/TWC subsumption rule.
 *
 * <p>When a Ranger policy produces both a TABLE-level SELECT and a same-table COLUMN-level SELECT,
 * {@code SyncService.detectAndGapTableTwcConflicts} recognizes that the table SELECT subsumes the
 * column grant and drops the redundant TWC <em>before</em> the diff/checkpoint. So the checkpoint
 * only ever holds the TABLE op, the phantom column REVOKE that Bug 2 hinged on is never generated,
 * and steady state issues no further API calls.
 *
 * <p>Scenario:
 *   Cycle 1 — policy emits TABLE SELECT + COLUMN SELECT; only the TABLE grant is applied and
 *             checkpointed (the column grant is subsumed).
 *   Cycle 2 — COLUMN gone from the source; since it was never checkpointed there is no REVOKE.
 *   Cycle 3 — no delta → no API calls.
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
    void tableSelectSubsumesColumnGrant_noPhantomRevoke_checkpointHoldsTableOnly() {
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

        when(awsSdkClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class)))
                .thenReturn(BatchGrantPermissionsResponse.builder()
                        .failures(Collections.emptyList()).build());

        // --- Cycle 1: the table SELECT subsumes the column SELECT before diffing, so only the
        //     TABLE grant is applied and checkpointed — the redundant TWC never lands. ---
        syncService.onPoliciesUpdated(makeServicePolicies(1L));

        verify(awsSdkClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));
        verify(awsSdkClient, never()).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));

        List<LFPermissionOperation> afterCycle1 = syncService.getPreviousOperations();
        assertEquals(1, afterCycle1.size(),
                "Checkpoint after cycle 1 holds only the TABLE op (column grant subsumed)");
        assertNull(afterCycle1.get(0).getResource().getColumnNames(),
                "Checkpointed op must be the TABLE-level grant (no column restriction)");

        // --- Cycle 2: COLUMN gone from the source. Because it was never checkpointed, there is no
        //     phantom REVOKE — the Bug-2 trigger is eliminated at the root. No new API calls. ---
        syncService.onPoliciesUpdated(makeServicePolicies(2L));

        verify(awsSdkClient, never()).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));
        verify(awsSdkClient, times(1)).batchGrantPermissions(any(BatchGrantPermissionsRequest.class));

        List<LFPermissionOperation> afterCycle2 = syncService.getPreviousOperations();
        assertEquals(1, afterCycle2.size(), "Checkpoint still holds only the TABLE op");

        // --- Cycle 3: no delta → still no API calls. ---
        syncService.onPoliciesUpdated(makeServicePolicies(3L));

        verify(awsSdkClient, never()).batchRevokePermissions(any(BatchRevokePermissionsRequest.class));
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
