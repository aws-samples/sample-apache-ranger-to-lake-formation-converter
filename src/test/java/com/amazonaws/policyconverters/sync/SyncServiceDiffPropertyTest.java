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

    // NOTE: These properties are stated at the individual-permission (atom) level, not the
    // whole-permission-set level. The diff reconciles permissions independently of which policy
    // contributed them, so overlapping grants from different policies are handled correctly (a
    // permission is only revoked when no remaining operation grants it). See
    // SyncServiceTest#computeDiffDoesNotRevokePermissionStillGrantedByAnotherPolicy.

    @Property(tries = 100)
    void newGrantsAreExactlyInCurrentButNotPrevious(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionAtom> previousAtoms = toAtomSet(pair.previous);
        Set<SyncService.PermissionAtom> currentAtoms = toAtomSet(pair.current);

        // Expected new grants: atoms in current but not in previous
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
    }

    @Property(tries = 100)
    void revocationsAreExactlyInPreviousButNotCurrent(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionAtom> previousAtoms = toAtomSet(pair.previous);
        Set<SyncService.PermissionAtom> currentAtoms = toAtomSet(pair.current);

        // Expected revocations: atoms in previous but not in current
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
    }

    @Property(tries = 100)
    void unchangedCountEqualsIntersectionSize(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionAtom> previousAtoms = toAtomSet(pair.previous);
        Set<SyncService.PermissionAtom> currentAtoms = toAtomSet(pair.current);

        // Expected unchanged: intersection of previous and current atoms
        Set<SyncService.PermissionAtom> intersection = new HashSet<>(previousAtoms);
        intersection.retainAll(currentAtoms);

        assertEquals(intersection.size(), diff.getUnchangedCount(),
                "Unchanged count should equal the size of the atom intersection");
    }

    @Property(tries = 100)
    void unionInvariantHolds(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionAtom> previousAtoms = toAtomSet(pair.previous);
        Set<SyncService.PermissionAtom> currentAtoms = toAtomSet(pair.current);

        // |previous ∪ current| (atoms) = grantAtoms + revokeAtoms + unchanged
        Set<SyncService.PermissionAtom> union = new HashSet<>(previousAtoms);
        union.addAll(currentAtoms);

        int diffTotal = toAtomSet(diff.getNewGrants()).size()
                + toAtomSet(diff.getRevocations()).size()
                + diff.getUnchangedCount();
        assertEquals(union.size(), diffTotal,
                "Union of previous and current atoms should equal grants + revocations + unchanged");
    }

    @Property(tries = 100)
    void diffPartitionsAreDisjoint(
            @ForAll("snapshotPairs") SnapshotPair pair
    ) {
        SyncService.PolicyDiff diff = SyncService.computeDiff(pair.previous, pair.current);

        Set<SyncService.PermissionAtom> grantAtoms = toAtomSet(diff.getNewGrants());
        Set<SyncService.PermissionAtom> revokeAtoms = toAtomSet(diff.getRevocations());

        // Grants and revocations must be disjoint at the atom level
        Set<SyncService.PermissionAtom> overlap = new HashSet<>(grantAtoms);
        overlap.retainAll(revokeAtoms);
        assertTrue(overlap.isEmpty(),
                "New grant atoms and revocation atoms must be disjoint sets");
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

    static class SnapshotPair {
        final List<LFPermissionOperation> previous;
        final List<LFPermissionOperation> current;

        SnapshotPair(List<LFPermissionOperation> previous, List<LFPermissionOperation> current) {
            this.previous = previous;
            this.current = current;
        }
    }
}
