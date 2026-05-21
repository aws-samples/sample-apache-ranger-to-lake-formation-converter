package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Containerized integration test verifying checkpoint persistence, server restart
 * resilience, and corrupted checkpoint recovery through the containerized conversion server.
 *
 * <p>Tests must run in order: the restart test depends on the checkpoint written by the
 * persistence test, and the corrupted checkpoint test depends on a policy existing in
 * Ranger Admin.</p>
 *
 * <p>Validates: Requirements 5.1–5.5, 6.1–6.4</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ContainerizedCheckpointIT extends ContainerizedPipelineIT {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerizedCheckpointIT.class);
    private static final String CHECKPOINT_FILENAME = "sync-checkpoint.json";

    // ---- Test 1: Checkpoint Persistence (Req 5) ----

    @Test
    @Order(1)
    void testCheckpointPersistence() throws Exception {
        // Create a policy granting SELECT on test_db.orders to analyst
        String policyJson = "{"
                + "\"name\":\"checkpoint-persistence-test\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"orders\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);

        // Wait for the conversion server to process the policy and produce dry-run output
        List<DryRunOutput> outputs = waitForDryRunOutput();
        assertFalse(outputs.isEmpty(), "Expected dry-run output after policy creation");

        // Verify GRANT operations were produced
        List<LFPermissionOperation> grants = outputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());
        assertFalse(grants.isEmpty(), "Expected GRANT operations after policy creation");

        // Read the checkpoint file and validate its contents
        Map<String, Object> checkpoint = readCheckpointFile();
        assertFalse(checkpoint.isEmpty(), "Checkpoint file should exist and be valid JSON after sync");

        // Assert cedarPolicyText is non-empty
        Object cedarPolicyText = checkpoint.get("cedarPolicyText");
        assertNotNull(cedarPolicyText, "Checkpoint should contain cedarPolicyText field");
        assertTrue(cedarPolicyText instanceof String, "cedarPolicyText should be a String");
        assertFalse(((String) cedarPolicyText).isEmpty(),
                "cedarPolicyText should be non-empty after a sync cycle with policies");

        // Assert policyVersion > 0
        Object policyVersion = checkpoint.get("policyVersion");
        assertNotNull(policyVersion, "Checkpoint should contain policyVersion field");
        assertTrue(policyVersion instanceof Number, "policyVersion should be a Number");
        assertTrue(((Number) policyVersion).longValue() > 0,
                "policyVersion should be greater than 0, got: " + policyVersion);

        // Assert timestamp is a valid ISO-8601 string
        Object timestamp = checkpoint.get("timestamp");
        assertNotNull(timestamp, "Checkpoint should contain timestamp field");
        assertTrue(timestamp instanceof String, "timestamp should be a String");
        assertDoesNotThrow(() -> Instant.parse((String) timestamp),
                "timestamp should be a valid ISO-8601 string, got: " + timestamp);

        LOG.info("Checkpoint validated: policyVersion={}, timestamp={}, cedarPolicyTextLength={}",
                policyVersion, timestamp, ((String) cedarPolicyText).length());
    }

    // ---- Test 2: Restart Resilience (Req 6) ----

    @Test
    @Order(2)
    void testRestartResilience() throws Exception {
        // Step 1: Create policy A and wait for GRANT
        String policyAJson = "{"
                + "\"name\":\"checkpoint-restart-policy-a\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"users\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"etl_user\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        String etlUserArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/etl_user";

        createAndTrackPolicy(policyAJson);
        List<DryRunOutput> initialOutputs = waitForDryRunOutput();
        assertFalse(initialOutputs.isEmpty(), "Expected dry-run output for policy A");

        List<LFPermissionOperation> initialGrants = initialOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> etlUserArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());
        assertFalse(initialGrants.isEmpty(), "Expected GRANT for etl_user from policy A");

        // Verify checkpoint exists before restart
        Map<String, Object> checkpointBeforeRestart = readCheckpointFile();
        assertFalse(checkpointBeforeRestart.isEmpty(),
                "Checkpoint should exist before restart");

        // Step 2: Restart the conversion server container
        LOG.info("Restarting conversion-server container...");
        restartConversionServer();

        // Step 3: After restart with a valid checkpoint, the server should NOT produce
        // duplicate GRANTs for policy A. The first sync cycle after restart loads the
        // checkpoint and computes a diff — if the checkpoint matches the current state,
        // no output is produced (which is correct behavior).
        clearDryRunOutputs();

        // Wait a short time to see if any output is produced after restart.
        // No output is the expected/correct behavior (checkpoint loaded, no diff).
        List<DryRunOutput> postRestartOutputs;
        try {
            postRestartOutputs = waitForDryRunOutput(20_000);
        } catch (AssertionError e) {
            // No output after restart — this is the expected behavior when checkpoint
            // is loaded correctly (no diff → no operations → no dry-run file)
            LOG.info("No dry-run output after restart — checkpoint loaded correctly, no diff detected");
            postRestartOutputs = List.of();
        }

        // If output was produced, assert no duplicate GRANT for policy A
        if (!postRestartOutputs.isEmpty()) {
            List<LFPermissionOperation> duplicateGrants = postRestartOutputs.stream()
                    .flatMap(o -> o.getOperations().stream())
                    .filter(op -> op.getOperationType() == OperationType.GRANT)
                    .filter(op -> etlUserArn.equals(op.getPrincipalArn()))
                    .collect(Collectors.toList());

            assertTrue(duplicateGrants.isEmpty(),
                    "Expected no duplicate GRANT for etl_user after restart with checkpoint, but found: "
                            + duplicateGrants);
        }

        // Step 4: Create policy B after restart and verify GRANT only for policy B
        clearDryRunOutputs();

        String policyBJson = "{"
                + "\"name\":\"checkpoint-restart-policy-b\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"products\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"data_admin\"],"
                + "  \"accesses\":[{\"type\":\"alter\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        String dataAdminArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/data_admin";

        createAndTrackPolicy(policyBJson);
        List<DryRunOutput> policyBOutputs = waitForDryRunOutput();
        assertFalse(policyBOutputs.isEmpty(), "Expected dry-run output for policy B");

        List<LFPermissionOperation> policyBGrants = policyBOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .filter(op -> dataAdminArn.equals(op.getPrincipalArn()))
                .collect(Collectors.toList());
        assertFalse(policyBGrants.isEmpty(),
                "Expected GRANT for data_admin from policy B after restart");

        LOG.info("Restart resilience verified: no duplicate GRANTs for policy A, "
                + "new GRANT produced for policy B");
    }

    // ---- Test 3: Corrupted Checkpoint Recovery (Req 6.4) ----

    @Test
    @Order(3)
    void testCorruptedCheckpointRecovery() throws Exception {
        // Step 1: Create a policy and wait for sync
        String policyJson = "{"
                + "\"name\":\"checkpoint-corruption-test\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"test_db\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"inventory\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"analyst\"],"
                + "  \"accesses\":[{\"type\":\"select\",\"isAllowed\":true}]"
                + "}]"
                + "}";

        createAndTrackPolicy(policyJson);
        List<DryRunOutput> initialOutputs = waitForDryRunOutput();
        assertFalse(initialOutputs.isEmpty(), "Expected dry-run output after policy creation");

        // Verify checkpoint exists
        Map<String, Object> checkpoint = readCheckpointFile();
        assertFalse(checkpoint.isEmpty(), "Checkpoint should exist after sync");

        // Step 2: Corrupt the checkpoint file with invalid JSON
        Path checkpointPath = dryRunOutputPath.resolve(CHECKPOINT_FILENAME);
        Files.writeString(checkpointPath, "THIS IS NOT VALID JSON {{{corrupted}}}");
        LOG.info("Corrupted checkpoint file at {}", checkpointPath);

        // Step 3: Restart the conversion server
        LOG.info("Restarting conversion-server with corrupted checkpoint...");
        restartConversionServer();

        // Step 4: Clear dry-run output and wait for full bulk sync.
        // Use a longer timeout here: after a cold restart the server must reconnect
        // to Ranger and complete a full re-sync before writing any dry-run output.
        clearDryRunOutputs();
        List<DryRunOutput> recoveryOutputs = waitForDryRunOutput(DEFAULT_HEALTH_TIMEOUT_MS);
        assertFalse(recoveryOutputs.isEmpty(),
                "Expected dry-run output after restart with corrupted checkpoint");

        // Assert full bulk sync produces GRANTs for existing policies
        // With a corrupted checkpoint, the server starts from empty state and
        // re-grants all existing policies
        List<LFPermissionOperation> recoveryGrants = recoveryOutputs.stream()
                .flatMap(o -> o.getOperations().stream())
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .collect(Collectors.toList());

        assertFalse(recoveryGrants.isEmpty(),
                "Expected GRANT operations from full bulk sync after corrupted checkpoint recovery");

        LOG.info("Corrupted checkpoint recovery verified: {} GRANT operations produced from bulk sync",
                recoveryGrants.size());
    }
}
