package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.client.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class DisabledPolicyIT extends DryRunPipelineIT {

    @Test
    void testDisabledPolicyHandledWithoutError() throws Exception {
        String pj = "{\"name\":\"test-disabled-ok\",\"service\":\"lakeformation\","
            + "\"isEnabled\":false,\"policyType\":0,"
            + "\"resources\":{\"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
            + "\"table\":{\"values\":[\"secret\"],\"isRecursive\":false}},"
            + "\"policyItems\":[{\"users\":[\"analyst\"],"
            + "\"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]}]}";
        createAndTrackPolicy(pj);
        triggerSync();
        assertNotNull(readDryRunOutputs());
    }

    @Test
    void testDeletePreviouslyActivePolicyRevokes() throws Exception {
        String pj = "{\"name\":\"test-del-active\",\"service\":\"lakeformation\","
            + "\"isEnabled\":true,\"policyType\":0,"
            + "\"resources\":{\"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
            + "\"table\":{\"values\":[\"toggle_t\"],\"isRecursive\":false}},"
            + "\"policyItems\":[{\"users\":[\"analyst\"],"
            + "\"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]}]}";
        int pid = createAndTrackPolicy(pj);
        triggerSync();
        clearDryRunOutputs();
        policyClient.deletePolicy(pid);
        createdPolicyIds.remove(Integer.valueOf(pid));
        triggerSync();
        List<DryRunOutput> out = readDryRunOutputs();
        assertFalse(out.isEmpty());
        String arn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";
        long revCnt = out.stream().flatMap(o -> o.getOperations().stream())
            .filter(op -> op.getOperationType() == OperationType.REVOKE)
            .filter(op -> arn.equals(op.getPrincipalArn())).count();
        assertTrue(revCnt > 0, "Expected REVOKE after deleting active policy");
    }
}
