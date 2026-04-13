package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.config.ConfigLoader;
import com.amazonaws.policyconverters.config.ConfigValidator;
import com.amazonaws.policyconverters.config.RangerConnectionConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.ranger.service.ServiceDefinitionInstaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Main entry point for installing Ranger service definitions into Apache Ranger Admin.
 * <p>
 * Supports two installation modes:
 * <ul>
 *   <li><b>rest</b>: POST the service definition JSON to Ranger Admin's
 *       /service/plugins/definitions REST endpoint</li>
 *   <li><b>file</b>: Copy the service definition JSON to the ranger-admin
 *       plugin directory on the local filesystem</li>
 * </ul>
 * <p>
 * When the configuration contains a {@code rangerServices} list, each configured
 * service definition is installed. When the list is absent or empty, the installer
 * falls back to the single LakeFormation service definition for backward compatibility.
 * <p>
 * Usage:
 * <pre>
 *   java -cp ... ServiceDefInstallerMain --mode rest --config &lt;path&gt; [--service-def &lt;path&gt;]
 *   java -cp ... ServiceDefInstallerMain --mode file --ranger-admin-home &lt;path&gt; [--service-def &lt;path&gt;]
 * </pre>
 */
public class ServiceDefInstallerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceDefInstallerMain.class);
    private static final String BUNDLED_SERVICE_DEF = "/ranger-servicedef-lakeformation.json";

    /**
     * Map of known service types to their bundled service definition classpath resources.
     */
    static final Map<String, String> BUNDLED_SERVICE_DEFS = Map.of(
            "lakeformation", "/ranger-servicedef-lakeformation.json",
            "hive", "/ranger-servicedef-hive.json",
            "presto", "/ranger-servicedef-presto.json",
            "trino", "/ranger-servicedef-trino.json"
    );

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            LOG.error("Fatal error during service definition installation: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Parse CLI arguments and execute the installation. Extracted from main()
     * for testability.
     */
    static void run(String[] args) throws IOException {
        CliArgs cliArgs = parseArgs(args);

        if ("rest".equalsIgnoreCase(cliArgs.mode)) {
            ConfigLoader configLoader = new ConfigLoader();
            SyncConfig config = configLoader.load(cliArgs.configPath);
            configLoader.logConfig(config);

            RangerConnectionConfig rangerConfig = config.getRangerConfig();
            if (rangerConfig == null || rangerConfig.getRangerAdminUrl() == null
                    || rangerConfig.getRangerAdminUrl().trim().isEmpty()) {
                throw new IllegalStateException(
                        "Ranger Admin URL is required for REST installation mode. "
                        + "Provide it in the config file or via RANGER_ADMIN_URL environment variable.");
            }

            ServiceDefinitionInstaller installer = new ServiceDefinitionInstaller();
            List<RangerServiceConfig> rangerServices = config.getRangerServices();

            if (rangerServices != null && !rangerServices.isEmpty()) {
                installMultipleServicesViaRest(installer, rangerConfig, rangerServices, cliArgs.serviceDefPath);
            } else {
                // Backward compatibility: single LakeFormation service
                String serviceDefJson = loadServiceDefinitionJson(cliArgs.serviceDefPath);
                installViaRest(installer, rangerConfig, serviceDefJson);
            }
        } else {
            String serviceDefJson = loadServiceDefinitionJson(cliArgs.serviceDefPath);
            ServiceDefinitionInstaller installer = new ServiceDefinitionInstaller();
            installViaFile(installer, cliArgs.rangerAdminHome, serviceDefJson);
        }
    }

    /**
     * Install service definitions for all configured services via REST.
     * Logs errors for individual failures but continues with remaining services.
     */
    static void installMultipleServicesViaRest(ServiceDefinitionInstaller installer,
                                                RangerConnectionConfig rangerConfig,
                                                List<RangerServiceConfig> rangerServices,
                                                String cliServiceDefPath) {
        LOG.info("Installing service definitions for {} configured service(s) via REST",
                rangerServices.size());

        int successCount = 0;
        int failureCount = 0;

        for (RangerServiceConfig serviceConfig : rangerServices) {
            String serviceType = serviceConfig.getServiceType();
            String instanceName = serviceConfig.getServiceInstanceName();
            LOG.info("Installing service definition for serviceType={}, instanceName={}",
                    serviceType, instanceName);

            try {
                String serviceDefJson = loadServiceDefForConfig(serviceConfig, cliServiceDefPath);
                installer.installViaRest(rangerConfig, serviceDefJson);
                LOG.info("Successfully installed service definition for serviceType={}", serviceType);
                successCount++;
            } catch (Exception e) {
                LOG.error("Failed to install service definition for serviceType={}, instanceName={}: {}",
                        serviceType, instanceName, e.getMessage(), e);
                failureCount++;
            }
        }

        LOG.info("Multi-service installation complete: {} succeeded, {} failed",
                successCount, failureCount);

        if (successCount == 0 && failureCount > 0) {
            throw new RuntimeException("All service definition installations failed");
        }
    }

    /**
     * Load the service definition JSON for a specific service config entry.
     * Uses the config's custom serviceDefPath if specified, otherwise loads
     * the bundled service definition for the service type.
     */
    static String loadServiceDefForConfig(RangerServiceConfig serviceConfig,
                                           String cliServiceDefPath) throws IOException {
        // If the config entry has a custom serviceDefPath, use it
        if (serviceConfig.getServiceDefPath() != null
                && !serviceConfig.getServiceDefPath().trim().isEmpty()) {
            LOG.info("Loading custom service definition for serviceType={} from: {}",
                    serviceConfig.getServiceType(), serviceConfig.getServiceDefPath());
            return loadServiceDefinitionFromFile(serviceConfig.getServiceDefPath());
        }

        // If a CLI-level --service-def was provided and there's only one service, use it
        // (backward compat for single-service CLI usage)
        // For multi-service, we load the bundled def for each service type
        String serviceType = serviceConfig.getServiceType();
        String bundledPath = BUNDLED_SERVICE_DEFS.get(serviceType);
        if (bundledPath == null) {
            throw new IOException("No bundled service definition found for service type: " + serviceType);
        }

        LOG.info("Loading bundled service definition for serviceType={} from classpath: {}",
                serviceType, bundledPath);
        return loadBundledServiceDefinition(bundledPath);
    }

    /**
     * Load the service definition JSON from a custom path or the bundled resource.
     */
    static String loadServiceDefinitionJson(String customPath) throws IOException {
        if (customPath != null) {
            return loadServiceDefinitionFromFile(customPath);
        }
        return loadBundledServiceDefinition(BUNDLED_SERVICE_DEF);
    }

    /**
     * Load a service definition JSON from a file path.
     */
    static String loadServiceDefinitionFromFile(String filePath) throws IOException {
        LOG.info("Loading service definition from path: {}", filePath);
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("Service definition file not found: " + filePath);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * Load a bundled service definition from the classpath.
     */
    static String loadBundledServiceDefinition(String resourcePath) throws IOException {
        LOG.info("Loading bundled service definition from classpath: {}", resourcePath);
        try (InputStream is = ServiceDefInstallerMain.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException(
                        "Bundled service definition not found on classpath: " + resourcePath);
            }
            byte[] buffer = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    /**
     * Install the service definition via REST using the given installer and config.
     */
    static void installViaRest(ServiceDefinitionInstaller installer,
                               RangerConnectionConfig rangerConfig,
                               String serviceDefJson) {
        LOG.info("Installing service definition via REST mode");
        installer.installViaRest(rangerConfig, serviceDefJson);
        LOG.info("Service definition installation via REST completed successfully");
    }

    /**
     * Install the service definition by copying it to the Ranger Admin
     * plugin directory.
     */
    static void installViaFile(ServiceDefinitionInstaller installer,
                               String rangerAdminHome, String serviceDefJson) {
        LOG.info("Installing service definition via file mode to: {}", rangerAdminHome);

        Path adminHomePath = Paths.get(rangerAdminHome);
        if (!Files.isDirectory(adminHomePath)) {
            throw new IllegalStateException(
                    "Ranger Admin home directory does not exist: " + rangerAdminHome);
        }

        installer.installViaFile(adminHomePath, serviceDefJson);
        LOG.info("Service definition installation via file completed successfully");
    }

    /**
     * Parse command-line arguments into a structured object.
     */
    static CliArgs parseArgs(String[] args) {
        CliArgs cliArgs = new CliArgs();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":
                    cliArgs.mode = requireNextArg(args, i, "--mode");
                    i++;
                    break;
                case "--config":
                    cliArgs.configPath = requireNextArg(args, i, "--config");
                    i++;
                    break;
                case "--ranger-admin-home":
                    cliArgs.rangerAdminHome = requireNextArg(args, i, "--ranger-admin-home");
                    i++;
                    break;
                case "--service-def":
                    cliArgs.serviceDefPath = requireNextArg(args, i, "--service-def");
                    i++;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        validateCliArgs(cliArgs);
        return cliArgs;
    }

    private static String requireNextArg(String[] args, int currentIndex, String flag) {
        if (currentIndex + 1 >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[currentIndex + 1];
    }

    private static void validateCliArgs(CliArgs cliArgs) {
        if (cliArgs.mode == null) {
            printUsageAndThrow("--mode is required");
        }

        if (!"rest".equalsIgnoreCase(cliArgs.mode) && !"file".equalsIgnoreCase(cliArgs.mode)) {
            printUsageAndThrow("--mode must be 'rest' or 'file', got: " + cliArgs.mode);
        }

        if ("rest".equalsIgnoreCase(cliArgs.mode) && cliArgs.configPath == null) {
            printUsageAndThrow("--config is required for REST installation mode");
        }

        if ("file".equalsIgnoreCase(cliArgs.mode) && cliArgs.rangerAdminHome == null) {
            printUsageAndThrow("--ranger-admin-home is required for file installation mode");
        }
    }

    private static void printUsageAndThrow(String error) {
        String usage = "Usage:\n"
                + "  ServiceDefInstallerMain --mode rest --config <path> [--service-def <path>]\n"
                + "  ServiceDefInstallerMain --mode file --ranger-admin-home <path> [--service-def <path>]\n"
                + "\nOptions:\n"
                + "  --mode              Installation mode: 'rest' or 'file'\n"
                + "  --config            Path to configuration file (required for REST mode)\n"
                + "  --ranger-admin-home Path to ranger-admin home directory (required for file mode)\n"
                + "  --service-def       Path to custom service definition JSON (optional, defaults to bundled)";
        System.err.println("Error: " + error);
        System.err.println(usage);
        throw new IllegalArgumentException(error);
    }

    /**
     * Parsed CLI arguments.
     */
    static class CliArgs {
        String mode;
        String configPath;
        String rangerAdminHome;
        String serviceDefPath;
    }
}
