package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.model.DryRunOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for DryRunLakeFormationClient round-trip serialization.
 *
 * Feature: dry-run-integration-tests, Property 1: Dry-run output round-trip serialization
 * **Validates: Requirements 1.2, 2.1, 2.2, 2.3, 2.4**
 */
class DryRunOutputRoundTripPropertyTest {

    /**
     * Property 1: For any valid list of LFPermissionOperation objects,
     * serializing through DryRunLakeFormationClient.applyBatch() and then
     * deserializing the resulting JSON file produces an operations list
     * equal to the original input.
     */
    @Property(tries = 100)
    void dryRunOutputRoundTrip(
            @ForAll("operationLists") List<LFPermissionOperation> operations
    ) throws Exception {
        // Create a fresh temp directory per invocation to isolate sequence counters
        Path runDir = Files.createTempDirectory("dryrun-roundtrip-");
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            DryRunLakeFormationClient client = new DryRunLakeFormationClient(runDir, objectMapper);

            // Serialize via applyBatch
            client.applyBatch(operations, null);

            // Read back the output file
            File outputFile = runDir.resolve("dry-run-001.json").toFile();
            assertTrue(outputFile.exists(), "Expected dry-run-001.json to exist");

            DryRunOutput deserialized = objectMapper.readValue(outputFile, DryRunOutput.class);

            // Assert round-trip equality
            assertNotNull(deserialized.getTimestamp(), "Timestamp should not be null");
            assertEquals(1, deserialized.getSequenceNumber(), "Sequence number should be 1");
            assertEquals(operations, deserialized.getOperations(),
                    "Deserialized operations should equal the original input");
        } finally {
            // Cleanup temp files
            File[] files = runDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            runDir.toFile().delete();
        }
    }

    // --- Arbitraries ---

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

        // Database-level resource: null tableName, null columnNames, null rowFilterExpression
        return Combinators.combine(catalogId, databaseName)
                .as((cat, db) -> new LFResource(cat, db, null, null, null));
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        // Generate at least 1 permission to avoid EnumSet.copyOf() on empty set
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(LFPermission.values().length);
    }
}
