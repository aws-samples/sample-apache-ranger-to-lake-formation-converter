package com.amazonaws.policyconverters.sync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.jqwik.api.*;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for SyncService diff logic and audit logging.
 * Uses jqwik to verify correctness across randomized inputs.
 */
class SyncServicePropertyTest {

    private static final String CATALOG_ID = "123456789012";

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 13: Policy diff correctness
    // **Validates: Requirements 4.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void policyDiffCorrectness(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        // Diff correctness is stated at the individual-permission (atom) level: computeDiff
        // reconciles permissions independently of which policy contributed them, so overlapping
        // grants from different policies are handled per-permission. See
        // SyncServiceTest#computeDiffDoesNotRevokePermissionStillGrantedByAnotherPolicy.
        Set<SyncService.PermissionAtom> previousAtoms = toAtomSet(pair.previous);
        Set<SyncService.PermissionAtom> currentAtoms = toAtomSet(pair.current);

        // (a) New grants: atoms in current but not in previous
        Set<SyncService.PermissionAtom> expectedNewAtoms = new HashSet<>(currentAtoms);
        expectedNewAtoms.removeAll(previousAtoms);

        Set<SyncService.PermissionAtom> actualNewAtoms = toAtomSet(diff.getNewGrants());
        assertEquals(expectedNewAtoms, actualNewAtoms,
                "New grants should be exactly the permission atoms in current but not in previous");

        // All new grants must have GRANT operation type
        for (LFPermissionOperation op : diff.getNewGrants()) {
            assertEquals(OperationType.GRANT, op.getOperationType(),
                    "New grant operations must have GRANT type");
        }

        // (b) Revocations: atoms in previous but not in current
        Set<SyncService.PermissionAtom> expectedRevokeAtoms = new HashSet<>(previousAtoms);
        expectedRevokeAtoms.removeAll(currentAtoms);

        Set<SyncService.PermissionAtom> actualRevokeAtoms = toAtomSet(diff.getRevocations());
        assertEquals(expectedRevokeAtoms, actualRevokeAtoms,
                "Revocations should be exactly the permission atoms in previous but not in current");

        // All revocations must have REVOKE operation type
        for (LFPermissionOperation op : diff.getRevocations()) {
            assertEquals(OperationType.REVOKE, op.getOperationType(),
                    "Revocation operations must have REVOKE type");
        }

        // (c) Unchanged count: atoms present in both
        Set<SyncService.PermissionAtom> expectedUnchanged = new HashSet<>(previousAtoms);
        expectedUnchanged.retainAll(currentAtoms);
        assertEquals(expectedUnchanged.size(), diff.getUnchangedCount(),
                "Unchanged count should equal the atom intersection of previous and current");

        // Completeness: grant atoms + revoke atoms + unchanged should equal |previous ∪ current|
        assertEquals(
                actualNewAtoms.size() + actualRevokeAtoms.size() + diff.getUnchangedCount(),
                expectedNewAtoms.size() + expectedRevokeAtoms.size() + expectedUnchanged.size(),
                "Total diff components should account for all unique permission atoms");
    }

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 14: Audit log completeness
    // **Validates: Requirements 4.6**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void auditLogCompleteness(
            @ForAll("operationLists") List<LFPermissionOperation> operations
    ) {
        // Attach a ListAppender to the audit logger to capture log entries
        Logger auditLogger = (Logger) LoggerFactory.getLogger(
                SyncService.class.getName() + ".audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);

        try {
            // Log audit entries for each operation
            for (LFPermissionOperation op : operations) {
                SyncService.logAuditEntry(op);
            }

            // Verify: one log entry per operation
            assertEquals(operations.size(), appender.list.size(),
                    "Audit log should contain exactly one entry per operation");

            // Verify each log entry contains the required fields
            for (int i = 0; i < operations.size(); i++) {
                LFPermissionOperation op = operations.get(i);
                String logMessage = appender.list.get(i).getFormattedMessage();

                // Must contain policy ID
                assertTrue(logMessage.contains(op.getSourcePolicyId()),
                        "Audit entry must contain policy ID: " + op.getSourcePolicyId()
                                + ", got: " + logMessage);

                // Must contain resource path
                String expectedResourcePath = SyncService.formatResourcePath(op.getResource());
                assertTrue(logMessage.contains(expectedResourcePath),
                        "Audit entry must contain resource path: " + expectedResourcePath
                                + ", got: " + logMessage);

                // Must contain principal ARN
                assertTrue(logMessage.contains(op.getPrincipalArn()),
                        "Audit entry must contain principal ARN: " + op.getPrincipalArn()
                                + ", got: " + logMessage);

                // Must contain permission type(s)
                assertTrue(logMessage.contains(op.getPermissions().toString()),
                        "Audit entry must contain permissions: " + op.getPermissions()
                                + ", got: " + logMessage);

                // Must contain operation type (GRANT/REVOKE)
                assertTrue(logMessage.contains(op.getOperationType().getValue()),
                        "Audit entry must contain operation type: " + op.getOperationType()
                                + ", got: " + logMessage);
            }
        } finally {
            auditLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // -----------------------------------------------------------------------
    // Arbitraries (generators)
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<SnapshotPair> snapshotPairs() {
        return Combinators.combine(
                operationLists(),
                operationLists()
        ).as(SnapshotPair::new);
    }

    @Provide
    Arbitrary<List<LFPermissionOperation>> operationLists() {
        return lfPermissionOperations()
                .list()
                .ofMinSize(0)
                .ofMaxSize(8);
    }

    private Arbitrary<LFPermissionOperation> lfPermissionOperations() {
        Arbitrary<String> policyIds = Arbitraries.integers().between(1, 50)
                .map(String::valueOf);
        Arbitrary<String> principalArns = Arbitraries.of(
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::123456789012:user/bob",
                "arn:aws:iam::123456789012:role/DataAnalyst",
                "arn:aws:iam::123456789012:role/Admin",
                "arn:aws:iam::123456789012:user/charlie"
        );
        Arbitrary<LFResource> resources = lfResources();
        Arbitrary<Set<LFPermission>> permissions = permissionSets();
        Arbitrary<Boolean> grantable = Arbitraries.of(true, false);
        Arbitrary<OperationType> opTypes = Arbitraries.of(OperationType.GRANT, OperationType.REVOKE);

        return Combinators.combine(opTypes, policyIds, principalArns, resources, permissions, grantable)
                .as(LFPermissionOperation::new);
    }

    private Arbitrary<LFResource> lfResources() {
        Arbitrary<String> databases = Arbitraries.of("analytics", "finance", "hr", "marketing");
        Arbitrary<String> tables = Arbitraries.of("events", "users", "transactions", "reports");

        return Combinators.combine(databases, tables, Arbitraries.of(true, false))
                .as((db, table, includeTable) -> new LFResource(
                        CATALOG_ID, db,
                        includeTable ? table : null,
                        null, null));
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(4);
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Explode operations into individual permission atoms — the granularity at which
     * {@link SyncService#computeDiff} reconciles permissions.
     */
    private static Set<SyncService.PermissionAtom> toAtomSet(List<LFPermissionOperation> ops) {
        Set<SyncService.PermissionAtom> atoms = new HashSet<>();
        for (LFPermissionOperation op : ops) {
            for (LFPermission permission : op.getPermissions()) {
                atoms.add(new SyncService.PermissionAtom(
                        op.getPrincipalArn(), op.getResource(), permission, op.isGrantable()));
            }
        }
        return atoms;
    }

    // -----------------------------------------------------------------------
    // Helper types
    // -----------------------------------------------------------------------

    static class SnapshotPair {
        final List<LFPermissionOperation> previous;
        final List<LFPermissionOperation> current;

        SnapshotPair(List<LFPermissionOperation> previous, List<LFPermissionOperation> current) {
            this.previous = previous;
            this.current = current;
        }
    }
}
