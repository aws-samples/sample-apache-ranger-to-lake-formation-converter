package com.example.ranger.lakeformation.simulator.remediation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class BundleWriter {
    private static final Logger LOG = LoggerFactory.getLogger(BundleWriter.class);
    private static final DateTimeFormatter DIR_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    private final Path baseDir;
    private final ObjectMapper mapper;

    public BundleWriter(Path baseDir) {
        this.baseDir = baseDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Write bundle to baseDir/&lt;timestamp&gt;/. Returns the path of the created directory.
     */
    public Path write(ReproductionBundle bundle) throws IOException {
        String dirName = "violation_" + DIR_FORMATTER.format(bundle.detectedAt());
        Path bundleDir = baseDir.resolve(dirName);
        Files.createDirectories(bundleDir);

        // mutations.json
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(bundleDir.resolve("mutations.json").toFile(), bundle.mutations());

        // ranger-snapshot.json (already a JSON string)
        Files.writeString(bundleDir.resolve("ranger-snapshot.json"), bundle.rangerSnapshotJson());

        // lf-actual.json
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(bundleDir.resolve("lf-actual.json").toFile(), bundle.lfActual());

        // lf-expected.json
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(bundleDir.resolve("lf-expected.json").toFile(), bundle.lfExpected());

        // diff.json — structured diff
        var diff = new java.util.LinkedHashMap<String, Object>();
        diff.put("overGrants", bundle.validationResult().overGrants());
        diff.put("underGrants", bundle.validationResult().underGrants());
        diff.put("description", bundle.validationResult().description());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(bundleDir.resolve("diff.json").toFile(), diff);

        // cycle-sequence.json
        var cycleSeq = new java.util.LinkedHashMap<String, Object>();
        cycleSeq.put("violationDetectedAfterCycle", bundle.violationDetectedAfterCycle());
        cycleSeq.put("lastSuccessfulCycle", bundle.lastSuccessfulCycle());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(bundleDir.resolve("cycle-sequence.json").toFile(), cycleSeq);

        // README.txt
        String readme = buildReadme(bundle, bundleDir);
        Files.writeString(bundleDir.resolve("README.txt"), readme);

        LOG.info("Reproduction bundle written to: {}", bundleDir);
        return bundleDir;
    }

    private String buildReadme(ReproductionBundle bundle, Path bundleDir) {
        return "REPRODUCTION BUNDLE\n" +
               "===================\n" +
               "Detected at: " + bundle.detectedAt() + "\n" +
               "Violation detected after cycle: " + bundle.violationDetectedAfterCycle() + "\n" +
               "Last successful cycle: " + bundle.lastSuccessfulCycle() + "\n\n" +
               "Outcome: " + bundle.validationResult().outcome() + "\n" +
               "Description: " + bundle.validationResult().description() + "\n\n" +
               "Files in this bundle:\n" +
               "  mutations.json         — all mutations applied up to this point\n" +
               "  ranger-snapshot.json   — Ranger policies at time of violation\n" +
               "  lf-actual.json         — actual LF permissions at violation time\n" +
               "  lf-expected.json       — expected LF permissions (computed independently)\n" +
               "  diff.json              — structured diff (over-grants, under-grants)\n" +
               "  cycle-sequence.json    — cycle numbers for reproduction\n\n" +
               "To reproduce:\n" +
               "  1. Restore Ranger state from ranger-snapshot.json\n" +
               "  2. Run a manual sync cycle\n" +
               "  3. Run ListPermissions and compare against lf-expected.json\n";
    }
}
