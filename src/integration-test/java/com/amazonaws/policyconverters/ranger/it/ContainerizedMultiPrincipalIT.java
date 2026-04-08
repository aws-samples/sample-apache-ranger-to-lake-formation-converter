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
 * Containerized integration test verifying that policies with different principal types
 * (users, groups, roles) are correctly mapped to IAM ARNs, and that unmapped principals
 * produce no GRANT operations.
 *
 * Validates: Requirements 13.1, 13.2, 13.3, 13.4
 */
public class ContainerizedMultiPrincipalIT extends ContainerizedPipelineIT {

    @Test
    void testUserMapping() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-user-mapping\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"user_map_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"user_map_tbl\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        List<DryRunOutput> outputs = waitForDryRunOutput();

        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "user_map_db".equals(op.getResource().getDatabaseName())
                        && "user_map_tbl".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertEquals(1, grants.size(),
                "Expected exactly one GRANT for user_map_db.user_map_tbl");

        LFPermissionOperation grant = grants.get(0);
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst",
                grant.getPrincipalArn(),
                "Expected principalArn to match user mapping for analyst");
        assertTrue(grant.getPermissions().contains(LFPermission.SELECT),
                "Expected permissions to contain SELECT");
    }

    @Test
    void testGroupMapping() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-group-mapping\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"group_map_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"group_map_tbl\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"groups\":[\"data_engineers\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        List<DryRunOutput> outputs = waitForDryRunOutput();

        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "group_map_db".equals(op.getResource().getDatabaseName())
                        && "group_map_tbl".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertEquals(1, grants.size(),
                "Expected exactly one GRANT for group_map_db.group_map_tbl");

        LFPermissionOperation grant = grants.get(0);
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/DataEngineersRole",
                grant.getPrincipalArn(),
                "Expected principalArn to match group mapping for data_engineers");
        assertTrue(grant.getPermissions().contains(LFPermission.SELECT),
                "Expected permissions to contain SELECT");
    }

    @Test
    void testRoleMapping() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-role-mapping\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"role_map_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"role_map_tbl\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"roles\":[\"admin_role\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        List<DryRunOutput> outputs = waitForDryRunOutput();

        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "role_map_db".equals(op.getResource().getDatabaseName())
                        && "role_map_tbl".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertEquals(1, grants.size(),
                "Expected exactly one GRANT for role_map_db.role_map_tbl");

        LFPermissionOperation grant = grants.get(0);
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/LFAdminRole",
                grant.getPrincipalArn(),
                "Expected principalArn to match role mapping for admin_role");
        assertTrue(grant.getPermissions().contains(LFPermission.SELECT),
                "Expected permissions to contain SELECT");
    }

    @Test
    void testUnmappedPrincipalProducesNoGrant() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-unmapped-principal\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"unmapped_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"unmapped_tbl\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"unknown_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);

        // Also create a "canary" policy with a mapped user so we know the server has processed
        // this sync cycle — if the canary GRANT appears, the unmapped policy was also processed.
        String canaryJson = "{"
                + "\"name\":\"containerized-unmapped-canary\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"unmapped_canary_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"unmapped_canary_tbl\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(canaryJson);
        List<DryRunOutput> outputs = waitForDryRunOutput();

        // Verify canary was processed
        List<LFPermissionOperation> canaryGrants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "unmapped_canary_db".equals(op.getResource().getDatabaseName()))
                .collect(Collectors.toList());

        assertFalse(canaryGrants.isEmpty(),
                "Canary policy should produce a GRANT, confirming sync cycle ran");

        // Verify unmapped principal produced no GRANT
        List<LFPermissionOperation> unmappedGrants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "unmapped_db".equals(op.getResource().getDatabaseName())
                        && "unmapped_tbl".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertTrue(unmappedGrants.isEmpty(),
                "Unmapped principal 'unknown_user' should produce no GRANT operations, found " + unmappedGrants.size());
    }
}
