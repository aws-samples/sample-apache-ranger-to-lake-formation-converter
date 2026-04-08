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
 * Containerized integration test verifying that a database-level grant policy in Ranger
 * produces the correct dry-run LF GRANT operation through the containerized conversion server.
 *
 * Validates: Requirements 7.1, 7.2, 7.3, 7.4
 */
public class ContainerizedDatabaseGrantIT extends ContainerizedPipelineIT {

    @Test
    void testDatabaseAlterGrant() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-db-alter-analyst\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"alter\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        List<DryRunOutput> outputs = waitForDryRunOutput();

        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());

        List<LFPermissionOperation> dbGrants = grants.stream()
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName()))
                .collect(Collectors.toList());

        assertEquals(1, dbGrants.size(),
                "Expected exactly one GRANT for database test_db, found " + dbGrants.size());

        LFPermissionOperation grant = dbGrants.get(0);
        assertTrue(grant.getPermissions().contains(LFPermission.ALTER),
                "Expected permissions to contain ALTER");
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst",
                grant.getPrincipalArn(),
                "Expected principalArn for analyst");
    }
}
