package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for DryRunLakeFormationClient BatchResult invariant.
 *
 * Feature: dry-run-integration-tests, Property 2: Dry-run BatchResult always-success invariant
 * **Validates: Requirements 1.4**
 */
class DryRunBatchResultPropertyTest {

    /**
     * Property 2: For any valid list of LFPermissionOperation objects,
     * calling DryRunLakeFormationClient.applyBatch() returns a BatchResult
     * with zero failures, zero rollbacks, and applied count equal to input size.
     */
    @Property(tries = 100)
    void dryRunBatchResultAlwaysSuccess(
            @ForAll("operationLists") List<LFPermissionOperation> operations
    ) throws Exception {
        Path runDir = Files.createTempDirectory("dryrun-batchresult-");
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            DryRunLakeFormationClient client = new DryRunLakeFormationClient(runDir, objectMapper);

            BatchResult result = client.applyBatch(operations, null);

            assertTrue(result.getFailedPolicyIds().isEmpty(),
                    "Dry-run BatchResult should have zero failed policy IDs");
            assertEquals(0, result.getRolledBackOperations(),
                    "Dry-run BatchResult should have zero rolled-back operations");
            assertEquals(operations.size(), result.getAppliedOperations(),
                    "Dry-run BatchResult applied count should equal input size");
        } finally {
            File[] files = runDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            runDir.toFile().delete();
        }
    }

    // --- Arbitraries (reused pattern from DryRunOutputRoundTripPropertyTest) ---

    @Provide
    Arbitrary<List<LFPermissionOperation>> operationLists() {
        return operation().list().ofMinSize(1).ofMaxSize(5);
    }

    private Arbitrary<LFPermissionOperation> operation() {
        Arbitrary<OperationType> opType = Arbitraries.of(OperationType.GRANT, OperationType.REVOKE);
        Arbitrary<String> sourcePolicyId = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(10);
        Arbitrary<String> principalArn = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(s -> "arn:aws:iam::123456789012:role/" + s);
        Arbitrary<LFResource> resource = resource();
        Arbitrary<Set<LFPermission>> permissions = permissionSets();
        Arbitrary<Boolean> grantable = Arbitraries.of(true, false);

        return Combinators.combine(opType, sourcePolicyId, principalArn, resource, permissions, grantable)
                .as(LFPermissionOperation::new);
    }

    private Arbitrary<LFResource> resource() {
        Arbitrary<String> catalogId = Arbitraries.strings().numeric().ofLength(12);
        Arbitrary<String> databaseName = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(15);

        return Combinators.combine(catalogId, databaseName)
                .as((cat, db) -> new LFResource(cat, db, null, null, null));
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(LFPermission.values().length);
    }
}
