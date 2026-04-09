package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Abstract base class for containerized integration tests.
 *
 * <p>Unlike {@link DryRunPipelineIT}, this class does NOT wire up the conversion pipeline
 * in-process. Instead, it reads dry-run output from a shared Docker volume that the
 * containerized conversion server writes to. Tests create/update/delete policies via the
 * Ranger REST API and wait for the container to pick up changes by polling the shared
 * volume for new or updated JSON files.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ContainerizedPipelineIT {

    private static final Logger LOG = LoggerFactory.getLogger(ContainerizedPipelineIT.class);

    // ---- Constants ----
    protected static final String TEST_ACCOUNT_ID = "123456789012";
    protected static final String DEFAULT_DRY_RUN_PATH = "integration-test/docker/dry-run-output";
    protected static final long DEFAULT_SYNC_TIMEOUT_MS = 60_000;
    protected static final long POLL_INTERVAL_MS = 1_000;

    private static final String DEFAULT_RANGER_URL = "http://localhost:6080";
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASSWORD = "rangerR0cks!";

    // ---- Instance state ----
    protected RangerPolicyRestClient policyClient;
    protected Path dryRunOutputPath;
    protected List<Integer> createdPolicyIds;
    protected ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        String rangerUrl = System.getProperty("ranger.admin.url", DEFAULT_RANGER_URL);
        String outputPath = System.getProperty("dry.run.output.path", DEFAULT_DRY_RUN_PATH);

        dryRunOutputPath = Paths.get(outputPath);
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        policyClient = new RangerPolicyRestClient(rangerUrl, AUTH_USER, AUTH_PASSWORD);
        createdPolicyIds = new ArrayList<>();

        clearDryRunOutputs();
    }

    @AfterEach
    void tearDown() {
        // Delete tracked policies (log warnings, don't fail the test)
        if (createdPolicyIds != null) {
            for (int policyId : createdPolicyIds) {
                try {
                    policyClient.deletePolicy(policyId);
                } catch (Exception e) {
                    LOG.warn("Failed to delete policy {} during teardown: {}", policyId, e.getMessage());
                }
            }
        }

        clearDryRunOutputs();
    }

    // ---- Policy helper methods ----

    /**
     * Create a policy via the REST client and track it for cleanup in @AfterEach.
     *
     * @param policyJson the policy JSON body
     * @return the ID of the created policy
     */
    protected int createAndTrackPolicy(String policyJson) {
        int policyId = policyClient.createPolicy(policyJson);
        createdPolicyIds.add(policyId);
        LOG.info("Created and tracked policy {}", policyId);
        return policyId;
    }

    /**
     * Update an existing policy.
     *
     * @param policyId   the ID of the policy to update
     * @param policyJson the updated policy JSON body
     */
    protected void updatePolicy(int policyId, String policyJson) {
        policyClient.updatePolicy(policyId, policyJson);
        LOG.info("Updated policy {}", policyId);
    }

    /**
     * Delete a policy and remove it from the tracked list so @AfterEach won't try again.
     *
     * @param policyId the ID of the policy to delete
     */
    protected void deletePolicyAndUntrack(int policyId) {
        policyClient.deletePolicy(policyId);
        createdPolicyIds.remove(Integer.valueOf(policyId));
        LOG.info("Deleted and untracked policy {}", policyId);
    }

    // ---- Dry-run output methods ----

    /**
     * Wait for new or updated dry-run output files to appear in the shared volume,
     * using the default timeout.
     *
     * @return the list of new/updated {@link DryRunOutput} objects
     */
    protected List<DryRunOutput> waitForDryRunOutput() {
        return waitForDryRunOutput(DEFAULT_SYNC_TIMEOUT_MS);
    }

    /**
     * Wait for new or updated dry-run output files to appear in the shared volume.
     *
     * <p>Polling strategy:
     * <ol>
     *   <li>Snapshot existing files and their lastModified timestamps</li>
     *   <li>Sleep {@link #POLL_INTERVAL_MS}, then check for new files or files with newer timestamps</li>
     *   <li>When found, parse and return the new/updated {@link DryRunOutput} objects</li>
     *   <li>If timeout exceeded, throw {@link AssertionError} with diagnostic message</li>
     * </ol>
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the list of new/updated {@link DryRunOutput} objects
     * @throws AssertionError if no new output appears within the timeout
     */
    protected List<DryRunOutput> waitForDryRunOutput(long timeoutMs) {
        File outputDir = dryRunOutputPath.toFile();

        // 1. Snapshot existing files and their lastModified timestamps
        Map<String, Long> baselineTimestamps = snapshotFileTimestamps(outputDir);

        long deadline = System.currentTimeMillis() + timeoutMs;

        // 2. Poll for new or updated files
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for dry-run output", e);
            }

            Map<String, Long> currentTimestamps = snapshotFileTimestamps(outputDir);
            List<File> newOrUpdatedFiles = findNewOrUpdatedFiles(baselineTimestamps, currentTimestamps, outputDir);

            if (!newOrUpdatedFiles.isEmpty()) {
                LOG.info("Found {} new/updated dry-run output file(s)", newOrUpdatedFiles.size());
                List<DryRunOutput> outputs = new ArrayList<>();
                for (File f : newOrUpdatedFiles) {
                    try {
                        outputs.add(objectMapper.readValue(f, DryRunOutput.class));
                    } catch (Exception e) {
                        LOG.warn("Failed to parse dry-run output file {}: {}", f.getName(), e.getMessage());
                    }
                }
                return outputs;
            }
        }

        // 3. Timeout — build diagnostic message
        File[] existingFiles = outputDir.listFiles((dir, name) ->
                name.startsWith("dry-run-") && name.endsWith(".json"));
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("Timed out after ").append(timeoutMs)
                .append("ms waiting for new dry-run output in ").append(dryRunOutputPath).append(". ");
        if (existingFiles == null || existingFiles.length == 0) {
            diagnostic.append("No dry-run files exist in the output directory.");
        } else {
            diagnostic.append("Existing files (").append(existingFiles.length).append("): ");
            for (File f : existingFiles) {
                diagnostic.append(f.getName())
                        .append(" (lastModified=").append(f.lastModified()).append(") ");
            }
        }
        diagnostic.append("Baseline snapshot had ").append(baselineTimestamps.size()).append(" file(s).");
        throw new AssertionError(diagnostic.toString());
    }

    /**
     * Read and parse all dry-run output JSON files from the output directory.
     *
     * @return list of parsed {@link DryRunOutput} objects, sorted by filename
     */
    protected List<DryRunOutput> readDryRunOutputs() {
        File outputDir = dryRunOutputPath.toFile();
        File[] files = outputDir.listFiles((dir, name) ->
                name.startsWith("dry-run-") && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        List<DryRunOutput> outputs = new ArrayList<>();
        for (File f : files) {
            try {
                outputs.add(objectMapper.readValue(f, DryRunOutput.class));
            } catch (Exception e) {
                LOG.warn("Failed to parse dry-run output file {}: {}", f.getName(), e.getMessage());
            }
        }
        return outputs;
    }

    /**
     * Delete all files in the dry-run output directory.
     */
    protected void clearDryRunOutputs() {
        File outputDir = dryRunOutputPath.toFile();
        if (!outputDir.exists()) {
            return;
        }
        File[] files = outputDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    LOG.warn("Failed to delete dry-run output file: {}", f);
                }
            }
        }
    }

    // ---- Private helpers ----

    /**
     * Snapshot the dry-run JSON files in the given directory, returning a map of
     * filename → lastModified timestamp.
     */
    private Map<String, Long> snapshotFileTimestamps(File directory) {
        Map<String, Long> timestamps = new HashMap<>();
        if (!directory.exists()) {
            return timestamps;
        }
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("dry-run-") && name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                timestamps.put(f.getName(), f.lastModified());
            }
        }
        return timestamps;
    }

    /**
     * Find files that are new (not in baseline) or updated (newer lastModified timestamp).
     */
    private List<File> findNewOrUpdatedFiles(
            Map<String, Long> baseline,
            Map<String, Long> current,
            File directory) {
        List<File> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            String name = entry.getKey();
            long currentTimestamp = entry.getValue();
            Long baselineTimestamp = baseline.get(name);
            if (baselineTimestamp == null || currentTimestamp > baselineTimestamp) {
                result.add(new File(directory, name));
            }
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }
}
