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
     * Optional static resource map: db → list of table names.
     * When null, GlueCatalogDiscovery is used at startup to populate this list.
     */
    private final Map<String, List<String>> databases;

    @JsonCreator
    public SimulatorConfig(
            @JsonProperty("cycleIntervalSeconds") Integer cycleIntervalSeconds,
            @JsonProperty("awsRegion") String awsRegion,
            @JsonProperty("rangerAdminUrl") String rangerAdminUrl,
            @JsonProperty("rangerAdminUser") String rangerAdminUser,
            @JsonProperty("rangerAdminPassword") String rangerAdminPassword,
            @JsonProperty("principalPool") List<String> principalPool,
            @JsonProperty("principalMappings") Map<String, String> principalMappings,
            @JsonProperty("rangerServiceName") String rangerServiceName,
            @JsonProperty("awsAccountId") String awsAccountId,
            @JsonProperty("cycleWaitTimeoutSeconds") Integer cycleWaitTimeoutSeconds,
            @JsonProperty("statusPort") Integer statusPort,
            @JsonProperty("statusHost") String statusHost,
            @JsonProperty("reproductionBundleDir") String reproductionBundleDir,
            @JsonProperty("databases") Map<String, List<String>> databases) {
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
        this.databases = databases;
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
    /** Returns the configured databases map, or null if Glue discovery should be used. */
    public Map<String, List<String>> getDatabases() { return databases; }

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
