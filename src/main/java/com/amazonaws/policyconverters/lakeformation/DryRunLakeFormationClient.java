package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.config.RetryConfig;
import com.amazonaws.policyconverters.model.DryRunOutput;
import com.amazonaws.policyconverters.model.TagSyncOutput;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dry-run implementation of {@link LakeFormationClient} that serializes
 * LF permission operations and tag sync operations to JSON files instead of
 * calling the AWS Lake Formation API.
 *
 * <p>Permission operations: each {@link #applyBatch} call writes a
 * {@code dry-run-NNN.json} file.</p>
 *
 * <p>Tag operations: each create/delete/attach/detach call writes a
 * {@code tag-sync-NNN.json} file with the operation type, tag key, and
 * optional resource descriptor. Tag state is maintained in-memory so that
 * {@link #listLFTagKeys} and {@link #getResourceLFTags} return consistent
 * results without a real AWS SDK client.</p>
 */
public class DryRunLakeFormationClient extends LakeFormationClient {

    private static final Logger LOG = LoggerFactory.getLogger(DryRunLakeFormationClient.class);

    private final Path outputDirectory;
    private final ObjectMapper objectMapper;
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);
    private final AtomicInteger tagSequenceCounter = new AtomicInteger(0);

    /** tagKey → tagValues (in-memory LF-Tag definition store) */
    private final Map<String, List<String>> tagDefinitions = new ConcurrentHashMap<>();

    /** resource descriptor → (tagKey → tagValue) (in-memory attachment store) */
    private final Map<String, Map<String, String>> resourceTagAttachments = new ConcurrentHashMap<>();

    public DryRunLakeFormationClient(Path outputDirectory, ObjectMapper objectMapper) {
        super(null, new RetryConfig());
        this.outputDirectory = outputDirectory;
        this.objectMapper = objectMapper;
    }

    @Override
    public BatchResult applyBatch(List<LFPermissionOperation> operations, DeadLetterLogger deadLetterLogger) {
        ensureOutputDirectory();
        int seq = sequenceCounter.incrementAndGet();
        String filename = String.format("dry-run-%03d.json", seq);
        Path outputFile = outputDirectory.resolve(filename);

        DryRunOutput output = new DryRunOutput(Instant.now().toString(), seq, operations);
        writeJson(outputFile, output);
        LOG.info("Dry-run output written to {}", outputFile);

        List<String> succeededPolicyIds = new ArrayList<>();
        for (LFPermissionOperation op : operations) {
            String policyId = op.getSourcePolicyId() != null ? op.getSourcePolicyId() : "__unknown__";
            if (!succeededPolicyIds.contains(policyId)) {
                succeededPolicyIds.add(policyId);
            }
        }
        return new BatchResult(succeededPolicyIds, List.of(), operations.size(), operations.size(), 0);
    }

    // -----------------------------------------------------------------------
    // LF-Tag definition management — in-memory simulation
    // -----------------------------------------------------------------------

    @Override
    public void createLFTag(String catalogId, String tagKey, List<String> tagValues) {
        tagDefinitions.put(tagKey, new ArrayList<>(tagValues));
        LOG.info("DryRun: created LF-Tag key={} values={}", tagKey, tagValues);
        writeTagSyncOutput("CREATE_TAG", tagKey, tagValues, null);
    }

    @Override
    public void deleteLFTag(String catalogId, String tagKey) {
        tagDefinitions.remove(tagKey);
        LOG.info("DryRun: deleted LF-Tag key={}", tagKey);
        writeTagSyncOutput("DELETE_TAG", tagKey, null, null);
    }

    @Override
    public List<String> listLFTagKeys(String catalogId) {
        return new ArrayList<>(tagDefinitions.keySet());
    }

    // -----------------------------------------------------------------------
    // LF-Tag resource attachment management — in-memory simulation
    // -----------------------------------------------------------------------

    @Override
    public Map<String, String> getResourceLFTags(LFResource resource, String catalogId) {
        String key = resourceKey(resource);
        return new HashMap<>(resourceTagAttachments.getOrDefault(key, Collections.emptyMap()));
    }

    @Override
    public void addLFTagsToResource(LFResource resource, Map<String, String> tags, String catalogId) {
        String key = resourceKey(resource);
        resourceTagAttachments.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).putAll(tags);
        LOG.info("DryRun: added LF-Tags to resource={} tags={}", key, tags);
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            writeTagSyncOutput("ATTACH_TAG", entry.getKey(), List.of(entry.getValue()),
                    buildResourceDescriptor(resource));
        }
    }

    @Override
    public void removeLFTagsFromResource(LFResource resource, List<String> tagKeys, String catalogId) {
        String key = resourceKey(resource);
        Map<String, String> existing = resourceTagAttachments.get(key);
        if (existing != null) {
            tagKeys.forEach(existing::remove);
        }
        LOG.info("DryRun: removed LF-Tags from resource={} keys={}", key, tagKeys);
        for (String tagKey : tagKeys) {
            writeTagSyncOutput("DETACH_TAG", tagKey, null, buildResourceDescriptor(resource));
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void ensureOutputDirectory() {
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create dry-run output directory: " + outputDirectory, e);
        }
    }

    private void writeTagSyncOutput(String operationType, String tagKey, List<String> tagValues,
                                    Map<String, String> resource) {
        ensureOutputDirectory();
        int seq = tagSequenceCounter.incrementAndGet();
        String filename = String.format("tag-sync-%03d.json", seq);
        Path outputFile = outputDirectory.resolve(filename);
        TagSyncOutput output = new TagSyncOutput(
                Instant.now().toString(), seq, operationType, tagKey, tagValues, resource);
        writeJson(outputFile, output);
        LOG.info("Tag-sync dry-run output written to {} op={} key={}", outputFile, operationType, tagKey);
    }

    private void writeJson(Path file, Object value) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write dry-run output to " + file, e);
        }
    }

    private static String resourceKey(LFResource resource) {
        if (resource == null) return "__null__";
        StringBuilder sb = new StringBuilder();
        if (resource.getDatabaseName() != null) sb.append("db=").append(resource.getDatabaseName());
        if (resource.getTableName() != null) sb.append("/tbl=").append(resource.getTableName());
        if (resource.getColumnNames() != null && !resource.getColumnNames().isEmpty()) {
            sb.append("/col=").append(resource.getColumnNames());
        }
        return sb.toString();
    }

    private static Map<String, String> buildResourceDescriptor(LFResource resource) {
        if (resource == null) return null;
        Map<String, String> desc = new LinkedHashMap<>();
        if (resource.getDatabaseName() != null) desc.put("database", resource.getDatabaseName());
        if (resource.getTableName() != null) desc.put("table", resource.getTableName());
        if (resource.getColumnNames() != null && !resource.getColumnNames().isEmpty()) {
            desc.put("column", resource.getColumnNames().iterator().next());
        }
        return desc;
    }
}
