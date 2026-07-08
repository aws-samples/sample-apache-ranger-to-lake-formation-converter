package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TrinoServiceAdapter}.
 *
 * <p>Trino's resource hierarchy is catalog → schema → table → column, where "schema" maps to the
 * Glue "database". These tests lock in the access-type mapping, catalog-based policy filtering, and
 * ARN construction — including the regression guard that {@code buildEntityRefFromValues} accepts
 * both "schema" and the converter's canonical "database" resource level (see
 * RangerToCedarConverterTest.trinoTablePolicyProducesPermitStatements).
 */
class TrinoServiceAdapterTest {

    private static final AwsContext CTX = new AwsContext("us-east-1", "123456789012", "123456789012");
    private static final String CATALOG = "hive";
    private TrinoServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TrinoServiceAdapter(CTX, CATALOG);
    }

    @Test
    void serviceType_isTrino() {
        assertEquals("trino", adapter.getServiceType());
    }

    // ---- access-type mapping (single-arg) ----

    @Test
    void select_mapsToSelect() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("select"));
    }

    @Test
    void insert_mapsToInsert() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("insert"));
    }

    @Test
    void delete_mapsToDelete() {
        assertEquals(Set.of("DELETE"), adapter.mapAccessTypeToCedarActions("delete"));
    }

    @Test
    void create_mapsToCreateTable() {
        assertEquals(Set.of("CREATE_TABLE"), adapter.mapAccessTypeToCedarActions("create"));
    }

    @Test
    void drop_mapsToDrop() {
        assertEquals(Set.of("DROP"), adapter.mapAccessTypeToCedarActions("drop"));
    }

    @Test
    void alter_mapsToAlter() {
        assertEquals(Set.of("ALTER"), adapter.mapAccessTypeToCedarActions("alter"));
    }

    @Test
    void use_mapsToDescribe() {
        assertEquals(Set.of("DESCRIBE"), adapter.mapAccessTypeToCedarActions("use"));
    }

    @Test
    void show_mapsToDescribe() {
        assertEquals(Set.of("DESCRIBE"), adapter.mapAccessTypeToCedarActions("show"));
    }

    @Test
    void accessType_isCaseInsensitiveAndTrimmed() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("  SELECT  "));
    }

    @Test
    void null_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions(null).isEmpty());
    }

    @Test
    void empty_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions("").isEmpty());
    }

    @Test
    void unmapped_grant_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions("grant").isEmpty());
    }

    @Test
    void unknown_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions("lock").isEmpty());
    }

    // ---- access-type mapping (two-arg, resource-level filtered) ----

    @Test
    void twoArg_columnLevel_select_isKept() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("select", "column"));
    }

    @Test
    void twoArg_columnLevel_alter_isFilteredOut() {
        // ALTER is not a valid column action → filtered to empty.
        assertTrue(adapter.mapAccessTypeToCedarActions("alter", "column").isEmpty());
    }

    @Test
    void twoArg_tableLevel_alter_isKept() {
        assertEquals(Set.of("ALTER"), adapter.mapAccessTypeToCedarActions("alter", "table"));
    }

    @Test
    void twoArg_schemaLevel_createTable_isKept() {
        assertEquals(Set.of("CREATE_TABLE"), adapter.mapAccessTypeToCedarActions("create", "schema"));
    }

    @Test
    void twoArg_schemaLevel_select_isFilteredOut() {
        // SELECT is not a valid database-level action → filtered to empty.
        assertTrue(adapter.mapAccessTypeToCedarActions("select", "schema").isEmpty());
    }

    // ---- ARN construction from explicit values ----

    @Test
    void buildEntityRef_schema_producesGlueDatabaseArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("schema", "analytics", null, null, null);
        assertEquals("DataCatalog::Database", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:database/analytics", ref.getEntityId());
    }

    @Test
    void buildEntityRef_database_aliasesToSchema() {
        // Regression guard: the converter's database-level branch passes "database"; the Trino
        // adapter must treat it as the schema level rather than throwing. Before the fix, Trino
        // table/schema policies produced zero LF grants.
        CedarEntityRef ref = adapter.buildEntityRefFromValues("database", "analytics", null, null, null);
        assertEquals("DataCatalog::Database", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:database/analytics", ref.getEntityId());
    }

    @Test
    void buildEntityRef_table_producesGlueTableArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("table", "analytics", "events", null, null);
        assertEquals("DataCatalog::Table", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:table/analytics/events", ref.getEntityId());
    }

    @Test
    void buildEntityRef_column_producesGlueColumnArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("column", "analytics", "events", "id", null);
        assertEquals("DataCatalog::Column", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:column/analytics/events/id", ref.getEntityId());
    }

    @Test
    void buildEntityRef_unknownLevel_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                adapter.buildEntityRefFromValues("url", "db", null, null, null));
    }

    // ---- ARN construction from a policy ----

    @Test
    void buildEntityRefFromPolicy_table_usesSchemaAsDatabase() {
        RangerPolicy policy = trinoPolicy(CATALOG, "analytics", "events", null);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "table");
        assertEquals("DataCatalog::Table", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:table/analytics/events", ref.getEntityId());
    }

    // ---- catalog-based policy filtering ----

    @Test
    void shouldProcessPolicy_matchingCatalog_returnsTrue() {
        RangerPolicy policy = trinoPolicy(CATALOG, "analytics", "events", null);
        assertTrue(adapter.shouldProcessPolicy(policy));
    }

    @Test
    void shouldProcessPolicy_nonMatchingCatalog_returnsFalse() {
        RangerPolicy policy = trinoPolicy("other_catalog", "analytics", "events", null);
        assertFalse(adapter.shouldProcessPolicy(policy));
    }

    @Test
    void shouldProcessPolicy_noCatalogResource_returnsTrue() {
        RangerPolicy policy = new RangerPolicy();
        policy.setResources(new HashMap<>());
        assertTrue(adapter.shouldProcessPolicy(policy));
    }

    // ---- misc ----

    @Test
    void buildPrincipalRef_returnsInputUnchanged() {
        assertEquals("arn:aws:iam::123:role/r", adapter.buildPrincipalRef("arn:aws:iam::123:role/r"));
    }

    @Test
    void getAwsContext_returnsNonEmpty() {
        assertTrue(adapter.getAwsContext().isPresent());
        assertEquals("us-east-1", adapter.getAwsContext().get().getRegion());
    }

    @Test
    void getGdcCatalogName_returnsConfiguredValue() {
        assertEquals(CATALOG, adapter.getGdcCatalogName());
    }

    @Test
    void setMetricsEmitter_recordsUnmappedAccessType() {
        MetricsEmitter emitter = mock(MetricsEmitter.class);
        adapter.setMetricsEmitter(emitter);
        adapter.mapAccessTypeToCedarActions("bogus_type");
        verify(emitter).recordUnmappedAccessType("bogus_type");
    }

    /** Build a Trino policy with catalog/schema[/table] resources. */
    private static RangerPolicy trinoPolicy(String catalog, String schema, String table, String column) {
        RangerPolicy policy = new RangerPolicy();
        policy.setService("trino");
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("catalog", resource(catalog));
        resources.put("schema", resource(schema));
        if (table != null) {
            resources.put("table", resource(table));
        }
        if (column != null) {
            resources.put("column", resource(column));
        }
        policy.setResources(resources);
        return policy;
    }

    private static RangerPolicyResource resource(String value) {
        RangerPolicyResource res = new RangerPolicyResource();
        res.setValues(List.of(value));
        res.setIsExcludes(false);
        return res;
    }
}
