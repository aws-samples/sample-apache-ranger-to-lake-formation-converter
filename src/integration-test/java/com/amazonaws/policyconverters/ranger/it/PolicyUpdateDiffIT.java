package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that policy updates produce the correct incremental
 * diff in dry-run output — new GRANTs when permissions are added, and REVOKEs
 * when users are removed.
 *
 * Validates: Requirements 8.1, 8.2, 8.3
 */
public class PolicyUpdateDiffIT extends DryRunPipelineIT {

    @Test
    void testPolicyUpdateAddPermission() throws Exception {
        // Step 1: Create SELECT-only policy for analyst on test_db.events (table level)
        String policyJson = "{"
                + "\"name\":\"test-update-diff\","
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

        // Step 2: Initial sync establishes baseline
        triggerSync();
        clearDryRunOutputs();

        // Step 3: Update policy to add DROP permission
        String updatedJson = "{"
                + "\"id\":" + policyId + ","
                + "\"name\":\"test-update-diff\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":["
                + "    {\"type\":\"select\",\"isAllowed\":true},"
                + "    {\"type\":\"drop\",\"isAllowed\":true}"
                + "  ]"
                + "}]"
                + "}";

        updatePolicy(policyId, updatedJson);

        // Step 4: Sync again — diff should show new GRANT for DROP
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after policy update");

        List<LFPermissionOperation> allOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .collect(Collectors.toList());

        List<LFPermissionOperation> insertGrants = allOps.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName())
                        && "events".equals(op.getResource().getTableName()))
                .filter(op -> op.getPermissions().contains(LFPermission.DROP))
                .collect(Collectors.toList());

        assertFalse(insertGrants.isEmpty(),
                "Expected a GRANT for DROP after adding drop permission");
    }

    @Test
    void testPolicyUpdateRemoveUser() throws Exception {
        // Step 1: Create policy with analyst user on table level
        String policyJson = "{"
                + "\"name\":\"test-update-remove-user\","
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

        // Step 2: Initial sync establishes baseline
        triggerSync();
        clearDryRunOutputs();

        // Step 3: Update policy to remove analyst, replace with etl_user
        String updatedJson = "{"
                + "\"id\":" + policyId + ","
                + "\"name\":\"test-update-remove-user\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        updatePolicy(policyId, updatedJson);

        // Step 4: Sync again — diff should show REVOKE for analyst
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after user removal");

        List<LFPermissionOperation> allOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .collect(Collectors.toList());

        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";
        List<LFPermissionOperation> analystRevokes = allOps.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        assertFalse(analystRevokes.isEmpty(),
                "Expected REVOKE operations for analyst after removing from policy");
    }
}
