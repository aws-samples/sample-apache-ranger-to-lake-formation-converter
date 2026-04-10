package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import net.jqwik.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for SyncService.computeDiff() partitioning logic.
 *
 * **Validates: Requirements 3.2**
 */
@Tag("Feature: wildcard-pattern-refresh, Property 4: Diff computation correctly partitions operations")
class SyncServiceDiffPropertyTest {

    private static final String CATALOG_ID = "123456789012";

    // -----------------------------------------------------------------------
    // Property 4: Diff computation correctly partitions operations into
    //             grants, revocations, and unchanged
    // **Validates: Requirements 3.2**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void newGrantsAreExactlyInCurrentButNotPrevious(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionKey> previousKeys = toKeySet(pair.previous);
        Set<SyncService.PermissionKey> currentKeys = toKeySet(pair.current);

        // Expected new grants: in current but not in previous
        Set<SyncService.PermissionKey> expectedNewKeys = new HashSet<>(currentKeys);
        expectedNewKeys.removeAll(previousKeys);

        Set<SyncService.PermissionKey> actualNewKeys = toKeySet(diff.getNewGrants());
        assertEquals(expectedNewKeys, actualNewKeys,
                "New grants should be exactly the permissions in current but not in previous");

        // All new grants must have GRANT operation type
        for (LFPermissionOperation op : diff.getNewGrants()) {
            assertEquals(OperationType.GRANT, op.getOperationType(),
                    "New grant operations must have GRANT type");
        }
    }

    @Property(tries = 100)
    void revocationsAreExactlyInPreviousButNotCurrent(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionKey> previousKeys = toKeySet(pair.previous);
        Set<SyncService.PermissionKey> currentKeys = toKeySet(pair.current);

        // Expected revocations: in previous but not in current
        Set<SyncService.PermissionKey> expectedRevokeKeys = new HashSet<>(previousKeys);
        expectedRevokeKeys.removeAll(currentKeys);

        Set<SyncService.PermissionKey> actualRevokeKeys = toKeySet(diff.getRevocations());
        assertEquals(expectedRevokeKeys, actualRevokeKeys,
                "Revocations should be exactly the permissions in previous but not in current");

        // All revocations must have REVOKE operation type
        for (LFPermissionOperation op : diff.getRevocations()) {
            assertEquals(OperationType.REVOKE, op.getOperationType(),
                    "Revocation operations must have REVOKE type");
        }
    }

    @Property(tries = 100)
    void unchangedCountEqualsIntersectionSize(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionKey> previousKeys = toKeySet(pair.previous);
        Set<SyncService.PermissionKey> currentKeys = toKeySet(pair.current);

        // Expected unchanged: intersection of previous and current
        Set<SyncService.PermissionKey> intersection = new HashSet<>(previousKeys);
        intersection.retainAll(currentKeys);

        assertEquals(intersection.size(), diff.getUnchangedCount(),
                "Unchanged count should equal the size of the intersection");
    }

    @Property(tries = 100)
    void unionInvariantHolds(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionKey> previousKeys = toKeySet(pair.previous);
        Set<SyncService.PermissionKey> currentKeys = toKeySet(pair.current);

        // |previous ∪ current| = newGrants + revocations + unchanged
        Set<SyncService.PermissionKey> union = new HashSet<>(previousKeys);
        union.addAll(currentKeys);

        int diffTotal = diff.getNewGrants().size() + diff.getRevocations().size() + diff.getUnchangedCount();
        assertEquals(union.size(), diffTotal,
                "Union of previous and current should equal grants + revocations + unchanged");
    }

    @Property(tries = 100)
    void diffPartitionsAreDisjoint(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionKey> grantKeys = toKeySet(diff.getNewGrants());
        Set<SyncService.PermissionKey> revokeKeys = toKeySet(diff.getRevocations());

        // Grants and revocations must be disjoint
        Set<SyncService.PermissionKey> overlap = new HashSet<>(grantKeys);
        overlap.retainAll(revokeKeys);
        assertTrue(overlap.isEmpty(),
                "New grants and revocations must be disjoint sets");
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
                .ofMaxSize(10);
    }

    private Arbitrary<LFPermissionOperation> lfPermissionOperations() {
        Arbitrary<String> policyIds = Arbitraries.integers().between(1, 30)
                .map(String::valueOf);
        Arbitrary<String> principalArns = Arbitraries.of(
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::123456789012:user/bob",
                "arn:aws:iam::123456789012:role/DataAnalyst",
                "arn:aws:iam::123456789012:role/Admin"
        );
        Arbitrary<LFResource> resources = lfResources();
        Arbitrary<Set<LFPermission>> permissions = permissionSets();
        Arbitrary<Boolean> grantable = Arbitraries.of(true, false);
        Arbitrary<OperationType> opTypes = Arbitraries.of(OperationType.GRANT, OperationType.REVOKE);

        return Combinators.combine(opTypes, policyIds, principalArns, resources, permissions, grantable)
                .as(LFPermissionOperation::new);
    }

    private Arbitrary<LFResource> lfResources() {
        Arbitrary<String> databases = Arbitraries.of("analytics", "finance", "hr");
        Arbitrary<String> tables = Arbitraries.of("events", "users", "transactions");

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
                .ofMaxSize(3);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Set<SyncService.PermissionKey> toKeySet(List<LFPermissionOperation> ops) {
        Set<SyncService.PermissionKey> keys = new HashSet<>();
        for (LFPermissionOperation op : ops) {
            keys.add(SyncService.PermissionKey.of(op));
        }
        return keys;
    }

    static class SnapshotPair {
        final List<LFPermissionOperation> previous;
        final List<LFPermissionOperation> current;

        SnapshotPair(List<LFPermissionOperation> previous, List<LFPermissionOperation> current) {
            this.previous = previous;
            this.current = current;
        }
    }
}
