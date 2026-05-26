package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a Ranger policy with delegateAdmin=true produces an LF permission
 * operation with isGrantable=true (permissionsWithGrantOption in the LF API call),
 * and that delegateAdmin=false does NOT produce a grantable operation.
 */
public class GrantablePermissionPropagationIT extends DryRunPipelineIT {

    private static final String DATA_ADMIN_ARN =
            "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin";
    private static final String ANALYST_ARN =
            "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

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
                .filter(op -> DATA_ADMIN_ARN.equals(op.getPrincipalArn()))
                .filter(op -> "grantable_db".equals(op.getResource().getDatabaseName()))
                .filter(LFPermissionOperation::isGrantable)
                .toList();

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

        // Precondition: the pipeline must have produced at least one GRANT for analyst —
        // otherwise the negative assertion below passes vacuously on a silent pipeline bug.
        List<LFPermissionOperation> analystGrants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> ANALYST_ARN.equals(op.getPrincipalArn()))
                .filter(op -> "nongrantable_db".equals(op.getResource().getDatabaseName()))
                .toList();
        assertFalse(analystGrants.isEmpty(),
                "Precondition: analyst must have at least one GRANT on nongrantable_db. " +
                "If zero grants exist, the privilege-escalation guard below is vacuously true.");

        List<LFPermissionOperation> wronglyGrantableOps = analystGrants.stream()
                .filter(LFPermissionOperation::isGrantable)
                .toList();

        assertTrue(wronglyGrantableOps.isEmpty(),
                "A policy without delegateAdmin must NOT produce any grantable operation. " +
                "analyst must not receive GRANT OPTION. Privilege escalation: " + wronglyGrantableOps);
    }
}
