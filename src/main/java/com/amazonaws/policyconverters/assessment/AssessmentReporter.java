package com.amazonaws.policyconverters.assessment;

import com.amazonaws.policyconverters.model.GapEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Formats and outputs an {@link AssessmentResult} to console and optionally
 * writes a machine-readable JSON report file.
 */
public class AssessmentReporter {

    private static final Logger LOG = LoggerFactory.getLogger(AssessmentReporter.class);

    private static final DateTimeFormatter FILE_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private static final Map<GapEntry.GapType, String> GAP_EXPLANATIONS;

    static {
        GAP_EXPLANATIONS = new EnumMap<>(GapEntry.GapType.class);
        GAP_EXPLANATIONS.put(GapEntry.GapType.DATA_MASKING,
                "LF has no column masking. Consider removing or migrating to row-level filters.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.TAG_BASED_POLICY,
                "Tag-based policies cannot be expressed as LF resource permissions.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.DENY_POLICY,
                "Deny rules are emitted as Cedar forbid statements; review carefully before applying.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.DENY_EXCEPTION,
                "Deny exceptions are emitted as Cedar deny-exception permits; verify intended access.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.VALIDITY_SCHEDULE,
                "Time-bound access control is not supported in LF permissions.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.CUSTOM_CONDITION,
                "Attribute-based conditions cannot be expressed as LF permissions.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.SECURITY_ZONE,
                "Ranger Security Zones have no equivalent in Lake Formation.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.DELEGATED_ADMIN,
                "Delegated admin (grantable) is partially supported; verify downstream grant behavior.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.WILDCARD_PATTERN,
                "Wildcard patterns were not expanded (no AWS credentials); resources treated as-is.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.UNSUPPORTED_SERVICE_TYPE,
                "No adapter registered for this Ranger service type; policies will be skipped.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.UNSUPPORTED_ACTION,
                "One or more Ranger access types have no LF permission equivalent and will be dropped.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.UNMAPPED_RESOURCE,
                "Resource paths cannot be mapped to LF data locations (HDFS/file:// not supported; see details below).");
        GAP_EXPLANATIONS.put(GapEntry.GapType.SCHEMA_VALIDATION_FAILURE,
                "A Cedar statement failed schema validation and was excluded from conversion.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.UNREGISTERED_S3_LOCATION,
                "S3 Access Grants location not registered for this prefix. Register the location in your S3 Access Grants instance before migrating (s3control:CreateAccessGrantsLocation).");
        GAP_EXPLANATIONS.put(GapEntry.GapType.CANNOT_VALIDATE_S3_LOCATION,
                "No S3 Access Grants configuration provided. Cannot validate whether S3 locations are registered. Add s3AccessGrants config to enable validation.");
        GAP_EXPLANATIONS.put(GapEntry.GapType.EXCLUDES_PATTERN,
                "isExcludes=true (\"all except\") patterns have no Lake Formation equivalent; these policies are skipped.");
    }

    private final ObjectMapper objectMapper;

    public AssessmentReporter() {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Prints a human-readable summary to {@code out} and, unless
     * {@code config.isConsoleOnly()}, writes a JSON report file to
     * {@code config.getOutputDir()}.
     *
     * @param result the assessment result to report
     * @param config the assessment config (for output directory and console-only flag)
     * @param out    the stream to print the console report to (typically {@code System.out})
     */
    public void report(AssessmentResult result, AssessmentConfig config, PrintStream out) {
        printConsoleReport(result, out);

        if (!config.isConsoleOnly()) {
            try {
                Path reportFile = writeJsonReport(result, config.getOutputDir());
                out.println("Full report written to: " + reportFile.toAbsolutePath());
            } catch (IOException e) {
                LOG.error("Failed to write assessment JSON report: {}", e.getMessage(), e);
                out.println("WARNING: could not write JSON report — " + e.getMessage());
            }
        }

        if (config.getLfPoliciesOutputPath() != null) {
            try {
                writeLfPoliciesFile(result, config.getLfPoliciesOutputPath());
                out.println("LF permission operations written to: "
                        + config.getLfPoliciesOutputPath().toAbsolutePath());
            } catch (IOException e) {
                LOG.error("Failed to write LF policies file: {}", e.getMessage(), e);
                out.println("WARNING: could not write LF policies file — " + e.getMessage());
            }
        }

        if (config.getGapsOutputPath() != null) {
            try {
                writeGapsFile(result, config.getGapsOutputPath());
                out.println("Gap report written to: "
                        + config.getGapsOutputPath().toAbsolutePath());
            } catch (IOException e) {
                LOG.error("Failed to write gaps file: {}", e.getMessage(), e);
                out.println("WARNING: could not write gaps file — " + e.getMessage());
            }
        }
    }

    private void printConsoleReport(AssessmentResult result, PrintStream out) {
        int total = result.getTotalPolicies();
        Map<GapEntry.GapType, Integer> summary = result.getGapReport().getSummary();
        int totalGaps = summary.values().stream().mapToInt(Integer::intValue).sum();

        // Warning banner — printed before the report header
        for (String warning : result.getWarnings()) {
            out.println("⚠  " + warning);
            out.println();
        }

        out.println();
        out.println("=== Apache Ranger → Lake Formation Assessment ===");
        if (result.getSource() != null) {
            out.println("Source:       " + result.getSource());
        }
        out.println("Assessed at:  " + result.getGapReport().getGeneratedAt());
        out.println();

        if (!result.getServices().isEmpty()) {
            out.println("Services assessed:");
            for (AssessedService svc : result.getServices()) {
                if (svc.isSkipped()) {
                    out.printf("  %-20s (%-16s) — skipped: %s%n",
                            svc.getName(), svc.getServiceType(), svc.getSkipReason());
                } else {
                    out.printf("  %-20s (%-16s) — assessed  (%d policies)%n",
                            svc.getName(), svc.getServiceType(), svc.getPoliciesScanned());
                }
            }
            out.println();
        }
        out.printf("Policies scanned:        %5d%n", total);
        out.printf("  Fully convertible:     %5d (%s)%n",
                result.getFullyConvertible(), pct(result.getFullyConvertible(), total));
        out.printf("  Partially convertible: %5d (%s)%n",
                result.getPartiallyConvertible(), pct(result.getPartiallyConvertible(), total));
        out.printf("  Not convertible:       %5d (%s)%n",
                result.getNotConvertible(), pct(result.getNotConvertible(), total));
        out.printf("Projected LF grants:     %5d%n", result.getProjectedGrantCount());
        out.println();

        if (totalGaps == 0) {
            out.println("No gaps detected. All policies should convert cleanly.");
        } else {
            out.printf("Gaps detected (%d total):%n", totalGaps);
            for (GapEntry.GapType gapType : GapEntry.GapType.values()) {
                int count = summary.getOrDefault(gapType, 0);
                if (count > 0) {
                    String explanation = GAP_EXPLANATIONS.getOrDefault(gapType, "");
                    out.printf("  %-28s: %3d  — %s%n", gapType.name(), count, explanation);
                }
            }

            // List unmapped resource paths so operators can decide what to do with them
            List<GapEntry> unmappedEntries = result.getGapReport().getEntries().stream()
                    .filter(e -> e.getGapType() == GapEntry.GapType.UNMAPPED_RESOURCE)
                    .collect(Collectors.toList());
            if (!unmappedEntries.isEmpty()) {
                out.println();
                out.printf("Unmapped resource paths (%d) — convert to S3 URIs or ignore:%n",
                        unmappedEntries.size());
                for (GapEntry e : unmappedEntries) {
                    String path = e.getResourcePath() != null ? e.getResourcePath() : "(unknown)";
                    String policy = e.getPolicyId() != null ? e.getPolicyId() : "?";
                    out.printf("  [%s] %s%n", policy, path);
                }
            }

            // Per-policy breakdown grouped by policy
            Map<String, List<GapEntry>> byPolicy = new LinkedHashMap<>();
            for (GapEntry entry : result.getGapReport().getEntries()) {
                String key = entry.getPolicyId() != null ? entry.getPolicyId() : "<unknown>";
                byPolicy.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }

            out.println();
            out.printf("Policies with gaps (%d):%n", byPolicy.size());
            for (List<GapEntry> entries : byPolicy.values()) {
                GapEntry first = entries.get(0);
                String label = first.getPolicyName() != null
                        ? first.getPolicyName() + " (id=" + first.getPolicyId() + ")"
                        : "id=" + first.getPolicyId();
                out.println("  Policy: " + label);
                if (first.getResourcePath() != null) {
                    out.println("    Resource: " + first.getResourcePath());
                }
                for (GapEntry entry : entries) {
                    out.println("    [" + entry.getGapType().name() + "] " + entry.getDetails());
                    out.println("      → " + entry.getRecommendation());
                }
            }
        }
        out.println();
    }

    private void writeGapsFile(AssessmentResult result, Path outputPath) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        objectMapper.writeValue(outputPath.toFile(), result.getPolicyGapSummaries());
        LOG.info("Gap report written to {}", outputPath.toAbsolutePath());
    }

    private void writeLfPoliciesFile(AssessmentResult result, Path outputPath) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        objectMapper.writeValue(outputPath.toFile(), result.getProjectedOperations());
        LOG.info("LF permission operations written to {}", outputPath.toAbsolutePath());
    }

    private Path writeJsonReport(AssessmentResult result, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String timestamp = FILE_TIMESTAMP_FMT.format(Instant.now());
        Path reportFile = outputDir.resolve("assessment-report-" + timestamp + ".json");
        objectMapper.writeValue(reportFile.toFile(), result);
        LOG.info("Assessment JSON report written to {}", reportFile.toAbsolutePath());
        return reportFile;
    }

    private static String pct(int part, int total) {
        if (total == 0) return "0%";
        return String.format("%d%%", Math.round(100.0 * part / total));
    }
}
