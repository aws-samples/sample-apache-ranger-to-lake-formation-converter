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
 *     --log-level &lt;level&gt;       TRACE|DEBUG|INFO|WARN|ERROR (default INFO)
 *     --log-file &lt;path&gt;         Also write a plain-text log to this file
 *     --skip-validation         Skip Cedar schema validation (use for large policy sets)
 *
 *   file &lt;export-file.json&gt; [options]
 *     --config &lt;file&gt;           server-config.yaml supplying principalMapping and gdcCatalogName
 *     --output-dir &lt;dir&gt;        Directory for JSON report (default: current dir)
 *     --aws-region &lt;region&gt;     Enable Glue wildcard expansion
 *     --console-only            Print report to console, skip JSON file
 *     --log-level &lt;level&gt;       TRACE|DEBUG|INFO|WARN|ERROR (default INFO)
 *     --log-file &lt;path&gt;         Also write a plain-text log to this file
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
            "    --log-level <level>                TRACE|DEBUG|INFO|WARN|ERROR (default INFO)",
            "    --log-file <path>                  Also write a plain-text log to this file",
            "    --skip-validation                  Skip Cedar schema validation (use for large policy sets)",
            "",
            "  file <export-file.json> [options]",
            "    --config <file>                    server-config.yaml supplying principalMapping and gdcCatalogName",
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
        AwsConfig fileAwsConfig = null;

        // If first arg is a readable file, load it as a config file
        if (!flagArgs.isEmpty() && !flagArgs.get(0).startsWith("--")) {
            String possibleConfigPath = flagArgs.get(0);
            if (Files.isReadable(Paths.get(possibleConfigPath))) {
                flagArgs.remove(0);
                try {
                    fileAwsConfig = applyConfigFile(possibleConfigPath, configBuilder);
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
                case "--log-level": {
                    String level = nextArg(flagArgs, i++, flag);
                    if (ch.qos.logback.classic.Level.toLevel(level, null) == null) {
                        System.err.println("Invalid --log-level '" + level
                                + "'. Expected one of: TRACE, DEBUG, INFO, WARN, ERROR, OFF");
                        return 1;
                    }
                    applyLogLevel(level);
                    break;
                }
                case "--log-file": {
                    if (!applyLogFile(nextArg(flagArgs, i++, flag))) {
                        return 1;
                    }
                    break;
                }
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
            configBuilder.awsConfig(mergeAwsConfig(fileAwsConfig, awsRegion, awsProfile));
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
        AwsConfig fileAwsConfig = null;
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
                case "--config": {
                    String configPath = nextArg(flagArgs, i++, flag);
                    if (!Files.isReadable(Paths.get(configPath))) {
                        System.err.println("Cannot read config file: " + configPath);
                        return 1;
                    }
                    try {
                        fileAwsConfig = applyConfigFile(configPath, configBuilder);
                    } catch (IOException e) {
                        System.err.println("Failed to load config file: " + e.getMessage());
                        return 1;
                    }
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
                case "--log-level": {
                    String level = nextArg(flagArgs, i++, flag);
                    if (ch.qos.logback.classic.Level.toLevel(level, null) == null) {
                        System.err.println("Invalid --log-level '" + level
                                + "'. Expected one of: TRACE, DEBUG, INFO, WARN, ERROR, OFF");
                        return 1;
                    }
                    applyLogLevel(level);
                    break;
                }
                case "--log-file": {
                    if (!applyLogFile(nextArg(flagArgs, i++, flag))) {
                        return 1;
                    }
                    break;
                }
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
            configBuilder.awsConfig(mergeAwsConfig(fileAwsConfig, awsRegion, awsProfile));
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

    private static AwsConfig applyConfigFile(String configFilePath, AssessmentConfig.Builder builder)
            throws IOException {
        // Environment overrides are deliberately disabled here. The sync server wants them, but
        // an assessment must be reproducible: with System::getenv, an ambient AWS_REGION would
        // synthesize an awsConfig the YAML never declared, which silently switches the run from
        // the offline PassthroughCatalogResolver to a Glue-backed one. Every Glue call then fails
        // for want of credentials, wildcard resources expand to nothing, and the affected
        // policies are misreported as UNMAPPED_RESOURCE instead of WILDCARD_PATTERN. AWS access
        // is opted into explicitly, via --aws-region/--aws-profile or an awsConfig block.
        ServerConfigLoader loader = new ServerConfigLoader(name -> null);
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

        // ConfigLoader always materializes an AwsConfig, even when the YAML declares none, so a
        // null check is not enough — an all-null AwsConfig would still switch the assessment onto
        // a Glue-backed CatalogResolver whose every call fails. Require a region.
        if (syncConfig.getAwsConfig() != null
                && syncConfig.getAwsConfig().getRegion() != null
                && !syncConfig.getAwsConfig().getRegion().isBlank()) {
            builder.awsConfig(syncConfig.getAwsConfig());
        }
        return syncConfig.getAwsConfig();
    }

    /**
     * Combines the {@code awsConfig} a config file declared with the {@code --aws-region} and
     * {@code --aws-profile} flags, letting the flags win only on the fields they actually supply.
     *
     * <p>These flags used to replace the whole block, which silently dropped every other field —
     * most damagingly {@code catalogId}, which the service adapters string-concatenate into Glue
     * ARNs. A null there produced ARNs containing the literal text {@code "null"} and a
     * {@code catalogId} of {@code "null"} on every projected grant. Because the flags are the
     * documented way to turn on Glue wildcard expansion, a catalogId in the config file could
     * never take effect.
     */
    private static AwsConfig mergeAwsConfig(AwsConfig fromFile, String region, String profile) {
        if (fromFile == null) {
            return new AwsConfig(region, null, null, null, null, profile);
        }
        return new AwsConfig(
                region != null ? region : fromFile.getRegion(),
                fromFile.getCatalogId(),
                fromFile.getAccessKey(),
                fromFile.getSecretKey(),
                fromFile.getRoleArn(),
                profile != null ? profile : fromFile.getProfile());
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

    /**
     * Applies {@code --log-level}, overriding the {@code INFO} baked into {@code logback.xml}.
     *
     * <p>Applied during argument parsing rather than at run time so that anything logged while
     * wiring the pipeline is already captured at the requested level.
     */
    private static void applyLogLevel(String level) {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO));
    }

    /**
     * Adds a file appender at {@code path}, in addition to the console and the default rolling
     * file.
     *
     * <p>Note the tool already writes {@code logs/ranger-lf-sync.log} via the {@code FILE} appender
     * in {@code logback.xml}; this flag exists so a run can be captured somewhere specific and
     * shared, without the caller needing to know that default path or supply their own logback
     * configuration.
     *
     * <p>Plain text rather than the JSON layout the default file uses: the point of this flag is a
     * log a human can read and paste into a ticket. Not rolling either — a single assessment run is
     * bounded, unlike the sync server's indefinite lifetime.
     */
    private static boolean applyLogFile(String path) {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.LoggerContext context = root.getLoggerContext();

        ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder =
                new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%logger{36}] %msg%n");
        encoder.start();

        ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.FileAppender<>();
        appender.setContext(context);
        appender.setName("ASSESSMENT_FILE");
        appender.setFile(path);
        appender.setAppend(false);
        appender.setEncoder(encoder);
        try {
            appender.start();
        } catch (RuntimeException e) {
            System.err.println("Cannot write log file '" + path + "': " + e.getMessage());
            return false;
        }
        if (!appender.isStarted()) {
            // Logback reports a bad path via its status manager rather than by throwing.
            System.err.println("Cannot write log file '" + path
                    + "' — check the directory exists and is writable.");
            return false;
        }
        root.addAppender(appender);
        return true;
    }
}
