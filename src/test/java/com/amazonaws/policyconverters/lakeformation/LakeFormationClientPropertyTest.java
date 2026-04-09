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
import software.amazon.awssdk.services.lakeformation.model.GrantPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.LakeFormationException;
import software.amazon.awssdk.services.lakeformation.model.RevokePermissionsRequest;

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
    // Feature: ranger-lakeformation-sync, Property 18: Atomic per-policy application
    // **Validates: Requirements 8.3**
    // For any batch grouped by policy, if any operation for a policy fails,
    // all operations for that policy are rolled back while other policies are unaffected.
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void atomicPerPolicyApplication(
            @ForAll("batchWithFailingPolicy") BatchScenario scenario
    ) {
        // Set up mock AWS client that fails on the designated policy's designated operation index
        software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient =
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class);

        // Track which operations are actually applied (grant/revoke calls that succeed)
        List<String> appliedGrants = Collections.synchronizedList(new ArrayList<>());
        List<String> appliedRevokes = Collections.synchronizedList(new ArrayList<>());

        // Count calls per policy to know when to fail
        Map<String, Integer> grantCallCountByPolicy = new HashMap<>();
        Map<String, Integer> revokeCallCountByPolicy = new HashMap<>();

        // We need to track which policy each call belongs to.
        // Since Mockito doesn't easily let us inspect the request's policy context,
        // we track by the order operations are submitted (they're grouped by policy).
        final int[] globalGrantIdx = {0};
        final int[] globalRevokeIdx = {0};

        // Pre-compute which global grant/revoke index should fail
        int failingGlobalGrantIdx = -1;
        int failingGlobalRevokeIdx = -1;
        int grantIdx = 0;
        int revokeIdx = 0;
        int opsBeforeFailingPolicy = 0;
        boolean foundFailingOp = false;

        for (LFPermissionOperation op : scenario.allOps) {
            if (!foundFailingOp && op.getSourcePolicyId().equals(scenario.failingPolicyId)) {
                int localIdx = 0;
                // Count how many ops of this policy we've seen so far
                for (int j = opsBeforeFailingPolicy; j < scenario.allOps.size(); j++) {
                    LFPermissionOperation check = scenario.allOps.get(j);
                    if (!check.getSourcePolicyId().equals(scenario.failingPolicyId)) break;
                    if (localIdx == scenario.failAtOpIndex) {
                        if (check.getOperationType() == OperationType.GRANT) {
                            failingGlobalGrantIdx = grantIdx;
                        } else {
                            failingGlobalRevokeIdx = revokeIdx;
                        }
                        foundFailingOp = true;
                        break;
                    }
                    if (check.getOperationType() == OperationType.GRANT) grantIdx++;
                    else revokeIdx++;
                    localIdx++;
                }
                break;
            }
            if (op.getOperationType() == OperationType.GRANT) grantIdx++;
            else revokeIdx++;
            opsBeforeFailingPolicy++;
        }

        final int failGrantIdx = failingGlobalGrantIdx;
        final int failRevokeIdx = failingGlobalRevokeIdx;

        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class))).thenAnswer(inv -> {
            int idx = globalGrantIdx[0]++;
            if (idx == failGrantIdx) {
                throw LakeFormationException.builder().message("Simulated failure").build();
            }
            return null;
        });

        when(awsClient.revokePermissions(any(RevokePermissionsRequest.class))).thenAnswer(inv -> {
            int idx = globalRevokeIdx[0]++;
            if (idx == failRevokeIdx) {
                throw LakeFormationException.builder().message("Simulated failure").build();
            }
            return null;
        });

        LakeFormationClient client = new LakeFormationClient(awsClient, RETRY_CONFIG, millis -> {});
        BatchResult result = client.applyBatch(scenario.allOps, null);

        // The failing policy should be in the failed list
        assertTrue(result.getFailedPolicyIds().contains(scenario.failingPolicyId),
                "Failing policy should be in failed list");

        // All non-failing policies should succeed
        for (String policyId : scenario.policyIds) {
            if (!policyId.equals(scenario.failingPolicyId)) {
                assertTrue(result.getSucceededPolicyIds().contains(policyId),
                        "Non-failing policy " + policyId + " should succeed");
            }
        }

        // The failing policy should NOT be in the succeeded list
        assertFalse(result.getSucceededPolicyIds().contains(scenario.failingPolicyId),
                "Failing policy should not be in succeeded list");

        // Applied operations count should equal total ops of succeeded policies only
        int expectedApplied = 0;
        for (LFPermissionOperation op : scenario.allOps) {
            if (!op.getSourcePolicyId().equals(scenario.failingPolicyId)) {
                expectedApplied++;
            }
        }
        assertEquals(expectedApplied, result.getAppliedOperations(),
                "Applied ops should equal total ops of succeeded policies");
    }

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 19: Dead-letter log completeness
    // **Validates: Requirements 8.4**
    // For any operation that fails after exhausting retries, the dead-letter log
    // contains an entry with policy ID, operation details, and error message.
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void deadLetterLogCompleteness(
            @ForAll("failingOps") List<LFPermissionOperation> ops
    ) throws Exception {
        // All operations will fail — mock AWS client to always throw
        software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient =
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class);

        when(awsClient.grantPermissions(any(GrantPermissionsRequest.class)))
                .thenThrow(LakeFormationException.builder().message("Permanent failure").build());
        when(awsClient.revokePermissions(any(RevokePermissionsRequest.class)))
                .thenThrow(LakeFormationException.builder().message("Permanent failure").build());

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

        // Parse dead-letter log lines
        String logContent = sw.toString().trim();
        assertFalse(logContent.isEmpty(), "Dead-letter log should not be empty");
        String[] lines = logContent.split("\n");

        // Every operation should have a dead-letter entry
        assertEquals(ops.size(), lines.length,
                "Dead-letter log should have one entry per operation");

        // Verify each line contains required fields
        Set<String> loggedPolicyIds = new HashSet<>();
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);

            // Must have policyId
            assertTrue(node.has("policyId"), "Entry must have policyId");
            loggedPolicyIds.add(node.get("policyId").asText());

            // Must have operation type
            assertTrue(node.has("operation"), "Entry must have operation type");
            String opType = node.get("operation").asText();
            assertTrue(opType.equals("GRANT") || opType.equals("REVOKE"),
                    "Operation must be GRANT or REVOKE");

            // Must have resource
            assertTrue(node.has("resource"), "Entry must have resource");

            // Must have principal
            assertTrue(node.has("principal"), "Entry must have principal");

            // Must have error message
            assertTrue(node.has("error"), "Entry must have error message");
            assertFalse(node.get("error").asText().isEmpty(), "Error message must not be empty");

            // Must have permissions
            assertTrue(node.has("permissions"), "Entry must have permissions");
        }

        // All policy IDs from the ops should appear in the dead-letter log
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
        // Generate 2-4 distinct policy IDs
        Arbitrary<List<String>> policyIdsArb = Arbitraries.integers().between(2, 4)
                .flatMap(count -> {
                    List<Arbitrary<String>> idArbs = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        final int idx = i;
                        idArbs.add(Arbitraries.just("policy-" + idx));
                    }
                    return Combinators.combine(idArbs).as(ids -> ids);
                });

        return policyIdsArb.flatMap(policyIds -> {
            // For each policy, generate 1-3 operations
            Arbitrary<List<List<LFPermissionOperation>>> opsPerPolicyArb =
                    Combinators.combine(
                            policyIds.stream()
                                    .map(pid -> opsForPolicy(pid).list().ofMinSize(1).ofMaxSize(3))
                                    .collect(Collectors.toList())
                    ).as(lists -> lists);

            return opsPerPolicyArb.flatMap(opsPerPolicy -> {
                // Pick which policy will fail
                return Arbitraries.integers().between(0, policyIds.size() - 1).flatMap(failIdx -> {
                    String failingPolicyId = policyIds.get(failIdx);
                    List<LFPermissionOperation> failingPolicyOps = opsPerPolicy.get(failIdx);
                    // Pick which operation index within that policy will fail
                    return Arbitraries.integers().between(0, failingPolicyOps.size() - 1).map(failAtOp -> {
                        List<LFPermissionOperation> allOps = new ArrayList<>();
                        for (List<LFPermissionOperation> policyOps : opsPerPolicy) {
                            allOps.addAll(policyOps);
                        }
                        return new BatchScenario(allOps, policyIds, failingPolicyId, failAtOp);
                    });
                });
            });
        });
    }

    @Provide
    Arbitrary<List<LFPermissionOperation>> failingOps() {
        // Generate 1-3 distinct policy IDs, each with 1-3 operations
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
        final List<String> policyIds;
        final String failingPolicyId;
        final int failAtOpIndex;

        BatchScenario(List<LFPermissionOperation> allOps, List<String> policyIds,
                      String failingPolicyId, int failAtOpIndex) {
            this.allOps = allOps;
            this.policyIds = policyIds;
            this.failingPolicyId = failingPolicyId;
            this.failAtOpIndex = failAtOpIndex;
        }

        @Override
        public String toString() {
            return "BatchScenario{policies=" + policyIds.size() +
                    ", totalOps=" + allOps.size() +
                    ", failingPolicy=" + failingPolicyId +
                    ", failAtOp=" + failAtOpIndex + "}";
        }
    }
}
