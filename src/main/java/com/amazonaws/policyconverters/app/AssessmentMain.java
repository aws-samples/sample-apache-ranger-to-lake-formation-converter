package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.assessment.AssessmentConfig;
import com.amazonaws.policyconverters.assessment.AssessmentReporter;
import com.amazonaws.policyconverters.assessment.AssessmentResult;
import com.amazonaws.policyconverters.assessment.AssessmentRunner;
import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.ServerConfigLoader;
import com.amazonaws.policyconverters.config.SyncConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the one-time Ranger policy gap assessment tool.
 * <p>
 * Usage:
 * <pre>
 *   assess [config-file] [options]
 *
 *   Options:
 *     --ranger-url &lt;url&gt;           Ranger Admin URL (required if no config file)
 *     --ranger-user &lt;user&gt;         Ranger Admin username
 *     --ranger-password &lt;pass&gt;     Ranger Admin password
 *     --services &lt;s1,s2,...&gt;       Comma-separated service instance names
 *     --output-dir &lt;dir&gt;           Directory for JSON report (default: current dir)
 *     --aws-region &lt;region&gt;        Enable Glue wildcard expansion with this region
 *     --aws-profile &lt;profile&gt;      AWS profile (reserved for future use)
 *     --console-only               Print report to console only, skip JSON file
 * </pre>
 * <p>
 * If a config file path is given as the first argument, settings from the file
 * are used as defaults. CLI flags override individual config file values.
 */
public class AssessmentMain {

    private static final String USAGE = String.join(System.lineSeparator(),
            "Usage: assess [<config-file>] [options]",
            "",
            "Options:",
            "  --ranger-url <url>        Ranger Admin URL (required if no config file)",
            "  --ranger-user <user>      Ranger Admin username",
            "  --ranger-password <pass>  Ranger Admin password",
            "  --services <s1,s2,...>    Comma-separated service instance names",
            "  --output-dir <dir>        Directory for JSON report (default: current dir)",
            "  --aws-region <region>     Enable Glue wildcard expansion",
            "  --aws-profile <profile>   AWS profile (reserved for future use)",
            "  --console-only            Print report to console, skip JSON file"
    );

    public static void main(String[] args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println(USAGE);
            return 1;
        }

        AssessmentConfig.Builder configBuilder = AssessmentConfig.builder();
        List<String> flagArgs = new ArrayList<>(Arrays.asList(args));

        // If first arg is a readable file, load it as a config file
        if (!flagArgs.isEmpty() && !flagArgs.get(0).startsWith("--")) {
            String possibleConfigPath = flagArgs.get(0);
            if (Files.isReadable(Paths.get(possibleConfigPath))) {
                flagArgs.remove(0);
                try {
                    applyConfigFile(possibleConfigPath, configBuilder);
                } catch (IOException e) {
                    System.err.println("Failed to load config file: " + e.getMessage());
                    return 1;
                }
            }
        }

        // Parse CLI flags, overriding config file values
        String awsRegion = null;
        for (int i = 0; i < flagArgs.size(); i++) {
            String flag = flagArgs.get(i);
            switch (flag) {
                case "--ranger-url":
                    configBuilder.rangerAdminUrl(nextArg(flagArgs, i++, flag));
                    break;
                case "--ranger-user":
                    configBuilder.rangerUsername(nextArg(flagArgs, i++, flag));
                    break;
                case "--ranger-password":
                    configBuilder.rangerPassword(nextArg(flagArgs, i++, flag));
                    break;
                case "--services": {
                    String raw = nextArg(flagArgs, i++, flag);
                    List<RangerServiceConfig> services = new ArrayList<>();
                    for (String name : raw.split(",")) {
                        name = name.trim();
                        if (!name.isEmpty()) {
                            // serviceType defaults to "lakeformation" for the assessment;
                            // the actual adapter type is best-effort from the service name
                            services.add(new RangerServiceConfig(
                                    guessServiceType(name), name, null, null));
                        }
                    }
                    configBuilder.services(services);
                    break;
                }
                case "--output-dir":
                    configBuilder.outputDir(Paths.get(nextArg(flagArgs, i++, flag)));
                    break;
                case "--aws-region":
                    awsRegion = nextArg(flagArgs, i++, flag);
                    break;
                case "--aws-profile":
                    nextArg(flagArgs, i++, flag); // accepted, reserved for future use
                    break;
                case "--console-only":
                    configBuilder.consoleOnly(true);
                    break;
                default:
                    System.err.println("Unknown flag: " + flag);
                    System.err.println(USAGE);
                    return 1;
            }
        }

        if (awsRegion != null) {
            configBuilder.awsConfig(new AwsConfig(awsRegion, null, null, null, null));
        }

        AssessmentConfig config = configBuilder.build();

        // rangerAdminUrl is required for server mode (fetching from Ranger Admin)
        if (config.getRangerAdminUrl() == null || config.getRangerAdminUrl().isBlank()) {
            System.err.println("Configuration error: rangerAdminUrl is required");
            System.err.println(USAGE);
            return 1;
        }

        try {
            AssessmentRunner runner = new AssessmentRunner();
            AssessmentResult result = runner.run(config);
            new AssessmentReporter().report(result, config, System.out);
            return 0;
        } catch (Exception e) {
            System.err.println("Assessment failed: " + e.getMessage());
            return 1;
        }
    }

    private static void applyConfigFile(String configFilePath, AssessmentConfig.Builder builder)
            throws IOException {
        ServerConfigLoader loader = new ServerConfigLoader();
        ServerConfigLoader.CompositeConfig compositeConfig = loader.load(configFilePath);
        SyncConfig syncConfig = compositeConfig.getSyncConfig();

        if (syncConfig.getRangerConfig() != null) {
            builder.rangerAdminUrl(syncConfig.getRangerConfig().getRangerAdminUrl());
            builder.rangerUsername(syncConfig.getRangerConfig().getUsername());
            builder.rangerPassword(syncConfig.getRangerConfig().getPassword());
        }

        if (syncConfig.getRangerServices() != null && !syncConfig.getRangerServices().isEmpty()) {
            builder.services(syncConfig.getRangerServices());
        }

        if (syncConfig.getPrincipalMapping() != null) {
            builder.principalMapping(syncConfig.getPrincipalMapping());
        }

        if (syncConfig.getAwsConfig() != null) {
            builder.awsConfig(syncConfig.getAwsConfig());
        }
    }

    private static String nextArg(List<String> args, int currentIndex, String flag) {
        int next = currentIndex + 1;
        if (next >= args.size()) {
            throw new IllegalArgumentException("Flag " + flag + " requires a value");
        }
        return args.get(next);
    }

    /**
     * Infers a Ranger service type from an instance name using common naming conventions.
     * Falls back to "lakeformation" if unrecognized.
     */
    static String guessServiceType(String instanceName) {
        String lower = instanceName.toLowerCase();
        if (lower.contains("hive")) return "hive";
        if (lower.contains("presto")) return "presto";
        if (lower.contains("trino")) return "trino";
        return "lakeformation";
    }
}
