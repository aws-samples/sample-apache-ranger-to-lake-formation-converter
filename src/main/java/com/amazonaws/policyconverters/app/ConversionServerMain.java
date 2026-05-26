package com.amazonaws.policyconverters.app;

import ch.qos.logback.classic.Level;
import com.amazonaws.policyconverters.cedar.CedarSchemaProvider;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.ServerConfig;
import com.amazonaws.policyconverters.config.ServerConfigLoader;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.model.SyncCycleResult;
import com.amazonaws.policyconverters.ranger.HiveServiceAdapter;
import com.amazonaws.policyconverters.ranger.PrestoServiceAdapter;
import com.amazonaws.policyconverters.ranger.TrinoServiceAdapter;
import com.amazonaws.policyconverters.lakeformation.TagMetadataSyncer;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.ranger.service.EmrfsRangerService;
import com.amazonaws.policyconverters.ranger.service.HiveRangerService;
import com.amazonaws.policyconverters.ranger.service.LakeFormationRangerService;
import com.amazonaws.policyconverters.ranger.service.PrestoRangerService;
import com.amazonaws.policyconverters.ranger.service.RangerTagService;
import com.amazonaws.policyconverters.ranger.service.TrinoRangerService;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import com.amazonaws.policyconverters.sync.CheckpointStore;
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
import com.amazonaws.policyconverters.ranger.AccessTypeMapper;
import com.amazonaws.policyconverters.ranger.CatalogResolver;
import com.amazonaws.policyconverters.ranger.RangerServiceAdapter;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.config.ConfigValidator;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapper;
import com.amazonaws.policyconverters.lakeformation.PrincipalMapperFactory;
import com.amazonaws.policyconverters.config.PrincipalMappingConfig;
import com.amazonaws.policyconverters.config.PrincipalMapperType;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;
import com.amazonaws.policyconverters.sync.SyncService;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
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
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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

        // Build MetricsEmitter early so it can be wired into PrincipalMapperFactory
        MetricsEmitter metricsEmitter = new MetricsEmitter(cloudWatchClient, serverConfig);

        // Build IdentitystoreClient only when needed
        final IdentitystoreClient identityStoreClient;
        PrincipalMappingConfig principalMappingConfig = syncConfig.getPrincipalMapping();
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

        // Build application components (reuse wiring from SyncServiceMain)
        PrincipalMapper principalMapper = PrincipalMapperFactory.create(
                principalMappingConfig, identityStoreClient, metricsEmitter);
        CatalogResolver catalogResolver = new CatalogResolver(glueClient);
        GapReporter gapReporter = new GapReporter();
        CedarSchemaProvider cedarSchemaProvider = new CedarSchemaProvider();

        AwsContext awsContext = new AwsContext(
                awsConfig.getRegion(),
                awsConfig.getCatalogId(),
                awsConfig.getCatalogId());

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

        // Build checkpoint store if configured
        CheckpointStore checkpointStore = null;
        if (syncConfig.getCheckpointPath() != null && !syncConfig.getCheckpointPath().isBlank()) {
            checkpointStore = new CheckpointStore(
                    Path.of(syncConfig.getCheckpointPath()), new ObjectMapper());
        }

        // Determine multi-service vs single-service mode (Req 6.2, 6.3, 7.1)
        List<RangerServiceConfig> rangerServiceConfigs = syncConfig.getRangerServices();
        boolean multiServiceMode = rangerServiceConfigs != null && !rangerServiceConfigs.isEmpty();

        RangerPlugin plugin = null;
        SyncService syncService;
        Map<String, SourcePolicyAdapter> adapterRegistry = new HashMap<>();
        List<SourcePolicyAdapter> allAdapters = new ArrayList<>();

        if (multiServiceMode) {
            // --- Multi-service pipeline (Req 6.3, 7.1) ---
            LOG.info("Multi-service mode: configuring {} Ranger service(s)", rangerServiceConfigs.size());

            List<BaseRangerService> rangerServices = new ArrayList<>();
            for (RangerServiceConfig cfg : rangerServiceConfigs) {
                BaseRangerService service = createRangerService(cfg);
                rangerServices.add(service);

                SourcePolicyAdapter adapter = service.createAdapter(awsContext);
                adapterRegistry.put(cfg.getServiceType(), adapter);
                allAdapters.add(adapter);

                LOG.info("Configured Ranger service: serviceType={}, instanceName={}",
                        cfg.getServiceType(), cfg.getServiceInstanceName());
            }

            RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                    adapterRegistry, principalMapper, catalogResolver, gapReporter, cedarSchemaProvider);

            S3AccessGrantsClient s3AgClient = syncConfig.getS3AccessGrants() != null
                    ? new S3AccessGrantsClient(syncConfig.getS3AccessGrants(), deadLetterLogger)
                    : null;
            syncService = new SyncService(
                    rangerServices, rangerToCedarConverter, cedarToLFConverter,
                    lakeFormationClient, gapReporter, deadLetterLogger, checkpointStore, s3AgClient);

            // Initialize all plugins (Req 6.3)
            for (BaseRangerService service : rangerServices) {
                try {
                    LOG.info("Initializing Ranger plugin: serviceType={}, instanceName={}",
                            service.getServiceType(), service.getServiceInstanceName());
                    service.init();
                } catch (Exception e) {
                    LOG.error("Failed to initialize Ranger plugin: serviceType={}, instanceName={}: {}",
                            service.getServiceType(), service.getServiceInstanceName(), e.getMessage(), e);
                }
            }
        } else {
            // --- Single-service pipeline (backward compatibility, Req 6.2) ---
            LOG.info("Single-service mode: using legacy LakeFormation plugin wiring");

            RangerServiceAdapter lfAdapter = new RangerServiceAdapter(awsContext);
            adapterRegistry.put("lakeformation", lfAdapter);
            allAdapters.add(lfAdapter);

            RangerToCedarConverter rangerToCedarConverter = new RangerToCedarConverter(
                    adapterRegistry, principalMapper, catalogResolver, gapReporter, cedarSchemaProvider);

            plugin = new RangerPlugin();
            syncService = new SyncService(
                    plugin, rangerToCedarConverter, cedarToLFConverter,
                    lakeFormationClient, gapReporter, deadLetterLogger, checkpointStore);
        }

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

        // Create the SyncCycleExecutor based on mode
        final ReverseSyncService finalReverseSyncService = reverseSyncService;
        final ReverseSyncConfig finalReverseSyncConfig = reverseSyncConfig;
        SyncCycleExecutor executor;
        if (multiServiceMode) {
            executor = createMultiServiceSyncCycleExecutor(syncService,
                    finalReverseSyncService, finalReverseSyncConfig);
        } else {
            // Extract Ranger Admin credentials for direct REST API fetch
            RangerConnectionConfig rangerConnectionConfig = syncConfig.getRangerConfig();
            String rangerAdminUrl = rangerConnectionConfig != null
                    ? rangerConnectionConfig.getRangerAdminUrl() : null;
            String rangerUsername = rangerConnectionConfig != null
                    ? rangerConnectionConfig.getUsername() : null;
            String rangerPassword = rangerConnectionConfig != null
                    ? rangerConnectionConfig.getPassword() : null;

            executor = createSyncCycleExecutor(plugin, syncService,
                    finalReverseSyncService, finalReverseSyncConfig,
                    rangerAdminUrl, rangerUsername, rangerPassword);
        }

        // Wire MetricsEmitter into all adapters and the static AccessTypeMapper
        // EmrfsServiceAdapter does not emit metrics — no arm needed
        for (SourcePolicyAdapter adapter : allAdapters) {
            if (adapter instanceof RangerServiceAdapter) {
                ((RangerServiceAdapter) adapter).setMetricsEmitter(metricsEmitter);
            } else if (adapter instanceof HiveServiceAdapter) {
                ((HiveServiceAdapter) adapter).setMetricsEmitter(metricsEmitter);
            } else if (adapter instanceof PrestoServiceAdapter) {
                ((PrestoServiceAdapter) adapter).setMetricsEmitter(metricsEmitter);
            } else if (adapter instanceof TrinoServiceAdapter) {
                ((TrinoServiceAdapter) adapter).setMetricsEmitter(metricsEmitter);
            }
        }
        AccessTypeMapper.setMetricsEmitter(metricsEmitter);

        // Create shared lock for mutual exclusion between sync cycles and wildcard refresh
        ReentrantLock cycleLock = new ReentrantLock();

        ServerLifecycle serverLifecycle = new ServerLifecycle(
                executor, metricsEmitter, serverConfig, syncConfig.getPolicyRefreshIntervalMs(), cycleLock);

        // Create WildcardRefreshScheduler with shared lock
        WildcardRefreshScheduler wildcardRefreshScheduler = new WildcardRefreshScheduler(
                syncService, metricsEmitter, cycleLock);

        int wildcardRefreshInterval = syncConfig.getWildcardRefreshIntervalSeconds();
        if (wildcardRefreshInterval > 0) {
            LOG.info("Wildcard refresh enabled with interval={}s", wildcardRefreshInterval);
            wildcardRefreshScheduler.start(wildcardRefreshInterval);
        }

        // Start the status HTTP endpoint
        StatusEndpoint statusEndpoint = new StatusEndpoint(
                18080,
                syncService.getLastCompletedCycle(),
                wildcardRefreshScheduler.getLastCompletedWildcardRefreshCycle());
        statusEndpoint.start();

        // Register SIGTERM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("SIGTERM received, initiating graceful shutdown");
            statusEndpoint.stop();
            wildcardRefreshScheduler.shutdown(serverConfig.getShutdownTimeoutSeconds());
            boolean completed = serverLifecycle.shutdown();
            try {
                deadLetterWriter.close();
            } catch (IOException e) {
                LOG.warn("Error closing dead-letter log: {}", e.getMessage());
            }
            glueClient.close();
            lfSdkClient.close();
            cloudWatchClient.close();
            if (identityStoreClient != null) {
                identityStoreClient.close();
            }
            if (!completed) {
                LOG.warn("Shutdown timeout exceeded, forcing exit");
            } else {
                LOG.info("Graceful shutdown complete");
            }
        }, "shutdown-hook"));

        // Wire tag sync components if enabled
        if (syncConfig.getTagSync().isEnabled()) {
            String tagServiceName = syncConfig.getTagSync().getTagServiceName();
            LOG.info("Tag sync enabled: tagServiceName={}, tagSyncIntervalMs={}",
                    tagServiceName, syncConfig.getTagSync().getTagSyncIntervalMs());
            RangerTagService rangerTagService = new RangerTagService(
                    tagServiceName, syncConfig.getRangerConfig());
            TagMetadataSyncer tagMetadataSyncer = new TagMetadataSyncer(
                    lakeFormationClient, awsConfig.getCatalogId());
            syncService.setTagSync(rangerTagService, tagMetadataSyncer);
        } else {
            LOG.debug("Tag sync disabled — set tagSync.enabled=true to activate");
        }

        // Initialize plugin and start sync service
        if (!multiServiceMode && plugin != null) {
            LOG.info("Initializing LakeFormation plugin and registering with Ranger Admin");
            plugin.init();
        }
        syncService.start(syncConfig);

        // Start the run-loop (blocks until shutdown)
        serverLifecycle.run();

        // Determine exit code based on shutdown result
        return serverLifecycle.isRunning() ? 1 : 0;
    }

    /**
     * Creates a BaseRangerService instance from a RangerServiceConfig entry.
     * Maps the service type string to the appropriate concrete class.
     *
     * @param config the service configuration entry
     * @return the created BaseRangerService instance
     * @throws IllegalArgumentException if the service type is unknown
     */
    public static BaseRangerService createRangerService(RangerServiceConfig config) {
        String serviceType = config.getServiceType();
        String instanceName = config.getServiceInstanceName();

        switch (serviceType) {
            case "lakeformation":
                return new LakeFormationRangerService(instanceName);
            case "hive":
                return new HiveRangerService(instanceName);
            case "presto":
                return new PrestoRangerService(instanceName, config.getGdcCatalogName());
            case "trino":
                return new TrinoRangerService(instanceName, config.getGdcCatalogName());
            case "amazon-emr-emrfs":
                return new EmrfsRangerService(instanceName);
            default:
                throw new IllegalArgumentException("Unknown Ranger service type: " + serviceType);
        }
    }

    /**
     * Creates a SyncCycleExecutor for multi-service mode that delegates to
     * {@link SyncService#executeSyncCycle()} and optionally triggers reverse-sync.
     */
    static SyncCycleExecutor createMultiServiceSyncCycleExecutor(
            SyncService syncService,
            ReverseSyncService reverseSyncService,
            ReverseSyncConfig reverseSyncConfig) {
        return () -> {
            long startMs = System.currentTimeMillis();
            try {
                syncService.executeSyncCycle();

                // Trigger reverse-sync after forward-sync if enabled
                if (reverseSyncService != null && reverseSyncConfig != null
                        && reverseSyncConfig.isEnabled()) {
                    try {
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
                return SyncCycleResult.success(durationMs, 0, 0, 0, 0);
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                return SyncCycleResult.failure(durationMs, e);
            }
        };
    }

    /**
     * Creates a SyncCycleExecutor that fetches policies directly from Ranger Admin
     * via the REST API (bypassing the PolicyRefresher), feeds them through the
     * SyncService pipeline, and optionally triggers reverse-sync after each
     * forward-sync cycle.
     */
    static SyncCycleExecutor createSyncCycleExecutor(RangerPlugin plugin, SyncService syncService,
                                                      ReverseSyncService reverseSyncService,
                                                      ReverseSyncConfig reverseSyncConfig,
                                                      String rangerAdminUrl, String username,
                                                      String password) {
        return () -> {
            long startMs = System.currentTimeMillis();
            try {
                // Fetch policies directly from Ranger Admin REST API
                // (bypasses PolicyRefresher backoff)
                org.apache.ranger.plugin.util.ServicePolicies servicePolicies =
                        fetchPoliciesFromRangerAdmin(rangerAdminUrl, username, password);
                int policyCount = 0;
                if (servicePolicies != null) {
                    policyCount = servicePolicies.getPolicies() != null
                            ? servicePolicies.getPolicies().size() : 0;
                    syncService.onPoliciesUpdated(servicePolicies);
                } else {
                    LOG.warn("No policies available from Ranger Admin REST API");
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
     * Fetches policies directly from Ranger Admin REST API, bypassing the PolicyRefresher.
     * Uses GET /service/public/v2/api/service/{serviceName}/policy with Basic auth.
     * Returns a ServicePolicies envelope with policyVersion = System.currentTimeMillis(),
     * or null on error.
     *
     * @param rangerAdminUrl the Ranger Admin base URL (e.g. http://ranger-admin:6080)
     * @param username       the Ranger Admin username
     * @param password       the Ranger Admin password
     * @return ServicePolicies containing the current policies, or null on error
     */
    static ServicePolicies fetchPoliciesFromRangerAdmin(
            String rangerAdminUrl, String username, String password) {
        return fetchPoliciesFromRangerAdmin(rangerAdminUrl, username, password, "lakeformation");
    }

    /**
     * Fetches policies for a specific Ranger service instance from Ranger Admin REST API.
     * Uses GET /service/public/v2/api/service/{serviceName}/policy with Basic auth.
     * Returns a ServicePolicies envelope with policyVersion = System.currentTimeMillis(),
     * or null on error.
     *
     * @param rangerAdminUrl  the Ranger Admin base URL (e.g. http://ranger-admin:6080)
     * @param username        the Ranger Admin username
     * @param password        the Ranger Admin password
     * @param serviceInstanceName the Ranger service instance name to fetch policies for
     * @return ServicePolicies containing the current policies, or null on error
     */
    public static ServicePolicies fetchPoliciesFromRangerAdmin(
            String rangerAdminUrl, String username, String password, String serviceInstanceName) {
        String endpoint = rangerAdminUrl + "/service/public/v2/api/service/" + serviceInstanceName + "/policy";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);

            int status = conn.getResponseCode();
            if (status != 200) {
                String errorBody = readResponseStream(
                        status >= 400 ? conn.getErrorStream() : conn.getInputStream());
                LOG.error("Failed to fetch policies from Ranger Admin: HTTP {} - {}",
                        status, errorBody);
                return null;
            }

            String responseBody = readResponseStream(conn.getInputStream());

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<RangerPolicy> policies = mapper.readValue(responseBody,
                    new TypeReference<List<RangerPolicy>>() {});

            // Filter out disabled policies — the REST API returns all policies
            // regardless of isEnabled status, but the conversion pipeline should
            // only process enabled policies (matching PolicyRefresher behavior).
            policies.removeIf(p -> p.getIsEnabled() != null && !p.getIsEnabled());

            ServicePolicies servicePolicies = new ServicePolicies();
            servicePolicies.setServiceName(serviceInstanceName);
            servicePolicies.setPolicies(policies);
            servicePolicies.setPolicyVersion(System.currentTimeMillis());

            LOG.debug("Fetched {} policies from Ranger Admin REST API for service '{}'",
                    policies.size(), serviceInstanceName);
            return servicePolicies;
        } catch (IOException e) {
            LOG.error("IOException fetching policies from Ranger Admin: {}", e.getMessage(), e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Reads the full content of an InputStream into a String.
     */
    private static String readResponseStream(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * Build an AWS credentials provider based on the configuration.
     * Supports static credentials, STS AssumeRole, or default credential chain.
     */
    public static AwsCredentialsProvider buildCredentialsProvider(AwsConfig awsConfig) {
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
