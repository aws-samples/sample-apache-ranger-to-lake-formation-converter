package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.BatchResult;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.model.WildcardRefreshResult;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.reporting.GapReporter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SyncService.executeWildcardRefresh().
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 5.1, 5.2, 5.4, 7.1
 */
class SyncServiceWildcardRefreshTest {

    private RangerPlugin plugin;
    private RangerToCedarConverter rangerToCedarConverter;
    private CedarToLFConverter cedarToLFConverter;
    private LakeFormationClient lakeFormationClient;
    private GapReporter gapReporter;
    private DeadLetterLogger deadLetterLogger;
    private CheckpointStore checkpointStore;
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
        checkpointStore = mock(CheckpointStore.class);

        syncService = new SyncService(
                plugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger,
                checkpointStore);

        syncConfig = new SyncConfig(null, null, null, 30000L, 5, 2000L,
                "/tmp/dead-letter.log", "/tmp/checkpoint.json", 300);
    }

    // ---------------------------------------------------------------
    // No glob policies → returns success with 0 evaluated (Req 3.1)
    // ---------------------------------------------------------------

    @Test
    void noGlobPolicies_returnsSuccessWithZeroEvaluated() {
        // Set up lastKnownPolicies with non-glob policies only
        seedLastKnownPolicies(createNonGlobPolicy(1L, "db1", "exact_table"));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        assertEquals(0, result.getPoliciesEvaluated());
        assertEquals(0, result.getNewGrants());
        assertEquals(0, result.getRevocations());
        verifyNoInteractions(lakeFormationClient);
        verifyNoInteractions(rangerToCedarConverter);
    }

    // ---------------------------------------------------------------
    // New table appears in Glue → new grant in delta (Req 3.2, 3.3)
    // ---------------------------------------------------------------

    @Test
    void newTableInGlue_producesNewGrantAndApplyBatchCalled() {
        // Seed with a glob policy
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        seedLastKnownPolicies(globPolicy);

        // Previous operations: table_1 grant from initial sync
        LFPermissionOperation existingOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        setPreviousOperations(existingOp);

        // Re-expansion now returns table_1 AND table_2 (new table appeared)
        LFPermissionOperation reExpandedOp1 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        LFPermissionOperation reExpandedOp2 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_2");
        mockConversionPipeline(Arrays.asList(reExpandedOp1, reExpandedOp2));

        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(List.of("10"), List.of(), 1, 1, 0));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPoliciesEvaluated());
        assertEquals(1, result.getNewGrants());
        assertEquals(0, result.getRevocations());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(1, applied.size());
        assertEquals(OperationType.GRANT, applied.get(0).getOperationType());
        assertEquals("table_2", applied.get(0).getResource().getTableName());
    }

    // ---------------------------------------------------------------
    // Table removed from Glue → revocation in delta (Req 3.2, 3.4)
    // ---------------------------------------------------------------

    @Test
    void tableRemovedFromGlue_producesRevocationAndApplyBatchCalled() {
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        seedLastKnownPolicies(globPolicy);

        // Previous operations: table_1 and table_2
        LFPermissionOperation prevOp1 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        LFPermissionOperation prevOp2 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_2");
        setPreviousOperations(prevOp1, prevOp2);

        // Re-expansion now returns only table_1 (table_2 was removed from Glue)
        LFPermissionOperation reExpandedOp1 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        mockConversionPipeline(Collections.singletonList(reExpandedOp1));

        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(List.of("10"), List.of(), 1, 1, 0));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPoliciesEvaluated());
        assertEquals(0, result.getNewGrants());
        assertEquals(1, result.getRevocations());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LFPermissionOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(lakeFormationClient).applyBatch(captor.capture(), eq(deadLetterLogger));

        List<LFPermissionOperation> applied = captor.getValue();
        assertEquals(1, applied.size());
        assertEquals(OperationType.REVOKE, applied.get(0).getOperationType());
        assertEquals("table_2", applied.get(0).getResource().getTableName());
    }

    // ---------------------------------------------------------------
    // Identical re-expansion → no delta, applyBatch not called (Req 3.5)
    // ---------------------------------------------------------------

    @Test
    void identicalReExpansion_noDeltaAndApplyBatchNotCalled() {
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        seedLastKnownPolicies(globPolicy);

        // Previous operations: table_1
        LFPermissionOperation prevOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        setPreviousOperations(prevOp);

        // Re-expansion returns the same: table_1
        LFPermissionOperation reExpandedOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        mockConversionPipeline(Collections.singletonList(reExpandedOp));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPoliciesEvaluated());
        assertEquals(0, result.getNewGrants());
        assertEquals(0, result.getRevocations());
        assertEquals(1, result.getUnchanged());
        verifyNoInteractions(lakeFormationClient);
    }

    // ---------------------------------------------------------------
    // CatalogResolver throws → previousOperations unchanged (Req 5.1)
    // ---------------------------------------------------------------

    @Test
    void catalogResolverThrows_previousOperationsUnchangedAndFailureReturned() {
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        seedLastKnownPolicies(globPolicy);

        LFPermissionOperation prevOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        setPreviousOperations(prevOp);

        // Simulate CatalogResolver failure during conversion
        when(rangerToCedarConverter.convert(anyList()))
                .thenThrow(new RuntimeException("Glue catalog unavailable"));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        assertEquals("Glue catalog unavailable", result.getError().getMessage());

        // previousOperations should remain unchanged
        assertEquals(1, syncService.getPreviousOperations().size());
        assertEquals("table_1", syncService.getPreviousOperations().get(0).getResource().getTableName());

        verifyNoInteractions(lakeFormationClient);
    }

    // ---------------------------------------------------------------
    // Partial LF failure → dead-letter entries written (Req 5.2)
    // ---------------------------------------------------------------

    @Test
    void partialLfFailure_deadLetterWrittenAndPreviousOperationsStillUpdated() {
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        seedLastKnownPolicies(globPolicy);

        // Previous: table_1 only
        LFPermissionOperation prevOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        setPreviousOperations(prevOp);

        // Re-expansion: table_1 + table_2 (new grant)
        LFPermissionOperation reExpanded1 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        LFPermissionOperation reExpanded2 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_2");
        mockConversionPipeline(Arrays.asList(reExpanded1, reExpanded2));

        // applyBatch returns partial failure
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(List.of(), List.of("10"), 1, 0, 1));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        // previousOperations should still be updated to the re-expanded state
        assertEquals(2, syncService.getPreviousOperations().size());

        verify(lakeFormationClient).applyBatch(anyList(), eq(deadLetterLogger));
    }

    // ---------------------------------------------------------------
    // Checkpoint persisted after successful refresh (Req 7.1)
    // ---------------------------------------------------------------

    @Test
    void checkpointPersistedAfterSuccessfulRefresh() {
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        seedLastKnownPolicies(globPolicy);

        LFPermissionOperation prevOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        setPreviousOperations(prevOp);

        // Clear checkpoint mock invocations from seed/setPrevious calls
        reset(checkpointStore);

        // Re-expansion returns same ops (no delta)
        mockConversionPipeline(Collections.singletonList(
                makeGrantOp("10", "arn:aws:iam::123:user/alice", "db1", "table_1")));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        verify(checkpointStore).save(anyLong(), anyString());
    }

    // ---------------------------------------------------------------
    // Uses lastKnownPolicies as source (connectivity loss) (Req 5.4)
    // ---------------------------------------------------------------

    @Test
    void usesLastKnownPoliciesAsSource_connectivityLossScenario() {
        // Simulate a normal sync that populates lastKnownPolicies
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(1);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(mockPolicySet.toCedarString()).thenReturn("");

        // Create a glob policy that will be in the ServicePolicies
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");

        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(1L);
        sp.setPolicies(Collections.singletonList(globPolicy));

        // First sync: set up the pipeline
        LFPermissionOperation initialOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.singletonList(initialOp));
        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(List.of("10"), List.of(), 1, 1, 0));

        syncService.onPoliciesUpdated(sp);

        // Verify lastKnownPolicies was set
        assertEquals(1, syncService.getLastKnownPolicies().size());
        assertEquals(10L, syncService.getLastKnownPolicies().get(0).getId());

        // Simulate connectivity loss
        syncService.onConnectivityLost();

        // Now reset mocks for the wildcard refresh
        reset(rangerToCedarConverter, cedarToLFConverter, lakeFormationClient);

        // Re-expansion returns same ops (no change)
        CedarPolicySet refreshCedarSet = mock(CedarPolicySet.class);
        when(rangerToCedarConverter.convert(anyList())).thenReturn(refreshCedarSet);
        when(cedarToLFConverter.convert(refreshCedarSet)).thenReturn(
                Collections.singletonList(initialOp));

        // Full re-conversion for Cedar text
        CedarPolicySet fullCedarSet = mock(CedarPolicySet.class);
        when(fullCedarSet.toCedarString()).thenReturn("permit(principal, action, resource);");
        when(rangerToCedarConverter.convert(anyList()))
                .thenReturn(refreshCedarSet)
                .thenReturn(fullCedarSet);
        when(cedarToLFConverter.convert(refreshCedarSet)).thenReturn(
                Collections.singletonList(initialOp));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPoliciesEvaluated());

        // Verify the converter was called with the glob policies from lastKnownPolicies
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RangerPolicy>> policyCaptor = ArgumentCaptor.forClass(List.class);
        verify(rangerToCedarConverter, atLeastOnce()).convert(policyCaptor.capture());

        // The first call should be with the glob policies from lastKnownPolicies
        List<RangerPolicy> convertedPolicies = policyCaptor.getAllValues().get(0);
        assertEquals(1, convertedPolicies.size());
        assertEquals(10L, convertedPolicies.get(0).getId());
    }

    // ---------------------------------------------------------------
    // Non-glob operations preserved during merge (Req 3.6)
    // ---------------------------------------------------------------

    @Test
    void nonGlobOperationsPreservedDuringMerge() {
        // Set up: one glob policy and one non-glob policy
        RangerPolicy globPolicy = createGlobPolicy(10L, "db1", "table_*");
        RangerPolicy nonGlobPolicy = createNonGlobPolicy(20L, "db2", "exact_table");
        seedLastKnownPolicies(globPolicy, nonGlobPolicy);

        // Previous operations: one from glob policy, one from non-glob policy
        LFPermissionOperation globOp = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        LFPermissionOperation nonGlobOp = makeGrantOp("20", "arn:aws:iam::123:user/bob",
                "db2", "exact_table");
        setPreviousOperations(globOp, nonGlobOp);

        // Re-expansion of glob policy returns table_1 + table_2
        LFPermissionOperation reExpanded1 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_1");
        LFPermissionOperation reExpanded2 = makeGrantOp("10", "arn:aws:iam::123:user/alice",
                "db1", "table_2");
        mockConversionPipeline(Arrays.asList(reExpanded1, reExpanded2));

        when(lakeFormationClient.applyBatch(anyList(), any()))
                .thenReturn(new BatchResult(List.of("10"), List.of(), 1, 1, 0));

        WildcardRefreshResult result = syncService.executeWildcardRefresh();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getNewGrants());

        // After refresh, previousOperations should contain both non-glob and re-expanded glob ops
        List<LFPermissionOperation> updatedOps = syncService.getPreviousOperations();
        assertEquals(3, updatedOps.size());

        // Verify non-glob op is preserved
        assertTrue(updatedOps.stream().anyMatch(op ->
                "lakeformation:20".equals(op.getSourcePolicyId()) && "exact_table".equals(op.getResource().getTableName())));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private RangerPolicy createGlobPolicy(long id, String database, String tablePattern) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("glob-policy-" + id);
        policy.setService("lakeformation");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);

        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList(tablePattern));
        resources.put("table", tableRes);

        policy.setResources(resources);
        return policy;
    }

    private RangerPolicy createNonGlobPolicy(long id, String database, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(id);
        policy.setName("exact-policy-" + id);
        policy.setService("lakeformation");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        RangerPolicyResource dbRes = new RangerPolicyResource();
        dbRes.setValues(Collections.singletonList(database));
        resources.put("database", dbRes);

        RangerPolicyResource tableRes = new RangerPolicyResource();
        tableRes.setValues(Collections.singletonList(table));
        resources.put("table", tableRes);

        policy.setResources(resources);
        return policy;
    }

    private LFPermissionOperation makeGrantOp(String policyId, String principalArn,
                                               String database, String table) {
        // Use service-type-prefixed source policy ID to match the @source("serviceType:policyId")
        // format used by RangerToCedarConverter with namespace isolation (Req 9.1)
        String sourcePolicyId = policyId.contains(":") ? policyId : "lakeformation:" + policyId;
        LFResource resource = new LFResource("catalog-1", database, table, null, null);
        Set<LFPermission> perms = EnumSet.of(LFPermission.SELECT);
        return new LFPermissionOperation(OperationType.GRANT, sourcePolicyId, principalArn,
                resource, perms, false);
    }

    /**
     * Seeds lastKnownPolicies by triggering a policy update through the normal sync path.
     */
    private void seedLastKnownPolicies(RangerPolicy... policies) {
        syncService.start(syncConfig);

        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(0);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(mockPolicySet.toCedarString()).thenReturn("");
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(any())).thenReturn(Collections.emptyList());

        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(1L);
        sp.setPolicies(Arrays.asList(policies));

        syncService.onPoliciesUpdated(sp);

        // Reset mocks so wildcard refresh tests start clean
        reset(rangerToCedarConverter, cedarToLFConverter, lakeFormationClient);
    }

    /**
     * Sets previousOperations by running a sync cycle with the given operations.
     */
    private void setPreviousOperations(LFPermissionOperation... ops) {
        // We need to re-seed previousOperations via another sync cycle
        CedarPolicySet mockPolicySet = mock(CedarPolicySet.class);
        when(mockPolicySet.getPermitCount()).thenReturn(ops.length);
        when(mockPolicySet.getForbidCount()).thenReturn(0);
        when(mockPolicySet.toCedarString()).thenReturn("");
        when(rangerToCedarConverter.convert(anyList())).thenReturn(mockPolicySet);
        when(cedarToLFConverter.convert(mockPolicySet)).thenReturn(Arrays.asList(ops));

        if (ops.length > 0) {
            when(lakeFormationClient.applyBatch(anyList(), any()))
                    .thenReturn(new BatchResult(List.of("1"), List.of(), ops.length, ops.length, 0));
        }

        // Trigger a sync to set previousOperations
        ServicePolicies sp = new ServicePolicies();
        sp.setServiceName("lakeformation");
        sp.setPolicyVersion(2L);
        sp.setPolicies(syncService.getLastKnownPolicies());
        syncService.onPoliciesUpdated(sp);

        // Reset mocks for the actual test
        reset(rangerToCedarConverter, cedarToLFConverter, lakeFormationClient);
    }

    /**
     * Mocks the conversion pipeline for wildcard refresh:
     * rangerToCedarConverter.convert() → CedarPolicySet → cedarToLFConverter.convert() → ops
     * Also mocks the second convert call for full Cedar text generation.
     */
    private void mockConversionPipeline(List<LFPermissionOperation> reExpandedOps) {
        CedarPolicySet globCedarSet = mock(CedarPolicySet.class);
        CedarPolicySet fullCedarSet = mock(CedarPolicySet.class);
        when(fullCedarSet.toCedarString()).thenReturn("permit(principal, action, resource);");

        // First call: glob policies conversion, Second call: full lastKnownPolicies conversion
        when(rangerToCedarConverter.convert(anyList()))
                .thenReturn(globCedarSet)
                .thenReturn(fullCedarSet);
        when(cedarToLFConverter.convert(globCedarSet)).thenReturn(reExpandedOps);
    }
}
