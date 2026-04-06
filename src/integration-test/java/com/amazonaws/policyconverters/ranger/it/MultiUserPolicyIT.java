package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that a policy granting access to multiple users
 * produces separate GRANT operations per principal in the dry-run output.
 */
public class MultiUserPolicyIT extends DryRunPipelineIT {

    @Test
    void testMultiUserGrant() throws Exception {
        String policyJson = "{"
                + "\"name\":\"test-multi-user\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"shared_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"metrics\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\",\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "shared_db".equals(op.getResource().getDatabaseName())
                        && "metrics".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        // Should have one GRANT per user
        Set<String> grantedPrincipals = grants.stream()
                .map(LFPermissionOperation::getPrincipalArn)
                .collect(Collectors.toSet());

        assertTrue(grantedPrincipals.contains("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst"),
                "Expected GRANT for analyst");
        assertTrue(grantedPrincipals.contains("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user"),
                "Expected GRANT for etl_user");

        // All grants should have SELECT
        for (LFPermissionOperation grant : grants) {
            assertTrue(grant.getPermissions().contains(LFPermission.SELECT),
                    "Expected SELECT permission for " + grant.getPrincipalArn());
        }
    }

    @Test
    void testMultiUserPartialRemoval() throws Exception {
        // Create policy for two users, sync, then remove one user
        String policyJson = "{"
                + "\"name\":\"test-multi-user-removal\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"shared_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"logs\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\",\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        int policyId = createAndTrackPolicy(policyJson);
        triggerSync();
        clearDryRunOutputs();

        // Remove etl_user, keep analyst
        String updatedJson = "{"
                + "\"id\":" + policyId + ","
                + "\"name\":\"test-multi-user-removal\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"shared_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"logs\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        updatePolicy(policyId, updatedJson);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after user removal");

        List<LFPermissionOperation> allOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .collect(Collectors.toList());

        // Should have REVOKE for etl_user but NOT for analyst
        String etlArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user";
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        List<LFPermissionOperation> etlRevokes = allOps.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> etlArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        List<LFPermissionOperation> analystRevokes = allOps.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        assertFalse(etlRevokes.isEmpty(),
                "Expected REVOKE for etl_user after removal");
        assertTrue(analystRevokes.isEmpty(),
                "Should NOT have REVOKE for analyst who was kept");
    }
}
