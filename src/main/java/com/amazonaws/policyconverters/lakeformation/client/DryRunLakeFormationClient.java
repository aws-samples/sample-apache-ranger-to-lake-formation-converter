package com.amazonaws.policyconverters.lakeformation.client;

import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.RetryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dry-run implementation of {@link LakeFormationClient} that serializes
 * LF permission operations to JSON files instead of calling the AWS Lake Formation API.
 *
 * <p>Each call to {@link #applyBatch} writes a separate JSON file with a monotonically
 * increasing sequence number (e.g., {@code dry-run-001.json}, {@code dry-run-002.json}).
 * The output directory is created automatically if it does not exist.</p>
 */
public class DryRunLakeFormationClient extends LakeFormationClient {

    private static final Logger LOG = LoggerFactory.getLogger(DryRunLakeFormationClient.class);

    private final Path outputDirectory;
    private final ObjectMapper objectMapper;
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    public DryRunLakeFormationClient(Path outputDirectory, ObjectMapper objectMapper) {
        super(null, new RetryConfig());
        this.outputDirectory = outputDirectory;
        this.objectMapper = objectMapper;
    }

    @Override
    public BatchResult applyBatch(List<LFPermissionOperation> operations, DeadLetterLogger deadLetterLogger) {
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create dry-run output directory: " + outputDirectory, e);
        }

        int seq = sequenceCounter.incrementAndGet();
        String filename = String.format("dry-run-%03d.json", seq);
        Path outputFile = outputDirectory.resolve(filename);

        DryRunOutput output = new DryRunOutput(
                Instant.now().toString(),
                seq,
                operations);

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), output);
            LOG.info("Dry-run output written to {}", outputFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write dry-run output to " + outputFile, e);
        }

        List<String> succeededPolicyIds = new ArrayList<>();
        for (LFPermissionOperation op : operations) {
            String policyId = op.getSourcePolicyId() != null ? op.getSourcePolicyId() : "__unknown__";
            if (!succeededPolicyIds.contains(policyId)) {
                succeededPolicyIds.add(policyId);
            }
        }

        return new BatchResult(
                succeededPolicyIds,
                List.of(),
                operations.size(),
                operations.size(),
                0);
    }
}
