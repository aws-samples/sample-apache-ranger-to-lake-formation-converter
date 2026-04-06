package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying correct behavior when multiple independent policies
 * exist simultaneously — creating, syncing, and selectively deleting policies
 * to verify that only the affected operations appear in the diff.
 */
public class MultiPolicyInteractionIT extends DryRunPipelineIT {

    @Test
    void testTwoPoliciesIndependentGrants() throws Exception {
        // Create two independent policies on different tables
        String policy1 = "{"
                + "\"name\":\"test-multi-policy-1\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"db_alpha\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"orders\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        String policy2 = "{"
                + "\"name\":\"test-multi-policy-2\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"db_beta\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"inventory\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policy1);
        createAndTrackPolicy(policy2);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty());

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());

        // Should have grants for both tables
        boolean hasOrders = grants.stream().anyMatch(op ->
                op.getResource() != null && "orders".equals(op.getResource().getTableName()));
        boolean hasInventory = grants.stream().anyMatch(op ->
                op.getResource() != null && "inventory".equals(op.getResource().getTableName()));

        assertTrue(hasOrders, "Expected GRANT for db_alpha.orders");
        assertTrue(hasInventory, "Expected GRANT for db_beta.inventory");
    }

    @Test
    void testDeleteOnePolicyKeepOther() throws Exception {
        // Create two policies, sync, delete one, sync again
        String policy1 = "{"
                + "\"name\":\"test-keep-policy\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"db_keep\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"retained\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        String policy2 = "{"
                + "\"name\":\"test-delete-policy\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"db_delete\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"removed\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policy1);
        int deleteId = createAndTrackPolicy(policy2);
        triggerSync();
        clearDryRunOutputs();

        // Delete only policy2
        deletePolicyAndUntrack(deleteId);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty());

        List<LFPermissionOperation> allOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .collect(Collectors.toList());

        // Should have REVOKE for db_delete.removed
        boolean hasRevoke = allOps.stream().anyMatch(op ->
                op.getOperationType() == OperationType.REVOKE
                        && op.getResource() != null
                        && "removed".equals(op.getResource().getTableName()));
        assertTrue(hasRevoke, "Expected REVOKE for deleted policy's table");

        // Should NOT have REVOKE for db_keep.retained
        boolean hasRetainedRevoke = allOps.stream().anyMatch(op ->
                op.getOperationType() == OperationType.REVOKE
                        && op.getResource() != null
                        && "retained".equals(op.getResource().getTableName()));
        assertFalse(hasRetainedRevoke,
                "Should NOT have REVOKE for the kept policy's table");

        // Should NOT have new GRANT for db_keep.retained (it's unchanged)
        boolean hasRetainedGrant = allOps.stream().anyMatch(op ->
                op.getOperationType() == OperationType.GRANT
                        && op.getResource() != null
                        && "retained".equals(op.getResource().getTableName()));
        assertFalse(hasRetainedGrant,
                "Should NOT have new GRANT for unchanged policy");
    }
}
