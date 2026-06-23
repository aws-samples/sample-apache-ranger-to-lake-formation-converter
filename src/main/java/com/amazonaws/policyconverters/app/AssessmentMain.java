package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.assessment.AssessmentConfig;
import com.amazonaws.policyconverters.assessment.AssessmentReporter;
import com.amazonaws.policyconverters.assessment.AssessmentResult;
import com.amazonaws.policyconverters.assessment.AssessmentRunner;
import com.amazonaws.policyconverters.assessment.PolicySource;
import com.amazonaws.policyconverters.assessment.RangerAdminPolicySource;
import com.amazonaws.policyconverters.assessment.RangerExportFilePolicySource;
import com.amazonaws.policyconverters.config.AwsConfig;
import com.amazonaws.policyconverters.config.RangerServiceConfig;
import com.amazonaws.policyconverters.config.ServerConfigLoader;
import com.amazonaws.policyconverters.config.SyncConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the one-time Ranger policy gap assessment tool.
 * <p>
 * Usage:
 * <pre>
 *   server [&lt;config-file&gt;] [options]
 *     --ranger-url &lt;url&gt;        Ranger Admin URL (required if no config file)
 *     --ranger-user &lt;user&gt;      Ranger Admin username
 *     --ranger-password &lt;pass&gt;  Ranger Admin password
 *     --services &lt;s1,s2,...&gt;    Comma-separated service instance names
 *     --output-dir &lt;dir&gt;        Directory for JSON report (default: current dir)
 *     --aws-region &lt;region&gt;     Enable Glue wildcard expansion
 *     --console-only            Print report to console, skip JSON file
 *     --skip-validation         Skip Cedar schema validation (use for large policy sets)
 *
 *   file &lt;export-file.json&gt; [options]
 *     --output-dir &lt;dir&gt;        Directory for JSON report (default: current dir)
 *     --aws-region &lt;region&gt;     Enable Glue wildcard expansion
 *     --console-only            Print report to console, skip JSON file
 *     --skip-validation         Skip Cedar schema validation (use for large policy sets)
 * </pre>
 */
public class AssessmentMain {

    private static final String USAGE = String.join(System.lineSeparator(),
            "Usage:",
            "  server [<config-file>] [options]",
            "    --ranger-url <url>        Ranger Admin URL (required if no config file)",
            "    --ranger-user <user>      Ranger Admin username",
            "    --ranger-password <pass>  Ranger Admin password",
            "    --services <s1,s2,...>    Comma-separated service instance names",
            "    --output-dir <dir>        Directory for JSON report (default: current dir)",
            "    --aws-region <region>              Enable Glue wildcard expansion",
            "    --aws-profile <profile>            AWS credentials profile for Glue/STS calls",
            "    --output-lf-policies-path <path>   Write projected LF permission operations to this JSON file",
            "    --output-gaps-path <path>          Write partially/not-convertible policies with gap details to this JSON file",
            "    --console-only                     Print report to console, skip JSON file",
            "    --skip-validation                  Skip Cedar schema validation (use for large policy sets)",
            "",
            "  file <export-file.json> [options]",
            "    --output-dir <dir>                 Directory for JSON report (default: current dir)",
            "    --aws-region <region>              Enable Glue wildcard expansion",
            "    --aws-profile <profile>            AWS credentials profile for Glue/STS calls",
            "    --output-lf-policies-path <path>   Write projected LF permission operations to this JSON file",
            "    --output-gaps-path <path>          Write partially/not-convertible policies with gap details to this JSON file",
            "    --console-only                     Print report to console, skip JSON file",
            "    --skip-validation                  Skip Cedar schema validation (use for large policy sets)"
    );

    public static void main(String[] args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    public static int run(String[] args) {
        if (args.length == 0) {
            System.err.println(USAGE);
            return 1;
        }
        switch (args[0]) {
            case "server": return runServer(Arrays.copyOfRange(args, 1, args.length));
            case "file":   return runFile(Arrays.copyOfRange(args, 1, args.length));
            default:
                System.err.println("Unknown subcommand: " + args[0]);
                System.err.println(USAGE);
                return 1;
        }
    }

    private static int runServer(String[] args) {
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
        String awsProfile = null;
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
                    awsProfile = nextArg(flagArgs, i++, flag);
                    break;
                case "--output-lf-policies-path":
                    configBuilder.lfPoliciesOutputPath(Paths.get(nextArg(flagArgs, i++, flag)));
                    break;
                case "--output-gaps-path":
                    configBuilder.gapsOutputPath(Paths.get(nextArg(flagArgs, i++, flag)));
                    break;
                case "--console-only":
                    configBuilder.consoleOnly(true);
                    break;
                case "--skip-validation":
                    configBuilder.skipCedarValidation(true);
                    break;
                default:
                    System.err.println("Unknown flag: " + flag);
                    System.err.println(USAGE);
                    return 1;
            }
        }

        if (awsRegion != null || awsProfile != null) {
            configBuilder.awsConfig(new AwsConfig(awsRegion, null, null, null, null, awsProfile));
        }

        AssessmentConfig config = configBuilder.build();

        if (config.getRangerAdminUrl() == null || config.getRangerAdminUrl().isBlank()) {
            System.err.println("--ranger-url is required for 'server' subcommand (or provide a config file)");
            return 1;
        }

        try {
            AssessmentRunner runner = new AssessmentRunner();
            PolicySource source = new RangerAdminPolicySource(
                    config.getRangerAdminUrl(),
                    config.getRangerUsername(),
                    config.getRangerPassword(),
                    config.getServices());
            AssessmentResult result = runner.run(config, source);
            new AssessmentReporter().report(result, config, System.out);
            return 0;
        } catch (Exception e) {
            System.err.println("Assessment failed: " + e.getMessage());
            return 1;
        }
    }

    private static int runFile(String[] args) {
        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("'file' subcommand requires a path to a Ranger export JSON file");
            System.err.println(USAGE);
            return 1;
        }

        Path exportFile = Paths.get(args[0]);
        if (!Files.isReadable(exportFile)) {
            System.err.println("Cannot read export file: " + exportFile);
            return 1;
        }

        AssessmentConfig.Builder configBuilder = AssessmentConfig.builder();
        String awsRegion = null;
        String awsProfile = null;
        List<String> flagArgs = new ArrayList<>(Arrays.asList(args).subList(1, args.length));

        for (int i = 0; i < flagArgs.size(); i++) {
            String flag = flagArgs.get(i);
            switch (flag) {
                case "--services":
                    System.err.println("--services is not supported in file mode; " +
                            "all services in the export are assessed automatically");
                    return 1;
                case "--output-dir":
                    configBuilder.outputDir(Paths.get(nextArg(flagArgs, i++, flag)));
                    break;
                case "--aws-region":
                    awsRegion = nextArg(flagArgs, i++, flag);
                    break;
                case "--aws-profile":
                    awsProfile = nextArg(flagArgs, i++, flag);
                    break;
                case "--output-lf-policies-path":
                    configBuilder.lfPoliciesOutputPath(Paths.get(nextArg(flagArgs, i++, flag)));
                    break;
                case "--output-gaps-path":
                    configBuilder.gapsOutputPath(Paths.get(nextArg(flagArgs, i++, flag)));
                    break;
                case "--console-only":
                    configBuilder.consoleOnly(true);
                    break;
                case "--skip-validation":
                    configBuilder.skipCedarValidation(true);
                    break;
                default:
                    System.err.println("Unknown flag: " + flag);
                    System.err.println(USAGE);
                    return 1;
            }
        }

        if (awsRegion != null || awsProfile != null) {
            configBuilder.awsConfig(new AwsConfig(awsRegion, null, null, null, null, awsProfile));
        }

        AssessmentConfig config = configBuilder.build();
        PolicySource source = new RangerExportFilePolicySource(exportFile);

        try {
            AssessmentResult result = new AssessmentRunner().run(config, source);
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
        if (lower.contains("emrfs")) return "amazon-emr-emrfs";
        if (lower.contains("hive")) return "hive";
        if (lower.contains("presto")) return "presto";
        if (lower.contains("trino")) return "trino";
        return "lakeformation";
    }
}
