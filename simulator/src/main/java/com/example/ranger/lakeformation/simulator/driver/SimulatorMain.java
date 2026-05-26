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
        LakeFormationClient lfClient = LakeFormationClient.builder()
                .region(Region.of(config.getAwsRegion()))
                .build();
        // S3ControlClient requires account ID endpoint
        S3ControlClient s3ControlClient = S3ControlClient.builder()
                .region(Region.of(config.getAwsRegion()))
                .build();

        // Wire simulator components
        RangerPolicyClient rangerClient = new RangerPolicyClient(
                config.getRangerAdminUrl(), config.getRangerAdminUser(), config.getRangerAdminPassword());

        Path logPath = Paths.get(config.getReproductionBundleDir()).resolve("mutation.log");
        Files.createDirectories(logPath.getParent());
        MutationLog mutationLog = new MutationLog(logPath);

        WorkloadOrchestrator orchestrator = new WorkloadOrchestrator(
                config.getPrincipalPool(), new ArrayList<>(), new Random());

        MutationDriver mutationDriver = new MutationDriver(rangerClient, mutationLog);

        SyncServiceStatusClient statusClient = new SyncServiceStatusClient(
                config.getStatusHost(), config.getStatusPort());
        CycleWaiter cycleWaiter = new CycleWaiter(statusClient,
                Duration.ofSeconds(config.getCycleWaitTimeoutSeconds()));
        RemediationRunner remediationRunner = new RemediationRunner(cycleWaiter);

        // Principal map — in a real deployment loaded from the sync service's principal mapping config.
        // Here: identity mapping using config.getPrincipalPool() values as both key and value.
        Map<String, String> principalMap = new LinkedHashMap<>();
        for (String arn : config.getPrincipalPool()) {
            principalMap.put(arn, arn);
        }

        // Determine S3 Access Grants instance ARN from environment (optional)
        String s3AgInstanceArn = System.getenv("S3AG_INSTANCE_ARN");
        String accountId = System.getenv("AWS_ACCOUNT_ID");
        if (accountId == null) accountId = "unknown";

        LFPermissionsFetcher lfFetcher = new LFPermissionsFetcher(lfClient, accountId);
        S3AgPermissionsFetcher s3AgFetcher = new S3AgPermissionsFetcher(s3ControlClient, accountId,
                s3AgInstanceArn != null ? s3AgInstanceArn : "");

        ExpectedPermissionsComputer expectedComputer = new ExpectedPermissionsComputer(
                principalMap, (db, pattern) -> List.of(pattern)); // no Glue expansion in simulator loop

        Phase1DriftValidator phase1 = new Phase1DriftValidator();
        Phase2CorrectnessValidator phase2 = new Phase2CorrectnessValidator(expectedComputer);

        BundleWriter bundleWriter = new BundleWriter(Paths.get(config.getReproductionBundleDir()));
        AlertEmitter alertEmitter = new AlertEmitter.LogAlertEmitter();

        long cycleNumber = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                runOneCycle(cycleNumber, config, rangerClient, mutationLog, orchestrator, mutationDriver,
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

    private void runOneCycle(long cycleNumber, SimulatorConfig config,
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

        // Step 4: Fetch actual permissions
        Set<SimulatorPermission> lfActual = new HashSet<>();
        lfActual.addAll(lfFetcher.fetchAll());
        lfActual.addAll(s3AgFetcher.fetchAll());

        // Step 5: Phase 1 drift check
        ValidationResult phase1Result = phase1.validate(lfActual);
        if (phase1Result.isViolation()) {
            LOG.warn("Phase1 drift detected after cycle {}", syncCycleAfter);
        }

        // Step 6: Fetch Ranger policies and Phase 2 correctness check
        var rangerPolicies = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        try {
            var policiesNode = rangerClient.listPolicies(config.getRangerAdminUrl());
            if (policiesNode.isArray()) {
                policiesNode.forEach(rangerPolicies::add);
            }
        } catch (Exception e) {
            LOG.warn("Failed to fetch Ranger policies for Phase2: {}", e.getMessage());
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
                new ExpectedPermissionsComputer(Map.of(), (d, p) -> List.of()).compute(rangerPolicies),
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
