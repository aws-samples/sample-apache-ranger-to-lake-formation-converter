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
 * Integration test verifying behavior with wildcard resource patterns.
 *
 * <p>The passthrough CatalogResolver in DryRunPipelineIT returns wildcard patterns
 * as-is (e.g., "*" is treated as a literal resource name). This tests that the
 * pipeline handles wildcard patterns without crashing and produces operations
 * with the wildcard value passed through.</p>
 */
public class WildcardPolicyIT extends DryRunPipelineIT {

    @Test
    void testWildcardTableGrant() throws Exception {
        // Wildcard table "*" — the passthrough resolver treats it as a literal table name
        String policyJson = "{"
                + "\"name\":\"test-wildcard-table\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"analytics\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"*\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output for wildcard policy");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getResource() != null
                        && "analytics".equals(op.getResource().getDatabaseName()))
                .collect(Collectors.toList());

        // The passthrough resolver returns "*" as a literal table name,
        // so we should get a grant with tableName="*"
        assertFalse(grants.isEmpty(),
                "Expected GRANT operations for wildcard table policy");

        LFPermissionOperation grant = grants.get(0);
        assertTrue(grant.getPermissions().contains(LFPermission.SELECT));
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst",
                grant.getPrincipalArn());
        // The table name should be "*" since the passthrough resolver doesn't expand
        assertEquals("*", grant.getResource().getTableName(),
                "Passthrough resolver should preserve wildcard as literal table name");
    }

    @Test
    void testWildcardDatabaseGrant() throws Exception {
        // Wildcard database "*" with ALTER
        String policyJson = "{"
                + "\"name\":\"test-wildcard-db\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"*\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"data_admin\"],"
                + "  \"accesses\":[{\"type\":\"alter\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        triggerSync();

        List<DryRunOutput> outputs = readDryRunOutputs();
        assertFalse(outputs.isEmpty(), "Expected dry-run output for wildcard database policy");

        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> op.getPermissions().contains(LFPermission.ALTER))
                .collect(Collectors.toList());

        assertFalse(grants.isEmpty(),
                "Expected GRANT for ALTER on wildcard database");
        assertEquals("arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin",
                grants.get(0).getPrincipalArn());
        // Database name should be "*" since passthrough resolver doesn't expand
        assertEquals("*", grants.get(0).getResource().getDatabaseName(),
                "Passthrough resolver should preserve wildcard as literal database name");
    }
}
