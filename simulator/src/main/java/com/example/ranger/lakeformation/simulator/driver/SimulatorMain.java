package com.example.ranger.lakeformation.simulator.driver;

import com.example.ranger.lakeformation.simulator.alert.AlertEmitter;
import com.example.ranger.lakeformation.simulator.remediation.*;
import com.example.ranger.lakeformation.simulator.status.*;
import com.example.ranger.lakeformation.simulator.validator.*;
import com.example.ranger.lakeformation.simulator.workload.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.lakeformation.LakeFormationClient;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.GetAccessGrantsInstanceRequest;
import software.amazon.awssdk.services.s3control.model.S3ControlException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

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
        AwsCredentialsProvider credentialsProvider = buildCredentialsProvider(config, region);
        LakeFormationClient lfClient = LakeFormationClient.builder()
                .region(region).credentialsProvider(credentialsProvider).build();
        S3ControlClient s3ControlClient = S3ControlClient.builder()
                .region(region).credentialsProvider(credentialsProvider).build();
        GlueClient glueClient = GlueClient.builder()
                .region(region).credentialsProvider(credentialsProvider).build();

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

        // LakeFormation-vocabulary policies target the lakeformation service directly.
        LakeFormationPolicyGenerator lfPolicyGenerator = new LakeFormationPolicyGenerator(
                databaseTables, principals, config.getRangerServiceName(), rng);
        // Hive-vocabulary policies target the dedicated hive service; the HiveServiceAdapter
        // maps the Hive access-type vocabulary to LF actions.
        HivePolicyGenerator hivePolicyGenerator = new HivePolicyGenerator(
                databaseTables, principals, config.getHiveServiceName(), rng);
        TrinoServiceGenerator trinoServiceGenerator = new TrinoServiceGenerator(
                databaseTables, principals, config.getTrinoServiceName(), rng);
        // Data-location policies are registered under the primary LF service name.
        DataLocationPolicyGenerator dataLocationGenerator = new DataLocationPolicyGenerator(
                config.getS3Prefixes(), principals, config.getRangerServiceName(), rng);
        TagPolicyGenerator tagPolicyGenerator = new TagPolicyGenerator(
                List.of(), principals, config.getTagServiceName(), rng);
        EmrfsPolicyGenerator emrfsPolicyGenerator = new EmrfsPolicyGenerator(
                config.getS3Prefixes(), principals, config.getEmrfsServiceName(), rng);
        EmrSparkPolicyGenerator emrSparkPolicyGenerator = new EmrSparkPolicyGenerator(
                databaseTables, principals, config.getEmrSparkServiceName(), rng);

        // Each generator targets a dedicated Ranger service so the full Ranger -> Cedar -> LF
        // translation path is exercised per source service type:
        //   lf-*      -> lakeformation service (LF access-type vocabulary)
        //   hive-*    -> hive service          (Hive vocabulary; HiveServiceAdapter maps to LF)
        //   trino     -> trino service
        //   emrfs     -> amazon-emr-emrfs service
        //   emrspark-*-> amazon-emr-spark service
        //   datalocation/tag -> lakeformation / tag services
        //
        // "hive-all"/lf-all omitted: the lakeformation Ranger service rejects "all" as an access type.
        List<GeneratorEntry> generators = List.of(
            // LakeFormation-vocabulary policies (target the lakeformation service directly)
            new GeneratorEntry("lf",              lfPolicyGenerator::generateTablePolicy,              15),
            new GeneratorEntry("lf-multi",        lfPolicyGenerator::generateMultiUserTablePolicy,      6),
            new GeneratorEntry("lf-db",           lfPolicyGenerator::generateDatabasePolicy,            3),
            new GeneratorEntry("lf-col",          lfPolicyGenerator::generateColumnPolicy,              3),
            new GeneratorEntry("lf-unmapped",     lfPolicyGenerator::generateUnmappedPrincipalPolicy,   2),
            new GeneratorEntry("lf-grantable",    lfPolicyGenerator::generateGrantableTablePolicy,      2),
            new GeneratorEntry("lf-deny",         lfPolicyGenerator::generateDenyTablePolicy,           3),
            // Hive-vocabulary policies (target the dedicated hive service)
            new GeneratorEntry("hive",            hivePolicyGenerator::generateTablePolicy,            15),
            new GeneratorEntry("hive-multi",      hivePolicyGenerator::generateMultiUserTablePolicy,    6),
            new GeneratorEntry("hive-db",         hivePolicyGenerator::generateDatabasePolicy,          3),
            new GeneratorEntry("hive-col",        hivePolicyGenerator::generateColumnPolicy,            3),
            new GeneratorEntry("hive-unmapped",   hivePolicyGenerator::generateUnmappedPrincipalPolicy, 2),
            new GeneratorEntry("hive-grantable",  hivePolicyGenerator::generateGrantableTablePolicy,    2),
            new GeneratorEntry("hive-wildcard",   hivePolicyGenerator::generateWildcardTablePolicy,     3),
            new GeneratorEntry("hive-deny",       hivePolicyGenerator::generateDenyTablePolicy,         3),
            new GeneratorEntry("hive-group",      hivePolicyGenerator::generateGroupTablePolicy,        1),
            new GeneratorEntry("hive-role",       hivePolicyGenerator::generateRoleTablePolicy,         1),
            // Other services
            new GeneratorEntry("trino",           trinoServiceGenerator::generate,                     12),
            new GeneratorEntry("datalocation",    dataLocationGenerator::generate,                      8),
            new GeneratorEntry("tag",             tagPolicyGenerator::generate,                          6),
            new GeneratorEntry("emrfs",           emrfsPolicyGenerator::generate,                        5),
            new GeneratorEntry("emrspark",        emrSparkPolicyGenerator::generateTablePolicy,          8),
            new GeneratorEntry("emrspark-db",     emrSparkPolicyGenerator::generateDatabasePolicy,       3),
            new GeneratorEntry("emrspark-col",    emrSparkPolicyGenerator::generateColumnPolicy,         2),
            new GeneratorEntry("emrspark-deny",   emrSparkPolicyGenerator::generateDenyTablePolicy,      2)
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

        // S3 Access Grants instance ARN — priority: config field → env var → auto-detect
        String accountId = config.getAwsAccountId();
        String s3AgInstanceArn = resolveS3AgInstanceArn(config, s3ControlClient, accountId);

        LFPermissionsFetcher lfFetcher = new LFPermissionsFetcher(lfClient, accountId);
        S3AgPermissionsFetcher s3AgFetcher = new S3AgPermissionsFetcher(s3ControlClient, accountId,
                s3AgInstanceArn != null ? s3AgInstanceArn : "");

        // Scope the validator to only the services the sync service actually processes.
        // EMR Spark is only included when validateEmrSpark=true in the config (requires the
        // sync service to have amazon-emr-spark in its rangerServices list).
        Set<String> managedServiceNames;
        if (config.isValidateEmrSpark()) {
            managedServiceNames = Set.of(
                    config.getRangerServiceName().toLowerCase(java.util.Locale.ROOT),
                    config.getEmrSparkServiceName().toLowerCase(java.util.Locale.ROOT));
        } else {
            managedServiceNames = Set.of(
                    config.getRangerServiceName().toLowerCase(java.util.Locale.ROOT));
        }

        // Use discovered/configured resource map for wildcard expansion in the independent validator
        ExpectedPermissionsComputer expectedComputer = new ExpectedPermissionsComputer(
                principalMap, (db, pattern) -> {
                    List<String> tables = databaseTables.getOrDefault(db, List.of());
                    String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                    return tables.stream().filter(t -> t.matches(regex)).toList();
                }, managedServiceNames);

        Phase1DriftValidator phase1 = new Phase1DriftValidator();
        Set<String> managedArns = new HashSet<>(principalMap.values());
        Phase2CorrectnessValidator phase2 = new Phase2CorrectnessValidator(expectedComputer, managedArns);

        BundleWriter bundleWriter = new BundleWriter(Paths.get(config.getReproductionBundleDir()));
        AlertEmitter alertEmitter = new AlertEmitter.LogAlertEmitter();

        // Optional human-readable round-by-round report (mutations, derived LF grants/revokes,
        // full LF state per cycle). Enabled only when roundReportPath is set in the config.
        RoundReporter roundReporter = config.getRoundReportPath() != null
                ? new RoundReporter(Paths.get(config.getRoundReportPath()))
                : null;
        if (roundReporter != null) {
            LOG.info("Round-by-round report enabled: {}", roundReporter.getReportPath());
        }
        // LF snapshot from the end of the previous cycle, used to derive this cycle's grants/revokes.
        // Starts empty (round 0 baseline is a clean account).
        Set<SimulatorPermission> previousLfState = new HashSet<>();

        List<String> allServiceNames = buildAllServiceNames(config);

        long cycleNumber = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                previousLfState = runOneCycle(cycleNumber, config, allServiceNames, rangerClient, mutationLog, orchestrator, mutationDriver,
                        statusClient, cycleWaiter, remediationRunner, lfFetcher, s3AgFetcher,
                        expectedComputer, phase1, phase2, bundleWriter, alertEmitter,
                        roundReporter, previousLfState);
                cycleNumber++;
                Thread.sleep(Duration.ofSeconds(config.getCycleIntervalSeconds()).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Simulator interrupted, shutting down");
            } catch (PersistentMutationFailureException e) {
                // A whole service's mutations are being rejected every time — fail loudly
                // instead of continuing to report "all permissions correct" for a service
                // whose policies never reach Ranger.
                alertEmitter.emit(new ValidationResult(ValidationResult.Outcome.PERSISTENT_VIOLATION,
                        java.util.Set.of(), java.util.Set.of(),
                        "Persistent mutation failures for service '" + e.getServiceName() + "' ("
                                + e.getConsecutiveFailures() + " consecutive). Aborting run: "
                                + e.getMessage()), null);
                LOG.error("Aborting simulator: {}", e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Resolves the S3 Access Grants instance ARN using priority order:
     * 1. Explicit value in simulator config (s3agInstanceArn field)
     * 2. S3AG_INSTANCE_ARN environment variable
     * 3. Auto-detection via GetAccessGrantsInstance API
     * Returns null if no instance exists or detection fails, which disables S3AG validation.
     */
    private static String resolveS3AgInstanceArn(SimulatorConfig config,
                                                  S3ControlClient s3ControlClient,
                                                  String accountId) {
        if (config.getS3agInstanceArn() != null) {
            LOG.info("S3 Access Grants instance ARN from config: {}", config.getS3agInstanceArn());
            return config.getS3agInstanceArn();
        }
        String fromEnv = System.getenv("S3AG_INSTANCE_ARN");
        if (fromEnv != null && !fromEnv.isBlank()) {
            LOG.info("S3 Access Grants instance ARN from env: {}", fromEnv);
            return fromEnv;
        }
        try {
            String arn = s3ControlClient.getAccessGrantsInstance(
                    GetAccessGrantsInstanceRequest.builder().accountId(accountId).build()
            ).accessGrantsInstanceArn();
            if (arn != null && !arn.isBlank()) {
                LOG.info("S3 Access Grants instance ARN auto-detected: {}", arn);
                return arn;
            }
        } catch (S3ControlException e) {
            if (e.statusCode() == 404) {
                LOG.info("No S3 Access Grants instance found in account {}; S3AG validation disabled", accountId);
            } else {
                LOG.warn("Could not auto-detect S3 Access Grants instance ARN (status={}): {}; S3AG validation disabled",
                        e.statusCode(), e.getMessage());
            }
        } catch (Exception e) {
            LOG.warn("Could not auto-detect S3 Access Grants instance ARN: {}; S3AG validation disabled", e.getMessage());
        }
        return null;
    }

    private static AwsCredentialsProvider buildCredentialsProvider(SimulatorConfig config, Region region) {
        if (config.getAwsProfile() != null) {
            LOG.info("Using AWS profile credentials: profile={}", config.getAwsProfile());
            return ProfileCredentialsProvider.create(config.getAwsProfile());
        }
        if (config.getRoleArn() != null) {
            LOG.info("Using STS AssumeRole credentials: roleArn={}", config.getRoleArn());
            StsClient stsClient = StsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .roleArn(config.getRoleArn())
                            .roleSessionName("ranger-lf-simulator")
                            .build())
                    .build();
        }
        LOG.info("Using default AWS credentials provider chain");
        return DefaultCredentialsProvider.create();
    }

    private static List<String> buildAllServiceNames(SimulatorConfig config) {
        // Every service the workload generates policies for must be listed here so the
        // Phase-2 correctness oracle fetches ALL Ranger policies when computing expected
        // LF permissions. Omitting a service makes its correctly-synced grants look like
        // over-grants (the oracle never sees the backing policy).
        List<String> names = new ArrayList<>();
        names.add(config.getRangerServiceName());
        names.add(config.getHiveServiceName());
        names.add(config.getTrinoServiceName());
        names.add(config.getEmrfsServiceName());
        names.add(config.getEmrSparkServiceName());
        names.add(config.getTagServiceName());
        return names;
    }

    /**
     * Run one simulator cycle and return the LF permission snapshot observed at the end of the
     * cycle (post-remediation if a violation occurred). The returned set becomes the next cycle's
     * "previous state" for deriving grants/revokes in the round report.
     */
    @SuppressWarnings("java:S107")
    private Set<SimulatorPermission> runOneCycle(long cycleNumber, SimulatorConfig config,
                              List<String> allServiceNames,
                              RangerPolicyClient rangerClient, MutationLog mutationLog,
                              WorkloadOrchestrator orchestrator, MutationDriver mutationDriver,
                              SyncServiceStatusClient statusClient, CycleWaiter cycleWaiter,
                              RemediationRunner remediationRunner,
                              LFPermissionsFetcher lfFetcher, S3AgPermissionsFetcher s3AgFetcher,
                              ExpectedPermissionsComputer expectedComputer,
                              Phase1DriftValidator phase1, Phase2CorrectnessValidator phase2,
                              BundleWriter bundleWriter, AlertEmitter alertEmitter,
                              RoundReporter roundReporter,
                              Set<SimulatorPermission> previousLfState) throws Exception {
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
            // No new LF snapshot observed — report the mutations against the unchanged state so
            // the round still appears in the report, then carry the previous state forward.
            // Ranger policies were not fetched this cycle; pass an empty list.
            writeRound(roundReporter, cycleNumber, batch, mutationDriver,
                    java.util.List.of(), previousLfState, previousLfState);
            return previousLfState;
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
            writeRound(roundReporter, cycleNumber, batch, mutationDriver, rangerPolicies, previousLfState, lfActual);
            return lfActual;
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
            // Report the round against the pre-remediation snapshot we did observe.
            writeRound(roundReporter, cycleNumber, batch, mutationDriver, rangerPolicies, previousLfState, lfActual);
            return lfActual;
        }

        // Re-validate after remediation
        Set<SimulatorPermission> lfAfterRemediation = new HashSet<>();
        lfAfterRemediation.addAll(lfFetcher.fetchAll());
        lfAfterRemediation.addAll(s3AgFetcher.fetchAll());
        if (!managedArns.isEmpty()) {
            lfAfterRemediation.removeIf(p -> !managedArns.contains(p.principalArn()));
        }
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
        // Report against the post-remediation snapshot — the true end-of-cycle LF state.
        writeRound(roundReporter, cycleNumber, batch, mutationDriver, rangerPolicies, previousLfState, lfAfterRemediation);
        return lfAfterRemediation;
    }

    /**
     * Emit one round to the report if reporting is enabled. Failures to write the report are
     * logged but never abort the simulator — the report is a diagnostic aid, not core function.
     *
     * @param rangerPolicies current Ranger policies across all services (for the Ranger State
     *                       section); may be empty if they could not be fetched this cycle.
     */
    private static void writeRound(RoundReporter reporter, long cycleNumber,
                                   List<MutationOperation> batch, MutationDriver driver,
                                   List<com.fasterxml.jackson.databind.JsonNode> rangerPolicies,
                                   Set<SimulatorPermission> previousState,
                                   Set<SimulatorPermission> currentState) {
        if (reporter == null) {
            return;
        }
        try {
            reporter.appendRound(cycleNumber, batch, driver, rangerPolicies, previousState, currentState);
        } catch (IOException e) {
            LOG.warn("Failed to write round report for cycle {}: {}", cycleNumber, e.getMessage());
        }
    }
}
