package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Containerized integration test verifying that policy updates produce the correct
 * incremental diff in dry-run output — new GRANTs when permissions are added, and
 * REVOKEs when users are removed.
 *
 * Validates: Requirements 11.1, 11.2, 11.3
 */
public class ContainerizedPolicyUpdateDiffIT extends ContainerizedPipelineIT {

    @Test
    void testPolicyUpdateAddPermission() throws Exception {
        // Step 1: Create SELECT-only policy for analyst on test_db.events
        String policyJson = "{"
                + "\"name\":\"containerized-update-diff\","
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

        // Step 2: Wait for initial sync to establish baseline
        waitForDryRunOutput();
        clearDryRunOutputs();

        // Step 3: Update policy to add DROP permission
        String updatedJson = "{"
                + "\"id\":" + policyId + ","
                + "\"name\":\"containerized-update-diff\","
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

        // Step 4: Wait for diff output — should show new GRANT for DROP
        List<DryRunOutput> outputs = waitForDryRunOutput();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after policy update");

        List<LFPermissionOperation> allOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .collect(Collectors.toList());

        List<LFPermissionOperation> dropGrants = allOps.stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName())
                        && "events".equals(op.getResource().getTableName()))
                .filter(op -> op.getPermissions().contains(LFPermission.DROP))
                .collect(Collectors.toList());

        assertFalse(dropGrants.isEmpty(),
                "Expected a GRANT for DROP after adding drop permission");
    }

    @Test
    void testPolicyUpdateRemoveUser() throws Exception {
        // Step 1: Create policy with analyst on test_db.events
        String policyJson = "{"
                + "\"name\":\"containerized-update-remove-user\","
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

        // Step 2: Wait for initial sync to establish baseline
        waitForDryRunOutput();
        clearDryRunOutputs();

        // Step 3: Update policy to replace analyst with etl_user
        String updatedJson = "{"
                + "\"id\":" + policyId + ","
                + "\"name\":\"containerized-update-remove-user\","
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

        // Step 4: Wait for diff output — should show REVOKE for analyst
        List<DryRunOutput> outputs = waitForDryRunOutput();
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
