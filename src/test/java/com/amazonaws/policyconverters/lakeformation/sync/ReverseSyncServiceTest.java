package com.amazonaws.policyconverters.lakeformation.sync;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.lakeformation.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.client.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.client.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.client.LFPermissionFetcher;
import com.amazonaws.policyconverters.lakeformation.client.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.client.LakeFormationClientException;
import com.amazonaws.policyconverters.lakeformation.model.DriftReport;
import com.amazonaws.policyconverters.lakeformation.model.DriftResult;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.ReverseSyncConfig;
import com.amazonaws.policyconverters.lakeformation.model.ReverseSyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReverseSyncService}.
 * Validates: Requirements 4.1–4.6, 5.1, 5.2, 5.4, 5.5, 5.6, 6.1, 6.4, 7.3, 7.4
 */
@ExtendWith(MockitoExtension.class)
class ReverseSyncServiceTest {

    private static final String CATALOG_ID = "123456789012";
    private static final String PRINCIPAL_A = "arn:aws:iam::123456789012:role/RoleA";
    private static final String PRINCIPAL_B = "arn:aws:iam::123456789012:role/RoleB";

    @Mock private LFPermissionFetcher fetcher;
    @Mock private DriftDetector driftDetector;
    @Mock private LakeFormationClient lakeFormationClient;
    @Mock private CedarToLFConverter cedarToLFConverter;
    @Mock private DeadLetterLogger deadLetterLogger;
    @Mock private CedarPolicySet cedarPolicySet;

    private ReverseSyncService service;
    private ReverseSyncConfig defaultConfig;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new ReverseSyncService(fetcher, driftDetector, lakeFormationClient,
                cedarToLFConverter, deadLetterLogger);
        defaultConfig = new ReverseSyncConfig(true, CATALOG_ID, false, false, null, null, 0L);

        // Set up log capture
        serviceLogger = (Logger) LoggerFactory.getLogger(ReverseSyncService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // --- Helpers ---

    private LFResource dbResource(String dbName) {
        return new LFResource(CATALOG_ID, dbName, null, null, null);
    }

    private LFResource tableResource(String dbName, String tableName) {
        return new LFResource(CATALOG_ID, dbName, tableName, null, null);
    }

    private LFPermissionOperation grantOp(String principal, LFResource resource,
                                           LFPermission permission) {
        return new LFPermissionOperation(OperationType.GRANT, "policy1", principal,
                resource, EnumSet.of(permission), false);
    }

    private LFPermissionOperation revokeOp(String principal, LFResource resource,
                                            LFPermission permission) {
        return new LFPermissionOperation(OperationType.REVOKE, null, principal,
                resource, EnumSet.of(permission), false);
    }

    private DriftResult driftResultWith(DriftReport report, List<LFPermissionOperation> ops) {
        return new DriftResult(report, ops);
    }

    private DriftReport report(int missing, int extra, int inSync) {
        return new DriftReport(missing, extra, inSync, Collections.emptyList(), Collections.emptyList());
    }

    // --- Test: happy path orchestration flow (fetch → drift → apply → result) ---

    @Test
    void execute_happyPath_fetchDriftApplyResult() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        List<LFPermissionOperation> desired = Collections.singletonList(
                grantOp(PRINCIPAL_A, dbResource("db1"), LFPermission.DESCRIBE));
        List<LFPermissionOperation> actual = Collections.singletonList(
                grantOp(PRINCIPAL_B, dbResource("db2"), LFPermission.ALTER));

        LFPermissionOperation grantCorrection = grantOp(PRINCIPAL_A, dbResource("db1"), LFPermission.DESCRIBE);
        LFPermissionOperation revokeCorrection = revokeOp(PRINCIPAL_B, dbResource("db2"), LFPermission.ALTER);
        List<LFPermissionOperation> corrections = Arrays.asList(revokeCorrection, grantCorrection);

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(desired);
        when(fetcher.fetchPermissions(CATALOG_ID, null)).thenReturn(actual);
        when(driftDetector.computeDrift(desired, actual, null, false))
                .thenReturn(driftResultWith(report(1, 1, 0), corrections));

        ReverseSyncResult result = service.execute(defaultConfig, cedarPolicySet);

        // Verify orchestration order
        InOrder inOrder = inOrder(cedarToLFConverter, fetcher, driftDetector, lakeFormationClient);
        inOrder.verify(cedarToLFConverter).convert(cedarPolicySet);
        inOrder.verify(fetcher).fetchPermissions(CATALOG_ID, null);
        inOrder.verify(driftDetector).computeDrift(desired, actual, null, false);
        inOrder.verify(lakeFormationClient).revokePermission(revokeCorrection);
        inOrder.verify(lakeFormationClient).grantPermission(grantCorrection);

        assertEquals(1, result.getSuccessfulGrants());
        assertEquals(1, result.getSuccessfulRevokes());
        assertEquals(0, result.getFailedOperations());
        assertTrue(result.getDurationMs() >= 0);
    }

    // --- Test: REVOKE operations applied before GRANT operations ---

    @Test
    void execute_revokeBeforeGrant_orderingEnforced() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        LFPermissionOperation grant1 = grantOp(PRINCIPAL_A, dbResource("db1"), LFPermission.DESCRIBE);
        LFPermissionOperation grant2 = grantOp(PRINCIPAL_A, dbResource("db2"), LFPermission.SELECT);
        LFPermissionOperation revoke1 = revokeOp(PRINCIPAL_B, dbResource("db3"), LFPermission.ALTER);
        LFPermissionOperation revoke2 = revokeOp(PRINCIPAL_B, dbResource("db4"), LFPermission.DROP);

        // Provide corrections in mixed order: grant, revoke, grant, revoke
        List<LFPermissionOperation> corrections = Arrays.asList(grant1, revoke1, grant2, revoke2);

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(2, 2, 0), corrections));

        service.execute(defaultConfig, cedarPolicySet);

        // Verify revokes come before grants
        InOrder inOrder = inOrder(lakeFormationClient);
        inOrder.verify(lakeFormationClient).revokePermission(revoke1);
        inOrder.verify(lakeFormationClient).revokePermission(revoke2);
        inOrder.verify(lakeFormationClient).grantPermission(grant1);
        inOrder.verify(lakeFormationClient).grantPermission(grant2);
    }

    // --- Test: report-only mode skips apply phase ---

    @Test
    void execute_reportOnlyMode_skipsApplyPhase() throws Exception {
        ReverseSyncConfig reportOnlyConfig = new ReverseSyncConfig(true, CATALOG_ID, true, false, null, null, 0L);
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        List<LFPermissionOperation> desired = Collections.singletonList(
                grantOp(PRINCIPAL_A, dbResource("db1"), LFPermission.DESCRIBE));
        List<LFPermissionOperation> actual = Collections.emptyList();

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(desired);
        when(fetcher.fetchPermissions(CATALOG_ID, null)).thenReturn(actual);
        when(driftDetector.computeDrift(desired, actual, null, true))
                .thenReturn(driftResultWith(report(1, 0, 0), Collections.emptyList()));

        ReverseSyncResult result = service.execute(reportOnlyConfig, cedarPolicySet);

        // No grant/revoke calls should be made
        verifyNoInteractions(lakeFormationClient);
        assertEquals(0, result.getSuccessfulGrants());
        assertEquals(0, result.getSuccessfulRevokes());
        assertEquals(0, result.getFailedOperations());
        assertNotNull(result.getDriftReport());
        assertEquals(1, result.getDriftReport().getMissingGrants());
    }

    // --- Test: dry-run mode uses DryRunLakeFormationClient ---

    @Test
    void execute_dryRunMode_usesDryRunClient() throws Exception {
        DryRunLakeFormationClient dryRunClient = mock(DryRunLakeFormationClient.class);
        ReverseSyncService dryRunService = new ReverseSyncService(fetcher, driftDetector,
                dryRunClient, cedarToLFConverter, deadLetterLogger);
        ReverseSyncConfig dryRunConfig = new ReverseSyncConfig(true, CATALOG_ID, false, true, null, null, 0L);

        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        LFPermissionOperation correction = grantOp(PRINCIPAL_A, dbResource("db1"), LFPermission.DESCRIBE);
        List<LFPermissionOperation> corrections = Collections.singletonList(correction);

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(1, 0, 0), corrections));

        ReverseSyncResult result = dryRunService.execute(dryRunConfig, cedarPolicySet);

        verify(dryRunClient).applyBatch(corrections, deadLetterLogger);
        // No individual grant/revoke calls
        verify(dryRunClient, never()).grantPermission(any());
        verify(dryRunClient, never()).revokePermission(any());
        assertEquals(0, result.getSuccessfulGrants());
        assertEquals(0, result.getSuccessfulRevokes());
    }

    // --- Test: empty Cedar policy set → skip cycle, zero-op result, no revocations ---

    @Test
    void execute_emptyCedarPolicySet_skipsCycleZeroOpResult() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("");

        ReverseSyncResult result = service.execute(defaultConfig, cedarPolicySet);

        verifyNoInteractions(cedarToLFConverter);
        verifyNoInteractions(fetcher);
        verifyNoInteractions(driftDetector);
        verifyNoInteractions(lakeFormationClient);
        assertEquals(0, result.getSuccessfulGrants());
        assertEquals(0, result.getSuccessfulRevokes());
        assertEquals(0, result.getFailedOperations());
        assertNotNull(result.getDriftReport());
    }

    // --- Test: null Cedar policy set → skip cycle, zero-op result ---

    @Test
    void execute_nullCedarPolicySet_skipsCycleZeroOpResult() throws Exception {
        ReverseSyncResult result = service.execute(defaultConfig, null);

        verifyNoInteractions(cedarToLFConverter);
        verifyNoInteractions(fetcher);
        verifyNoInteractions(driftDetector);
        verifyNoInteractions(lakeFormationClient);
        assertEquals(0, result.getSuccessfulGrants());
        assertEquals(0, result.getSuccessfulRevokes());
        assertEquals(0, result.getFailedOperations());
    }

    // --- Test: concurrent execution rejected with IllegalStateException ---

    @Test
    void execute_concurrentExecution_rejectedWithIllegalStateException() throws Exception {
        CountDownLatch enteredLatch = new CountDownLatch(1);
        CountDownLatch proceedLatch = new CountDownLatch(1);

        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");
        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());

        // Make fetcher block until we signal it to proceed
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenAnswer(invocation -> {
            enteredLatch.countDown();
            proceedLatch.await();
            return Collections.emptyList();
        });
        when(driftDetector.computeDrift(any(), any(), any(), anyBoolean()))
                .thenReturn(driftResultWith(report(0, 0, 0), Collections.emptyList()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Start first execution
            Future<ReverseSyncResult> first = executor.submit(
                    () -> service.execute(defaultConfig, cedarPolicySet));

            // Wait until first execution has entered
            enteredLatch.await();

            // Second execution should be rejected
            assertThrows(IllegalStateException.class,
                    () -> service.execute(defaultConfig, cedarPolicySet));

            // Let first execution complete
            proceedLatch.countDown();
            assertNotNull(first.get());
        } finally {
            executor.shutdownNow();
        }
    }

    // --- Test: corrective operation failure → logged to DeadLetterLogger, remaining ops continue ---

    @Test
    void execute_correctiveOperationFailure_loggedAndRemainingContinue() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        LFPermissionOperation revokeOp = revokeOp(PRINCIPAL_A, dbResource("db1"), LFPermission.ALTER);
        LFPermissionOperation grantOp = grantOp(PRINCIPAL_B, dbResource("db2"), LFPermission.DESCRIBE);
        List<LFPermissionOperation> corrections = Arrays.asList(revokeOp, grantOp);

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(1, 1, 0), corrections));

        // First op (revoke) fails
        doThrow(new LakeFormationClientException("Revoke failed"))
                .when(lakeFormationClient).revokePermission(revokeOp);

        ReverseSyncResult result = service.execute(defaultConfig, cedarPolicySet);

        // Grant should still be attempted
        verify(lakeFormationClient).grantPermission(grantOp);
        // Failure logged to dead letter
        verify(deadLetterLogger).logFailedOperation(eq(revokeOp), eq("Revoke failed"), eq(0));
        assertEquals(1, result.getSuccessfulGrants());
        assertEquals(0, result.getSuccessfulRevokes());
        assertEquals(1, result.getFailedOperations());
    }

    // --- Test: multiple failures → all recorded in result, all remaining ops attempted ---

    @Test
    void execute_multipleFailures_allRecordedAllAttempted() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        LFPermissionOperation revoke1 = revokeOp(PRINCIPAL_A, dbResource("db1"), LFPermission.ALTER);
        LFPermissionOperation revoke2 = revokeOp(PRINCIPAL_A, dbResource("db2"), LFPermission.DROP);
        LFPermissionOperation grant1 = grantOp(PRINCIPAL_B, dbResource("db3"), LFPermission.DESCRIBE);
        LFPermissionOperation grant2 = grantOp(PRINCIPAL_B, dbResource("db4"), LFPermission.SELECT);
        List<LFPermissionOperation> corrections = Arrays.asList(revoke1, revoke2, grant1, grant2);

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(2, 2, 0), corrections));

        // revoke1 and grant1 fail
        doThrow(new LakeFormationClientException("Revoke1 failed"))
                .when(lakeFormationClient).revokePermission(revoke1);
        doThrow(new LakeFormationClientException("Grant1 failed"))
                .when(lakeFormationClient).grantPermission(grant1);

        ReverseSyncResult result = service.execute(defaultConfig, cedarPolicySet);

        // All 4 ops should be attempted
        verify(lakeFormationClient).revokePermission(revoke1);
        verify(lakeFormationClient).revokePermission(revoke2);
        verify(lakeFormationClient).grantPermission(grant1);
        verify(lakeFormationClient).grantPermission(grant2);

        // 2 failures logged
        verify(deadLetterLogger, times(2)).logFailedOperation(any(), any(), eq(0));

        assertEquals(1, result.getSuccessfulGrants());
        assertEquals(1, result.getSuccessfulRevokes());
        assertEquals(2, result.getFailedOperations());
        assertEquals(2, result.getDriftReport().getFailedOperations().size());
    }

    // --- Test: structured log entries emitted at start and end of cycle ---

    @Test
    void execute_structuredLogEntries_emittedAtStartAndEnd() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");
        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(0, 0, 0), Collections.emptyList()));

        service.execute(defaultConfig, cedarPolicySet);

        List<ILoggingEvent> logs = logAppender.list;
        // Should have at least a start and end log entry
        assertTrue(logs.size() >= 2, "Expected at least 2 log entries (start + end)");

        boolean hasStartLog = logs.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("Reverse-sync cycle starting"));
        boolean hasEndLog = logs.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("Reverse-sync cycle completed"));

        assertTrue(hasStartLog, "Expected a 'starting' log entry");
        assertTrue(hasEndLog, "Expected a 'completed' log entry");
    }

    // --- Test: running flag reset even when exception occurs ---

    @Test
    void execute_runningFlagReset_evenWhenExceptionOccurs() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");
        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());

        // Make fetcher throw an exception
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any()))
                .thenThrow(new LakeFormationClientException("Fetch failed"));

        // First call should throw
        assertThrows(LakeFormationClientException.class,
                () -> service.execute(defaultConfig, cedarPolicySet));

        // Second call should NOT throw IllegalStateException — running flag was reset
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(0, 0, 0), Collections.emptyList()));

        ReverseSyncResult result = service.execute(defaultConfig, cedarPolicySet);
        assertNotNull(result);
    }

    // --- Test: result contains correct counts ---

    @Test
    void execute_resultContainsCorrectCounts() throws Exception {
        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");

        LFPermissionOperation revoke1 = revokeOp(PRINCIPAL_A, dbResource("db1"), LFPermission.ALTER);
        LFPermissionOperation revoke2 = revokeOp(PRINCIPAL_A, dbResource("db2"), LFPermission.DROP);
        LFPermissionOperation grant1 = grantOp(PRINCIPAL_B, dbResource("db3"), LFPermission.DESCRIBE);
        LFPermissionOperation grant2 = grantOp(PRINCIPAL_B, dbResource("db4"), LFPermission.SELECT);
        LFPermissionOperation grant3 = grantOp(PRINCIPAL_B, dbResource("db5"), LFPermission.INSERT);
        List<LFPermissionOperation> corrections = Arrays.asList(revoke1, revoke2, grant1, grant2, grant3);

        when(cedarToLFConverter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(driftResultWith(report(3, 2, 1), corrections));

        // grant2 fails — use lenient stubbing since multiple grants are called
        lenient().doThrow(new LakeFormationClientException("Grant2 failed"))
                .when(lakeFormationClient).grantPermission(grant2);

        ReverseSyncResult result = service.execute(defaultConfig, cedarPolicySet);

        assertEquals(2, result.getSuccessfulGrants());   // grant1 + grant3
        assertEquals(2, result.getSuccessfulRevokes());   // revoke1 + revoke2
        assertEquals(1, result.getFailedOperations());    // grant2
        assertTrue(result.getDurationMs() >= 0);
        assertNotNull(result.getDriftReport());
        assertEquals(3, result.getDriftReport().getMissingGrants());
        assertEquals(2, result.getDriftReport().getExtraPermissions());
        assertEquals(1, result.getDriftReport().getInSyncCount());
    }
}
