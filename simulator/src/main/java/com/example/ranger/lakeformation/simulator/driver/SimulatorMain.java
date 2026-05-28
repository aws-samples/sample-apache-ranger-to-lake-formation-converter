package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.alert.AlertEmitter;
import com.example.ranger.lakeformation.simulator.remediation.*;
import com.example.ranger.lakeformation.simulator.status.*;
import com.example.ranger.lakeformation.simulator.validator.*;
import com.example.ranger.lakeformation.simulator.workload.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.lakeformation.LakeFormationClient;
import software.amazon.awssdk.services.s3control.S3ControlClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Simulator entry point.
 *
 * Usage: SimulatorMain &lt;config.json&gt;
 *
 * The config file is a JSON file matching SimulatorConfig fields.
 * All AWS credentials come from the standard DefaultCredentialsProvider chain.
 */
public class SimulatorMain {
    private static final Logger LOG = LoggerFactory.getLogger(SimulatorMain.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            LOG.error("Usage: SimulatorMain <config.json>");
            System.exit(1);
        }
        SimulatorConfig config = new ObjectMapper().readValue(Paths.get(args[0]).toFile(), SimulatorConfig.class);
        LOG.info("Starting simulator with config: {}", config);
        new SimulatorMain().run(config);
    }

    public void run(SimulatorConfig config) throws Exception {
        // Wire AWS clients
        Region region = Region.of(config.getAwsRegion());
        LakeFormationClient lfClient = LakeFormationClient.builder().region(region).build();
        S3ControlClient s3ControlClient = S3ControlClient.builder().region(region).build();
        GlueClient glueClient = GlueClient.builder().region(region).build();

        // Wire simulator components
        RangerPolicyClient rangerClient = new RangerPolicyClient(
                config.getRangerAdminUrl(), config.getRangerAdminUser(), config.getRangerAdminPassword());

        Path logPath = Paths.get(config.getReproductionBundleDir()).resolve("mutation.log");
        Files.createDirectories(logPath.getParent());
        MutationLog mutationLog = new MutationLog(logPath);

        // Single source of truth: config-provided map or Glue discovery
        Map<String, List<String>> databaseTables = config.getDatabases() != null
                ? config.getDatabases()
                : new GlueCatalogDiscovery(glueClient).discover();
        if (databaseTables.isEmpty()) {
            LOG.warn("No databases found — check Glue catalog access and region ({})", config.getAwsRegion());
        }

        List<String> principals = config.getPrincipalPool().isEmpty()
                ? new ArrayList<>(config.getPrincipalMappings().keySet())
                : config.getPrincipalPool();

        // rng is used only on the main simulator loop thread — generators and orchestrator do not share threads.
        Random rng = new Random();

        HivePolicyGenerator hivePolicyGenerator = new HivePolicyGenerator(
                databaseTables, principals, config.getRangerServiceName(), rng);
        TrinoServiceGenerator trinoServiceGenerator = new TrinoServiceGenerator(
                databaseTables, principals, config.getTrinoServiceName(), rng);
        // Data-location policies are registered under the primary LF service name — same service as Hive.
        DataLocationPolicyGenerator dataLocationGenerator = new DataLocationPolicyGenerator(
                config.getS3Prefixes(), principals, config.getRangerServiceName(), rng);
        TagPolicyGenerator tagPolicyGenerator = new TagPolicyGenerator(
                List.of(), principals, config.getTagServiceName(), rng);
        EmrfsPolicyGenerator emrfsPolicyGenerator = new EmrfsPolicyGenerator(
                config.getS3Prefixes(), principals, config.getEmrfsServiceName(), rng);

        List<GeneratorEntry> generators = List.of(
            new GeneratorEntry("hive",         hivePolicyGenerator::generateTablePolicy, 45),
            new GeneratorEntry("trino",        trinoServiceGenerator::generate,          25),
            new GeneratorEntry("datalocation", dataLocationGenerator::generate,          15),
            new GeneratorEntry("tag",          tagPolicyGenerator::generate,             10),
            new GeneratorEntry("emrfs",        emrfsPolicyGenerator::generate,            5)
        );
        WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(
                new ArrayList<>(), generators, rng);

        MutationDriver mutationDriver = new MutationDriver(rangerClient, mutationLog);

        SyncServiceStatusClient statusClient = new SyncServiceStatusClient(
                config.getStatusHost(), config.getStatusPort());
        CycleWaiter cycleWaiter = new CycleWaiter(statusClient,
                Duration.ofSeconds(config.getCycleWaitTimeoutSeconds()));
        RemediationRunner remediationRunner = new RemediationRunner(cycleWaiter);

        // Principal map: Ranger name → IAM ARN (from config)
        Map<String, String> principalMap = new LinkedHashMap<>(config.getPrincipalMappings());

        // S3 Access Grants instance ARN and account ID
        String s3AgInstanceArn = System.getenv("S3AG_INSTANCE_ARN");
        String accountId = config.getAwsAccountId();

        LFPermissionsFetcher lfFetcher = new LFPermissionsFetcher(lfClient, accountId);
        S3AgPermissionsFetcher s3AgFetcher = new S3AgPermissionsFetcher(s3ControlClient, accountId,
                s3AgInstanceArn != null ? s3AgInstanceArn : "");

        // Use discovered/configured resource map for wildcard expansion in the independent validator
        ExpectedPermissionsComputer expectedComputer = new ExpectedPermissionsComputer(
                principalMap, (db, pattern) -> {
                    List<String> tables = databaseTables.getOrDefault(db, List.of());
                    String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                    return tables.stream().filter(t -> t.matches(regex)).toList();
                });

        Phase1DriftValidator phase1 = new Phase1DriftValidator();
        Set<String> managedArns = new HashSet<>(principalMap.values());
        Phase2CorrectnessValidator phase2 = new Phase2CorrectnessValidator(expectedComputer, managedArns);

        BundleWriter bundleWriter = new BundleWriter(Paths.get(config.getReproductionBundleDir()));
        AlertEmitter alertEmitter = new AlertEmitter.LogAlertEmitter();

        List<String> allServiceNames = buildAllServiceNames(config);

        long cycleNumber = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                runOneCycle(cycleNumber, config, allServiceNames, rangerClient, mutationLog, orchestrator, mutationDriver,
                        statusClient, cycleWaiter, remediationRunner, lfFetcher, s3AgFetcher,
                        expectedComputer, phase1, phase2, bundleWriter, alertEmitter);
                cycleNumber++;
                Thread.sleep(Duration.ofSeconds(config.getCycleIntervalSeconds()).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Simulator interrupted, shutting down");
            }
        }
    }

    private static List<String> buildAllServiceNames(SimulatorConfig config) {
        // All four service names have non-null defaults in SimulatorConfig.
        List<String> names = new ArrayList<>();
        names.add(config.getRangerServiceName());
        names.add(config.getTrinoServiceName());
        names.add(config.getEmrfsServiceName());
        names.add(config.getTagServiceName());
        return names;
    }

    @SuppressWarnings("java:S107")
    private void runOneCycle(long cycleNumber, SimulatorConfig config,
                              List<String> allServiceNames,
                              RangerPolicyClient rangerClient, MutationLog mutationLog,
                              WorkloadOrchestrator orchestrator, MutationDriver mutationDriver,
                              SyncServiceStatusClient statusClient, CycleWaiter cycleWaiter,
                              RemediationRunner remediationRunner,
                              LFPermissionsFetcher lfFetcher, S3AgPermissionsFetcher s3AgFetcher,
                              ExpectedPermissionsComputer expectedComputer,
                              Phase1DriftValidator phase1, Phase2CorrectnessValidator phase2,
                              BundleWriter bundleWriter, AlertEmitter alertEmitter) throws Exception {
        LOG.info("=== Simulator cycle {} ===", cycleNumber);

        // Step 1: Record current sync cycle number
        long syncCycleBefore = statusClient.fetchStatus().lastCompletedCycle();

        // Step 2: Generate and apply mutations
        List<MutationOperation> batch = orchestrator.generateBatch();
        LOG.info("Applying batch of {} mutations", batch.size());
        mutationDriver.applyBatch(batch);

        // Step 3: Wait for sync to complete
        long syncCycleAfter;
        try {
            syncCycleAfter = cycleWaiter.waitForCycleAfter(syncCycleBefore);
        } catch (CycleTimeoutException e) {
            LOG.error("Sync cycle timeout: {}", e.getMessage());
            return;
        }

        // Step 4: Fetch actual permissions (filtered to managed principals only)
        Set<SimulatorPermission> lfActual = new HashSet<>();
        lfActual.addAll(lfFetcher.fetchAll());
        lfActual.addAll(s3AgFetcher.fetchAll());
        Set<String> managedArns = new HashSet<>(config.getPrincipalMappings().values());
        if (!managedArns.isEmpty()) {
            lfActual.removeIf(p -> !managedArns.contains(p.principalArn()));
        }

        // Step 5: Phase 1 drift check
        ValidationResult phase1Result = phase1.validate(lfActual);
        if (phase1Result.isViolation()) {
            LOG.warn("Phase1 drift detected after cycle {}", syncCycleAfter);
        }

        // Step 6: Fetch Ranger policies and Phase 2 correctness check
        var rangerPolicies = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String svcName : allServiceNames) {
            try {
                var node = rangerClient.listPolicies(svcName);
                if (node.isArray()) node.forEach(rangerPolicies::add);
            } catch (Exception e) {
                LOG.warn("Failed to fetch Ranger policies for service {}: {}", svcName, e.getMessage());
            }
        }

        ValidationResult phase2Result = phase2.validate(lfActual, rangerPolicies);

        if (!phase2Result.isViolation()) {
            phase1.updateCheckpoint(lfActual);
            LOG.info("Cycle {} complete — all permissions correct", cycleNumber);
            return;
        }

        // Step 7: Violation detected — write bundle and attempt remediation
        String rangerSnapshotJson = rangerPolicies.isEmpty() ? "[]" : rangerPolicies.toString();
        ReproductionBundle bundle = new ReproductionBundle(
                Instant.now(), syncCycleAfter, cycleNumber - 1 >= 0 ? cycleNumber - 1 : 0,
                mutationLog.getEntries(), rangerSnapshotJson, lfActual,
                expectedComputer.compute(rangerPolicies),
                phase2Result);

        bundleWriter.write(bundle);
        LOG.warn("Violation bundle written for cycle {}", syncCycleAfter);

        // Wait for next cycle (remediation)
        long remediationCycle;
        try {
            remediationCycle = remediationRunner.waitForRemediation(syncCycleAfter);
        } catch (CycleTimeoutException e) {
            LOG.error("Remediation timeout: {}", e.getMessage());
            alertEmitter.emit(new ValidationResult(ValidationResult.Outcome.PERSISTENT_VIOLATION,
                    phase2Result.overGrants(), phase2Result.underGrants(),
                    "Remediation timeout: " + e.getMessage()), bundle);
            return;
        }

        // Re-validate after remediation
        Set<SimulatorPermission> lfAfterRemediation = new HashSet<>();
        lfAfterRemediation.addAll(lfFetcher.fetchAll());
        lfAfterRemediation.addAll(s3AgFetcher.fetchAll());
        ValidationResult recheck = phase2.validate(lfAfterRemediation, rangerPolicies);

        if (!recheck.isViolation()) {
            alertEmitter.emit(new ValidationResult(ValidationResult.Outcome.TRANSIENT_VIOLATION,
                    phase2Result.overGrants(), phase2Result.underGrants(),
                    "Self-healed after remediation cycle " + remediationCycle), bundle);
            phase1.updateCheckpoint(lfAfterRemediation);
            LOG.info("Transient violation self-healed after cycle {}", remediationCycle);
        } else {
            alertEmitter.emit(new ValidationResult(ValidationResult.Outcome.PERSISTENT_VIOLATION,
                    recheck.overGrants(), recheck.underGrants(),
                    "Persistent violation after remediation cycle " + remediationCycle), bundle);
            LOG.error("PERSISTENT VIOLATION detected after remediation cycle {}", remediationCycle);
        }
    }
}
