package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a Ranger deny item suppresses the corresponding allow item for the
 * same (user, resource) pair within a single policy — the user must receive ZERO LF grants.
 *
 * <p>Ranger enforces uniqueness per resource: only one policy may target a given resource
 * combination. Both policyItems (allow) and denyPolicyItems (deny) must therefore be
 * expressed in the same policy object. The pipeline's Cedar layer evaluates both and
 * the deny must win, producing zero grants for the affected principal.
 *
 * <p>Security context: Lake Formation has no native deny model. The denial must be
 * enforced by suppressing the Cedar permit during conversion, not by issuing an LF deny.
 */
public class CrossPolicyDenyInteractionIT extends DryRunPipelineIT {

    @Test
    void denyItemSuppressesAllowItemForSameUserAndResource() throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // Single policy with both policyItems (allow analyst SELECT) and
        // denyPolicyItems (deny analyst SELECT) on the same resource.
        // The deny must win: analyst receives ZERO LF grants.
        String combinedPolicy = "{"
                + "\"name\":\"cross-policy-allow-deny-" + runId + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"deny_test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"deny_test_table\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}],"
                + "\"denyPolicyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(combinedPolicy);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        List<LFPermissionOperation> grantsForAnalyst = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPrincipalArn() != null && op.getPrincipalArn().contains("analyst"))
                .filter(op -> op.getResource() != null
                        && "deny_test_db".equals(op.getResource().getDatabaseName()))
                .collect(Collectors.toList());

        assertEquals(0, grantsForAnalyst.size(),
                "A Ranger deny item must suppress the allow item for the same user and resource. "
                + "analyst must receive ZERO LF grants on deny_test_db.deny_test_table. "
                + "Actual grants: " + grantsForAnalyst);
    }
}
