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
 * Integration test verifying that a data-location grant policy in Ranger
 * produces the correct dry-run LF GRANT operation with DATA_LOCATION_ACCESS.
 */
public class DataLocationGrantPolicyIT extends DryRunPipelineIT {

    @Test
    void testDataLocationGrant() throws Exception {
        String policyJson = "{"
                + "\"name\":\"test-datalocation-grant\","
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
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected at least one dry-run output file");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());

        // Find the data location grant
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
                grant.getPrincipalArn());
    }

    @Test
    void testDataLocationDeletionRevoke() throws Exception {
        // Create, sync, delete, sync — verify REVOKE
        String policyJson = "{"
                + "\"name\":\"test-datalocation-revoke\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"datalocation\":{\"values\":[\"analytics-bucket/raw\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"data_admin\"],"
                + "  \"accesses\":[{\"type\":\"data_location_access\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        int policyId = createAndTrackPolicy(policyJson);
        triggerSync();

        // Verify initial GRANT exists
        List<DryRunOutput> initialOutputs = readDryRunOutputs();
        assertFalse(initialOutputs.isEmpty());
        long grantCount = initialOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPermissions().contains(LFPermission.DATA_LOCATION_ACCESS))
                .count();
        assertTrue(grantCount > 0, "Expected initial GRANT for data location");

        clearDryRunOutputs();

        // Delete and sync
        deletePolicyAndUntrack(policyId);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after deletion");

        List<LFPermissionOperation> revokes = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .filter(op -> op.getPermissions().contains(LFPermission.DATA_LOCATION_ACCESS))
                .collect(Collectors.toList());

        assertFalse(revokes.isEmpty(),
                "Expected REVOKE for DATA_LOCATION_ACCESS after policy deletion");
    }
}
