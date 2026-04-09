package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying that policies referencing users without a principal
 * mapping are silently skipped — no GRANT operations are produced for unmapped users.
 */
public class UnmappedPrincipalPolicyIT extends DryRunPipelineIT {

    @Test
    void testUnmappedUserProducesNoGrant() throws Exception {
        // "unknown_user" has no entry in the PrincipalMapper
        String policyJson = "{"
                + "\"name\":\"test-unmapped-user\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"unknown_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();

        // Should have no GRANT operations for the unmapped user
        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "events".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        assertTrue(grants.isEmpty(),
                "Unmapped user should produce no GRANT operations, found " + grants.size());
    }

    @Test
    void testMixedMappedAndUnmappedUsers() throws Exception {
        // Policy with one mapped user (analyst) and one unmapped (unknown_user)
        String policyJson = "{"
                + "\"name\":\"test-mixed-mapping\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"mixed_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\",\"unknown_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "mixed_table".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        // Should have exactly one GRANT — for analyst only
        assertEquals(1, grants.size(),
                "Expected exactly one GRANT (for mapped user only), found " + grants.size());
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst",
                grants.get(0).getPrincipalArn(),
                "GRANT should be for the mapped user (analyst)");
    }
}
