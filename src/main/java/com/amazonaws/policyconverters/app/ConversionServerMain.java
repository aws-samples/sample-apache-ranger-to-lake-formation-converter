package com.amazonaws.policyconverters.app;

import ch.qos.logback.classic.Level;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.config.ServerConfigLoader;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.LFPermissionFetcher;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.RetryConfig;
import com.amazonaws.policyconverters.config.ReverseSyncConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.sync.DriftDetector;
import com.amazonaws.policyconverters.sync.ReverseSyncService;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.config.ConfigValidator;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.sync.SyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the Conversion Server.
 * <p>
 * Parses CLI args, loads and validates configuration, wires the sync pipeline,
 * creates a {@link ServerLifecycle}, registers a SIGTERM shutdown hook, and
 * starts the run-loop.
 * <p>
 * Usage: java -cp ... ConversionServerMain &lt;config-file-path&gt;
 */
public class ConversionServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ConversionServerMain.class);
    private static final String VERSION = "1.0.0";
    private static final String DEFAULT_DEAD_LETTER_PATH = "dead-letter.log";

    public static void main(String[] args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    /**
     * Core logic extracted from main for testability. Returns the exit code.
     */
    static int run(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: ConversionServerMain <config-file-path>");
            return 1;
        }

        String configFilePath = args[0];

        // Load configuration
        ServerConfigLoader.CompositeConfig compositeConfig;
        try {
            compositeConfig = loadConfig(configFilePath);
        } catch (Exception e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            return 1;
        }

        SyncConfig syncConfig = compositeConfig.getSyncConfig();
        ServerConfig serverConfig = compositeConfig.getServerConfig();
        ReverseSyncConfig reverseSyncConfig = compositeConfig.getReverseSyncConfig();

        // Validate sync configuration
        List<String> errors = validateConfig(syncConfig);
        if (!errors.isEmpty()) {
            for (String error : errors) {
                System.err.println("Configuration error: " + error);
            }
            return 1;
        }

        // Set Logback log level from ServerConfig
        setLogLevel(serverConfig.getLogLevel());

        LOG.info("Starting Conversion Server v{}, syncInterval={}ms, logLevel={}",
                VERSION, syncConfig.getPolicyRefreshIntervalMs(), serverConfig.getLogLevel());

        try {
            return startServer(syncConfig, serverConfig, reverseSyncConfig);
        } catch (Exception e) {
            LOG.error("Fatal error starting Conversion Server: {}", e.getMessage(), e);
            System.err.println("Fatal error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Loads the composite configuration from the given file path.
     */
    static ServerConfigLoader.CompositeConfig loadConfig(String configFilePath) throws IOException {
        ServerConfigLoader loader = new ServerConfigLoader();
        return loader.load(configFilePath);
    }

    /**
     * Validates the sync configuration and returns a list of errors (empty if valid).
     */
    static List<String> validateConfig(SyncConfig syncConfig) {
        ConfigValidator validator = new ConfigValidator();
        return validator.validate(syncConfig);
    }

    /**
     * Sets the Logback root logger level programmatically.
     */
    static void setLogLevel(String logLevel) {
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));
    }

    /**
     * Wires all components and starts the server lifecycle.
     * Returns exit code 0 on graceful shutdown, 1 on timeout.
     */
    static int startServer(SyncConfig syncConfig, ServerConfig serverConfig,
                           ReverseSyncConfig reverseSyncConfig) throws IOException {
        AwsConfig awsConfig = syncConfig.getAwsConfig();
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

        CloudWatchClient cloudWatchClient = CloudWatchClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        // Build application components (reuse wiring from SyncServiceMain)
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(syncConfig.getPrincipalMapping());
        CatalogResolver catalogResolver = new CatalogResolver(glueClient);
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider cedarSchemaProvider = new CedarSchemaProvider();

        AwsContext awsContext = new AwsContext(
                awsConfig.getRegion(),
                awsConfig.getCatalogId(),
                awsConfig.getCatalogId());
        RangerServiceAdapter lfAdapter = new RangerServiceAdapter(awsContext);

        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        adapterRegistry.put("lakeformation", lfAdapter);

        RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                adapterRegistry, principalMapper, catalogResolver, gapReporter, cedarSchemaProvider);

        CedarToLFConverter cedarToLFConverter = new CedarToLFConverter(
                cedarSchemaProvider, gapReporter, null);

        RetryConfig retryConfig = new RetryConfig(
                syncConfig.getMaxLfRetries(),
                syncConfig.getLfRetryBackoffMs(),
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

        String deadLetterPath = syncConfig.getDeadLetterLogPath() != null
                ? syncConfig.getDeadLetterLogPath()
                : DEFAULT_DEAD_LETTER_PATH;
        BufferedWriter deadLetterWriter = new BufferedWriter(new FileWriter(deadLetterPath, true));
        DeadLetterLogger deadLetterLogger = new DeadLetterLogger(deadLetterWriter);

        // Create plugin and sync service
        RangerPlugin plugin = new RangerPlugin();
        SyncService syncService = new SyncService(
                plugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger);

        // Create the SyncCycleExecutor that wraps the pipeline
        // Optionally wire reverse-sync if enabled
        ReverseSyncService reverseSyncService = null;
        if (reverseSyncConfig != null && reverseSyncConfig.isEnabled()) {
            LOG.info("Reverse-sync enabled: reportOnly={}, dryRun={}, periodicIntervalMs={}",
                    reverseSyncConfig.isReportOnly(), reverseSyncConfig.isDryRun(),
                    reverseSyncConfig.getPeriodicIntervalMs());

            LFPermissionFetcher lfPermissionFetcher = new LFPermissionFetcher(
                    lfSdkClient);
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
        }

        final ReverseSyncService finalReverseSyncService = reverseSyncService;
        final ReverseSyncConfig finalReverseSyncConfig = reverseSyncConfig;
        SyncCycleExecutor executor = createSyncCycleExecutor(plugin, syncService,
                finalReverseSyncService, finalReverseSyncConfig);

        // Create MetricsEmitter and ServerLifecycle
        MetricsEmitter metricsEmitter = new MetricsEmitter(cloudWatchClient, serverConfig);
        ServerLifecycle serverLifecycle = new ServerLifecycle(
                executor, metricsEmitter, serverConfig, syncConfig.getPolicyRefreshIntervalMs());

        // Register SIGTERM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("SIGTERM received, initiating graceful shutdown");
            boolean completed = serverLifecycle.shutdown();
            try {
                deadLetterWriter.close();
            } catch (IOException e) {
                LOG.warn("Error closing dead-letter log: {}", e.getMessage());
            }
            glueClient.close();
            lfSdkClient.close();
            cloudWatchClient.close();
            if (!completed) {
                LOG.warn("Shutdown timeout exceeded, forcing exit");
            } else {
                LOG.info("Graceful shutdown complete");
            }
        }, "shutdown-hook"));

        // Initialize the plugin (registers with Ranger Admin)
        LOG.info("Initializing LakeFormation plugin and registering with Ranger Admin");
        plugin.init();
        syncService.start(syncConfig);

        // Start the run-loop (blocks until shutdown)
        serverLifecycle.run();

        // Determine exit code based on shutdown result
        return serverLifecycle.isRunning() ? 1 : 0;
    }

    /**
     * Creates a SyncCycleExecutor that fetches policies from the plugin,
     * feeds them through the SyncService pipeline, and optionally triggers
     * reverse-sync after each forward-sync cycle.
     */
    static SyncCycleExecutor createSyncCycleExecutor(RangerPlugin plugin, SyncService syncService,
                                                      ReverseSyncService reverseSyncService,
                                                      ReverseSyncConfig reverseSyncConfig) {
        return () -> {
            long startMs = System.currentTimeMillis();
            try {
                // Fetch latest policies from Ranger Admin via the plugin
                org.apache.ranger.plugin.util.ServicePolicies servicePolicies = plugin.getLatestPolicies();
                int policyCount = 0;
                if (servicePolicies != null) {
                    policyCount = servicePolicies.getPolicies() != null
                            ? servicePolicies.getPolicies().size() : 0;
                    syncService.onPoliciesUpdated(servicePolicies);
                } else {
                    LOG.warn("No policies available from Ranger Admin plugin");
                }

                // Trigger reverse-sync after forward-sync if enabled
                if (reverseSyncService != null && reverseSyncConfig != null
                        && reverseSyncConfig.isEnabled()) {
                    try {
                        // Get the current Cedar policy set from the sync service's last conversion
                        com.amazonaws.policyconverters.cedar.CedarPolicySet cedarPolicySet =
                                syncService.getLastCedarPolicySet();
                        if (cedarPolicySet != null) {
                            reverseSyncService.execute(reverseSyncConfig, cedarPolicySet);
                        } else {
                            LOG.debug("No Cedar policy set available for reverse-sync");
                        }
                    } catch (Exception e) {
                        LOG.error("Reverse-sync cycle failed: {}", e.getMessage(), e);
                    }
                }

                long durationMs = System.currentTimeMillis() - startMs;
                return SyncCycleResult.success(durationMs, policyCount, 0, 0, 0);
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                return SyncCycleResult.failure(durationMs, e);
            }
        };
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
                            .roleSessionName("conversion-server")
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
                            .roleSessionName("conversion-server")
                            .build())
                    .build();
        } else {
            LOG.info("Using default AWS credential chain");
            return DefaultCredentialsProvider.create();
        }
    }
}
