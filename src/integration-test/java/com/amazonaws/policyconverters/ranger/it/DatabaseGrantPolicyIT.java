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
 * Integration test verifying that a database-level grant policy in Ranger
 * produces the correct dry-run LF GRANT operation.
 *
 * Validates: Requirements 6.1, 6.2, 6.3, 6.4
 */
public class DatabaseGrantPolicyIT extends DryRunPipelineIT {

    @Test
    void testDatabaseSelectGrant() throws Exception {
        // Use ALTER on database — supported by both Ranger service def and the adapter
        String policyJson = "{"
                + "\"name\":\"test-db-alter-analyst\","
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
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
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
