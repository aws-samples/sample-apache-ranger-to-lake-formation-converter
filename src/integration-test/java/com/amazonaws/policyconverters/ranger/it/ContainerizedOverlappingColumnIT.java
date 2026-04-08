package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Containerized integration test verifying that when two overlapping column-level policies
 * exist and one is disabled, only the columns unique to the disabled policy are revoked.
 * Columns still covered by the remaining active policy must not be revoked.
 *
 * Validates: Requirements 15.1, 15.2, 15.3, 15.4
 */
public class ContainerizedOverlappingColumnIT extends ContainerizedPipelineIT {

    @Test
    void testOverlappingColumnPoliciesPartialRevoke() throws Exception {
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        // Step 1: Create Policy A — SELECT on columns d, e, f
        String policyAJson = "{"
                + "\"name\":\"containerized-overlap-col-a\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false},"
                + "  \"column\":{\"values\":[\"d\",\"e\",\"f\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        // Step 2: Create Policy B — SELECT on columns e, f, g
        String policyBJson = "{"
                + "\"name\":\"containerized-overlap-col-b\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false},"
                + "  \"column\":{\"values\":[\"e\",\"f\",\"g\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyAJson);
        int policyBId = createAndTrackPolicy(policyBJson);

        // Step 3: Wait for initial GRANTs
        List<DryRunOutput> initialOutputs = waitForDryRunOutput();
        assertFalse(initialOutputs.isEmpty(), "Expected initial dry-run output");

        List<LFPermissionOperation> grants = initialOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName())
                        && "events".equals(op.getResource().getTableName())
                        && op.getResource().getColumnNames() != null)
                .collect(Collectors.toList());

        // Step 4: Assert GRANTs cover columns d, e, f, g
        Set<String> grantedColumns = new HashSet<>();
        for (LFPermissionOperation grant : grants) {
            assertTrue(grant.getPermissions().contains(LFPermission.SELECT),
                    "Expected SELECT permission in GRANT");
            grantedColumns.addAll(grant.getResource().getColumnNames());
        }

        assertTrue(grantedColumns.contains("d"), "Expected GRANT covering column d");
        assertTrue(grantedColumns.contains("e"), "Expected GRANT covering column e");
        assertTrue(grantedColumns.contains("f"), "Expected GRANT covering column f");
        assertTrue(grantedColumns.contains("g"), "Expected GRANT covering column g");

        // Step 5: Clear outputs before disabling Policy B
        clearDryRunOutputs();

        // Step 6: Update Policy B to isEnabled=false
        String disabledPolicyBJson = "{"
                + "\"id\":" + policyBId + ","
                + "\"name\":\"containerized-overlap-col-b\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":false,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"events\"],\"isRecursive\":false},"
                + "  \"column\":{\"values\":[\"e\",\"f\",\"g\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        updatePolicy(policyBId, disabledPolicyBJson);

        // Step 7: Wait for the diff output
        List<DryRunOutput> diffOutputs = waitForDryRunOutput();
        assertFalse(diffOutputs.isEmpty(), "Expected dry-run output after disabling Policy B");

        List<LFPermissionOperation> allOps = diffOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> analystArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());

        List<LFPermissionOperation> revokes = allOps.stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> op.getResource() != null
                        && "test_db".equals(op.getResource().getDatabaseName())
                        && "events".equals(op.getResource().getTableName()))
                .collect(Collectors.toList());

        // Step 8: Assert REVOKE only for column g (unique to disabled Policy B)
        Set<String> revokedColumns = new HashSet<>();
        for (LFPermissionOperation revoke : revokes) {
            if (revoke.getResource().getColumnNames() != null) {
                revokedColumns.addAll(revoke.getResource().getColumnNames());
            }
        }

        assertTrue(revokedColumns.contains("g"),
                "Expected REVOKE for column g (unique to disabled Policy B)");

        // Step 9: Assert NO REVOKE for columns d, e, f (still covered by active Policy A)
        assertFalse(revokedColumns.contains("d"),
                "Column d should NOT be revoked — it was never part of Policy B");
        assertFalse(revokedColumns.contains("e"),
                "Column e should NOT be revoked — still covered by active Policy A");
        assertFalse(revokedColumns.contains("f"),
                "Column f should NOT be revoked — still covered by active Policy A");
    }
}
