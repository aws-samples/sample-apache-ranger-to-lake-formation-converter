package com.amazonaws.policyconverters.ranger.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/**
 * Persists and restores sync checkpoint state to/from a JSON file.
 * The checkpoint contains the Cedar policy text (source of truth) and
 * the Ranger policy version it was derived from.
 * <p>
 * Uses atomic write-via-rename to avoid corrupted checkpoints on crash.
 */
public class CheckpointStore {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointStore.class);
    private final Path checkpointPath;
    private final ObjectMapper objectMapper;

    public CheckpointStore(Path checkpointPath, ObjectMapper objectMapper) {
        this.checkpointPath = checkpointPath;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist the current Cedar policy state as a checkpoint.
     *
     * @param policyVersion   the Ranger policy version
     * @param cedarPolicyText the Cedar policy text to persist
     */
    public void save(long policyVersion, String cedarPolicyText) {
        SyncCheckpoint checkpoint = new SyncCheckpoint(
                policyVersion, Instant.now().toString(), cedarPolicyText);
        Path tempFile = checkpointPath.resolveSibling(
                checkpointPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(checkpointPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tempFile.toFile(), checkpoint);
            Files.move(tempFile, checkpointPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Checkpoint saved: policyVersion={}, cedarTextLength={}",
                    policyVersion, cedarPolicyText.length());
        } catch (IOException e) {
            LOG.error("Failed to save checkpoint to {}: {}",
                    checkpointPath, e.getMessage(), e);
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
        }
    }

    /**
     * Load the most recent checkpoint, if one exists and is valid.
     *
     * @return the checkpoint, or empty if no valid checkpoint exists
     */
    public Optional<SyncCheckpoint> load() {
        if (!Files.exists(checkpointPath)) {
            LOG.info("No checkpoint file found at {}, starting from empty state",
                    checkpointPath);
            return Optional.empty();
        }
        try {
            SyncCheckpoint checkpoint = objectMapper.readValue(
                    checkpointPath.toFile(), SyncCheckpoint.class);
            LOG.info("Checkpoint loaded: {}", checkpoint);
            return Optional.of(checkpoint);
        } catch (IOException e) {
            LOG.warn("Failed to load checkpoint from {}, starting from empty state: {}",
                    checkpointPath, e.getMessage());
            return Optional.empty();
        }
    }

    public Path getCheckpointPath() {
        return checkpointPath;
    }
}
