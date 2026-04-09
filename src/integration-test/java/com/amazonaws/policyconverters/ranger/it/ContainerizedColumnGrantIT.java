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
 * Containerized integration test verifying that a column-level SELECT grant policy in Ranger
 * produces the correct dry-run LF GRANT operation targeting a specific column through the
 * containerized conversion server.
 *
 * Validates: Requirements 9.1, 9.2, 9.3
 */
public class ContainerizedColumnGrantIT extends ContainerizedPipelineIT {

    @Test
    void testColumnSelectGrant() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-column-select\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false},"
                + "  \"column\":{\"values\":[\"user_id\"],\"isRecursive\":false}"
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
                .collect(Collectors.toList());

        List<LFPermissionOperation> colGrants = grants.stream()
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName())
                        && "events".equals(op.getResource().getTableName())
                        && op.getResource().getColumnNames() != null
                        && op.getResource().getColumnNames().contains("user_id"))
                .collect(Collectors.toList());

        assertEquals(1, colGrants.size(),
                "Expected exactly one GRANT for column user_id, found " + colGrants.size());

        LFPermissionOperation grant = colGrants.get(0);
        assertTrue(grant.getPermissions().contains(LFPermission.SELECT),
                "Expected permissions to contain SELECT");
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst",
                grant.getPrincipalArn(),
                "Expected principalArn for analyst");
    }
}
