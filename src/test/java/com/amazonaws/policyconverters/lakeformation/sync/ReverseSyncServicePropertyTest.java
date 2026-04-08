package com.amazonaws.policyconverters.lakeformation.sync;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.lakeformation.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.client.DeadLetterLogger;
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
import net.jqwik.api.*;

import java.util.*;
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
 * Property-based tests for {@link ReverseSyncService}.
 * Validates REVOKE-before-GRANT ordering, continue-on-failure, empty Cedar safety,
 * and concurrency guard properties.
 */
class ReverseSyncServicePropertyTest {

    private static final String CATALOG_ID = "123456789012";

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 8: REVOKE-before-GRANT ordering
    // **Validates: Requirements 4.1**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void revokeBeforeGrantOrdering(
            @ForAll("mixedOpLists") List<LFPermissionOperation> operations) {

        // Call the package-private orderCorrectiveOperations directly
        ReverseSyncService service = new ReverseSyncService(
                mock(LFPermissionFetcher.class),
                mock(DriftDetector.class),
                mock(LakeFormationClient.class),
                mock(CedarToLFConverter.class),
                mock(DeadLetterLogger.class));

        List<LFPermissionOperation> ordered = service.orderCorrectiveOperations(operations);

        // Verify: all REVOKEs appear before all GRANTs
        boolean seenGrant = false;
        for (LFPermissionOperation op : ordered) {
            if (op.getOperationType() == OperationType.GRANT) {
                seenGrant = true;
            }
            if (op.getOperationType() == OperationType.REVOKE && seenGrant) {
                fail("REVOKE operation found after a GRANT operation — " +
                        "all REVOKEs must precede all GRANTs");
            }
        }

        // Verify: same elements, just reordered
        assertEquals(operations.size(), ordered.size(),
                "Ordered list should have the same size as input");

        long inputRevokes = operations.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE).count();
        long inputGrants = operations.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT).count();
        long orderedRevokes = ordered.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE).count();
        long orderedGrants = ordered.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT).count();

        assertEquals(inputRevokes, orderedRevokes, "REVOKE count should be preserved");
        assertEquals(inputGrants, orderedGrants, "GRANT count should be preserved");
    }


    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 9: Continue-on-failure
    // **Validates: Requirements 4.3, 4.4, 7.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void continueOnFailure(
            @ForAll("mixedOpLists") List<LFPermissionOperation> operations,
            @ForAll("failureIndices") Set<Integer> failurePositions) throws Exception {

        // Deduplicate operations to avoid Mockito verification issues with duplicate ops
        List<LFPermissionOperation> dedupedOps = new ArrayList<>(new java.util.LinkedHashSet<>(operations));
        if (dedupedOps.isEmpty()) {
            return; // nothing to test with empty ops
        }

        // Normalize failure positions to valid indices
        Set<Integer> normalizedFailures = new HashSet<>();
        for (Integer pos : failurePositions) {
            normalizedFailures.add(Math.abs(pos) % dedupedOps.size());
        }

        // Set up mocks
        LFPermissionFetcher fetcher = mock(LFPermissionFetcher.class);
        DriftDetector driftDetector = mock(DriftDetector.class);
        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        CedarToLFConverter converter = mock(CedarToLFConverter.class);
        DeadLetterLogger deadLetterLogger = mock(DeadLetterLogger.class);
        CedarPolicySet cedarPolicySet = mock(CedarPolicySet.class);

        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");
        when(converter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());
        when(fetcher.fetchPermissions(eq(CATALOG_ID), any())).thenReturn(Collections.emptyList());

        DriftReport report = new DriftReport(0, 0, 0, Collections.emptyList(), Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), eq(false)))
                .thenReturn(new DriftResult(report, dedupedOps));

        // Order the operations the same way the service will
        ReverseSyncService tempService = new ReverseSyncService(fetcher, driftDetector,
                lfClient, converter, deadLetterLogger);
        List<LFPermissionOperation> ordered = tempService.orderCorrectiveOperations(dedupedOps);

        // Configure which operations fail based on their position in the ordered list
        AtomicInteger callIndex = new AtomicInteger(0);
        Set<Integer> orderedFailurePositions = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            // Map original failure positions to ordered positions
            int origIdx = dedupedOps.indexOf(ordered.get(i));
            if (normalizedFailures.contains(origIdx)) {
                orderedFailurePositions.add(i);
            }
        }

        // Set up grant/revoke mocks to fail at specific positions
        for (int i = 0; i < ordered.size(); i++) {
            LFPermissionOperation op = ordered.get(i);
            if (orderedFailurePositions.contains(i)) {
                if (op.getOperationType() == OperationType.REVOKE) {
                    doThrow(new LakeFormationClientException("Simulated failure"))
                            .when(lfClient).revokePermission(op);
                } else {
                    doThrow(new LakeFormationClientException("Simulated failure"))
                            .when(lfClient).grantPermission(op);
                }
            }
        }

        ReverseSyncConfig config = new ReverseSyncConfig(true, CATALOG_ID, false, false, null, null, 0L);
        ReverseSyncResult result = tempService.execute(config, cedarPolicySet);

        // Verify all operations were attempted
        for (LFPermissionOperation op : ordered) {
            if (op.getOperationType() == OperationType.REVOKE) {
                verify(lfClient).revokePermission(op);
            } else {
                verify(lfClient).grantPermission(op);
            }
        }

        // Verify counts are accurate
        int expectedFailed = orderedFailurePositions.size();
        int expectedSuccessful = ordered.size() - expectedFailed;

        assertEquals(expectedFailed, result.getFailedOperations(),
                "Failed operation count should match number of configured failures");
        assertEquals(expectedSuccessful,
                result.getSuccessfulGrants() + result.getSuccessfulRevokes(),
                "Successful operation count should match total minus failures");
    }


    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 10: Empty Cedar safety guard
    // **Validates: Requirements 5.5**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void emptyCedarSafetyGuard(
            @ForAll("emptyCedarVariants") CedarPolicySet cedarPolicySet) throws Exception {

        LFPermissionFetcher fetcher = mock(LFPermissionFetcher.class);
        DriftDetector driftDetector = mock(DriftDetector.class);
        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        CedarToLFConverter converter = mock(CedarToLFConverter.class);
        DeadLetterLogger deadLetterLogger = mock(DeadLetterLogger.class);

        ReverseSyncService service = new ReverseSyncService(fetcher, driftDetector,
                lfClient, converter, deadLetterLogger);
        ReverseSyncConfig config = new ReverseSyncConfig(true, CATALOG_ID, false, false, null, null, 0L);

        ReverseSyncResult result = service.execute(config, cedarPolicySet);

        // No LF operations should be attempted
        verifyNoInteractions(lfClient);
        verifyNoInteractions(fetcher);
        verifyNoInteractions(driftDetector);
        verifyNoInteractions(converter);

        // Result should have zero corrective operations
        assertEquals(0, result.getSuccessfulGrants(),
                "Empty Cedar should produce zero successful grants");
        assertEquals(0, result.getSuccessfulRevokes(),
                "Empty Cedar should produce zero successful revokes");
        assertEquals(0, result.getFailedOperations(),
                "Empty Cedar should produce zero failed operations");
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 11: Concurrency guard
    // **Validates: Requirements 5.4**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void concurrencyGuard(@ForAll("catalogIds") String catalogId) throws Exception {

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch proceedLatch = new CountDownLatch(1);

        LFPermissionFetcher fetcher = mock(LFPermissionFetcher.class);
        DriftDetector driftDetector = mock(DriftDetector.class);
        LakeFormationClient lfClient = mock(LakeFormationClient.class);
        CedarToLFConverter converter = mock(CedarToLFConverter.class);
        DeadLetterLogger deadLetterLogger = mock(DeadLetterLogger.class);
        CedarPolicySet cedarPolicySet = mock(CedarPolicySet.class);

        when(cedarPolicySet.toCedarString()).thenReturn("permit(principal, action, resource);");
        when(converter.convert(cedarPolicySet)).thenReturn(Collections.emptyList());

        // Make fetcher block until both threads are ready, then proceed
        when(fetcher.fetchPermissions(any(), any())).thenAnswer(invocation -> {
            bothReady.countDown();
            proceedLatch.await();
            return Collections.emptyList();
        });

        DriftReport report = new DriftReport(0, 0, 0, Collections.emptyList(), Collections.emptyList());
        when(driftDetector.computeDrift(any(), any(), any(), anyBoolean()))
                .thenReturn(new DriftResult(report, Collections.emptyList()));

        ReverseSyncService service = new ReverseSyncService(fetcher, driftDetector,
                lfClient, converter, deadLetterLogger);
        ReverseSyncConfig config = new ReverseSyncConfig(true, catalogId, false, false, null, null, 0L);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Runnable task = () -> {
                try {
                    service.execute(config, cedarPolicySet);
                    successCount.incrementAndGet();
                } catch (IllegalStateException e) {
                    rejectedCount.incrementAndGet();
                } catch (Throwable e) {
                    unexpectedError.compareAndSet(null, e);
                }
            };

            Future<?> f1 = executor.submit(task);
            Future<?> f2 = executor.submit(task);

            // Wait for at least one thread to enter execute (the fetcher blocks)
            // Give a short time for both threads to attempt entry
            Thread.sleep(100);

            // Release the blocking fetcher
            proceedLatch.countDown();

            f1.get();
            f2.get();

            assertNull(unexpectedError.get(),
                    "No unexpected errors should occur: " + unexpectedError.get());
            assertEquals(1, successCount.get(),
                    "Exactly one invocation should succeed");
            assertEquals(1, rejectedCount.get(),
                    "Exactly one invocation should be rejected with IllegalStateException");
        } finally {
            executor.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Arbitraries (generators)
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<List<LFPermissionOperation>> mixedOpLists() {
        return mixedOps().list().ofMinSize(1).ofMaxSize(10);
    }

    private Arbitrary<LFPermissionOperation> mixedOps() {
        return Combinators.combine(
                Arbitraries.of(OperationType.GRANT, OperationType.REVOKE),
                principalArns(),
                lfResources(),
                permissionSets(),
                Arbitraries.of(true, false)
        ).as((opType, principal, resource, perms, grantable) ->
                new LFPermissionOperation(opType, null, principal, resource, perms, grantable));
    }

    @Provide
    Arbitrary<Set<Integer>> failureIndices() {
        return Arbitraries.integers().between(0, 9)
                .set().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<CedarPolicySet> emptyCedarVariants() {
        // Generate either null or a mock that returns empty string
        return Arbitraries.of(0, 1, 2).map(variant -> {
            if (variant == 0) {
                return null;
            } else if (variant == 1) {
                CedarPolicySet mock = mock(CedarPolicySet.class);
                when(mock.toCedarString()).thenReturn("");
                return mock;
            } else {
                CedarPolicySet mock = mock(CedarPolicySet.class);
                when(mock.toCedarString()).thenReturn("   ");
                return mock;
            }
        });
    }

    @Provide
    Arbitrary<String> catalogIds() {
        return Arbitraries.of("123456789012", "987654321098", "111222333444");
    }

    private Arbitrary<String> principalArns() {
        return Arbitraries.of(
                "arn:aws:iam::123456789012:role/RoleA",
                "arn:aws:iam::123456789012:role/RoleB",
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::123456789012:role/DataAnalyst",
                "arn:aws:iam::123456789012:role/Admin");
    }

    private Arbitrary<LFResource> lfResources() {
        return Arbitraries.oneOf(
                databaseResources(),
                tableResources(),
                columnResources()
        );
    }

    private Arbitrary<LFResource> databaseResources() {
        return Combinators.combine(
                Arbitraries.of("123456789012", "987654321098"),
                Arbitraries.of("analytics", "finance", "hr", "sales")
        ).as((catalogId, dbName) -> new LFResource(catalogId, dbName, null, null, null));
    }

    private Arbitrary<LFResource> tableResources() {
        return Combinators.combine(
                Arbitraries.of("123456789012", "987654321098"),
                Arbitraries.of("analytics", "finance", "hr", "sales"),
                Arbitraries.of("events", "users", "transactions", "orders")
        ).as((catalogId, dbName, tableName) ->
                new LFResource(catalogId, dbName, tableName, null, null));
    }

    private Arbitrary<LFResource> columnResources() {
        return Combinators.combine(
                Arbitraries.of("123456789012", "987654321098"),
                Arbitraries.of("analytics", "finance"),
                Arbitraries.of("events", "users"),
                Arbitraries.of("col1", "col2", "col3").set().ofMinSize(1).ofMaxSize(3)
        ).as((catalogId, dbName, tableName, cols) ->
                new LFResource(catalogId, dbName, tableName, cols, null));
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(3);
    }
}
