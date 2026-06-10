package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmrSparkServiceAdapterTest {

    private static final AwsContext CTX = new AwsContext("us-east-1", "123456789012", "123456789012");
    private EmrSparkServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new EmrSparkServiceAdapter(CTX);
    }

    @Test
    void serviceType_isAmazonEmrSpark() {
        assertEquals("amazon-emr-spark", adapter.getServiceType());
    }

    @Test
    void select_mapsToCatalogSelect() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("select"));
    }

    @Test
    void update_mapsToInsert() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("update"));
    }

    @Test
    void alter_mapsToAlter() {
        assertEquals(Set.of("ALTER"), adapter.mapAccessTypeToCedarActions("alter"));
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
    void read_mapsToSelect() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("read"));
    }

    @Test
    void write_mapsToInsert() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("write"));
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
    void unknown_accessType_returnsEmpty() {
        assertTrue(adapter.mapAccessTypeToCedarActions("lock").isEmpty());
    }

    @Test
    void buildEntityRef_database_producesGlueDatabaseArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("database", "mydb", null, null, null);
        assertEquals("DataCatalog::Database", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:database/mydb", ref.getEntityId());
    }

    @Test
    void buildEntityRef_table_producesGlueTableArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("table", "mydb", "mytable", null, null);
        assertEquals("DataCatalog::Table", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:table/mydb/mytable", ref.getEntityId());
    }

    @Test
    void buildEntityRef_column_producesGlueColumnArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("column", "mydb", "mytable", "mycol", null);
        assertEquals("DataCatalog::Column", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:column/mydb/mytable/mycol", ref.getEntityId());
    }

    @Test
    void buildEntityRef_url_stripsS3SchemeAndProducesS3Arn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("url", null, null, null, "s3://my-bucket/data/");
        assertEquals("DataCatalog::DataLocation", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/data/", ref.getEntityId());
    }

    @Test
    void buildEntityRef_url_withoutS3Scheme_prefixesArn() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("url", null, null, null, "my-bucket/path");
        assertEquals("DataCatalog::DataLocation", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/path", ref.getEntityId());
    }

    @Test
    void buildEntityRef_url_withWildcard_preservesWildcard() {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("url", null, null, null, "s3://bucket/*");
        assertEquals("arn:aws:s3:::bucket/*", ref.getEntityId());
    }

    @Test
    void buildEntityRef_unknownLevel_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                adapter.buildEntityRefFromValues("unknown", "db", null, null, null));
    }

    @Test
    void buildPrincipalRef_returnsInputUnchanged() {
        assertEquals("arn:aws:iam::123:user/bob", adapter.buildPrincipalRef("arn:aws:iam::123:user/bob"));
    }

    @Test
    void getAwsContext_returnsNonEmpty() {
        assertTrue(adapter.getAwsContext().isPresent());
        assertEquals("us-east-1", adapter.getAwsContext().get().getRegion());
    }

    @Test
    void setMetricsEmitter_recordsUnmappedAccessType() {
        MetricsEmitter emitter = mock(MetricsEmitter.class);
        adapter.setMetricsEmitter(emitter);
        adapter.mapAccessTypeToCedarActions("bogus_type");
        verify(emitter).recordUnmappedAccessType("bogus_type");
    }

    // ---- mapAccessTypeToCedarActions (two-arg, url resource level) ----

    @Test
    void twoArg_urlLevel_select_mapsToDataLocationAccess() {
        assertEquals(Set.of("DATA_LOCATION_ACCESS"),
                adapter.mapAccessTypeToCedarActions("select", "url"));
    }

    @Test
    void twoArg_urlLevel_read_mapsToDataLocationAccess() {
        assertEquals(Set.of("DATA_LOCATION_ACCESS"),
                adapter.mapAccessTypeToCedarActions("read", "url"));
    }

    @Test
    void twoArg_urlLevel_write_mapsToDataLocationAccess() {
        assertEquals(Set.of("DATA_LOCATION_ACCESS"),
                adapter.mapAccessTypeToCedarActions("write", "url"));
    }

    @Test
    void twoArg_tableLevel_select_mapsToCatalogSelect() {
        assertEquals(Set.of("SELECT"),
                adapter.mapAccessTypeToCedarActions("select", "table"));
    }

    @Test
    void twoArg_databaseLevel_alter_mapsToAlter() {
        assertEquals(Set.of("ALTER"),
                adapter.mapAccessTypeToCedarActions("alter", "database"));
    }
}
