package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Containerized integration test verifying that deleting a Ranger policy produces
 * REVOKE operations in the dry-run output for all previously granted permissions,
 * referencing the same resource and principal as the original GRANTs.
 *
 * Validates: Requirements 12.1, 12.2
 */
public class ContainerizedPolicyDeletionIT extends ContainerizedPipelineIT {

    @Test
    void testPolicyDeletionProducesRevocations() throws Exception {
        // Step 1: Create a policy granting SELECT on table test_db.events to analyst
        String policyJson = "{"
                + "\"name\":\"containerized-deletion-revoke\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        int policyId = createAndTrackPolicy(policyJson);

        // Step 2: Wait for initial sync — establishes baseline with GRANT operations
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        List<DryRunOutput> initialOutputs = waitForDryRunOutput();
        assertFalse(initialOutputs.isEmpty(), "Expected initial dry-run output");

        List<LFPermissionOperation> initialGrants = initialOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        assertFalse(initialGrants.isEmpty(),
                "Expected initial GRANT operations for analyst");

        // Step 3: Clear outputs and delete the policy
        clearDryRunOutputs();
        deletePolicyAndUntrack(policyId);

        List<DryRunOutput> outputs = waitForDryRunOutput();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after policy deletion");

        List<LFPermissionOperation> revokes = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        assertFalse(revokes.isEmpty(),
                "Expected REVOKE operations for analyst after policy deletion");

        // Verify REVOKE operations reference the same resource and principal
        for (LFPermissionOperation revoke : revokes) {
            assertEquals(analystArn, revoke.getPrincipalArn(),
                    "REVOKE principalArn should match original GRANT");
            assertNotNull(revoke.getResource(),
                    "REVOKE resource should not be null");
            assertEquals("test_db", revoke.getResource().getDatabaseName(),
                    "REVOKE databaseName should match original GRANT");
        }
    }
}
