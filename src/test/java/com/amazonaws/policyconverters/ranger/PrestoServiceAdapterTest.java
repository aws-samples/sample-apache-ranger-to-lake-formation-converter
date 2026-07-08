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
 * Unit tests for {@link PrestoServiceAdapter}.
 *
 * <p>Presto's resource hierarchy is catalog → schema → table → column, where "schema" maps to the
 * Glue "database". These tests lock in the access-type mapping, catalog-based policy filtering, and
 * ARN construction — including the regression guard that {@code buildEntityRefFromValues} accepts
 * both "schema" and the converter's canonical "database" resource level.
 */
class PrestoServiceAdapterTest {

    private static final AwsContext CTX = new AwsContext("us-east-1", "123456789012", "123456789012");
    private static final String CATALOG = "hive";
    private PrestoServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PrestoServiceAdapter(CTX, CATALOG);
    }

    @Test
    void serviceType_isPresto() {
        assertEquals("presto", adapter.getServiceType());
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
        // Regression guard: the converter's database-level branch passes "database"; the Presto
        // adapter must treat it as the schema level rather than throwing.
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

    // ---- catalog-based policy filtering ----

    @Test
    void shouldProcessPolicy_matchingCatalog_returnsTrue() {
        RangerPolicy policy = prestoPolicy(CATALOG, "analytics", "events");
        assertTrue(adapter.shouldProcessPolicy(policy));
    }

    @Test
    void shouldProcessPolicy_nonMatchingCatalog_returnsFalse() {
        RangerPolicy policy = prestoPolicy("other_catalog", "analytics", "events");
        assertFalse(adapter.shouldProcessPolicy(policy));
    }

    // ---- misc ----

    @Test
    void buildPrincipalRef_returnsInputUnchanged() {
        assertEquals("arn:aws:iam::123:role/r", adapter.buildPrincipalRef("arn:aws:iam::123:role/r"));
    }

    @Test
    void getAwsContext_returnsNonEmpty() {
        assertTrue(adapter.getAwsContext().isPresent());
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

    private static RangerPolicy prestoPolicy(String catalog, String schema, String table) {
        RangerPolicy policy = new RangerPolicy();
        policy.setService("presto");
        Map<String, RangerPolicyResource> resources = new HashMap<>();
        resources.put("catalog", resource(catalog));
        resources.put("schema", resource(schema));
        if (table != null) {
            resources.put("table", resource(table));
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
