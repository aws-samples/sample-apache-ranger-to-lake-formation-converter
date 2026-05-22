package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        writeCheckpoint(checkpoint, cedarPolicyText.length());
    }

    /**
     * Persist the current Cedar policy state as a checkpoint with per-service version tracking.
     *
     * @param serviceVersions map of service type to Ranger policy version
     * @param cedarPolicyText the Cedar policy text to persist
     */
    public void save(Map<String, Long> serviceVersions, String cedarPolicyText) {
        long combinedVersion = serviceVersions.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        SyncCheckpoint checkpoint = new SyncCheckpoint(
                combinedVersion, serviceVersions, Instant.now().toString(), cedarPolicyText);
        writeCheckpoint(checkpoint, cedarPolicyText.length());
    }

    /**
     * Persist tag sync state into the existing checkpoint, preserving all other fields.
     * If no checkpoint exists yet, this is a no-op (tag state will be included on next full save).
     *
     * @param tagVersion     the last known Ranger tag version
     * @param managedTagNames the set of LF-Tag keys created by this pipeline
     */
    public void saveTagState(long tagVersion, Set<String> managedTagNames) {
        Optional<SyncCheckpoint> existing = load();
        SyncCheckpoint base = existing.orElse(
                new SyncCheckpoint(0L, null, Instant.now().toString(), ""));
        SyncCheckpoint updated = new SyncCheckpoint(
                base.getPolicyVersion(),
                base.getServiceVersions(),
                base.getTimestamp(),
                base.getCedarPolicyText(),
                tagVersion,
                managedTagNames);
        writeCheckpoint(updated, base.getCedarPolicyText().length());
    }

    /**
     * Persist S3 Access Grants operation state into the existing checkpoint, preserving all other fields.
     * If no checkpoint exists yet, a default base checkpoint is created.
     *
     * @param policyVersion the Ranger policy version associated with these operations
     * @param ops           the list of S3 Access Grant operations to persist
     */
    public void saveS3AgOperations(long policyVersion, List<S3AccessGrantOperation> ops) {
        Optional<SyncCheckpoint> existing = load();
        SyncCheckpoint base = existing.orElse(
                new SyncCheckpoint(policyVersion, null, Instant.now().toString(), ""));
        SyncCheckpoint updated = new SyncCheckpoint(
                policyVersion,
                base.getServiceVersions(),
                base.getTimestamp(),
                base.getCedarPolicyText(),
                base.getLastKnownTagVersion(),
                base.getLastKnownRangerTagNames(),
                ops);
        writeCheckpoint(updated, base.getCedarPolicyText().length());
    }

    private void writeCheckpoint(SyncCheckpoint checkpoint, int cedarTextLength) {
        Path tempFile = checkpointPath.resolveSibling(
                checkpointPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(checkpointPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tempFile.toFile(), checkpoint);
            Files.move(tempFile, checkpointPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Checkpoint saved: {}, cedarTextLength={}",
                    checkpoint, cedarTextLength);
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
