package com.amazonaws.policyconverters.ranger;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.lakeformation.AwsContext;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerPolicy.RangerPolicyResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RangerServiceAdapterTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");
    private RangerServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RangerServiceAdapter(AWS_CONTEXT);
    }

    // --- getServiceType ---

    @Test
    void getServiceTypeReturnsLakeformation() {
        assertEquals("lakeformation", adapter.getServiceType());
    }

    // --- mapAccessTypeToCedarActions: known types ---

    @Test
    void selectMapsToSELECT() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("select"));
    }

    @Test
    void updateMapsToINSERT() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("update"));
    }

    @Test
    void createMapsToCREATE_TABLE() {
        assertEquals(Set.of("CREATE_TABLE"), adapter.mapAccessTypeToCedarActions("create"));
    }

    @Test
    void dropMapsToDROP() {
        assertEquals(Set.of("DROP"), adapter.mapAccessTypeToCedarActions("drop"));
    }

    @Test
    void alterMapsToALTER() {
        assertEquals(Set.of("ALTER"), adapter.mapAccessTypeToCedarActions("alter"));
    }

    @Test
    void readMapsToSELECT() {
        assertEquals(Set.of("SELECT"), adapter.mapAccessTypeToCedarActions("read"));
    }

    @Test
    void writeMapsToINSERT() {
        assertEquals(Set.of("INSERT"), adapter.mapAccessTypeToCedarActions("write"));
    }

    @Test
    void allMapsToALL() {
        assertEquals(Set.of("ALL"), adapter.mapAccessTypeToCedarActions("all"));
    }

    @Test
    void datalocationMapsToDATA_LOCATION_ACCESS() {
        assertEquals(Set.of("DATA_LOCATION_ACCESS"), adapter.mapAccessTypeToCedarActions("datalocation"));
    }

    @Test
    void dataLocationAccessMapsToDATA_LOCATION_ACCESS() {
        assertEquals(Set.of("DATA_LOCATION_ACCESS"), adapter.mapAccessTypeToCedarActions("data_location_access"));
    }

    // --- mapAccessTypeToCedarActions: null, empty, unknown ---

    @Test
    void nullAccessTypeReturnsEmptySet() {
        assertTrue(adapter.mapAccessTypeToCedarActions(null).isEmpty());
    }

    @Test
    void emptyAccessTypeReturnsEmptySet() {
        assertTrue(adapter.mapAccessTypeToCedarActions("").isEmpty());
    }

    @Test
    void unknownAccessTypeReturnsEmptySet() {
        assertTrue(adapter.mapAccessTypeToCedarActions("unknown_type").isEmpty());
    }

    // --- buildEntityRef: database level ---

    @Test
    void buildEntityRefForDatabaseLevel() {
        RangerPolicy policy = buildPolicyWithResources("mydb", null, null, null);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "database");

        assertEquals("DataCatalog::Database", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:database/mydb", ref.getEntityId());
    }

    // --- buildEntityRef: table level ---

    @Test
    void buildEntityRefForTableLevel() {
        RangerPolicy policy = buildPolicyWithResources("mydb", "orders", null, null);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "table");

        assertEquals("DataCatalog::Table", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:table/mydb/orders", ref.getEntityId());
    }

    // --- buildEntityRef: column level ---

    @Test
    void buildEntityRefForColumnLevel() {
        RangerPolicy policy = buildPolicyWithResources("mydb", "orders", "email", null);
        CedarEntityRef ref = adapter.buildEntityRef(policy, "column");

        assertEquals("DataCatalog::Column", ref.getEntityType());
        assertEquals("arn:aws:glue:us-east-1:123456789012:column/mydb/orders/email", ref.getEntityId());
    }

    // --- buildEntityRef: datalocation level ---

    @Test
    void buildEntityRefForDatalocationLevel() {
        RangerPolicy policy = buildPolicyWithResources(null, null, null, "my-bucket/data/path");
        CedarEntityRef ref = adapter.buildEntityRef(policy, "datalocation");

        assertEquals("DataCatalog::DataLocation", ref.getEntityType());
        assertEquals("arn:aws:s3:::my-bucket/data/path", ref.getEntityId());
    }

    @Test
    void buildDataLocationArn_barePathProducesArn() {
        assertEquals("arn:aws:s3:::my-bucket/data/", adapter.buildDataLocationArn("my-bucket/data/"));
    }

    @Test
    void buildDataLocationArn_s3UrlStrippedBeforeArnPrefix() {
        // Ranger policies stored with s3:// prefix must not produce arn:aws:s3:::s3://...
        assertEquals("arn:aws:s3:::my-bucket/data/", adapter.buildDataLocationArn("s3://my-bucket/data/"));
    }

    // --- buildPrincipalRef ---

    @Test
    void buildPrincipalRefReturnsInputAsIs() {
        String iamArn = "arn:aws:iam::123456789012:role/AnalystRole";
        assertEquals(iamArn, adapter.buildPrincipalRef(iamArn));
    }

    // --- getAwsContext ---

    @Test
    void getAwsContextReturnsOptionalOfContext() {
        Optional<AwsContext> ctx = adapter.getAwsContext();
        assertTrue(ctx.isPresent());
        assertEquals("us-east-1", ctx.get().getRegion());
        assertEquals("123456789012", ctx.get().getAccountId());
    }

    // --- Helper ---

    private static RangerPolicy buildPolicyWithResources(String database, String table,
                                                         String column, String datalocation) {
        RangerPolicy policy = new RangerPolicy();
        policy.setId(1L);
        policy.setName("test_policy");
        policy.setService("lakeformation");

        Map<String, RangerPolicyResource> resources = new HashMap<>();
        if (database != null) {
            RangerPolicyResource dbRes = new RangerPolicyResource();
            dbRes.setValues(Collections.singletonList(database));
            resources.put("database", dbRes);
        }
        if (table != null) {
            RangerPolicyResource tableRes = new RangerPolicyResource();
            tableRes.setValues(Collections.singletonList(table));
            resources.put("table", tableRes);
        }
        if (column != null) {
            RangerPolicyResource colRes = new RangerPolicyResource();
            colRes.setValues(Collections.singletonList(column));
            resources.put("column", colRes);
        }
        if (datalocation != null) {
            RangerPolicyResource locRes = new RangerPolicyResource();
            locRes.setValues(Collections.singletonList(datalocation));
            resources.put("datalocation", locRes);
        }
        policy.setResources(resources);
        return policy;
    }
}
