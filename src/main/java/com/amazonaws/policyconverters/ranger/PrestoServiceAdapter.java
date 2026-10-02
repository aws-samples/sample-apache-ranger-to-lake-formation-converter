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
 * {@link SourcePolicyAdapter} for the Presto Ranger service definition.
 *
 * <p>Maps Presto access types to Cedar action name strings and produces
 * AWS Glue ARN-formatted entity identifiers. The Presto resource hierarchy
 * is catalog → schema → table → column, where "schema" maps to "database"
 * in the Glue Data Catalog model.
 *
 * <p>Only policies whose catalog resource matches the configured
 * {@code gdcCatalogName} are processed; all others are skipped with a
 * DEBUG log.
 */
public class PrestoServiceAdapter implements SourcePolicyAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(PrestoServiceAdapter.class);

    private static final String SERVICE_TYPE = "presto";

    private static final Map<String, Set<String>> ACTION_MAPPING;

    static {
        Map<String, Set<String>> m = new HashMap<>();
        m.put("select", Collections.singleton("SELECT"));
        m.put("insert", Collections.singleton("INSERT"));
        m.put("delete", Collections.singleton("DELETE"));
        m.put("create", Collections.singleton("CREATE_TABLE"));
        m.put("drop", Collections.singleton("DROP"));
        m.put("alter", Collections.singleton("ALTER"));
        m.put("use", Collections.singleton("DESCRIBE"));
        m.put("show", Collections.singleton("DESCRIBE"));
        // "read"/"write" only appear on url (external_location) policies in real Presto exports,
        // mirroring HiveServiceAdapter's mapping so mapAccessTypeToCedarActions(type, "url") below
        // recognises them as known access types and collapses them to DATA_LOCATION_ACCESS.
        m.put("read", Collections.singleton("SELECT"));
        m.put("write", Collections.singleton("INSERT"));
        m.put("update", Collections.singleton("INSERT"));
        // "all" is Ranger's shorthand for every privilege on the resource. It maps to LF's own
        // ALL ("Super") permission rather than being expanded, matching HiveServiceAdapter, and
        // is already accepted by the table/column/database resource-level filters below.
        m.put("all", Collections.singleton("ALL"));
        ACTION_MAPPING = Collections.unmodifiableMap(m);
    }

    /**
     * Presto access types recognised but deliberately not mapped, because Lake Formation has no
     * equivalent concept:
     * <ul>
     *   <li>{@code grant}/{@code revoke} — standalone privileges to delegate access. LF models
     *       delegation as a per-permission grant option, not a standalone privilege. Mapping these
     *       onto the grant option is a tracked follow-up, not a silent drop.</li>
     *   <li>{@code execute} — the right to run a function or procedure; LF governs data access
     *       only.</li>
     *   <li>{@code impersonate} — the right to act as another user; an engine-level capability
     *       with no catalog-permission analogue.</li>
     * </ul>
     * Distinct from an unrecognised access type, which signals a coverage gap in this adapter and
     * stays actionable.
     */
    private static final Set<String> UNMAPPED_ACCESS_TYPES =
            Set.of("grant", "revoke", "execute", "impersonate");

    private final AwsContext awsContext;
    private final String gdcCatalogName;
    private volatile MetricsEmitter metricsEmitter;

    public PrestoServiceAdapter(AwsContext awsContext, String gdcCatalogName) {
        this(awsContext, gdcCatalogName, null);
    }

    public PrestoServiceAdapter(AwsContext awsContext, String gdcCatalogName, MetricsEmitter metricsEmitter) {
        this.awsContext = awsContext;
        this.gdcCatalogName = gdcCatalogName;
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

    /**
     * Returns the configured GDC catalog name used for policy filtering.
     */
    public String getGdcCatalogName() {
        return gdcCatalogName;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public Set<String> getKnownUnmappableAccessTypes() {
        return UNMAPPED_ACCESS_TYPES;
    }

    @Override
    public Set<String> mapAccessTypeToCedarActions(String sourceAccessType) {
        if (sourceAccessType == null || sourceAccessType.trim().isEmpty()) {
            LOG.debug("Null or empty access type provided");
            return Collections.emptySet();
        }
        String normalized = sourceAccessType.trim().toLowerCase();

        // Known unmapped types — log and return empty
        if (UNMAPPED_ACCESS_TYPES.contains(normalized)) {
            LOG.debug("Presto access type \'{}\' has no Cedar mapping and will be skipped", sourceAccessType);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedAccessType(sourceAccessType);
            }
            return Collections.emptySet();
        }

        Set<String> result = ACTION_MAPPING.get(normalized);
        if (result == null) {
            LOG.debug("Unknown Presto access type: '{}' — this access type will be skipped, "
                    + "affected policy items may lose permissions", sourceAccessType);
            if (metricsEmitter != null) {
                metricsEmitter.recordUnmappedAccessType(sourceAccessType);
            }
            return Collections.emptySet();
        }
        return result;
    }

    private static final Set<String> TABLE_VALID_ACTIONS =
            Set.of("SELECT", "INSERT", "DELETE", "DESCRIBE", "ALTER", "DROP", "ALL");
    private static final Set<String> COLUMN_VALID_ACTIONS = Set.of("SELECT");
    private static final Set<String> DATABASE_VALID_ACTIONS =
            Set.of("CREATE_TABLE", "ALTER", "DROP", "DESCRIBE", "ALL");

    @Override
    public Set<String> mapAccessTypeToCedarActions(String sourceAccessType, String resourceLevel) {
        if ("url".equals(resourceLevel)) {
            // Mirrors HiveServiceAdapter: Lake Formation accepts only DATA_LOCATION_ACCESS on a
            // data location, so any recognised access type (Presto url policies use "read" and
            // "write") collapses to that single action rather than SELECT/INSERT.
            Set<String> raw = mapAccessTypeToCedarActions(sourceAccessType);
            return raw.isEmpty() ? Collections.emptySet() : Collections.singleton("DATA_LOCATION_ACCESS");
        }
        Set<String> actions = mapAccessTypeToCedarActions(sourceAccessType);
        if ("column".equals(resourceLevel)) {
            if (actions.contains("ALL")) {
                // LF rejects ALL on a column-restricted (TableWithColumns) resource — verified
                // directly against the API. Narrow to the one permission LF accepts here rather
                // than dropping the grant entirely.
                return Collections.singleton("SELECT");
            }
            return actions.stream()
                    .filter(COLUMN_VALID_ACTIONS::contains)
                    .collect(java.util.stream.Collectors.toSet());
        }
        if ("table".equals(resourceLevel)) {
            return actions.stream()
                    .filter(TABLE_VALID_ACTIONS::contains)
                    .collect(java.util.stream.Collectors.toSet());
        }
        if ("schema".equals(resourceLevel) || "database".equals(resourceLevel)) {
            return actions.stream()
                    .filter(DATABASE_VALID_ACTIONS::contains)
                    .collect(java.util.stream.Collectors.toSet());
        }
        return actions;
    }

    @Override
    public boolean shouldProcessPolicy(RangerPolicy policy) {
        // No GDC catalog configured (e.g. assessment runs without a config file) —
        // catalog filtering is disabled and every policy is processed.
        if (gdcCatalogName == null || gdcCatalogName.isBlank()) {
            return true;
        }
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            return true;
        }
        RangerPolicyResource catalogResource = resources.get("catalog");
        if (catalogResource == null) {
            return true;
        }
        List<String> values = catalogResource.getValues();
        if (values == null || values.isEmpty()) {
            return true;
        }
        String catalogValue = values.get(0);
        if (gdcCatalogName.equals(catalogValue)) {
            return true;
        }
        LOG.debug("Skipping Presto policy '{}' (id={}) — catalog '{}' does not match configured GDC catalog '{}'",
                policy.getName(), policy.getId(), catalogValue, gdcCatalogName);
        return false;
    }

    /**
     * Presto resource keys that name a real Ranger resource type but have no shape in the Glue
     * Data Catalog / Lake Formation model at all — not a database, table, column, or data
     * location. Without this check these policies fall through {@code determineResourceLevel}'s
     * default to "database" and (if any of their access types happen to be database-valid, e.g.
     * "alter") would silently project a grant onto a nonexistent or wrong database.
     */
    private static final Set<String> PRESTO_ONLY_RESOURCE_TYPES =
            Set.of("procedure", "function", "trinouser", "sessionproperty", "systemproperty");

    @Override
    public Optional<String> detectUnmappableResourceType(RangerPolicy policy) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            return Optional.empty();
        }
        for (String key : PRESTO_ONLY_RESOURCE_TYPES) {
            RangerPolicyResource res = resources.get(key);
            if (res != null && res.getValues() != null && !res.getValues().isEmpty()) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    @Override
    public CedarEntityRef buildEntityRef(RangerPolicy policy, String resourceLevel) {
        Map<String, RangerPolicyResource> resources = policy.getResources();
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("Policy has no resources");
        }

        switch (resourceLevel) {
            case "schema": {
                // Presto "schema" maps to "database" in Glue
                String schema = getFirstResourceValue(resources, "schema");
                return new CedarEntityRef("DataCatalog::Database", buildDatabaseArn(schema));
            }
            case "table": {
                String schema = getFirstResourceValue(resources, "schema");
                String table = getFirstResourceValue(resources, "table");
                return new CedarEntityRef("DataCatalog::Table", buildTableArn(schema, table));
            }
            case "column": {
                String schema = getFirstResourceValue(resources, "schema");
                String table = getFirstResourceValue(resources, "table");
                String col = getFirstResourceValue(resources, "column");
                return new CedarEntityRef("DataCatalog::Column", buildColumnArn(schema, table, col));
            }
            case "url": {
                // Guards CREATE TABLE ... WITH (external_location = ...) in Presto — the same
                // semantics as Lake Formation's DataLocation, mirroring HiveServiceAdapter.
                String url = getFirstResourceValue(resources, "url");
                return new CedarEntityRef("DataCatalog::DataLocation",
                        url != null ? url.replaceFirst("^s3://", "arn:aws:s3:::") : "");
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
     * In Presto, "schema" maps to "database" in the Glue Data Catalog.
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
     * <p>For Presto, the "schema" resource level maps to "database" in Glue ARNs.
     *
     * @param resourceLevel one of "schema", "table", "column", "url"
     * @param database      database/schema name (required for schema/table/column levels)
     * @param table         table name (required for table/column levels, null otherwise)
     * @param column        column name (required for column level, null otherwise)
     * @param dataLocation  S3 location (required for url level, null otherwise)
     * @return the constructed CedarEntityRef
     */
    public CedarEntityRef buildEntityRefFromValues(String resourceLevel,
                                                   String database,
                                                   String table,
                                                   String column,
                                                   String dataLocation) {
        switch (resourceLevel) {
            // "database" is the converter's canonical name for the schema/database level; Presto's
            // own resource key is "schema". Accept both so the converter's database-level branch works.
            case "schema":
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
