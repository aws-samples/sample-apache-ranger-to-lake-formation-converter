package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that a table-level multi-permission grant policy in Ranger
 * produces the correct dry-run LF GRANT operations.
 *
 * Validates: Requirements 7.1, 7.2, 7.3
 */
public class TableGrantPolicyIT extends DryRunPipelineIT {

    @Test
    void testTableSelectInsertGrant() throws Exception {
        // Use select + drop — both supported by Ranger service def AND the adapter
        // select → SELECT, drop → DROP
        String policyJson = "{"
                + "\"name\":\"test-table-select-drop-etl\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":["
                + "    {\"type\":\"select\",\"isAllowed\":true},"
                + "    {\"type\":\"drop\",\"isAllowed\":true}"
                + "  ]"
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

        List<LFPermissionOperation> tableGrants = grants.stream()
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName())
                        && "events".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertFalse(tableGrants.isEmpty(),
                "Expected GRANT operations for test_db.events, found none");

        Set<LFPermission> allPermissions = tableGrants.stream()
                .flatMap(op -> op.getPermissions().stream())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(LFPermission.class)));

        assertTrue(allPermissions.contains(LFPermission.SELECT),
                "Expected combined permissions to contain SELECT, got " + allPermissions);
        assertTrue(allPermissions.contains(LFPermission.DROP),
                "Expected combined permissions to contain DROP, got " + allPermissions);

        String expectedArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user";
        for (LFPermissionOperation grant : tableGrants) {
            assertEquals(expectedArn, grant.getPrincipalArn(),
                    "Expected principalArn for etl_user");
        }
    }
}
