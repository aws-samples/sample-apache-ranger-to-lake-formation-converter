package com.amazonaws.policyconverters.sync;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.BatchResult;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapEntry.GapType;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SyncService with Cedar pipeline wiring.
 * Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5
 */
class SyncServiceTest {

    private RangerPlugin plugin;
    private RangerToCedarConverter rangerToCedarConverter;
    private CedarToLFConverter cedarToLFConverter;
    private LakeFormationClient lakeFormationClient;
    private GapReporter gapReporter;
    private DeadLetterLogger deadLetterLogger;
    private SyncService syncService;
    private SyncConfig syncConfig;

    @BeforeEach
    void setUp() {
        plugin = new RangerPlugin();
        rangerToCedarConverter = mock(RangerToCedarConverter.class);
        cedarToLFConverter = mock(CedarToLFConverter.class);
        lakeFormationClient = mock(LakeFormationClient.class);
        gapReporter = new GapReporter();
        deadLetterLogger = mock(DeadLetterLogger.class);

        syncService = new SyncService(
                plugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger);

        syncConfig = new SyncConfig(null, null, null, 30000L, 5, 2000L, "/tmp/dead-letter.log");
    }

    // ---------------------------------------------------------------
    // Lifecycle tests
    // ---------------------------------------------------------------

    @Test
    void startRegistersAsListenerAndSetsRunning() {
        syncService.start(syncConfig);
        assertTrue(syncService.isRunning());
    }

    @Test
    void stopUnregistersListenerAndClearsRunning() {
        syncService.start(syncConfig);
        syncService.stop();
        assertFalse(syncService.isRunning());
    }

    @Test
    void startIsIdempotent() {
        syncService.start(syncConfig);
        syncService.start(syncConfig);
        assertTrue(syncService.isRunning());
    }

    @Test
    void stopIsIdempotentWhenNotRunning() {
        assertDoesNotThrow(() -> syncService.stop());
    }

    @Test
    void updateIgnoredWhenNotRunning() {
        ServicePolicies sp = createServicePolicies(1L, 1);
        syncService.onPoliciesUpdated(sp);
        verifyNoInteractions(rangerToCedarConverter);
        verifyNoInteractions(lakeFormationClient);
    }

    // ---------------------------------------------------------------
    // End-to-end Cedar pipeline flow (Req 11.1, 11.2)
    // ---------------------------------------------------------------

    @Test
    void endToEndFlow_rangerPoliciesThroughCedarPipelineToApplyBatch() {
        syncService.start(syncConfig);

        // Mock the Cedar pipeline: RangerToCedarConverter → CedarPolicySet → CedarToLFConverter → ops
        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(mockPolicySet.getPermitCount()).thenReturn(2);
        when(mockPolicySet.getForbidCount()).thenReturn(0);

        List<LFPermissionOperation> ops = Arrays.asList(
                makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null),
                makeGrantOp("1", "arn:aws:iam::123:user/bob", "db1", "table1")
        );
        when(cedarToLFConverter.convert(mockPolicySet)).thenReturn(ops);

        BatchResult batchResult = new BatchResult(
                Collections.singletonList("1"), Collections.<String>emptyList(), 2, 2, 0);
        when(lakeFormationClient.applyBatch(anyList(), any())).thenReturn(batchResult);

        // Trigger policy update
        ServicePolicies sp = createServicePolicies(1L, 1);
        syncService.onPoliciesUpdated(sp);

        // Verify the full pipeline was invoked in order
        verify(rangerToCedarConverter).convert(anyList());
        verify(cedarToLFConverter).convert(mockPolicySet);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(2, applied.size());
        assertTrue(applied.stream().allMatch(op -> op.getOperationType() == OperationType.GRANT));
    }

    @Test
    void firstUpdatePerformsBulkSync_allOperationsAreNewGrants() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(mockPolicySet.getPermitCount()).thenReturn(1);
        when(mockPolicySet.getForbidCount()).thenReturn(0);

        List<LFPermissionOperation> ops = Collections.singletonList(
                makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null));
        when(cedarToLFConverter.convert(mockPolicySet)).thenReturn(ops);
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 1, 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Previous operations should now contain the ops from this cycle
        assertEquals(1, syncService.getPreviousOperations().size());
    }

    // ---------------------------------------------------------------
    // Diff mechanism: grants + revocations (Req 11.2)
    // ---------------------------------------------------------------

    @Test
    void secondUpdateComputesDiff_producesGrantsAndRevocations() {
        syncService.start(syncConfig);

        // First update: op1 + op2
        CedarPolicySet mockPolicySet1 = mock(CedarPolicySet.class);
        when(mockPolicySet1.getPermitCount()).thenReturn(2);
        when(mockPolicySet1.getForbidCount()).thenReturn(0);

        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        LFPermissionOperation op2 = makeGrantOp("1", "arn:aws:iam::123:user/bob", "db1", "table1");

        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet1);
        when(cedarToLFConverter.convert(mockPolicySet1)).thenReturn(Arrays.asList(op1, op2));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 2, 2, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Second update: op1 unchanged, op2 removed, op3 added
        CedarPolicySet mockPolicySet2 = mock(CedarPolicySet.class);
        when(mockPolicySet2.getPermitCount()).thenReturn(2);
        when(mockPolicySet2.getForbidCount()).thenReturn(0);

        LFPermissionOperation op3 = makeGrantOp("2", "arn:aws:iam::123:user/charlie", "db2", null);

        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet2);
        when(cedarToLFConverter.convert(mockPolicySet2)).thenReturn(Arrays.asList(op1, op3));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Arrays.asList("1", "2"),
                        Collections.<String>emptyList(), 2, 2, 0));

        syncService.onPoliciesUpdated(createServicePolicies(2L, 2));

        // Verify second batch: 1 new grant (op3) + 1 revoke (op2)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient, times(2)).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> secondBatch = captor.getAllValues().get(1);
        assertEquals(2, secondBatch.size());

        long grantCount = secondBatch.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT).count();
        long revokeCount = secondBatch.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE).count();
        assertEquals(1, grantCount);
        assertEquals(1, revokeCount);
    }

    @Test
    void noChangesSkipsApplyBatch() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(1);
        when(mockPolicySet.getForbidCount()).thenReturn(0);

        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(mockPolicySet)).thenReturn(Collections.singletonList(op1));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 1, 1, 0));

        // First update
        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Second update with same operations — no diff
        syncService.onPoliciesUpdated(createServicePolicies(2L, 1));

        // applyBatch should only be called once (for the first update)
        verify(lakeFormationClient, times(1)).applyBatch(anyList(), any());
    }

    // ---------------------------------------------------------------
    // Gap entries from both converters (Req 11.5)
    // ---------------------------------------------------------------

    @Test
    void gapEntriesFromRangerToCedarConverterAppearInGapReporter() {
        syncService.start(syncConfig);

        // Simulate RangerToCedarConverter recording a DATA_MASKING gap
        when(rangerToCedarConverter.convert(anyList())).thenAnswer(invocation -> {
            gapReporter.recordGap(new GapEntry(
                    "42", "masking-policy", GapType.DATA_MASKING,
                    "db1/table1", "Data masking not supported in Cedar",
                    "Use column-level permissions"));
            CedarPolicySet mockPs = mock(CedarPolicySet.class);
            when(mockPs.getPermitCount()).thenReturn(0);
            when(mockPs.getForbidCount()).thenReturn(0);
            when(mockPs.toCedarString()).thenReturn("");
            return mockPs;
        });
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.emptyList());

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        List<GapEntry> entries = gapReporter.getReport().getEntries();
        assertEquals(1, entries.size());
        assertEquals(GapType.DATA_MASKING, entries.get(0).getGapType());
    }

    @Test
    void gapEntriesFromCedarToLFConverterAppearInGapReporter() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(1);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);

        // Simulate CedarToLFConverter recording an UNSUPPORTED_ACTION gap
        when(cedarToLFConverter.convert(mockPolicySet)).thenAnswer(invocation -> {
            gapReporter.recordGap(new GapEntry(
                    "55", null, GapType.UNSUPPORTED_ACTION,
                    "some-resource", "Action 'CUSTOM_ACTION' not supported by LF",
                    "Remove or remap the action"));
            return Collections.emptyList();
        });

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        List<GapEntry> entries = gapReporter.getReport().getEntries();
        assertEquals(1, entries.size());
        assertEquals(GapType.UNSUPPORTED_ACTION, entries.get(0).getGapType());
    }

    @Test
    void gapEntriesFromBothConvertersAggregateInSharedGapReporter() {
        syncService.start(syncConfig);

        // RangerToCedarConverter records a DATA_MASKING gap
        when(rangerToCedarConverter.convert(anyList())).thenAnswer(invocation -> {
            gapReporter.recordGap(new GapEntry(
                    "10", "masking-policy", GapType.DATA_MASKING,
                    "db1/table1", "Data masking not supported",
                    "Use column-level permissions"));
            CedarPolicySet mockPs = mock(CedarPolicySet.class);
            when(mockPs.getPermitCount()).thenReturn(1);
            when(mockPs.getForbidCount()).thenReturn(0);
            return mockPs;
        });

        // CedarToLFConverter records an UNSUPPORTED_ACTION gap and an UNMAPPED_RESOURCE gap
        when(cedarToLFConverter.convert(any(CedarPolicySet.class))).thenAnswer(invocation -> {
            gapReporter.recordGap(new GapEntry(
                    "11", null, GapType.UNSUPPORTED_ACTION,
                    "resource-a", "Action not supported by LF",
                    "Remap the action"));
            gapReporter.recordGap(new GapEntry(
                    "12", null, GapType.UNMAPPED_RESOURCE,
                    "urn:custom:resource", "Non-ARN identifier",
                    "Configure resource mapping"));
            return Collections.emptyList();
        });

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // All 3 gap entries should be in the shared GapReporter
        List<GapEntry> entries = gapReporter.getReport().getEntries();
        assertEquals(3, entries.size());

        Map<GapType, Integer> summary = gapReporter.getReport().getSummary();
        assertEquals(Integer.valueOf(1), summary.get(GapType.DATA_MASKING));
        assertEquals(Integer.valueOf(1), summary.get(GapType.UNSUPPORTED_ACTION));
        assertEquals(Integer.valueOf(1), summary.get(GapType.UNMAPPED_RESOURCE));
    }

    // ---------------------------------------------------------------
    // Plugin registration tests
    // ---------------------------------------------------------------

    @Test
    void startRegistersSyncServiceAsPluginListener() {
        RangerPlugin realPlugin = new RangerPlugin();

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(0);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.emptyList());

        SyncService svc = new SyncService(
                realPlugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger);
        svc.start(syncConfig);

        // When setPolicies is called on the plugin, it should delegate to SyncService
        ServicePolicies sp = createServicePolicies(1L, 1);
        realPlugin.setPolicies(sp);

        verify(rangerToCedarConverter).convert(anyList());
    }

    @Test
    void stopUnregistersPluginListener() {
        RangerPlugin realPlugin = new RangerPlugin();
        SyncService svc = new SyncService(
                realPlugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger);

        svc.start(syncConfig);
        svc.stop();

        ServicePolicies sp = createServicePolicies(1L, 1);
        realPlugin.setPolicies(sp);

        verifyNoInteractions(rangerToCedarConverter);
    }

    // ---------------------------------------------------------------
    // Connectivity resilience tests
    // ---------------------------------------------------------------

    @Test
    void lastKnownPoliciesUpdatedOnEachSync() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(0);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.emptyList());

        ServicePolicies sp = createServicePolicies(1L, 3);
        syncService.onPoliciesUpdated(sp);

        assertEquals(3, syncService.getLastKnownPolicies().size());
    }

    @Test
    void onConnectivityLostDoesNotClearState() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(0);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.emptyList());

        syncService.onPoliciesUpdated(createServicePolicies(1L, 3));
        syncService.onConnectivityLost();

        assertEquals(3, syncService.getLastKnownPolicies().size());
        assertTrue(syncService.isRunning());
    }

    @Test
    void onConnectivityRestoredDoesNotThrow() {
        syncService.start(syncConfig);
        assertDoesNotThrow(() -> syncService.onConnectivityRestored());
    }

    @Test
    void onConnectivityLostIgnoredWhenNotRunning() {
        assertDoesNotThrow(() -> syncService.onConnectivityLost());
    }

    @Test
    void onConnectivityRestoredIgnoredWhenNotRunning() {
        assertDoesNotThrow(() -> syncService.onConnectivityRestored());
    }

    // ---------------------------------------------------------------
    // computeDiff tests
    // ---------------------------------------------------------------

    @Test
    void computeDiffEmptyToNonEmpty() {
        List<LFPermissionOperation> previous = Collections.emptyList();
        List<LFPermissionOperation> current = Arrays.asList(
                makeGrantOp("1", "arn:user/a", "db1", null),
                makeGrantOp("1", "arn:user/b", "db1", "t1")
        );

        SyncService.PolicyDiff diff = SyncService.computeDiff(previous, current);
        assertEquals(2, diff.getNewGrants().size());
        assertEquals(0, diff.getRevocations().size());
        assertEquals(0, diff.getUnchangedCount());
    }

    @Test
    void computeDiffNonEmptyToEmpty() {
        List<LFPermissionOperation> previous = Arrays.asList(
                makeGrantOp("1", "arn:user/a", "db1", null),
                makeGrantOp("1", "arn:user/b", "db1", "t1")
        );
        List<LFPermissionOperation> current = Collections.emptyList();

        SyncService.PolicyDiff diff = SyncService.computeDiff(previous, current);
        assertEquals(0, diff.getNewGrants().size());
        assertEquals(2, diff.getRevocations().size());
        assertTrue(diff.getRevocations().stream()
                .allMatch(op -> op.getOperationType() == OperationType.REVOKE));
    }

    @Test
    void computeDiffIdenticalSnapshots() {
        LFPermissionOperation op = makeGrantOp("1", "arn:user/a", "db1", null);
        SyncService.PolicyDiff diff = SyncService.computeDiff(
                Collections.singletonList(op), Collections.singletonList(op));
        assertEquals(0, diff.getNewGrants().size());
        assertEquals(0, diff.getRevocations().size());
        assertEquals(1, diff.getUnchangedCount());
    }

    @Test
    void computeDiffMixedChanges() {
        LFPermissionOperation unchanged = makeGrantOp("1", "arn:user/a", "db1", null);
        LFPermissionOperation removed = makeGrantOp("1", "arn:user/b", "db1", "t1");
        LFPermissionOperation added = makeGrantOp("2", "arn:user/c", "db2", null);

        SyncService.PolicyDiff diff = SyncService.computeDiff(
                Arrays.asList(unchanged, removed), Arrays.asList(unchanged, added));

        assertEquals(1, diff.getNewGrants().size());
        assertEquals(1, diff.getRevocations().size());
        assertEquals(1, diff.getUnchangedCount());
        assertEquals("arn:user/c", diff.getNewGrants().get(0).getPrincipalArn());
        assertEquals(OperationType.GRANT, diff.getNewGrants().get(0).getOperationType());
        assertEquals("arn:user/b", diff.getRevocations().get(0).getPrincipalArn());
        assertEquals(OperationType.REVOKE, diff.getRevocations().get(0).getOperationType());
    }

    @Test
    void computeDiffIgnoresPolicyIdChanges() {
        LFPermissionOperation prev = makeGrantOp("1", "arn:user/a", "db1", null);
        LFPermissionOperation curr = makeGrantOp("99", "arn:user/a", "db1", null);

        SyncService.PolicyDiff diff = SyncService.computeDiff(
                Collections.singletonList(prev), Collections.singletonList(curr));
        assertEquals(0, diff.getNewGrants().size());
        assertEquals(0, diff.getRevocations().size());
        assertEquals(1, diff.getUnchangedCount());
    }

    @Test
    void computeDiffDistinguishesByGrantable() {
        LFPermissionOperation nonGrantable = new LFPermissionOperation(
                OperationType.GRANT, "1", "arn:user/a",
                new LFResource("cat", "db1", null, null, null),
                EnumSet.of(LFPermission.SELECT), false);
        LFPermissionOperation grantable = new LFPermissionOperation(
                OperationType.GRANT, "1", "arn:user/a",
                new LFResource("cat", "db1", null, null, null),
                EnumSet.of(LFPermission.SELECT), true);

        SyncService.PolicyDiff diff = SyncService.computeDiff(
                Collections.singletonList(nonGrantable), Collections.singletonList(grantable));
        assertEquals(1, diff.getNewGrants().size());
        assertEquals(1, diff.getRevocations().size());
        assertEquals(0, diff.getUnchangedCount());
    }

    @Test
    void computeDiffBothEmpty() {
        SyncService.PolicyDiff diff = SyncService.computeDiff(
                Collections.<LFPermissionOperation>emptyList(),
                Collections.<LFPermissionOperation>emptyList());
        assertEquals(0, diff.getNewGrants().size());
        assertEquals(0, diff.getRevocations().size());
        assertEquals(0, diff.getUnchangedCount());
    }

    @Test
    void grantableTrueOnOperationFlowsThroughToApplyBatch() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(mockPolicySet.getPermitCount()).thenReturn(1);
        when(mockPolicySet.getForbidCount()).thenReturn(0);

        // Operation with isGrantable=true — simulates delegateAdmin=true flowing through the pipeline
        LFResource resource = new LFResource("cat", "db1", "table1", null, null);
        LFPermissionOperation grantableOp = new LFPermissionOperation(
                OperationType.GRANT, "42", "arn:aws:iam::123:role/data_admin",
                resource, EnumSet.of(LFPermission.SELECT), true);
        when(cedarToLFConverter.convert(mockPolicySet)).thenReturn(Collections.singletonList(grantableOp));

        BatchResult batchResult = new BatchResult(
                Collections.singletonList("42"), Collections.<String>emptyList(), 1, 1, 0);
        when(lakeFormationClient.applyBatch(anyList(), any())).thenReturn(batchResult);

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(1, applied.size());
        assertTrue(applied.get(0).isGrantable(),
                "An operation with isGrantable=true must reach applyBatch unchanged");
    }

    // ---------------------------------------------------------------
    // Audit logging tests
    // ---------------------------------------------------------------

    @Test
    void formatResourcePathDatabaseOnly() {
        LFResource resource = new LFResource("cat-1", "mydb", null, null, null);
        assertEquals("cat-1/mydb", SyncService.formatResourcePath(resource));
    }

    @Test
    void formatResourcePathDatabaseAndTable() {
        LFResource resource = new LFResource("cat-1", "mydb", "mytable", null, null);
        assertEquals("cat-1/mydb/mytable", SyncService.formatResourcePath(resource));
    }

    @Test
    void formatResourcePathWithColumns() {
        Set<String> columns = new HashSet<>(Arrays.asList("col1", "col2"));
        LFResource resource = new LFResource("cat-1", "mydb", "mytable", columns, null);
        String path = SyncService.formatResourcePath(resource);
        assertTrue(path.startsWith("cat-1/mydb/mytable/"));
        assertTrue(path.contains("col1"));
        assertTrue(path.contains("col2"));
    }

    @Test
    void formatResourcePathNullResource() {
        assertEquals("unknown", SyncService.formatResourcePath(null));
    }

    @Test
    void logAuditEntryDoesNotThrow() {
        LFPermissionOperation op = makeGrantOp("42", "arn:aws:iam::123:user/alice", "db1", "t1");
        assertDoesNotThrow(() -> SyncService.logAuditEntry(op));
    }

    // ---------------------------------------------------------------
    // Failed-grant retry behavior (snapshot exclusion)
    // ---------------------------------------------------------------

    /**
     * Verifies that when applyBatch reports a policy as failed, that policy's
     * operation is excluded from previousOperations (the snapshot), causing
     * computeDiff to treat it as a new grant on the next cycle and retry it.
     *
     * Exercises the scheduler-driven path via {@code executeSyncCycle()}.
     *
     * Cycle 1: applyBatch fails policy "42" → snapshot must NOT contain op for "42"
     * Cycle 2: same policy "42" appears again → computeDiff sees it as new → applyBatch
     *          is called again with a GRANT for "42"
     */
    @Test
    void failedGrantsAreExcludedFromSnapshotAndRetriedOnNextCycle() {
        // Build a SyncService in multi-service mode so executeSyncCycle() is functional.
        BaseRangerService mockRangerService = mock(BaseRangerService.class);
        when(mockRangerService.getServiceType()).thenReturn("lakeformation");
        when(mockRangerService.getServiceInstanceName()).thenReturn("lakeformation-instance");
        when(mockRangerService.getLastKnownGoodPolicies()).thenReturn(Collections.emptyList());

        // Both cycles see the same single policy — the mock always returns it.
        ServicePolicies sp = createServicePolicies(1L, 1);
        when(mockRangerService.getLatestPolicies()).thenReturn(sp);

        SyncService multiSyncService = new SyncService(
                Collections.singletonList(mockRangerService),
                rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger,
                null, null);
        multiSyncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(1);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);

        LFPermissionOperation grantOp = makeGrantOp("42", "arn:aws:iam::123:user/alice", "db1", "table1");
        when(cedarToLFConverter.convert(mockPolicySet)).thenReturn(Collections.singletonList(grantOp));

        // Cycle 1: applyBatch reports policy "42" as failed
        BatchResult failedResult = new BatchResult(
                Collections.<String>emptyList(),
                Collections.singletonList("42"),
                1, 0, 1);
        when(lakeFormationClient.applyBatch(anyList(), any())).thenReturn(failedResult);

        multiSyncService.executeSyncCycle();

        // Guard: confirm applyBatch was actually invoked in cycle 1 (rules out early-exit paths).
        verify(lakeFormationClient, times(1)).applyBatch(anyList(), any());

        // The failed operation must NOT be in the snapshot — otherwise cycle 2 would
        // see it as unchanged and skip it.
        List<LFPermissionOperation> snapshotAfterCycle1 = multiSyncService.getPreviousOperations();
        assertFalse(
                snapshotAfterCycle1.stream().anyMatch(op -> "42".equals(op.getSourcePolicyId())),
                "Failed policy '42' should be excluded from previousOperations after cycle 1");

        // Cycle 2: same policy still present — applyBatch now succeeds
        BatchResult successResult = new BatchResult(
                Collections.singletonList("42"),
                Collections.<String>emptyList(),
                1, 1, 0);
        when(lakeFormationClient.applyBatch(anyList(), any())).thenReturn(successResult);

        multiSyncService.executeSyncCycle();

        // applyBatch must have been invoked on cycle 2 with a GRANT for policy "42"
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient, times(2)).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> cycle2Batch = captor.getAllValues().get(1);
        assertEquals(1, cycle2Batch.size(),
                "Cycle 2 batch should contain exactly the retried grant");
        LFPermissionOperation retried = cycle2Batch.get(0);
        assertEquals(OperationType.GRANT, retried.getOperationType(),
                "Retried operation should be a GRANT");
        assertEquals("42", retried.getSourcePolicyId(),
                "Retried operation should be for the previously failed policy '42'");

        // After successful cycle 2, the snapshot should contain the operation
        List<LFPermissionOperation> snapshotAfterCycle2 = multiSyncService.getPreviousOperations();
        assertTrue(
                snapshotAfterCycle2.stream().anyMatch(op -> "42".equals(op.getSourcePolicyId())),
                "Snapshot after successful cycle 2 should contain policy '42'");
    }

    // ---------------------------------------------------------------
    // Null policy list handling
    // ---------------------------------------------------------------

    @Test
    void nullPolicyListTreatedAsEmpty() {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(0);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.emptyList());

        ServicePolicies sp = new ServicePolicies();
        sp.setPolicyVersion(1L);
        sp.setPolicies(null);

        syncService.onPoliciesUpdated(sp);

        verify(rangerToCedarConverter).convert(eq(Collections.<RangerPolicy>emptyList()));
    }

    // ---------------------------------------------------------------
    // TABLE / TWC conflict detection tests
    // ---------------------------------------------------------------

    /**
     * TABLE op for policy 100 + TWC op for policy 200 on the same (principal, table).
     * Lower ID (100) wins; policy 200 (TWC) is suppressed and logged as GAP.
     */
    @Test
    void tableTwcConflict_lowerIdWins_loserSuppressedAndGapped() {
        BaseRangerService mockRangerService = mock(BaseRangerService.class);
        when(mockRangerService.getServiceType()).thenReturn("lakeformation");
        when(mockRangerService.getServiceInstanceName()).thenReturn("lakeformation-instance");
        when(mockRangerService.getLastKnownGoodPolicies()).thenReturn(Collections.emptyList());
        ServicePolicies sp = createServicePolicies(1L, 1);
        when(mockRangerService.getLatestPolicies()).thenReturn(sp);

        SyncService svc = new SyncService(
                Collections.singletonList(mockRangerService),
                rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger,
                null, null);
        svc.start(syncConfig);

        CedarPolicySet mockPs = mock(CedarPolicySet.class);
        when(mockPs.getPermitCount()).thenReturn(2);
        when(mockPs.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPs);

        // TABLE op — policy 100 (lower → wins)
        LFResource tableRes = new LFResource("cat", "db", "products", null, null);
        LFPermissionOperation tableOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:100", "arn:aws:iam::123:role/analyst",
                tableRes, EnumSet.of(LFPermission.SELECT), false);

        // TWC op — policy 200 (higher → loses)
        LFResource twcRes = new LFResource("cat", "db", "products",
                java.util.Set.of("created_at"), null);
        LFPermissionOperation twcOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:200", "arn:aws:iam::123:role/analyst",
                twcRes, EnumSet.of(LFPermission.SELECT), false);

        when(cedarToLFConverter.convert(mockPs)).thenReturn(Arrays.asList(tableOp, twcOp));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("lakeformation:100"),
                        Collections.emptyList(), 1, 1, 0));

        svc.executeSyncCycle();

        // applyBatch must only see the winning (TABLE) op
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));
        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(1, applied.size(), "Only the winning op should reach applyBatch");
        assertEquals("lakeformation:100", applied.get(0).getSourcePolicyId());

        // Dead-letter must have a GAP entry for the losing TWC op
        ArgumentCaptor<LFPermissionOperation> gapCaptor = ArgumentCaptor.forClass(LFPermissionOperation.class);
        verify(deadLetterLogger).logGapOperation(gapCaptor.capture(), contains("CONFLICTING_LF_RESOURCE_TYPE"));
        assertEquals("lakeformation:200", gapCaptor.getValue().getSourcePolicyId());
    }

    /**
     * TWC op for policy 50 + TABLE op for policy 300 on the same (principal, table).
     * TWC has the lower ID (50) → TWC wins, TABLE loses.
     */
    @Test
    void tableTwcConflict_twcWinsWhenItHasLowerId() {
        BaseRangerService mockRangerService = mock(BaseRangerService.class);
        when(mockRangerService.getServiceType()).thenReturn("lakeformation");
        when(mockRangerService.getServiceInstanceName()).thenReturn("lakeformation-instance");
        when(mockRangerService.getLastKnownGoodPolicies()).thenReturn(Collections.emptyList());
        ServicePolicies sp = createServicePolicies(1L, 1);
        when(mockRangerService.getLatestPolicies()).thenReturn(sp);

        SyncService svc = new SyncService(
                Collections.singletonList(mockRangerService),
                rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger,
                null, null);
        svc.start(syncConfig);

        CedarPolicySet mockPs = mock(CedarPolicySet.class);
        when(mockPs.getPermitCount()).thenReturn(2);
        when(mockPs.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPs);

        // TWC op — policy 50 (lower → wins)
        LFResource twcRes = new LFResource("cat", "db", "events",
                java.util.Set.of("ts"), null);
        LFPermissionOperation twcOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:50", "arn:aws:iam::123:role/analyst",
                twcRes, EnumSet.of(LFPermission.SELECT), false);

        // TABLE op — policy 300 (higher → loses)
        LFResource tableRes = new LFResource("cat", "db", "events", null, null);
        LFPermissionOperation tableOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:300", "arn:aws:iam::123:role/analyst",
                tableRes, EnumSet.of(LFPermission.SELECT), false);

        when(cedarToLFConverter.convert(mockPs)).thenReturn(Arrays.asList(twcOp, tableOp));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("lakeformation:50"),
                        Collections.emptyList(), 1, 1, 0));

        svc.executeSyncCycle();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));
        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(1, applied.size());
        assertEquals("lakeformation:50", applied.get(0).getSourcePolicyId());

        verify(deadLetterLogger).logGapOperation(
                argThat(op -> "lakeformation:300".equals(op.getSourcePolicyId())),
                contains("CONFLICTING_LF_RESOURCE_TYPE"));
    }

    /**
     * When TABLE and TWC conflict, the dead-letter gap must be logged only once, not on every cycle.
     */
    @Test
    void tableTwcConflict_gapLoggedOnlyOnFirstDetection() {
        BaseRangerService mockRangerService = mock(BaseRangerService.class);
        when(mockRangerService.getServiceType()).thenReturn("lakeformation");
        when(mockRangerService.getServiceInstanceName()).thenReturn("lakeformation-instance");
        when(mockRangerService.getLastKnownGoodPolicies()).thenReturn(Collections.emptyList());
        when(mockRangerService.getLatestPolicies()).thenReturn(createServicePolicies(1L, 1));

        SyncService svc = new SyncService(
                Collections.singletonList(mockRangerService),
                rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger,
                null, null);
        svc.start(syncConfig);

        CedarPolicySet mockPs = mock(CedarPolicySet.class);
        when(mockPs.getPermitCount()).thenReturn(2);
        when(mockPs.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPs);

        LFResource tableRes = new LFResource("cat", "db", "sales", null, null);
        LFPermissionOperation tableOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:10", "arn:aws:iam::123:role/analyst",
                tableRes, EnumSet.of(LFPermission.SELECT), false);
        LFResource twcRes = new LFResource("cat", "db", "sales",
                java.util.Set.of("amount"), null);
        LFPermissionOperation twcOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:20", "arn:aws:iam::123:role/analyst",
                twcRes, EnumSet.of(LFPermission.SELECT), false);

        // Both cycles return the same conflicting pair
        when(cedarToLFConverter.convert(mockPs)).thenReturn(Arrays.asList(tableOp, twcOp));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("lakeformation:10"),
                        Collections.emptyList(), 1, 1, 0));

        svc.executeSyncCycle();
        svc.executeSyncCycle();

        // logGapOperation should be called exactly once regardless of cycle count
        verify(deadLetterLogger, times(1)).logGapOperation(any(), anyString());
    }

    /**
     * No conflict when two operations touch the same table but for different principals.
     */
    @Test
    void tableTwcConflict_noneWhenDifferentPrincipals() {
        BaseRangerService mockRangerService = mock(BaseRangerService.class);
        when(mockRangerService.getServiceType()).thenReturn("lakeformation");
        when(mockRangerService.getServiceInstanceName()).thenReturn("lakeformation-instance");
        when(mockRangerService.getLastKnownGoodPolicies()).thenReturn(Collections.emptyList());
        when(mockRangerService.getLatestPolicies()).thenReturn(createServicePolicies(1L, 1));

        SyncService svc = new SyncService(
                Collections.singletonList(mockRangerService),
                rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger,
                null, null);
        svc.start(syncConfig);

        CedarPolicySet mockPs = mock(CedarPolicySet.class);
        when(mockPs.getPermitCount()).thenReturn(2);
        when(mockPs.getForbidCount()).thenReturn(0);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPs);

        // TABLE for principal A
        LFResource tableRes = new LFResource("cat", "db", "logs", null, null);
        LFPermissionOperation tableOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:1", "arn:aws:iam::123:role/roleA",
                tableRes, EnumSet.of(LFPermission.SELECT), false);
        // TWC for principal B (different principal — no conflict)
        LFResource twcRes = new LFResource("cat", "db", "logs",
                java.util.Set.of("col"), null);
        LFPermissionOperation twcOp = new LFPermissionOperation(
                OperationType.GRANT, "lakeformation:2", "arn:aws:iam::123:role/roleB",
                twcRes, EnumSet.of(LFPermission.SELECT), false);

        when(cedarToLFConverter.convert(mockPs)).thenReturn(Arrays.asList(tableOp, twcOp));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Arrays.asList("lakeformation:1", "lakeformation:2"),
                        Collections.emptyList(), 2, 2, 0));

        svc.executeSyncCycle();

        // Both ops must pass through unchanged — no GAP logged
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));
        assertEquals(2, captor.getValue().size());
        verify(deadLetterLogger, never()).logGapOperation(any(), anyString());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private LFPermissionOperation makeGrantOp(String policyId, String principalArn,
                                               String database, String table) {
        LFResource resource = new LFResource("catalog-1", database, table, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        return new LFPermissionOperation(OperationType.GRANT, policyId, principalArn,
                resource, perms, false);
    }

    private ServicePolicies createServicePolicies(long version, int policyCount) {
        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(version);

        List<RangerPolicy> policies = new ArrayList<>();
        for (int i = 0; i < policyCount; i++) {
            RangerPolicy policy = new RangerPolicy();
            policy.setId((long) (i + 1));
            policy.setName("policy-" + (i + 1));
            policy.setService("lakeformation");
            policies.add(policy);
        }
        sp.setPolicies(policies);
        return sp;
    }
}
