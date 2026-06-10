package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.cedar.SourcePolicyAdapter;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EmrSparkServiceAdapter implements SourcePolicyAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(EmrSparkServiceAdapter.class);

    private static final String SERVICE_TYPE = "amazon-emr-spark";

    private static final Map<String, Set<String>> CATALOG_ACTION_MAPPING;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("select", Collections.singleton("SELECT"));
        m.put("update", Collections.singleton("INSERT"));
        m.put("alter",  Collections.singleton("ALTER"));
        m.put("create", Collections.singleton("CREATE_TABLE"));
        m.put("drop",   Collections.singleton("DROP"));
        m.put("read",   Collections.singleton("SELECT"));
        m.put("write",  Collections.singleton("INSERT"));
        CATALOG_ACTION_MAPPING = Collections.unmodifiableMap(m);
    }

    private static final Set<String> URL_ACTIONS = Collections.singleton("DATA_LOCATION_ACCESS");

    private final AwsContext awsContext;
    private volatile MetricsEmitter metricsEmitter;

    public EmrSparkServiceAdapter(AwsContext awsContext) {
        this(awsContext, null);
    }

    public EmrSparkServiceAdapter(AwsContext awsContext, MetricsEmitter metricsEmitter) {
        this.awsContext = awsContext;
        this.metricsEmitter = metricsEmitter;
    }

    public void setMetricsEmitter(MetricsEmitter metricsEmitter) {
        this.metricsEmitter = metricsEmitter;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public Set<String> mapAccessTypeToCedarActions(String sourceAccessType) {
        if (sourceAccessType == null || sourceAccessType.trim().isEmpty()) {
            LOG.error("Null or empty access type provided");
            return Collections.emptySet();
        }
        String normalized = sourceAccessType.trim().toLowerCase();
        Set<String> result = CATALOG_ACTION_MAPPING.get(normalized);
        if (result == null) {
            LOG.error("Unknown EMR Spark access type: '{}' — this access type will be skipped, "
                    + "affected policy items may lose permissions", sourceAccessType);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedAccessType(sourceAccessType);
            }
            return Collections.emptySet();
        }
        return result;
    }

    @Override
    public CedarEntityRef buildEntityRef(RangerPolicy policy, String resourceLevel) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("Policy has no resources");
        }
        switch (resourceLevel) {
            case "database": {
                String db = getFirstResourceValue(resources, "database");
                return new CedarEntityRef("DataCatalog::Database", buildDatabaseArn(db));
            }
            case "table": {
                String db = getFirstResourceValue(resources, "database");
                String table = getFirstResourceValue(resources, "table");
                return new CedarEntityRef("DataCatalog::Table", buildTableArn(db, table));
            }
            case "column": {
                String db = getFirstResourceValue(resources, "database");
                String table = getFirstResourceValue(resources, "table");
                String col = getFirstResourceValue(resources, "column");
                return new CedarEntityRef("DataCatalog::Column", buildColumnArn(db, table, col));
            }
            case "url": {
                String url = getFirstResourceValue(resources, "url");
                return new CedarEntityRef("DataCatalog::DataLocation", buildUrlArn(url));
            }
            default:
                throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
        }
    }

    @Override
    public String buildPrincipalRef(String resolvedPrincipalId) {
        return resolvedPrincipalId;
    }

    @Override
    public Optional<AwsContext> getAwsContext() {
        return Optional.of(awsContext);
    }

    public CedarEntityRef buildEntityRefFromValues(String resourceLevel,
                                                   String database,
                                                   String table,
                                                   String column,
                                                   String dataLocation) {
        switch (resourceLevel) {
            case "database":
                return new CedarEntityRef("DataCatalog::Database", buildDatabaseArn(database));
            case "table":
                return new CedarEntityRef("DataCatalog::Table", buildTableArn(database, table));
            case "column":
                return new CedarEntityRef("DataCatalog::Column", buildColumnArn(database, table, column));
            case "url":
                return new CedarEntityRef("DataCatalog::DataLocation", buildUrlArn(dataLocation));
            default:
                throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
        }
    }

    Set<String> mapUrlAccessType(String sourceAccessType) {
        if (sourceAccessType == null || sourceAccessType.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return URL_ACTIONS;
    }

    private String buildDatabaseArn(String database) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":database/" + database;
    }

    private String buildTableArn(String database, String table) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":table/" + database + "/" + table;
    }

    private String buildColumnArn(String database, String table, String column) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":column/" + database + "/" + table + "/" + column;
    }

    private String buildUrlArn(String url) {
        String path = url != null ? url.replaceFirst("^s3://", "") : "";
        return "arn:aws:s3:::" + path;
    }

    private static String getFirstResourceValue(Map<String, RangerPolicyResource> resources, String key) {
        RangerPolicyResource res = resources.get(key);
        if (res == null) {
            throw new IllegalArgumentException("Resource key '" + key + "' not found in policy resources");
        }
        List<String> values = res.getValues();
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Resource key '" + key + "' has no values");
        }
        return values.get(0);
    }
}
