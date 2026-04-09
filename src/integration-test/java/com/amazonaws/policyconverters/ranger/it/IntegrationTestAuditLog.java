package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes a human-readable JSON audit log for integration tests.
 * Each test method produces a sequence of entries showing the Ranger
 * input action and the resulting Lake Formation output operations.
 *
 * Output goes to {@code logs/it-audit-<TestClassName>.json}.
 */
public class IntegrationTestAuditLog {

    private final ObjectMapper mapper;
    private final ArrayNode testEntries;
    private final String testClassName;
    private ArrayNode currentTestSteps;
    private String currentTestMethod;

    public IntegrationTestAuditLog(String testClassName) {
        this.testClassName = testClassName;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.testEntries = mapper.createArrayNode();
    }

    /** Call at the start of each test method. */
    public void beginTest(String methodName) {
        this.currentTestMethod = methodName;
        this.currentTestSteps = mapper.createArrayNode();
    }

    /** Record a Ranger policy creation. */
    public void logRangerCreate(int policyId, String policyJson) {
        ObjectNode step = mapper.createObjectNode();
        step.put("timestamp", Instant.now().toString());
        ObjectNode input = mapper.createObjectNode();
        input.put("action", "ranger");
        input.put("operation", "CREATE_POLICY");
        input.put("policyId", policyId);
        try {
            input.set("policy", mapper.readTree(policyJson));
        } catch (IOException e) {
            input.put("policyRaw", policyJson);
        }
        step.set("input", input);
        currentTestSteps.add(step);
    }

    /** Record a Ranger policy update. */
    public void logRangerUpdate(int policyId, String policyJson) {
        ObjectNode step = mapper.createObjectNode();
        step.put("timestamp", Instant.now().toString());
        ObjectNode input = mapper.createObjectNode();
        input.put("action", "ranger");
        input.put("operation", "UPDATE_POLICY");
        input.put("policyId", policyId);
        try {
            input.set("policy", mapper.readTree(policyJson));
        } catch (IOException e) {
            input.put("policyRaw", policyJson);
        }
        step.set("input", input);
        currentTestSteps.add(step);
    }

    /** Record a Ranger policy deletion. */
    public void logRangerDelete(int policyId) {
        ObjectNode step = mapper.createObjectNode();
        step.put("timestamp", Instant.now().toString());
        ObjectNode input = mapper.createObjectNode();
        input.put("action", "ranger");
        input.put("operation", "DELETE_POLICY");
        input.put("policyId", policyId);
        step.set("input", input);
        currentTestSteps.add(step);
    }

    /** Record the LF operations produced by a sync cycle. */
    public void logSyncResult(List<DryRunOutput> outputs) {
        ObjectNode step = mapper.createObjectNode();
        step.put("timestamp", Instant.now().toString());
        ArrayNode outputOps = mapper.createArrayNode();

        for (DryRunOutput dryOut : outputs) {
            for (LFPermissionOperation op : dryOut.getOperations()) {
                ObjectNode entry = mapper.createObjectNode();
                entry.put("action", "lf");
                entry.put("operation", op.getOperationType().getValue());
                entry.put("principal", op.getPrincipalArn());
                entry.put("sourcePolicyId", op.getSourcePolicyId());

                String perms = op.getPermissions().stream()
                        .map(LFPermission::getValue)
                        .collect(Collectors.joining(", "));
                entry.put("permissions", perms);

                // Build the api-call representation
                ObjectNode apiCall = mapper.createObjectNode();
                if (op.getOperationType() == LFPermissionOperation.OperationType.GRANT) {
                    apiCall.put("api", "lakeformation:GrantPermissions");
                } else {
                    apiCall.put("api", "lakeformation:RevokePermissions");
                }
                apiCall.put("principal", op.getPrincipalArn());
                apiCall.set("resource", buildResourceNode(op.getResource()));
                apiCall.put("permissions", perms);
                if (op.isGrantable()) {
                    apiCall.put("grantable", true);
                }
                entry.set("api_call", apiCall);

                outputOps.add(entry);
            }
        }

        if (outputOps.isEmpty()) {
            ObjectNode noOp = mapper.createObjectNode();
            noOp.put("action", "lf");
            noOp.put("operation", "NO_CHANGES");
            noOp.put("description", "Sync produced no delta operations");
            outputOps.add(noOp);
        }

        step.set("output", outputOps);
        currentTestSteps.add(step);
    }

    /** Call at the end of each test method. */
    public void endTest() {
        ObjectNode testEntry = mapper.createObjectNode();
        testEntry.put("test", testClassName + "." + currentTestMethod);
        testEntry.put("timestamp", Instant.now().toString());
        testEntry.set("steps", currentTestSteps);
        testEntries.add(testEntry);
    }

    /** Write the accumulated log to disk. Call once in @AfterAll. */
    public void flush() throws IOException {
        File logsDir = new File("logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        File outFile = new File(logsDir, "it-audit-" + testClassName + ".json");
        mapper.writeValue(outFile, testEntries);
    }

    private ObjectNode buildResourceNode(LFResource res) {
        ObjectNode node = mapper.createObjectNode();
        if (res == null) {
            node.put("type", "unknown");
            return node;
        }
        if (res.getDataLocationPath() != null) {
            node.put("type", "DataLocation");
            node.put("dataLocationPath", res.getDataLocationPath());
        } else if (res.getColumnNames() != null && !res.getColumnNames().isEmpty()) {
            node.put("type", "Column");
            node.put("catalogId", res.getCatalogId());
            node.put("databaseName", res.getDatabaseName());
            node.put("tableName", res.getTableName());
            node.put("columnNames", String.join(", ", res.getColumnNames()));
        } else if (res.getTableName() != null) {
            node.put("type", "Table");
            node.put("catalogId", res.getCatalogId());
            node.put("databaseName", res.getDatabaseName());
            node.put("tableName", res.getTableName());
        } else {
            node.put("type", "Database");
            node.put("catalogId", res.getCatalogId());
            node.put("databaseName", res.getDatabaseName());
        }
        return node;
    }
}
