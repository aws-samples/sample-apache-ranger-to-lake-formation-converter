package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.model.TagSyncOutput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

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
    protected static final long DEFAULT_HEALTH_TIMEOUT_MS = 120_000;
    protected static final long HEALTH_POLL_INTERVAL_MS = 2_000;
    private static final String COMPOSE_FILE_PATH = System.getProperty(
            "compose.file.path", "integration-test/docker/docker-compose.yml");
    private static final String CHECKPOINT_FILENAME = "sync-checkpoint.json";

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
     * Delete dry-run output files (matching {@code dry-run-*.json}) from the output directory.
     * Preserves other files such as the checkpoint file ({@code sync-checkpoint.json}).
     */
    protected void clearDryRunOutputs() {
        File outputDir = dryRunOutputPath.toFile();
        if (!outputDir.exists()) {
            return;
        }
        File[] files = outputDir.listFiles((dir, name) ->
                name.startsWith("dry-run-") && name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    LOG.warn("Failed to delete dry-run output file: {}", f);
                }
            }
        }
    }

    // ---- Checkpoint helpers ----

    /**
     * Read and parse the checkpoint file ({@code sync-checkpoint.json}) from the dry-run output directory.
     *
     * @return a map of checkpoint fields, or an empty map if the file does not exist or cannot be parsed
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> readCheckpointFile() {
        File checkpointFile = dryRunOutputPath.resolve(CHECKPOINT_FILENAME).toFile();
        if (!checkpointFile.exists()) {
            LOG.info("Checkpoint file does not exist: {}", checkpointFile);
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(checkpointFile, Map.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse checkpoint file {}: {}", checkpointFile, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ---- Container lifecycle helpers ----

    /**
     * Return the Docker Compose file path used by the integration test stack.
     *
     * @return the relative path to {@code docker-compose.yml}
     */
    protected String getComposeFilePath() {
        return COMPOSE_FILE_PATH;
    }

    /**
     * Poll the conversion-server container until it reaches a running/healthy state.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @throws AssertionError if the container does not become healthy within the timeout
     */
    protected void waitForContainerHealth(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        LOG.info("Waiting up to {}ms for conversion-server container to become healthy", timeoutMs);

        while (System.currentTimeMillis() < deadline) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "compose", "-f", getComposeFilePath(), "ps", "conversion-server");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                String output;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    output = reader.lines().collect(Collectors.joining("\n"));
                }
                int exitCode = process.waitFor();

                if (exitCode == 0 && output.toLowerCase().contains("running")) {
                    LOG.info("conversion-server container is running");
                    return;
                }

                LOG.debug("Container not yet healthy (exit={}): {}", exitCode, output);
            } catch (Exception e) {
                LOG.warn("Error checking container health: {}", e.getMessage());
            }

            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for container health", e);
            }
        }

        throw new AssertionError(
                "conversion-server container did not become healthy within " + timeoutMs + "ms");
    }

    /**
     * Restart the conversion-server container and wait for it to become healthy.
     *
     * @throws Exception if the restart command fails or the container does not become healthy
     */
    protected void restartConversionServer() throws Exception {
        LOG.info("Restarting conversion-server container via docker stop + start");
        // Use 'docker stop' + 'docker start' instead of 'docker restart' to avoid
        // nerdctl healthcheck timer issues on macOS (systemd-run fails).
        String containerId = resolveConversionServerContainerId();

        // Stop the container
        ProcessBuilder stopPb = new ProcessBuilder("docker", "stop", containerId);
        stopPb.redirectErrorStream(true);
        Process stopProcess = stopPb.start();
        try (java.io.InputStream is = stopProcess.getInputStream()) {
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) { /* drain */ }
        }
        int stopExit = stopProcess.waitFor();
        LOG.info("docker stop exit code: {}", stopExit);
        if (stopExit != 0) {
            throw new RuntimeException("docker stop conversion-server failed with exit code " + stopExit);
        }

        // Start the container
        ProcessBuilder startPb = new ProcessBuilder("docker", "start", containerId);
        startPb.redirectErrorStream(true);
        Process startProcess = startPb.start();
        try (java.io.InputStream is = startProcess.getInputStream()) {
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) { /* drain */ }
        }
        int startExit = startProcess.waitFor();
        if (startExit != 0) {
            throw new RuntimeException("docker start conversion-server failed with exit code " + startExit);
        }
        LOG.info("Restart command completed, waiting for container health");
        waitForContainerHealth(DEFAULT_HEALTH_TIMEOUT_MS);
    }

    /**
     * Resolve the container ID of the running conversion-server via {@code docker compose ps -q}.
     *
     * <p>Using the container ID rather than a hard-coded name makes the method immune to
     * the checkout directory name (e.g. {@code docker-conversion-server-1} vs
     * {@code apacherangertolf-conversion-server-1}).</p>
     *
     * @return the container ID string
     * @throws IllegalStateException if the container is not currently running
     */
    private static String resolveConversionServerContainerId() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "compose",
            "-f", COMPOSE_FILE_PATH,
            "ps", "-q", "conversion-server");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            String id = new String(p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();
            if (exitCode != 0 || id.isEmpty()) {
                throw new IllegalStateException(
                    "docker compose ps -q failed (exit=" + exitCode + "): " + id);
            }
            return id;
        } finally {
            p.destroy();
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

    // ---- Tag-sync output helpers ----

    /**
     * Wait for new tag-sync output files (tag-sync-*.json) to appear, using the default timeout.
     *
     * @return list of parsed {@link TagSyncOutput} objects from new files
     */
    protected List<TagSyncOutput> waitForTagSyncOutput() {
        return waitForTagSyncOutput(DEFAULT_SYNC_TIMEOUT_MS);
    }

    /**
     * Wait for new tag-sync output files (tag-sync-*.json) to appear.
     *
     * @param timeoutMs maximum time to wait
     * @return list of parsed {@link TagSyncOutput} objects from new files
     * @throws AssertionError if no new output appears within the timeout
     */
    protected List<TagSyncOutput> waitForTagSyncOutput(long timeoutMs) {
        File outputDir = dryRunOutputPath.toFile();
        Map<String, Long> baseline = snapshotTagSyncTimestamps(outputDir);
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for tag-sync output", e);
            }

            Map<String, Long> current = snapshotTagSyncTimestamps(outputDir);
            List<File> newOrUpdated = findNewOrUpdatedFiles(baseline, current, outputDir);
            if (!newOrUpdated.isEmpty()) {
                LOG.info("Found {} new/updated tag-sync output file(s)", newOrUpdated.size());
                List<TagSyncOutput> outputs = new ArrayList<>();
                for (File f : newOrUpdated) {
                    try {
                        outputs.add(objectMapper.readValue(f, TagSyncOutput.class));
                    } catch (Exception e) {
                        LOG.warn("Failed to parse tag-sync output file {}: {}", f.getName(), e.getMessage());
                    }
                }
                return outputs;
            }
        }

        throw new AssertionError("Timed out after " + timeoutMs
                + "ms waiting for tag-sync output in " + dryRunOutputPath);
    }

    /**
     * Delete tag-sync output files (tag-sync-*.json) from the output directory.
     */
    protected void clearTagSyncOutputs() {
        File outputDir = dryRunOutputPath.toFile();
        if (!outputDir.exists()) return;
        File[] files = outputDir.listFiles((dir, name) ->
                name.startsWith("tag-sync-") && name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    LOG.warn("Failed to delete tag-sync output file: {}", f);
                }
            }
        }
    }

    /**
     * Read all tag-sync output files currently in the output directory.
     */
    protected List<TagSyncOutput> readTagSyncOutputs() {
        File outputDir = dryRunOutputPath.toFile();
        File[] files = outputDir.listFiles((dir, name) ->
                name.startsWith("tag-sync-") && name.endsWith(".json"));
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<TagSyncOutput> outputs = new ArrayList<>();
        for (File f : files) {
            try {
                outputs.add(objectMapper.readValue(f, TagSyncOutput.class));
            } catch (Exception e) {
                LOG.warn("Failed to parse tag-sync output file {}: {}", f.getName(), e.getMessage());
            }
        }
        return outputs;
    }

    private Map<String, Long> snapshotTagSyncTimestamps(File directory) {
        Map<String, Long> timestamps = new HashMap<>();
        if (!directory.exists()) return timestamps;
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("tag-sync-") && name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                timestamps.put(f.getName(), f.lastModified());
            }
        }
        return timestamps;
    }
}
