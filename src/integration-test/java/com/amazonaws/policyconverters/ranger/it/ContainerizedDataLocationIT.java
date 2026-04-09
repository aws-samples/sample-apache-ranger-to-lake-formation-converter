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
 * Containerized integration test verifying that data location grant and revoke policies
 * in Ranger produce the correct dry-run LF operations through the containerized conversion
 * server.
 *
 * Validates: Requirements 10.1, 10.2
 */
public class ContainerizedDataLocationIT extends ContainerizedPipelineIT {

    @Test
    void testDataLocationGrant() throws Exception {
        String policyJson = "{"
                + "\"name\":\"containerized-datalocation-grant\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"datalocation\":{\"values\":[\"my-bucket/data/warehouse\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"data_admin\"],"
                + "  \"accesses\":[{\"type\":\"data_location_access\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        List<DryRunOutput> outputs = waitForDryRunOutput();

        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());

        List<LFPermissionOperation> dlGrants = grants.stream()
                .filter(op -> op.getResource() != null
                        && op.getResource().getDataLocationPath() != null
                        && op.getResource().getDataLocationPath().contains("my-bucket"))
                .collect(Collectors.toList());

        assertEquals(1, dlGrants.size(),
                "Expected exactly one GRANT for data location, found " + dlGrants.size());

        LFPermissionOperation grant = dlGrants.get(0);
        assertTrue(grant.getPermissions().contains(LFPermission.DATA_LOCATION_ACCESS),
                "Expected permissions to contain DATA_LOCATION_ACCESS");
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin",
                grant.getPrincipalArn(),
                "Expected principalArn for data_admin");
    }

    @Test
    void testDataLocationDeletionRevoke() throws Exception {
        // Step 1: Create policy and confirm initial GRANT
        String policyJson = "{"
                + "\"name\":\"containerized-datalocation-revoke\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"datalocation\":{\"values\":[\"my-bucket/data/warehouse\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"data_admin\"],"
                + "  \"accesses\":[{\"type\":\"data_location_access\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        int policyId = createAndTrackPolicy(policyJson);
        List<DryRunOutput> initialOutputs = waitForDryRunOutput();

        assertFalse(initialOutputs.isEmpty(), "Expected initial dry-run output");
        long grantCount = initialOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPermissions().contains(LFPermission.DATA_LOCATION_ACCESS))
                .count();
        assertTrue(grantCount > 0, "Expected initial GRANT for data location");

        // Step 2: Clear outputs and delete the policy
        clearDryRunOutputs();
        deletePolicyAndUntrack(policyId);

        // Step 3: Wait for revoke output (use extended timeout — the Ranger SDK PolicyRefresher
        // may take longer to detect deletions due to internal backoff logic when policyDeltas=null)
        List<DryRunOutput> revokeOutputs = waitForDryRunOutput();

        assertFalse(revokeOutputs.isEmpty(), "Expected dry-run output after deletion");

        List<LFPermissionOperation> revokes = revokeOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> op.getPermissions().contains(LFPermission.DATA_LOCATION_ACCESS))
                .collect(Collectors.toList());

        assertFalse(revokes.isEmpty(),
                "Expected REVOKE for DATA_LOCATION_ACCESS after policy deletion");
    }
}
