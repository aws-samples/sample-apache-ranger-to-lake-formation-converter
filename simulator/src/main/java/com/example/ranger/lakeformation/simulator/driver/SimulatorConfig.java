package com.example.ranger.lakeformation.simulator.driver;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulatorConfig {

    // Defaults
    private static final int DEFAULT_CYCLE_INTERVAL_SECONDS = 60;
    private static final String DEFAULT_AWS_REGION = "us-east-1";
    private static final int DEFAULT_CYCLE_WAIT_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_STATUS_PORT = 18080;
    private static final String DEFAULT_STATUS_HOST = "localhost";
    private static final String DEFAULT_HIVE_SERVICE_NAME      = "hive";
    private static final String DEFAULT_TRINO_SERVICE_NAME     = "trino";
    private static final String DEFAULT_EMRFS_SERVICE_NAME     = "amazon-emr-emrfs";
    private static final String DEFAULT_EMR_SPARK_SERVICE_NAME = "amazon-emr-spark";
    private static final String DEFAULT_TAG_SERVICE_NAME       = "cl_tag";
    private static final List<String> DEFAULT_S3_PREFIXES  =
            List.of("s3://my-bucket/data/", "s3://my-bucket/logs/");

    private final int cycleIntervalSeconds;
    private final String awsRegion;
    private final String rangerAdminUrl;
    private final String rangerAdminUser;
    private final String rangerAdminPassword;
    private final List<String> principalPool;
    private final Map<String, String> principalMappings;
    private final String rangerServiceName;
    private final String awsAccountId;
    private final int cycleWaitTimeoutSeconds;
    private final int statusPort;
    private final String statusHost;
    private final String reproductionBundleDir;
    /**
     * Optional path for the human-readable round-by-round report (Ranger mutations, derived LF
     * grants/revokes, and full LF state per cycle). When null, no report is written.
     */
    private final String roundReportPath;
    /**
     * Optional static resource map: db → list of table names.
     * When null, GlueCatalogDiscovery is used at startup to populate this list.
     */
    private final Map<String, List<String>> databases;
    private final String hiveServiceName;
    private final String trinoServiceName;
    private final String emrfsServiceName;
    private final String emrSparkServiceName;
    private final String tagServiceName;
    private final List<String> s3Prefixes;
    /** Optional IAM role ARN to assume for all AWS API calls. If null, uses the default credential chain. */
    private final String roleArn;
    /** Optional AWS credentials profile name. Takes precedence over roleArn when set. */
    private final String awsProfile;
    /**
     * When true, EMR Spark policies are included in Phase2 validation (requires the sync service
     * to be configured with amazon-emr-spark in its rangerServices list). Default: false.
     */
    private final boolean validateEmrSpark;

    /**
     * Optional explicit S3 Access Grants instance ARN. If null, SimulatorMain will attempt
     * to auto-detect it via GetAccessGrantsInstance. Set to empty string to disable S3AG validation.
     */
    private final String s3agInstanceArn;

    @JsonCreator
    public SimulatorConfig(
            @JsonProperty("cycleIntervalSeconds")    Integer cycleIntervalSeconds,
            @JsonProperty("awsRegion")               String  awsRegion,
            @JsonProperty("rangerAdminUrl")          String  rangerAdminUrl,
            @JsonProperty("rangerAdminUser")         String  rangerAdminUser,
            @JsonProperty("rangerAdminPassword")     String  rangerAdminPassword,
            @JsonProperty("principalPool")           List<String> principalPool,
            @JsonProperty("principalMappings")       Map<String, String> principalMappings,
            @JsonProperty("rangerServiceName")       String  rangerServiceName,
            @JsonProperty("awsAccountId")            String  awsAccountId,
            @JsonProperty("cycleWaitTimeoutSeconds") Integer cycleWaitTimeoutSeconds,
            @JsonProperty("statusPort")              Integer statusPort,
            @JsonProperty("statusHost")              String  statusHost,
            @JsonProperty("reproductionBundleDir")   String  reproductionBundleDir,
            @JsonProperty("databases")               Map<String, List<String>> databases,
            @JsonProperty("trinoServiceName")        String  trinoServiceName,
            @JsonProperty("emrfsServiceName")        String  emrfsServiceName,
            @JsonProperty("emrSparkServiceName")     String  emrSparkServiceName,
            @JsonProperty("tagServiceName")          String  tagServiceName,
            @JsonProperty("s3Prefixes")              List<String> s3Prefixes,
            @JsonProperty("roleArn")                 String  roleArn,
            @JsonProperty("validateEmrSpark")        Boolean validateEmrSpark,
            @JsonProperty("awsProfile")              String  awsProfile,
            @JsonProperty("s3agInstanceArn")         String  s3agInstanceArn,
            @JsonProperty("hiveServiceName")         String  hiveServiceName,
            @JsonProperty("roundReportPath")         String  roundReportPath) {
        this.cycleIntervalSeconds = cycleIntervalSeconds != null ? cycleIntervalSeconds : DEFAULT_CYCLE_INTERVAL_SECONDS;
        this.awsRegion = awsRegion != null ? awsRegion : DEFAULT_AWS_REGION;
        this.rangerAdminUrl = rangerAdminUrl;
        this.rangerAdminUser = rangerAdminUser;
        this.rangerAdminPassword = rangerAdminPassword;
        this.principalPool = principalPool != null ? List.copyOf(principalPool) : List.of();
        this.principalMappings = principalMappings != null ? Map.copyOf(principalMappings) : Map.of();
        this.rangerServiceName = rangerServiceName != null ? rangerServiceName : "lakeformation";
        this.awsAccountId = awsAccountId != null ? awsAccountId : "unknown";
        this.cycleWaitTimeoutSeconds = cycleWaitTimeoutSeconds != null ? cycleWaitTimeoutSeconds : DEFAULT_CYCLE_WAIT_TIMEOUT_SECONDS;
        this.statusPort = statusPort != null ? statusPort : DEFAULT_STATUS_PORT;
        this.statusHost = statusHost != null ? statusHost : DEFAULT_STATUS_HOST;
        this.reproductionBundleDir = reproductionBundleDir != null ? reproductionBundleDir : "reproduction-bundles";
        this.roundReportPath = (roundReportPath != null && !roundReportPath.isBlank()) ? roundReportPath : null;
        this.databases = databases;
        this.hiveServiceName      = hiveServiceName      != null ? hiveServiceName      : DEFAULT_HIVE_SERVICE_NAME;
        this.trinoServiceName     = trinoServiceName     != null ? trinoServiceName     : DEFAULT_TRINO_SERVICE_NAME;
        this.emrfsServiceName     = emrfsServiceName     != null ? emrfsServiceName     : DEFAULT_EMRFS_SERVICE_NAME;
        this.emrSparkServiceName  = emrSparkServiceName  != null ? emrSparkServiceName  : DEFAULT_EMR_SPARK_SERVICE_NAME;
        this.tagServiceName       = tagServiceName       != null ? tagServiceName       : DEFAULT_TAG_SERVICE_NAME;
        this.s3Prefixes       = s3Prefixes != null && !s3Prefixes.isEmpty()
                                ? List.copyOf(s3Prefixes)
                                : DEFAULT_S3_PREFIXES;
        this.roleArn = (roleArn != null && !roleArn.isBlank()) ? roleArn : null;
        this.validateEmrSpark = Boolean.TRUE.equals(validateEmrSpark);
        this.awsProfile = (awsProfile != null && !awsProfile.isBlank()) ? awsProfile : null;
        this.s3agInstanceArn = (s3agInstanceArn != null && !s3agInstanceArn.isBlank()) ? s3agInstanceArn : null;
    }

    public int getCycleIntervalSeconds() { return cycleIntervalSeconds; }
    public String getAwsRegion() { return awsRegion; }
    public String getRangerAdminUrl() { return rangerAdminUrl; }
    public String getRangerAdminUser() { return rangerAdminUser; }
    public String getRangerAdminPassword() { return rangerAdminPassword; }
    public List<String> getPrincipalPool() { return principalPool; }
    public Map<String, String> getPrincipalMappings() { return principalMappings; }
    public String getRangerServiceName() { return rangerServiceName; }
    public String getAwsAccountId() { return awsAccountId; }
    public int getCycleWaitTimeoutSeconds() { return cycleWaitTimeoutSeconds; }
    public int getStatusPort() { return statusPort; }
    public String getStatusHost() { return statusHost; }
    public String getReproductionBundleDir() { return reproductionBundleDir; }
    /** Returns the round-by-round report path, or null if no report should be written. */
    public String getRoundReportPath() { return roundReportPath; }
    /** Returns the configured databases map, or null if Glue discovery should be used. */
    public Map<String, List<String>> getDatabases() { return databases; }
    public String       getHiveServiceName()     { return hiveServiceName; }
    public String       getTrinoServiceName()    { return trinoServiceName; }
    public String       getEmrfsServiceName()    { return emrfsServiceName; }
    public String       getEmrSparkServiceName() { return emrSparkServiceName; }
    public String       getTagServiceName()      { return tagServiceName; }
    public List<String> getS3Prefixes()       { return s3Prefixes; }
    /** Returns the IAM role ARN to assume, or null to use the default credential chain. */
    public String getRoleArn()                { return roleArn; }
    /** Returns the AWS profile name, or null to use the default credential chain. */
    public String getAwsProfile()             { return awsProfile; }
    /** Returns true if EMR Spark policies should be included in Phase2 LF validation. */
    public boolean isValidateEmrSpark()       { return validateEmrSpark; }
    /**
     * Returns the explicitly configured S3 Access Grants instance ARN, or null if auto-detection
     * should be attempted. Returns empty string if S3AG validation is explicitly disabled.
     */
    public String getS3agInstanceArn()        { return s3agInstanceArn; }

    @Override
    public String toString() {
        return "SimulatorConfig{cycleIntervalSeconds=" + cycleIntervalSeconds +
                ", awsRegion='" + awsRegion + '\'' +
                ", rangerAdminUrl='" + rangerAdminUrl + '\'' +
                ", statusHost='" + statusHost + '\'' +
                ", statusPort=" + statusPort +
                ", principalPool=" + principalPool + '}';
    }
}
