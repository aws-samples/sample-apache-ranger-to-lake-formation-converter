package org.apache.ranger.lakeformation;

import org.apache.ranger.lakeformation.catalog.CatalogResolver;
import org.apache.ranger.lakeformation.client.DeadLetterLogger;
import org.apache.ranger.lakeformation.client.LakeFormationClient;
import org.apache.ranger.lakeformation.config.ConfigLoader;
import org.apache.ranger.lakeformation.config.ConfigValidator;
import org.apache.ranger.lakeformation.converter.PolicyConverter;
import org.apache.ranger.lakeformation.mapper.PrincipalMapper;
import org.apache.ranger.lakeformation.model.AwsConfig;
import org.apache.ranger.lakeformation.model.RetryConfig;
import org.apache.ranger.lakeformation.model.SyncConfig;
import org.apache.ranger.lakeformation.reporter.GapReporter;
import org.apache.ranger.lakeformation.sync.LakeFormationPlugin;
import org.apache.ranger.lakeformation.sync.SyncService;
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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Main entry point for the Ranger-LakeFormation sync service.
 * <p>
 * Loads configuration from an external YAML/properties file, validates it,
 * instantiates all dependencies, and starts the LakeFormationPlugin and
 * SyncService to begin receiving and applying policy updates from Ranger Admin.
 * <p>
 * On first startup with an empty previous snapshot, the first policy refresh
 * effectively performs a bulk sync (all current policies are treated as new grants).
 * <p>
 * Usage: java -cp ... org.apache.ranger.lakeformation.SyncServiceMain &lt;config-file-path&gt;
 */
public class SyncServiceMain {

    private static final Logger LOG = LoggerFactory.getLogger(SyncServiceMain.class);
    private static final String DEFAULT_DEAD_LETTER_PATH = "dead-letter.log";

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
        ConfigLoader configLoader = new ConfigLoader();
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
    static void startSyncService(SyncConfig config) throws IOException {
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

        // Build application components
        PrincipalMapper principalMapper = PrincipalMapper.fromConfig(config.getPrincipalMapping());
        CatalogResolver catalogResolver = new CatalogResolver(glueClient);
        GapReporter gapReporter = new GapReporter();
        PolicyConverter policyConverter = new PolicyConverter(awsConfig.getCatalogId());

        RetryConfig retryConfig = new RetryConfig(
                config.getMaxLfRetries(),
                config.getLfRetryBackoffMs(),
                2.0,
                30000L);
        LakeFormationClient lakeFormationClient = new LakeFormationClient(lfSdkClient, retryConfig);

        String deadLetterPath = config.getDeadLetterLogPath() != null
                ? config.getDeadLetterLogPath()
                : DEFAULT_DEAD_LETTER_PATH;
        BufferedWriter deadLetterWriter = new BufferedWriter(new FileWriter(deadLetterPath, true));
        DeadLetterLogger deadLetterLogger = new DeadLetterLogger(deadLetterWriter);

        // Create plugin and sync service
        LakeFormationPlugin plugin = new LakeFormationPlugin();
        SyncService syncService = new SyncService(
                plugin, policyConverter, principalMapper, catalogResolver,
                lakeFormationClient, gapReporter, deadLetterLogger);

        // Register shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received, stopping Sync Service");
            syncService.stop();
            try {
                deadLetterWriter.close();
            } catch (IOException e) {
                LOG.warn("Error closing dead-letter log: {}", e.getMessage());
            }
            glueClient.close();
            lfSdkClient.close();
            LOG.info("Sync Service shutdown complete");
        }));

        // Initialize the plugin (registers with Ranger Admin)
        LOG.info("Initializing LakeFormation plugin and registering with Ranger Admin");
        plugin.init();

        // Start the sync service (begins listening for policy updates)
        syncService.start(config);

        LOG.info("Ranger-LakeFormation Sync Service is running. "
                + "First policy refresh will perform bulk sync from empty snapshot.");
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
}
