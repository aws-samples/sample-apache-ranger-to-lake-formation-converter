package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.amazonaws.policyconverters.sync.DriftDetector;
import com.amazonaws.policyconverters.sync.ReverseSyncService;
import com.amazonaws.policyconverters.lakeformation.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.LFPermissionFetcher;
import com.amazonaws.policyconverters.lakeformation.LiveGlueTableLister;
import com.amazonaws.policyconverters.lakeformation.TableLister;
import com.amazonaws.policyconverters.config.ConfigLoader;
import com.amazonaws.policyconverters.config.ConfigValidator;
import com.amazonaws.policyconverters.config.ReverseSyncConfig;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapperFactory;
import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;
import com.amazonaws.policyconverters.config.RetryConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
import com.amazonaws.policyconverters.sync.CheckpointStore;
import com.amazonaws.policyconverters.sync.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Ranger-LakeFormation sync service.
 * <p>
 * Loads configuration from an external YAML/properties file, validates it,
 * instantiates all dependencies, and starts the RangerPlugin and
 * SyncService to begin receiving and applying policy updates from Ranger Admin.
 * <p>
 * On first startup with an empty previous snapshot, the first policy refresh
 * effectively performs a bulk sync (all current policies are treated as new grants).
 * <p>
 * Usage: java -cp ... com.amazonaws.policyconverters.ranger.SyncServiceMain &lt;config-file-path&gt;
 */
public class SyncServiceMain {

    private static final Logger LOG = LoggerFactory.getLogger(SyncServiceMain.class);
    private static final String DEFAULT_DEAD_LETTER_PATH = "dead-letter.log";
    private static final CountDownLatch KEEP_ALIVE_LATCH = new CountDownLatch(1);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: SyncServiceMain <config-file-path>");
            System.exit(1);
        }

        String configFilePath = args[0];
        LOG.info("Starting Ranger-LakeFormation Sync Service with config: {}", configFilePath);

        try {
            SyncConfig config = loadAndValidateConfig(configFilePath);
            startSyncService(config);
        } catch (Exception e) {
            LOG.error("Fatal error starting Sync Service: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Load configuration from the given file path, apply environment variable
     * overrides, and validate all required parameters.
     *
     * @param configFilePath path to the YAML or properties configuration file
     * @return validated SyncConfig
     * @throws IOException              if the config file cannot be read
     * @throws IllegalStateException    if configuration validation fails
     */
    static SyncConfig loadAndValidateConfig(String configFilePath) throws IOException {
        return loadAndValidateConfig(configFilePath, new ConfigLoader());
    }

    static SyncConfig loadAndValidateConfig(String configFilePath, ConfigLoader configLoader) throws IOException {
        SyncConfig config = configLoader.load(configFilePath);

        // Log masked configuration for diagnostics
        configLoader.logConfig(config);

        // Validate configuration
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = validator.validate(config);
        if (!errors.isEmpty()) {
            for (String error : errors) {
                LOG.error("Configuration error: {}", error);
            }
            throw new IllegalStateException(
                    "Configuration validation failed with " + errors.size() + " error(s)");
        }

        LOG.info("Configuration loaded and validated successfully");
        return config;
    }

    /**
     * Instantiate all dependencies and start the sync service.
     * Registers a shutdown hook for graceful termination.
     *
     * @param config the validated sync configuration
     * @throws IOException if the dead-letter log file cannot be opened
     */
    static void startSyncService(SyncConfig config) throws IOException, InterruptedException {
        // Fail-fast: verify cedar-java native library can load before wiring anything
        verifyCedarNativeLibrary();

        AwsConfig awsConfig = config.getAwsConfig();
        Region region = Region.of(awsConfig.getRegion());
        AwsCredentialsProvider credentialsProvider = buildCredentialsProvider(awsConfig);

        // Build AWS clients
        GlueClient glueClient = GlueClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        software.amazon.awssdk.services.lakeformation.LakeFormationClient lfSdkClient =
                software.amazon.awssdk.services.lakeformation.LakeFormationClient.builder()
                        .region(region)
                        .credentialsProvider(credentialsProvider)
                        .build();

        // Build IdentitystoreClient only when needed
        final IdentitystoreClient identityStoreClient;
        PrincipalMappingConfig principalMappingConfig = config.getPrincipalMapping();
        if (principalMappingConfig != null
                && principalMappingConfig.getType() == PrincipalMapperType.IDENTITY_CENTER) {
            identityStoreClient = IdentitystoreClient.builder()
                    .region(Region.of(principalMappingConfig.getIdcConfig().getRegion()))
                    .credentialsProvider(credentialsProvider)
                    .build();
        } else {
            identityStoreClient = null;
        }
        if (principalMappingConfig == null) {
            principalMappingConfig = new PrincipalMappingConfig(null, null, null);
        }

        // SyncServiceMain has no CloudWatchClient or ServerConfig, so MetricsEmitter is null here.
        // Metrics will not be emitted from the principal mapper in this entry point.
        PrincipalMapper principalMapper = PrincipalMapperFactory.create(
                principalMappingConfig, identityStoreClient, null);
        CatalogResolver catalogResolver = new CatalogResolver(glueClient);
        GapReporter gapReporter = new GapReporter();

        // Wire Cedar components
        CedarSchemaProvider cedarSchemaProvider = new CedarSchemaProvider();

        AwsContext awsContext = new AwsContext(
                awsConfig.getRegion(),
                awsConfig.getCatalogId(),
                awsConfig.getCatalogId());

        // Build the list of Ranger services from configuration.
        // EmrfsRangerService is registered when the config contains an entry with
        // serviceType "amazon-emr-emrfs"; ConversionServerMain.createRangerService handles
        // instantiation for all supported service types including EmrfsRangerService.
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        List<BaseRangerService> rangerServiceList = new ArrayList<>();

        List<RangerServiceConfig> rangerServiceConfigs = config.getRangerServices();
        if (rangerServiceConfigs != null && !rangerServiceConfigs.isEmpty()) {
            for (RangerServiceConfig cfg : rangerServiceConfigs) {
                BaseRangerService service = ConversionServerMain.createRangerService(cfg);
                rangerServiceList.add(service);
                adapterRegistry.put(cfg.getServiceType(), service.createAdapter(awsContext));
            }
        } else {
            // Default: lakeformation service when no rangerServices list is configured
            RangerServiceAdapter lfAdapter = new RangerServiceAdapter(awsContext);
            adapterRegistry.put("lakeformation", lfAdapter);
        }

        Set<String> tagServiceNames = new HashSet<>();
        if (config.getTagSync() != null && config.getTagSync().getTagServiceName() != null) {
            tagServiceNames.add(config.getTagSync().getTagServiceName());
        }

        RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, catalogResolver, gapReporter, cedarSchemaProvider, tagServiceNames);

        CedarToLFConverter cedarToLFConverter = new CedarToLFConverter(
                cedarSchemaProvider, gapReporter, null);

        RetryConfig retryConfig = new RetryConfig(
                config.getMaxLfRetries(),
                config.getLfRetryBackoffMs(),
                2.0,
                30000L);
        LakeFormationClient lakeFormationClient;
        boolean dryRunEnabled = "true".equalsIgnoreCase(System.getenv("DRY_RUN_ENABLED"));
        if (dryRunEnabled) {
            String outputDir = System.getenv("DRY_RUN_OUTPUT_DIR");
            if (outputDir == null || outputDir.isBlank()) {
                outputDir = "./dry-run-output";
            }
            LOG.info("Dry-run mode enabled. Output directory: {}", outputDir);
            lakeFormationClient = new DryRunLakeFormationClient(
                    Path.of(outputDir), new ObjectMapper());
        } else {
            lakeFormationClient = new LakeFormationClient(lfSdkClient, retryConfig);
        }

        String deadLetterPath = config.getDeadLetterLogPath() != null
                ? config.getDeadLetterLogPath()
                : DEFAULT_DEAD_LETTER_PATH;
        BufferedWriter deadLetterWriter = new BufferedWriter(new FileWriter(deadLetterPath, true));
        DeadLetterLogger deadLetterLogger = new DeadLetterLogger(deadLetterWriter);

        S3AccessGrantsClient s3AgClient = config.getS3AccessGrants() != null
                ? new S3AccessGrantsClient(config.getS3AccessGrants(), deadLetterLogger)
                : null;

        // Create sync service in multi-service mode
        String checkpointPath = config.getCheckpointPath() != null
                ? config.getCheckpointPath()
                : "./checkpoint/sync-checkpoint.json";
        CheckpointStore checkpointStore = new CheckpointStore(
                Path.of(checkpointPath), new ObjectMapper());
        SyncService syncService = new SyncService(
                rangerServiceList, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger, checkpointStore, s3AgClient);

        // Wire reverse-sync if enabled in config
        ReverseSyncConfig reverseSyncConfig = config.getReverseSyncConfig();
        final ReverseSyncService reverseSyncService;
        if (reverseSyncConfig.isEnabled()) {
            LOG.info("Reverse-sync enabled: reportOnly={}, dryRun={}",
                    reverseSyncConfig.isReportOnly(), reverseSyncConfig.isDryRun());
            LFPermissionFetcher lfPermissionFetcher = new LFPermissionFetcher(lfSdkClient);
            DriftDetector driftDetector = new DriftDetector();
            LakeFormationClient reverseSyncLfClient;
            if (reverseSyncConfig.isDryRun()) {
                String outputDir = System.getenv("DRY_RUN_OUTPUT_DIR");
                if (outputDir == null || outputDir.isBlank()) {
                    outputDir = "./dry-run-output";
                }
                reverseSyncLfClient = new DryRunLakeFormationClient(
                        Path.of(outputDir), new ObjectMapper());
            } else {
                reverseSyncLfClient = lakeFormationClient;
            }
            reverseSyncService = new ReverseSyncService(
                    lfPermissionFetcher, driftDetector, reverseSyncLfClient,
                    cedarToLFConverter, deadLetterLogger);
        } else {
            reverseSyncService = null;
        }

        // Scheduled executor drives the sync cycle loop
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-cycle");
            t.setDaemon(true);
            return t;
        });

        // Register shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received, stopping Sync Service");
            scheduler.shutdown();
            syncService.stop();
            try {
                deadLetterWriter.close();
            } catch (IOException e) {
                LOG.warn("Error closing dead-letter log: {}", e.getMessage());
            }
            glueClient.close();
            lfSdkClient.close();
            if (identityStoreClient != null) {
                identityStoreClient.close();
            }
            KEEP_ALIVE_LATCH.countDown();
            LOG.info("Sync Service shutdown complete");
        }));

        // Initialize all Ranger plugins (registers with Ranger Admin)
        LOG.info("Initializing Ranger plugin(s) and registering with Ranger Admin");
        for (BaseRangerService service : rangerServiceList) {
            LOG.info("Initializing Ranger plugin: serviceType={}, instanceName={}",
                    service.getServiceType(), service.getServiceInstanceName());
            service.init();
        }

        // Start the sync service
        syncService.start(config);

        // Schedule the recurring sync cycle
        long intervalMs = config.getPolicyRefreshIntervalMs();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                syncService.executeSyncCycle();

                if (reverseSyncService != null && reverseSyncConfig.isEnabled()) {
                    try {
                        com.amazonaws.policyconverters.cedar.CedarPolicySet cedarPolicySet =
                                syncService.getLastCedarPolicySet();
                        if (cedarPolicySet != null) {
                            reverseSyncService.execute(reverseSyncConfig, cedarPolicySet);
                        }
                    } catch (Exception e) {
                        LOG.error("Reverse-sync cycle failed: {}", e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                LOG.error("Sync cycle failed: {}", e.getMessage(), e);
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        LOG.info("Ranger-LakeFormation Sync Service is running. Cycle interval={}ms.", intervalMs);

        // Block the main thread until shutdown signal is received
        KEEP_ALIVE_LATCH.await();
    }

    /**
     * Build an AWS credentials provider based on the configuration.
     * Supports static credentials, STS AssumeRole, or default credential chain.
     */
    static AwsCredentialsProvider buildCredentialsProvider(AwsConfig awsConfig) {
        boolean hasStaticCreds = awsConfig.getAccessKey() != null
                && !awsConfig.getAccessKey().isEmpty()
                && awsConfig.getSecretKey() != null
                && !awsConfig.getSecretKey().isEmpty();
        boolean hasRoleArn = awsConfig.getRoleArn() != null
                && !awsConfig.getRoleArn().isEmpty();

        if (hasStaticCreds && hasRoleArn) {
            LOG.info("Using STS AssumeRole with static credentials for role: {}", awsConfig.getRoleArn());
            StaticCredentialsProvider baseProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsConfig.getAccessKey(), awsConfig.getSecretKey()));
            StsClient stsClient = StsClient.builder()
                    .region(Region.of(awsConfig.getRegion()))
                    .credentialsProvider(baseProvider)
                    .build();
            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .roleArn(awsConfig.getRoleArn())
                            .roleSessionName("ranger-lf-sync")
                            .build())
                    .build();
        } else if (hasStaticCreds) {
            LOG.info("Using static AWS credentials");
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsConfig.getAccessKey(), awsConfig.getSecretKey()));
        } else if (hasRoleArn) {
            LOG.info("Using STS AssumeRole with default credentials for role: {}", awsConfig.getRoleArn());
            StsClient stsClient = StsClient.builder()
                    .region(Region.of(awsConfig.getRegion()))
                    .build();
            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .roleArn(awsConfig.getRoleArn())
                            .roleSessionName("ranger-lf-sync")
                            .build())
                    .build();
        } else {
            LOG.info("Using default AWS credential chain");
            return DefaultCredentialsProvider.create();
        }
    }

    /**
     * Verify that the cedar-java native library can be loaded.
     * Attempts to instantiate a Cedar SDK class that triggers JNI initialization.
     * If the native library is missing or incompatible, logs a descriptive error
     * with platform and JDK info and exits immediately.
     */
    static void verifyCedarNativeLibrary() {
        try {
            // Trigger cedar-java native library loading by instantiating the engine
            new com.cedarpolicy.BasicAuthorizationEngine();
            LOG.info("Cedar native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            LOG.error("Failed to load cedar-java native library. "
                    + "Platform: os.name={}, os.arch={}, JDK: java.version={}, java.vendor={}. "
                    + "The cedar-java SDK requires a compatible native library for this platform. "
                    + "Error: {}",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    e.getMessage(), e);
            System.exit(1);
        } catch (ExceptionInInitializerError e) {
            LOG.error("Failed to initialize cedar-java native library. "
                    + "Platform: os.name={}, os.arch={}, JDK: java.version={}, java.vendor={}. "
                    + "The cedar-java SDK failed during static initialization. "
                    + "Error: {}",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    e.getMessage(), e);
            System.exit(1);
        }
    }
}
