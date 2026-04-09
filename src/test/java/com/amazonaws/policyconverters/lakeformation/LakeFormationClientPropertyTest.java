package com.amazonaws.policyconverters.lakeformation;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.config.RetryConfig;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchGrantPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.BatchRevokePermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsFailureEntry;
import software.amazon.awssdk.services.lakeformation.model.BatchPermissionsRequestEntry;
import software.amazon.awssdk.services.lakeformation.model.ErrorDetail;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for LakeFormationClient batch application and dead-letter logging.
 * Uses jqwik to verify correctness across randomized batches of LF permission operations.
 */
class LakeFormationClientPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RetryConfig RETRY_CONFIG = new RetryConfig(2, 10L, 2.0, 100L);

    // -----------------------------------------------------------------------
    // Property 18: Batch partial failure handling
    // For any batch, entries that fail in the batch response are reported as
    // failures while successful entries are counted correctly.
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void batchPartialFailureHandling(
            @ForAll("batchWithFailingPolicy") BatchScenario scenario
    ) {
        software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient =
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class);

        // Consolidate to know what the client will actually send
        List<LFPermissionOperation> consolidated = LakeFormationClient.consolidateOperations(scenario.allOps);
        List<LFPermissionOperation> consolidatedGrants = consolidated.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());
        List<LFPermissionOperation> consolidatedRevokes = consolidated.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .collect(Collectors.toList());

        // Fail entries whose sourcePolicyId matches the failing policy
        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class))).thenAnswer(inv -> {
            BatchGrantPermissionsRequest req = inv.getArgument(0);
            List<BatchPermissionsFailureEntry> failures = new ArrayList<>();
            for (BatchPermissionsRequestEntry entry : req.entries()) {
                int idx = Integer.parseInt(entry.id().replace("grant-", ""));
                LFPermissionOperation op = consolidatedGrants.get(idx);
                if (op.getSourcePolicyId().equals(scenario.failingPolicyId)) {
                    failures.add(BatchPermissionsFailureEntry.builder()
                            .requestEntry(entry)
                            .error(ErrorDetail.builder().errorMessage("Simulated failure").build())
                            .build());
                }
            }
            return BatchGrantPermissionsResponse.builder().failures(failures).build();
        });

        when(awsClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class))).thenAnswer(inv -> {
            BatchRevokePermissionsRequest req = inv.getArgument(0);
            List<BatchPermissionsFailureEntry> failures = new ArrayList<>();
            for (BatchPermissionsRequestEntry entry : req.entries()) {
                int idx = Integer.parseInt(entry.id().replace("revoke-", ""));
                LFPermissionOperation op = consolidatedRevokes.get(idx);
                if (op.getSourcePolicyId().equals(scenario.failingPolicyId)) {
                    failures.add(BatchPermissionsFailureEntry.builder()
                            .requestEntry(entry)
                            .error(ErrorDetail.builder().errorMessage("Simulated failure").build())
                            .build());
                }
            }
            return BatchRevokePermissionsResponse.builder().failures(failures).build();
        });

        LakeFormationClient client = new LakeFormationClient(awsClient, RETRY_CONFIG, millis -> {});
        BatchResult result = client.applyBatch(scenario.allOps, null);

        // The failing policy should be in the failed list
        assertTrue(result.getFailedPolicyIds().contains(scenario.failingPolicyId),
                "Failing policy should be in failed list");

        // The failing policy should NOT be in the succeeded list
        assertFalse(result.getSucceededPolicyIds().contains(scenario.failingPolicyId),
                "Failing policy should not be in succeeded list");

        // Non-failing policies should succeed
        for (String policyId : scenario.policyIds) {
            if (!policyId.equals(scenario.failingPolicyId)) {
                assertTrue(result.getSucceededPolicyIds().contains(policyId),
                        "Non-failing policy " + policyId + " should succeed");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 19: Dead-letter log completeness with batch API
    // For any batch where all entries fail, the dead-letter log contains
    // an entry for each failed operation.
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void deadLetterLogCompleteness(
            @ForAll("failingOps") List<LFPermissionOperation> ops
    ) throws Exception {
        software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient =
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class);

        // All entries fail in the batch response
        when(awsClient.batchGrantPermissions(any(BatchGrantPermissionsRequest.class))).thenAnswer(inv -> {
            BatchGrantPermissionsRequest req = inv.getArgument(0);
            List<BatchPermissionsFailureEntry> failures = req.entries().stream()
                    .map(e -> BatchPermissionsFailureEntry.builder()
                            .requestEntry(e)
                            .error(ErrorDetail.builder().errorMessage("Permanent failure").build())
                            .build())
                    .collect(Collectors.toList());
            return BatchGrantPermissionsResponse.builder().failures(failures).build();
        });

        when(awsClient.batchRevokePermissions(any(BatchRevokePermissionsRequest.class))).thenAnswer(inv -> {
            BatchRevokePermissionsRequest req = inv.getArgument(0);
            List<BatchPermissionsFailureEntry> failures = req.entries().stream()
                    .map(e -> BatchPermissionsFailureEntry.builder()
                            .requestEntry(e)
                            .error(ErrorDetail.builder().errorMessage("Permanent failure").build())
                            .build())
                    .collect(Collectors.toList());
            return BatchRevokePermissionsResponse.builder().failures(failures).build();
        });

        LakeFormationClient client = new LakeFormationClient(awsClient, RETRY_CONFIG, millis -> {});

        StringWriter sw = new StringWriter();
        DeadLetterLogger dll = new DeadLetterLogger(new BufferedWriter(sw));

        BatchResult result = client.applyBatch(ops, dll);

        // All policies should fail
        Set<String> policyIds = ops.stream()
                .map(LFPermissionOperation::getSourcePolicyId)
                .collect(Collectors.toSet());
        assertEquals(policyIds.size(), result.getFailedPolicyIds().size(),
                "All policies should fail");

        // Parse dead-letter log lines — count matches consolidated entries, not original ops
        List<LFPermissionOperation> consolidated = LakeFormationClient.consolidateOperations(ops);
        String logContent = sw.toString().trim();
        assertFalse(logContent.isEmpty(), "Dead-letter log should not be empty");
        String[] lines = logContent.split("\n");

        assertEquals(consolidated.size(), lines.length,
                "Dead-letter log should have one entry per consolidated operation");

        Set<String> loggedPolicyIds = new HashSet<>();
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            assertTrue(node.has("policyId"), "Entry must have policyId");
            loggedPolicyIds.add(node.get("policyId").asText());
            assertTrue(node.has("operation"), "Entry must have operation type");
            assertTrue(node.has("resource"), "Entry must have resource");
            assertTrue(node.has("principal"), "Entry must have principal");
            assertTrue(node.has("error"), "Entry must have error message");
        }

        for (String pid : policyIds) {
            assertTrue(loggedPolicyIds.contains(pid),
                    "Policy " + pid + " should appear in dead-letter log");
        }
    }

    // -----------------------------------------------------------------------
    // Providers
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<BatchScenario> batchWithFailingPolicy() {
        Arbitrary<List<String>> policyIdsArb = Arbitraries.integers().between(2, 4)
                .flatMap(count -> {
                    List<Arbitrary<String>> idArbs = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        idArbs.add(Arbitraries.just("policy-" + i));
                    }
                    return Combinators.combine(idArbs).as(ids -> ids);
                });

        return policyIdsArb.flatMap(policyIds -> {
            Arbitrary<List<List<LFPermissionOperation>>> opsPerPolicyArb =
                    Combinators.combine(
                            policyIds.stream()
                                    .map(pid -> opsForPolicy(pid).list().ofMinSize(1).ofMaxSize(3))
                                    .collect(Collectors.toList())
                    ).as(lists -> lists);

            return opsPerPolicyArb.flatMap(opsPerPolicy -> {
                return Arbitraries.integers().between(0, policyIds.size() - 1).map(failIdx -> {
                    String failingPolicyId = policyIds.get(failIdx);
                    List<LFPermissionOperation> allOps = new ArrayList<>();
                    for (List<LFPermissionOperation> policyOps : opsPerPolicy) {
                        allOps.addAll(policyOps);
                    }
                    return new BatchScenario(allOps, policyIds, failingPolicyId);
                });
            });
        });
    }

    @Provide
    Arbitrary<List<LFPermissionOperation>> failingOps() {
        return Arbitraries.integers().between(1, 3).flatMap(numPolicies -> {
            List<String> policyIds = new ArrayList<>();
            for (int i = 0; i < numPolicies; i++) {
                policyIds.add("dlpolicy-" + i);
            }
            List<Arbitrary<List<LFPermissionOperation>>> perPolicy = policyIds.stream()
                    .map(pid -> opsForPolicy(pid).list().ofMinSize(1).ofMaxSize(3))
                    .collect(Collectors.toList());
            return Combinators.combine(perPolicy).as(lists -> {
                List<LFPermissionOperation> all = new ArrayList<>();
                for (List<LFPermissionOperation> l : lists) {
                    all.addAll(l);
                }
                return all;
            });
        });
    }

    private Arbitrary<LFPermissionOperation> opsForPolicy(String policyId) {
        Arbitrary<OperationType> opTypeArb = Arbitraries.of(OperationType.GRANT, OperationType.REVOKE);
        Arbitrary<String> tableArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
                .map(s -> "table_" + s.toLowerCase());
        Arbitrary<LFPermission> permArb = Arbitraries.of(LFPermission.values());

        return Combinators.combine(opTypeArb, tableArb, permArb.set().ofMinSize(1).ofMaxSize(3))
                .as((opType, table, perms) -> {
                    LFResource resource = new LFResource("catalog1", "testdb", table, null, null);
                    return new LFPermissionOperation(opType, policyId,
                            "arn:aws:iam::123456789012:role/TestRole", resource,
                            EnumSet.copyOf(perms), false);
                });
    }

    // -----------------------------------------------------------------------
    // Helper classes
    // -----------------------------------------------------------------------

    static class BatchScenario {
        final List<LFPermissionOperation> allOps;
        final List<LFPermissionOperation> grantOps;
        final List<LFPermissionOperation> revokeOps;
        final List<String> policyIds;
        final String failingPolicyId;

        BatchScenario(List<LFPermissionOperation> allOps, List<String> policyIds,
                      String failingPolicyId) {
            this.allOps = allOps;
            this.policyIds = policyIds;
            this.failingPolicyId = failingPolicyId;
            this.grantOps = allOps.stream()
                    .filter(op -> op.getOperationType() == OperationType.GRANT)
                    .collect(Collectors.toList());
            this.revokeOps = allOps.stream()
                    .filter(op -> op.getOperationType() == OperationType.REVOKE)
                    .collect(Collectors.toList());
        }

        @Override
        public String toString() {
            return "BatchScenario{policies=" + policyIds.size() +
                    ", totalOps=" + allOps.size() +
                    ", grants=" + grantOps.size() +
                    ", revokes=" + revokeOps.size() +
                    ", failingPolicy=" + failingPolicyId + "}";
        }
    }
}
