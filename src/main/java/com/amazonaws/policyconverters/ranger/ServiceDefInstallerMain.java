package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.ranger.config.ConfigLoader;
import com.amazonaws.policyconverters.ranger.config.ConfigValidator;
import com.amazonaws.policyconverters.lakeformation.model.RangerConnectionConfig;
import com.amazonaws.policyconverters.lakeformation.model.SyncConfig;
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

/**
 * Main entry point for installing the Lake Formation service definition
 * into Apache Ranger Admin.
 * <p>
 * Supports two installation modes:
 * <ul>
 *   <li><b>rest</b>: POST the service definition JSON to Ranger Admin's
 *       /service/plugins/definitions REST endpoint</li>
 *   <li><b>file</b>: Copy the service definition JSON to the ranger-admin
 *       plugin directory on the local filesystem</li>
 * </ul>
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
        String serviceDefJson = loadServiceDefinitionJson(cliArgs.serviceDefPath);

        ServiceDefinitionInstaller installer = new ServiceDefinitionInstaller();

        if ("rest".equalsIgnoreCase(cliArgs.mode)) {
            installViaRest(installer, cliArgs.configPath, serviceDefJson);
        } else {
            installViaFile(installer, cliArgs.rangerAdminHome, serviceDefJson);
        }
    }

    /**
     * Load configuration from the given file, validate the Ranger connection
     * parameters, and install the service definition via REST.
     */
    static void installViaRest(ServiceDefinitionInstaller installer,
                               String configPath, String serviceDefJson) throws IOException {
        LOG.info("Installing service definition via REST mode");

        ConfigLoader configLoader = new ConfigLoader();
        SyncConfig config = configLoader.load(configPath);
        configLoader.logConfig(config);

        // Validate that Ranger connection config is present
        ConfigValidator validator = new ConfigValidator();
        List<String> errors = validator.validate(config);
        // Filter to only Ranger-related errors for REST mode
        RangerConnectionConfig rangerConfig = config.getRangerConfig();
        if (rangerConfig == null || rangerConfig.getRangerAdminUrl() == null
                || rangerConfig.getRangerAdminUrl().trim().isEmpty()) {
            throw new IllegalStateException(
                    "Ranger Admin URL is required for REST installation mode. "
                    + "Provide it in the config file or via RANGER_ADMIN_URL environment variable.");
        }

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
     * Load the service definition JSON from a custom path or the bundled resource.
     */
    static String loadServiceDefinitionJson(String customPath) throws IOException {
        if (customPath != null) {
            LOG.info("Loading service definition from custom path: {}", customPath);
            Path path = Paths.get(customPath);
            if (!Files.exists(path)) {
                throw new IOException("Service definition file not found: " + customPath);
            }
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        }

        LOG.info("Loading bundled service definition from classpath: {}", BUNDLED_SERVICE_DEF);
        try (InputStream is = ServiceDefInstallerMain.class.getResourceAsStream(BUNDLED_SERVICE_DEF)) {
            if (is == null) {
                throw new IOException(
                        "Bundled service definition not found on classpath: " + BUNDLED_SERVICE_DEF);
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
