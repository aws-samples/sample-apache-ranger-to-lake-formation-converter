package com.amazonaws.policyconverters.ranger.sync;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import net.jqwik.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for deletion-revocation symmetry.
 *
 * Feature: dry-run-integration-tests, Property 4: Deletion-revocation symmetry
 * **Validates: Requirements 9.3**
 */
class DeletionRevocationSymmetryPropertyTest {

    @Property(tries = 100)
    void deletionProducesMatchingRevocations(
            @ForAll("grantOperationLists") List<LFPermissionOperation> previousGrants
    ) {
        // Compute diff with empty current snapshot (simulating full deletion)
        SyncService.PolicyDiff diff = SyncService.computeDiff(
                previousGrants, Collections.emptyList());

        // No new grants expected
        assertEquals(0, diff.getNewGrants().size(),
                "Expected zero new grants when current snapshot is empty");

        // Revocations should match the previous grants
        List<LFPermissionOperation> revocations = diff.getRevocations();
        assertEquals(previousGrants.size(), revocations.size(),
                "Expected one revocation per previous grant");

        // Each revocation should be REVOKE type
        for (LFPermissionOperation revoke : revocations) {
            assertEquals(OperationType.REVOKE, revoke.getOperationType(),
                    "All revocations should have REVOKE operation type");
        }

        // Build a set of (principalArn, resource) from original grants
        Set<String> grantKeys = previousGrants.stream()
                .map(op -> op.getPrincipalArn() + "|" + op.getResource())
                .collect(Collectors.toSet());

        // Each revocation should match an original grant on principalArn and resource
        for (LFPermissionOperation revoke : revocations) {
            String revokeKey = revoke.getPrincipalArn() + "|" + revoke.getResource();
            assertTrue(grantKeys.contains(revokeKey),
                    "REVOKE principalArn+resource should match an original GRANT: " + revokeKey);
        }
    }

    @Provide
    Arbitrary<List<LFPermissionOperation>> grantOperationLists() {
        return grantOperation().list().ofMinSize(1).ofMaxSize(5)
                .filter(list -> {
                    // Ensure unique PermissionKeys (principalArn + resource + permissions + grantable)
                    Set<String> keys = new HashSet<>();
                    for (LFPermissionOperation op : list) {
                        String key = op.getPrincipalArn() + "|" + op.getResource() + "|"
                                + op.getPermissions() + "|" + op.isGrantable();
                        if (!keys.add(key)) return false;
                    }
                    return true;
                });
    }

    private Arbitrary<LFPermissionOperation> grantOperation() {
        Arbitrary<String> sourcePolicyId = Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('0', '9')
                .ofMinLength(1).ofMaxLength(10);
        Arbitrary<String> principalArn = Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('0', '9')
                .ofMinLength(3).ofMaxLength(10)
                .map(s -> "arn:aws:iam::123456789012:role/" + s);
        Arbitrary<LFResource> resource = resource();
        Arbitrary<Set<LFPermission>> permissions = permissionSets();
        Arbitrary<Boolean> grantable = Arbitraries.of(true, false);

        return Combinators.combine(sourcePolicyId, principalArn, resource, permissions, grantable)
                .as((pid, arn, res, perms, grant) ->
                        new LFPermissionOperation(OperationType.GRANT, pid, arn, res, perms, grant));
    }

    private Arbitrary<LFResource> resource() {
        Arbitrary<String> catalogId = Arbitraries.strings().numeric().ofLength(12);
        Arbitrary<String> databaseName = Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('0', '9')
                .ofMinLength(1).ofMaxLength(15);
        return Combinators.combine(catalogId, databaseName)
                .as((cat, db) -> new LFResource(cat, db, null, null, null));
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set().ofMinSize(1).ofMaxSize(LFPermission.values().length);
    }
}
