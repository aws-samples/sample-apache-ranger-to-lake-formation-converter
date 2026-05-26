package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a Ranger policy with delegateAdmin=true produces an LF permission
 * operation with isGrantable=true (permissionsWithGrantOption in the LF API call),
 * and that delegateAdmin=false does NOT produce a grantable operation.
 */
public class GrantablePermissionPropagationIT extends DryRunPipelineIT {

    @Test
    void delegateAdminPolicyProducesGrantableOperation() throws Exception {
        String grantablePolicy = """
                {
                  "service": "lakeformation",
                  "name": "grantable-test-policy",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["grantable_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["grantable_table"], "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["data_admin"],
                      "groups": [], "roles": [], "conditions": [],
                      "delegateAdmin": true
                    }
                  ]
                }
                """;

        createAndTrackPolicy(grantablePolicy);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        List<LFPermissionOperation> grantableOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().contains("data_admin"))
                .filter(op -> "grantable_db".equals(op.getResource().getDatabaseName()))
                .filter(LFPermissionOperation::isGrantable)
                .collect(Collectors.toList());

        assertFalse(grantableOps.isEmpty(),
                "A policy with delegateAdmin=true must produce at least one operation with " +
                "isGrantable=true (permissionsWithGrantOption). " +
                "data_admin must be able to delegate permissions on grantable_db.grantable_table.");
    }

    @Test
    void nonDelegateAdminPolicyProducesNonGrantableOperation() throws Exception {
        String nonGrantablePolicy = """
                {
                  "service": "lakeformation",
                  "name": "non-grantable-test-policy",
                  "isEnabled": true,
                  "resources": {
                    "database": { "values": ["nongrantable_db"], "isExcludes": false, "isRecursive": false },
                    "table":    { "values": ["nongrantable_table"], "isExcludes": false, "isRecursive": false }
                  },
                  "policyItems": [
                    {
                      "accesses": [{ "type": "select", "isAllowed": true }],
                      "users": ["analyst"],
                      "groups": [], "roles": [], "conditions": [],
                      "delegateAdmin": false
                    }
                  ]
                }
                """;

        createAndTrackPolicy(nonGrantablePolicy);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        List<LFPermissionOperation> wronglyGrantableOps = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn().contains("analyst"))
                .filter(op -> "nongrantable_db".equals(op.getResource().getDatabaseName()))
                .filter(LFPermissionOperation::isGrantable)
                .collect(Collectors.toList());

        assertTrue(wronglyGrantableOps.isEmpty(),
                "A policy without delegateAdmin must NOT produce any grantable operation. " +
                "analyst must not receive GRANT OPTION. Privilege escalation: " + wronglyGrantableOps);
    }
}
