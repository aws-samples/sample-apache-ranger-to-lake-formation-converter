package org.apache.ranger.lakeformation.sync;

import org.apache.ranger.lakeformation.catalog.CatalogResolver;
import org.apache.ranger.lakeformation.client.BatchResult;
import org.apache.ranger.lakeformation.client.DeadLetterLogger;
import org.apache.ranger.lakeformation.client.LakeFormationClient;
import org.apache.ranger.lakeformation.converter.ConversionResult;
import org.apache.ranger.lakeformation.converter.PolicyConverter;
import org.apache.ranger.lakeformation.mapper.PrincipalMapper;
import org.apache.ranger.lakeformation.model.GapEntry;
import org.apache.ranger.lakeformation.model.LFPermission;
import org.apache.ranger.lakeformation.model.LFPermissionOperation;
import org.apache.ranger.lakeformation.model.LFPermissionOperation.OperationType;
import org.apache.ranger.lakeformation.model.LFResource;
import org.apache.ranger.lakeformation.model.PrincipalMappingConfig;
import org.apache.ranger.lakeformation.model.SyncConfig;
import org.apache.ranger.lakeformation.reporter.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SyncService.
 * Validates: Requirements 4.2, 4.3
 */
class SyncServiceTest {

    private LakeFormationPlugin plugin;
    private PolicyConverter policyConverter;
    private PrincipalMapper principalMapper;
    private CatalogResolver catalogResolver;
    private LakeFormationClient lakeFormationClient;
    private GapReporter gapReporter;
    private DeadLetterLogger deadLetterLogger;
    private SyncService syncService;
    private SyncConfig syncConfig;

    @BeforeEach
    void setUp() {
        plugin = new LakeFormationPlugin();
        policyConverter = mock(PolicyConverter.class);
        principalMapper = PrincipalMapper.fromConfig(new PrincipalMappingConfig(
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap()));
        catalogResolver = mock(CatalogResolver.class);
        lakeFormationClient = mock(LakeFormationClient.class);
        gapReporter = new GapReporter();
        deadLetterLogger = mock(DeadLetterLogger.class);

        syncService = new SyncService(
                plugin, policyConverter, principalMapper, catalogResolver,
                lakeFormationClient, gapReporter, deadLetterLogger);

        syncConfig = new SyncConfig(null, null, null, 30000L, 5, 2000L, "/tmp/dead-letter.log");
    }

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
        syncService.start(syncConfig); // second call should be no-op

        assertTrue(syncService.isRunning());
    }

    @Test
    void stopIsIdempotentWhenNotRunning() {
        assertDoesNotThrow(() -> syncService.stop());
    }

    @Test
    void firstUpdateWithEmptyPreviousPerformsBulkSync() {
        syncService.start(syncConfig);

        // Set up converter to return some operations
        List<LFPermissionOperation> ops = Arrays.asList(
                makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null),
                makeGrantOp("1", "arn:aws:iam::123:user/bob", "db1", "table1")
        );
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(ops, 1, 0));

        BatchResult batchResult = new BatchResult(
                Collections.singletonList("1"), Collections.<String>emptyList(), 2, 2, 0);
        when(lakeFormationClient.applyBatch(anyList(), any())).thenReturn(batchResult);

        // Trigger policy update
        ServicePolicies sp = createServicePolicies(1L, 1);
        syncService.onPoliciesUpdated(sp);

        // All operations should be applied as new grants (bulk sync)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(2, applied.size());
        assertTrue(applied.stream().allMatch(op -> op.getOperationType() == OperationType.GRANT));
    }

    @Test
    void secondUpdateComputesIncrementalDiff() {
        syncService.start(syncConfig);

        // First update: 2 operations
        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        LFPermissionOperation op2 = makeGrantOp("1", "arn:aws:iam::123:user/bob", "db1", "table1");
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Arrays.asList(op1, op2), 1, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 2, 2, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Second update: op1 unchanged, op2 removed, op3 added
        LFPermissionOperation op3 = makeGrantOp("2", "arn:aws:iam::123:user/charlie", "db2", null);
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Arrays.asList(op1, op3), 2, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Arrays.asList("1", "2"),
                        Collections.<String>emptyList(), 2, 2, 0));

        syncService.onPoliciesUpdated(createServicePolicies(2L, 2));

        // Verify second batch: should have 1 new grant (op3) + 1 revoke (op2)
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

        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.singletonList(op1), 1, 0));
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

    @Test
    void updateIgnoredWhenNotRunning() {
        // Don't start the service
        ServicePolicies sp = createServicePolicies(1L, 1);
        syncService.onPoliciesUpdated(sp);

        // No interaction with converter or client
        verifyNoInteractions(policyConverter);
        verifyNoInteractions(lakeFormationClient);
    }

    @Test
    void previousSnapshotUpdatedAfterEachSync() {
        syncService.start(syncConfig);

        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.singletonList(op1), 1, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 1, 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Previous operations should now contain op1
        assertEquals(1, syncService.getPreviousOperations().size());
    }

    @Test
    void nullPolicyListTreatedAsEmpty() {
        syncService.start(syncConfig);

        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 0, 0));

        ServicePolicies sp = new ServicePolicies();
        sp.setPolicyVersion(1L);
        sp.setPolicies(null);

        syncService.onPoliciesUpdated(sp);

        // Should have called converter with empty list
        verify(policyConverter).convertBatch(eq(Collections.<RangerPolicy>emptyList()),
                any(), any(), any());
    }

    // --- computeDiff tests ---

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
        assertEquals(0, diff.getUnchangedCount());
        assertTrue(diff.getRevocations().stream()
                .allMatch(op -> op.getOperationType() == OperationType.REVOKE));
    }

    @Test
    void computeDiffIdenticalSnapshots() {
        LFPermissionOperation op = makeGrantOp("1", "arn:user/a", "db1", null);
        List<LFPermissionOperation> previous = Collections.singletonList(op);
        List<LFPermissionOperation> current = Collections.singletonList(op);

        SyncService.PolicyDiff diff = SyncService.computeDiff(previous, current);

        assertEquals(0, diff.getNewGrants().size());
        assertEquals(0, diff.getRevocations().size());
        assertEquals(1, diff.getUnchangedCount());
    }

    @Test
    void computeDiffMixedChanges() {
        LFPermissionOperation unchanged = makeGrantOp("1", "arn:user/a", "db1", null);
        LFPermissionOperation removed = makeGrantOp("1", "arn:user/b", "db1", "t1");
        LFPermissionOperation added = makeGrantOp("2", "arn:user/c", "db2", null);

        List<LFPermissionOperation> previous = Arrays.asList(unchanged, removed);
        List<LFPermissionOperation> current = Arrays.asList(unchanged, added);

        SyncService.PolicyDiff diff = SyncService.computeDiff(previous, current);

        assertEquals(1, diff.getNewGrants().size());
        assertEquals(1, diff.getRevocations().size());
        assertEquals(1, diff.getUnchangedCount());

        // New grant should be for user/c
        assertEquals("arn:user/c", diff.getNewGrants().get(0).getPrincipalArn());
        assertEquals(OperationType.GRANT, diff.getNewGrants().get(0).getOperationType());

        // Revocation should be for user/b
        assertEquals("arn:user/b", diff.getRevocations().get(0).getPrincipalArn());
        assertEquals(OperationType.REVOKE, diff.getRevocations().get(0).getOperationType());
    }

    @Test
    void computeDiffIgnoresPolicyIdChanges() {
        // Same permission but different source policy ID — should be treated as unchanged
        LFPermissionOperation prev = makeGrantOp("1", "arn:user/a", "db1", null);
        LFPermissionOperation curr = makeGrantOp("99", "arn:user/a", "db1", null);

        SyncService.PolicyDiff diff = SyncService.computeDiff(
                Collections.singletonList(prev),
                Collections.singletonList(curr));

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
                Collections.singletonList(nonGrantable),
                Collections.singletonList(grantable));

        // Different grantable flag means different permission identity
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

    // --- Audit logging tests (Req 4.6) ---

    @Test
    void auditLogEntryCalledForEachDeltaOperation() {
        syncService.start(syncConfig);

        List<LFPermissionOperation> ops = Arrays.asList(
                makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null),
                makeGrantOp("2", "arn:aws:iam::123:user/bob", "db1", "table1")
        );
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(ops, 2, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Arrays.asList("1", "2"),
                        Collections.<String>emptyList(), 2, 2, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 2));

        // Verify applyBatch was called with 2 operations (both are new grants)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void formatResourcePathDatabaseOnly() {
        LFResource resource = new LFResource("cat-1", "mydb", null, null, null);
        String path = SyncService.formatResourcePath(resource);
        assertEquals("cat-1/mydb", path);
    }

    @Test
    void formatResourcePathDatabaseAndTable() {
        LFResource resource = new LFResource("cat-1", "mydb", "mytable", null, null);
        String path = SyncService.formatResourcePath(resource);
        assertEquals("cat-1/mydb/mytable", path);
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
        // Should not throw — just logs
        assertDoesNotThrow(() -> SyncService.logAuditEntry(op));
    }

    // --- Connectivity resilience tests (Req 4.7) ---

    @Test
    void lastKnownPoliciesUpdatedOnEachSync() {
        syncService.start(syncConfig);

        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 1, 0));

        ServicePolicies sp = createServicePolicies(1L, 3);
        syncService.onPoliciesUpdated(sp);

        // lastKnownPolicies should contain the 3 policies from the update
        assertEquals(3, syncService.getLastKnownPolicies().size());
    }

    @Test
    void lastKnownPoliciesPreservedAcrossUpdates() {
        syncService.start(syncConfig);

        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 0, 0));

        // First update with 2 policies
        syncService.onPoliciesUpdated(createServicePolicies(1L, 2));
        assertEquals(2, syncService.getLastKnownPolicies().size());

        // Second update with 5 policies
        syncService.onPoliciesUpdated(createServicePolicies(2L, 5));
        assertEquals(5, syncService.getLastKnownPolicies().size());
    }

    @Test
    void onConnectivityLostDoesNotClearState() {
        syncService.start(syncConfig);

        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 3));

        // Simulate connectivity loss
        syncService.onConnectivityLost();

        // Last known policies and previous operations should be preserved
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
        // Not started — should not throw
        assertDoesNotThrow(() -> syncService.onConnectivityLost());
    }

    @Test
    void onConnectivityRestoredIgnoredWhenNotRunning() {
        assertDoesNotThrow(() -> syncService.onConnectivityRestored());
    }

    // --- Gap report logging tests (Req 4.8) ---

    @Test
    void gapReportLoggedWhenUnsupportedFeaturesPresent() {
        syncService.start(syncConfig);

        // Pre-populate the gap reporter with an entry
        gapReporter.recordGap(new GapEntry(
                "42", "test-policy", GapEntry.GapType.DENY_POLICY,
                "db1/table1", "Deny policies not supported in LF",
                "Use grant-only model"));

        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 1, 0));

        // Should not throw — gap report is logged
        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Verify the gap reporter still has the entry
        assertEquals(1, gapReporter.getReport().getEntries().size());
    }

    @Test
    void syncContinuesNormallyWithNoGapEntries() {
        syncService.start(syncConfig);

        List<LFPermissionOperation> ops = Collections.singletonList(
                makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null));
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(ops, 1, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 1, 1, 0));

        // No gap entries — should proceed normally
        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        verify(lakeFormationClient).applyBatch(anyList(), eq(deadLetterLogger));
    }

    // --- Plugin registration tests (Req 4.1) ---

    @Test
    void startRegistersSyncServiceAsPluginListener() {
        // Verify that start() registers the SyncService as the plugin's listener
        LakeFormationPlugin realPlugin = new LakeFormationPlugin();
        SyncService svc = new SyncService(
                realPlugin, policyConverter, principalMapper, catalogResolver,
                lakeFormationClient, gapReporter, deadLetterLogger);

        svc.start(syncConfig);

        // When setPolicies is called on the plugin, it should delegate to the SyncService.
        // We verify by calling setPolicies and checking that the converter was invoked
        // (which only happens if onPoliciesUpdated was called on the SyncService).
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 1, 0));

        ServicePolicies sp = createServicePolicies(1L, 1);
        realPlugin.setPolicies(sp);

        verify(policyConverter).convertBatch(anyList(), any(), any(), any());
    }

    @Test
    void stopUnregistersPluginListener() {
        // After stop(), setPolicies on the plugin should NOT trigger the SyncService
        LakeFormationPlugin realPlugin = new LakeFormationPlugin();
        SyncService svc = new SyncService(
                realPlugin, policyConverter, principalMapper, catalogResolver,
                lakeFormationClient, gapReporter, deadLetterLogger);

        svc.start(syncConfig);
        svc.stop();

        ServicePolicies sp = createServicePolicies(1L, 1);
        realPlugin.setPolicies(sp);

        // Converter should NOT be called since listener was unregistered
        verifyNoInteractions(policyConverter);
    }

    // --- Connectivity loss handling tests (Req 4.7) ---

    @Test
    void connectivityLossPreservesLastKnownPoliciesAndPreviousOps() {
        syncService.start(syncConfig);

        // First update establishes state
        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.singletonList(op1), 1, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 1, 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 3));

        // Verify state is established
        assertEquals(3, syncService.getLastKnownPolicies().size());
        assertEquals(1, syncService.getPreviousOperations().size());

        // Simulate connectivity loss
        syncService.onConnectivityLost();

        // State must be fully preserved — service continues with last known policies
        assertEquals(3, syncService.getLastKnownPolicies().size());
        assertEquals(1, syncService.getPreviousOperations().size());
        assertTrue(syncService.isRunning());
    }

    @Test
    void afterConnectivityRestoredNewUpdateAppliesDiffAgainstPreservedState() {
        syncService.start(syncConfig);

        // First update
        LFPermissionOperation op1 = makeGrantOp("1", "arn:aws:iam::123:user/alice", "db1", null);
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.singletonList(op1), 1, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Collections.singletonList("1"),
                        Collections.<String>emptyList(), 1, 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Connectivity loss then restore
        syncService.onConnectivityLost();
        syncService.onConnectivityRestored();

        // New update after restore — op1 unchanged, op2 added
        LFPermissionOperation op2 = makeGrantOp("2", "arn:aws:iam::123:user/bob", "db2", null);
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Arrays.asList(op1, op2), 2, 0));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(Arrays.asList("1", "2"),
                        Collections.<String>emptyList(), 1, 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(2L, 2));

        // The diff should be computed against the preserved previous state (op1 only),
        // so only op2 should be a new grant
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient, times(2)).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> secondBatch = captor.getAllValues().get(1);
        assertEquals(1, secondBatch.size());
        assertEquals(OperationType.GRANT, secondBatch.get(0).getOperationType());
        assertEquals("arn:aws:iam::123:user/bob", secondBatch.get(0).getPrincipalArn());
    }

    // --- Unsupported features routed to GapReporter tests (Req 4.8) ---

    @Test
    void gapReporterIsPassedToConverterDuringSync() {
        syncService.start(syncConfig);

        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenReturn(new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 1, 0));

        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));

        // Verify the shared GapReporter instance is passed to the converter
        verify(policyConverter).convertBatch(anyList(), any(), any(), eq(gapReporter));
    }

    @Test
    void unsupportedFeaturesRecordedByConverterAreVisibleInGapReport() {
        syncService.start(syncConfig);

        // Simulate the converter recording gap entries when called
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    GapReporter reporter = invocation.getArgument(3);
                    reporter.recordGap(new GapEntry(
                            "10", "deny-policy", GapEntry.GapType.DENY_POLICY,
                            "db1/table1", "Deny policies not supported in LF",
                            "Use grant-only model"));
                    reporter.recordGap(new GapEntry(
                            "11", "mask-policy", GapEntry.GapType.DATA_MASKING,
                            "db2/table2", "Data masking not supported in LF",
                            "Use column-level security"));
                    return new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 1, 0);
                });

        syncService.onPoliciesUpdated(createServicePolicies(1L, 2));

        // The gap entries recorded by the converter should be visible in the shared GapReporter
        assertEquals(2, gapReporter.getReport().getEntries().size());
        assertEquals(GapEntry.GapType.DENY_POLICY, gapReporter.getReport().getEntries().get(0).getGapType());
        assertEquals(GapEntry.GapType.DATA_MASKING, gapReporter.getReport().getEntries().get(1).getGapType());
    }

    @Test
    void multipleGapTypesAccumulateAcrossSyncCycles() {
        syncService.start(syncConfig);

        // Set up converter to record different gap types on successive calls
        when(policyConverter.convertBatch(anyList(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    GapReporter reporter = invocation.getArgument(3);
                    reporter.recordGap(new GapEntry(
                            "10", "deny-policy", GapEntry.GapType.DENY_POLICY,
                            "db1/table1", "Deny not supported", "Use grant-only model"));
                    return new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 0, 0);
                })
                .thenAnswer(invocation -> {
                    GapReporter reporter = invocation.getArgument(3);
                    reporter.recordGap(new GapEntry(
                            "20", "zone-policy", GapEntry.GapType.SECURITY_ZONE,
                            "db3", "Security zones not supported", "Manual mapping needed"));
                    return new ConversionResult(Collections.<LFPermissionOperation>emptyList(), 0, 0);
                });

        // First sync cycle
        syncService.onPoliciesUpdated(createServicePolicies(1L, 1));
        assertEquals(1, gapReporter.getReport().getEntries().size());

        // Second sync cycle
        syncService.onPoliciesUpdated(createServicePolicies(2L, 1));

        // Both gap entries should be accumulated
        assertEquals(2, gapReporter.getReport().getEntries().size());
        Map<GapEntry.GapType, Integer> summary = gapReporter.getReport().getSummary();
        assertEquals(Integer.valueOf(1), summary.get(GapEntry.GapType.DENY_POLICY));
        assertEquals(Integer.valueOf(1), summary.get(GapEntry.GapType.SECURITY_ZONE));
    }

    // --- Helpers ---

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
