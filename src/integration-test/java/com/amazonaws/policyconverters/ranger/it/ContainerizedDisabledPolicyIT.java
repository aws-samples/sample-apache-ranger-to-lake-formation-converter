package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Containerized integration test verifying that disabled policies produce no GRANT
 * operations, and that disabling a previously active policy produces REVOKE operations
 * for all permissions that were previously granted.
 *
 * Validates: Requirements 14.1, 14.2, 14.3
 */
public class ContainerizedDisabledPolicyIT extends ContainerizedPipelineIT {

    @Test
    void testDisabledPolicyProducesNoGrant() throws Exception {
        // Create a disabled policy on test_db.disabled_t for analyst — should produce no GRANT
        String disabledPolicyJson = "{"
                + "\"name\":\"containerized-disabled-no-grant\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":false,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"disabled_t\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(disabledPolicyJson);

        // Create a canary enabled policy so we know the sync cycle ran
        String canaryPolicyJson = "{"
                + "\"name\":\"containerized-disabled-canary\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"canary_t\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(canaryPolicyJson);

        // Wait for the canary GRANT to confirm the sync cycle completed
        List<DryRunOutput> outputs = waitForDryRunOutput();
        assertFalse(outputs.isEmpty(), "Expected dry-run output from canary policy");

        List<LFPermissionOperation> canaryGrants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "canary_t".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertFalse(canaryGrants.isEmpty(),
                "Expected canary GRANT for canary_t to confirm sync ran");

        // Assert no GRANT for the disabled policy's resource
        List<LFPermissionOperation> disabledGrants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "disabled_t".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertTrue(disabledGrants.isEmpty(),
                "Expected no GRANT for disabled policy's resource disabled_t, but found " + disabledGrants.size());
    }

    @Test
    void testDisableActivePolicyProducesRevoke() throws Exception {
        // Step 1: Create enabled policy on test_db.toggle_t for analyst
        String policyJson = "{"
                + "\"name\":\"containerized-disable-active\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"toggle_t\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        int policyId = createAndTrackPolicy(policyJson);

        // Step 2: Wait for initial GRANT
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        List<DryRunOutput> initialOutputs = waitForDryRunOutput();
        assertFalse(initialOutputs.isEmpty(), "Expected initial dry-run output");

        List<LFPermissionOperation> initialGrants = initialOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .filter(op -> op.getResource() != null
                        && "toggle_t".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertFalse(initialGrants.isEmpty(),
                "Expected initial GRANT for analyst on toggle_t");

        // Step 3: Clear outputs and update policy to disabled
        clearDryRunOutputs();

        String disabledJson = "{"
                + "\"id\":" + policyId + ","
                + "\"name\":\"containerized-disable-active\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":false,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"toggle_t\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        updatePolicy(policyId, disabledJson);

        // Step 4: Wait for REVOKE
        List<DryRunOutput> outputs = waitForDryRunOutput();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after disabling policy");

        List<LFPermissionOperation> revokes = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        assertFalse(revokes.isEmpty(),
                "Expected REVOKE for analyst after disabling active policy");
    }
}
