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

/**
 * {@link SourcePolicyAdapter} for the Apache Hive Ranger service definition.
 *
 * <p>Maps Hive access types to Cedar action name strings and produces
 * AWS Glue ARN-formatted entity identifiers (same format as
 * {@link RangerServiceAdapter}) for the database/table/column hierarchy.
 */
public class HiveServiceAdapter implements SourcePolicyAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(HiveServiceAdapter.class);

    private static final String SERVICE_TYPE = "hive";

    private static final Map<String, Set<String>> ACTION_MAPPING;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("select", Collections.singleton("SELECT"));
        m.put("update", Collections.singleton("INSERT"));
        m.put("create", Collections.singleton("CREATE_TABLE"));
        m.put("drop", Collections.singleton("DROP"));
        m.put("alter", Collections.singleton("ALTER"));
        m.put("read", Collections.singleton("SELECT"));
        m.put("write", Collections.singleton("INSERT"));
        m.put("all", Collections.singleton("ALL"));
        ACTION_MAPPING = Collections.unmodifiableMap(m);
    }

    /** Hive access types that have no Cedar mapping and are silently skipped with a log. */
    private static final Set<String> UNMAPPED_ACCESS_TYPES = Set.of("index", "lock");

    private final AwsContext awsContext;
    private volatile MetricsEmitter metricsEmitter;

    public HiveServiceAdapter(AwsContext awsContext) {
        this(awsContext, null);
    }

    public HiveServiceAdapter(AwsContext awsContext, MetricsEmitter metricsEmitter) {
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

        // Known unmapped types — log and return empty
        if (UNMAPPED_ACCESS_TYPES.contains(normalized)) {
            LOG.warn("Hive access type '{}' has no Cedar mapping and will be skipped", sourceAccessType);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedAccessType(sourceAccessType);
            }
            return Collections.emptySet();
        }

        Set<String> result = ACTION_MAPPING.get(normalized);
        if (result == null) {
            LOG.error("Unknown Hive access type: '{}' — this access type will be skipped, "
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

    // ---- ARN construction utilities ----

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
     * Build a {@link CedarEntityRef} from explicit resource values rather than
     * extracting from a policy. Useful when the converter iterates over expanded
     * resource combinations.
     *
     * @param resourceLevel one of "database", "table", "column"
     * @param database      database name (required for all levels)
     * @param table         table name (required for table/column levels, null otherwise)
     * @param column        column name (required for column level, null otherwise)
     * @param dataLocation  unused for Hive (no datalocation resource)
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
            case "url":
                return new CedarEntityRef("DataCatalog::DataLocation",
                        dataLocation != null ? dataLocation.replaceFirst("^s3://", "arn:aws:s3:::") : "");
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
