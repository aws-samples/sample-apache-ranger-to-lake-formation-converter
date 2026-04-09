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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SourcePolicyAdapter} for the Ranger LakeFormation service definition.
 *
 * <p>Maps LakeFormation access types to Cedar action name strings using the same
 * rules as {@link com.amazonaws.policyconverters.ranger.AccessTypeMapper},
 * and produces AWS Glue ARN-formatted entity identifiers and IAM ARN-formatted
 * principal identifiers.
 */
public class RangerServiceAdapter implements SourcePolicyAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(RangerServiceAdapter.class);

    private static final String SERVICE_TYPE = "lakeformation";

    private static final Map<String, Set<String>> ACTION_MAPPING;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("select", Collections.singleton("SELECT"));
        m.put("insert", Collections.singleton("INSERT"));
        m.put("delete", Collections.singleton("DELETE"));
        m.put("describe", Collections.singleton("DESCRIBE"));
        m.put("alter", Collections.singleton("ALTER"));
        m.put("drop", Collections.singleton("DROP"));
        m.put("create_database", Collections.singleton("CREATE_DATABASE"));
        m.put("create_table", Collections.singleton("CREATE_TABLE"));

        // Legacy aliases from older Ranger service definitions
        m.put("update", Collections.singleton("INSERT"));
        m.put("create", Collections.singleton("CREATE_TABLE"));
        m.put("read", Collections.singleton("SELECT"));
        m.put("write", Collections.singleton("INSERT"));

        Set<String> allActions = new HashSet<>();
        allActions.add("SELECT");
        allActions.add("INSERT");
        allActions.add("DELETE");
        allActions.add("ALTER");
        allActions.add("DROP");
        allActions.add("DESCRIBE");
        m.put("all", Collections.unmodifiableSet(allActions));

        m.put("datalocation", Collections.singleton("DATA_LOCATION_ACCESS"));
        m.put("data_location_access", Collections.singleton("DATA_LOCATION_ACCESS"));
        ACTION_MAPPING = Collections.unmodifiableMap(m);
    }

    private final AwsContext awsContext;
    private volatile MetricsEmitter metricsEmitter;

    public RangerServiceAdapter(AwsContext awsContext) {
        this(awsContext, null);
    }

    public RangerServiceAdapter(AwsContext awsContext, MetricsEmitter metricsEmitter) {
        this.awsContext = awsContext;
        this.metricsEmitter = metricsEmitter;
    }

    /**
     * Set the MetricsEmitter for publishing unmapped access type metrics to CloudWatch.
     *
     * @param metricsEmitter the MetricsEmitter instance
     */
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
        Set<String> result = ACTION_MAPPING.get(normalized);
        if (result == null) {
            LOG.error("Unknown Ranger access type: '{}' — this access type will be skipped, "
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
            case "datalocation": {
                String s3Path = getFirstResourceValue(resources, "datalocation");
                return new CedarEntityRef("DataCatalog::DataLocation", buildDataLocationArn(s3Path));
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

    // ---- ARN construction utilities (public for use by the converter) ----

    /**
     * Build a Glue database ARN: {@code arn:aws:glue:{region}:{account}:database/{db}}.
     */
    public String buildDatabaseArn(String database) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":database/" + database;
    }

    /**
     * Build a Glue table ARN: {@code arn:aws:glue:{region}:{account}:table/{db}/{table}}.
     */
    public String buildTableArn(String database, String table) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":table/" + database + "/" + table;
    }

    /**
     * Build a Glue column ARN: {@code arn:aws:glue:{region}:{account}:column/{db}/{table}/{col}}.
     */
    public String buildColumnArn(String database, String table, String column) {
        return "arn:aws:glue:" + awsContext.getRegion() + ":" + awsContext.getAccountId()
                + ":column/" + database + "/" + table + "/" + column;
    }

    /**
     * Build an S3 data location ARN: {@code arn:aws:s3:::{s3Path}}.
     * The s3Path is the raw value from the Ranger policy's datalocation resource
     * (e.g., "my-bucket/data/path").
     */
    public String buildDataLocationArn(String s3Path) {
        return "arn:aws:s3:::" + s3Path;
    }

    /**
     * Build a {@link CedarEntityRef} from explicit resource values rather than
     * extracting from a policy. Useful when the converter iterates over expanded
     * resource combinations.
     *
     * @param resourceLevel one of "database", "table", "column", "datalocation"
     * @param database      database name (required for database/table/column levels)
     * @param table         table name (required for table/column levels, null otherwise)
     * @param column        column name (required for column level, null otherwise)
     * @param dataLocation  S3 path (required for datalocation level, null otherwise)
     * @return the constructed CedarEntityRef
     */
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
            case "datalocation":
                return new CedarEntityRef("DataCatalog::DataLocation", buildDataLocationArn(dataLocation));
            default:
                throw new IllegalArgumentException("Unknown resource level: " + resourceLevel);
        }
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
