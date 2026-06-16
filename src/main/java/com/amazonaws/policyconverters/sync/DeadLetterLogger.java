package com.amazonaws.policyconverters.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Instant;

/**
 * Writes failed LF permission operations to a dead-letter log in JSON lines format.
 * Each line is a self-contained JSON object with operation details and error info.
 */
public class DeadLetterLogger {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter writer;

    public DeadLetterLogger(BufferedWriter writer) {
        this.writer = writer;
    }

    /**
     * Write a pre-serialised JSON line to the dead-letter log.
     *
     * <p>Used by components (e.g. S3 Access Grants) whose operation types are not
     * {@link LFPermissionOperation} and therefore cannot use {@link #logFailedOperation}.
     *
     * @param jsonLine a single JSON string (must not contain embedded newlines)
     */
    public synchronized void logEntry(String jsonLine) {
        try {
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOG.error("Failed to write entry to dead-letter log: {}", e.getMessage(), e);
        }
    }

    /**
     * Log a permanent gap (e.g. irreconcilable TABLE/TWC conflict) to the dead-letter log.
     * Unlike logFailedOperation, retryCount is omitted — the entry will never be retried.
     *
     * @param op    the losing operation that was suppressed
     * @param error human-readable reason for the gap
     */
    public synchronized void logGapOperation(LFPermissionOperation op, String error) {
        try {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("timestamp", Instant.now().toString());
            entry.put("type", "GAP");
            entry.put("policyId", op.getSourcePolicyId());
            entry.put("operation", op.getOperationType().getValue());

            ObjectNode resource = MAPPER.createObjectNode();
            LFResource res = op.getResource();
            if (res.getDatabaseName() != null) {
                resource.put("database", res.getDatabaseName());
            }
            if (res.getTableName() != null) {
                resource.put("table", res.getTableName());
            }
            if (res.getColumnNames() != null && !res.getColumnNames().isEmpty()) {
                ArrayNode cols = MAPPER.createArrayNode();
                res.getColumnNames().forEach(cols::add);
                resource.set("columns", cols);
            }
            entry.set("resource", resource);

            entry.put("principal", op.getPrincipalArn());

            ArrayNode permsArray = MAPPER.createArrayNode();
            for (LFPermission perm : op.getPermissions()) {
                permsArray.add(perm.getValue());
            }
            entry.set("permissions", permsArray);

            entry.put("error", error);

            writer.write(MAPPER.writeValueAsString(entry));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOG.error("Failed to write gap entry to dead-letter log: {}", e.getMessage(), e);
        }
    }

    /**
     * Log a failed operation to the dead-letter log.
     *
     * @param op         the operation that failed
     * @param error      the error message
     * @param retryCount number of retries attempted
     */
    public synchronized void logFailedOperation(LFPermissionOperation op, String error, int retryCount) {
        try {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("timestamp", Instant.now().toString());
            entry.put("policyId", op.getSourcePolicyId());
            entry.put("operation", op.getOperationType().getValue());

            ObjectNode resource = MAPPER.createObjectNode();
            LFResource res = op.getResource();
            if (res.getDatabaseName() != null) {
                resource.put("database", res.getDatabaseName());
            }
            if (res.getTableName() != null) {
                resource.put("table", res.getTableName());
            }
            entry.set("resource", resource);

            entry.put("principal", op.getPrincipalArn());

            ArrayNode permsArray = MAPPER.createArrayNode();
            for (LFPermission perm : op.getPermissions()) {
                permsArray.add(perm.getValue());
            }
            entry.set("permissions", permsArray);

            entry.put("error", error);
            entry.put("retryCount", retryCount);

            writer.write(MAPPER.writeValueAsString(entry));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOG.error("Failed to write to dead-letter log: {}", e.getMessage(), e);
        }
    }
}
