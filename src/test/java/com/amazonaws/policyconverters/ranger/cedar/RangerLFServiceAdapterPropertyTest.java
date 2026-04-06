package com.amazonaws.policyconverters.ranger.cedar;

import com.amazonaws.policyconverters.cedar.CedarEntityRef;
import com.amazonaws.policyconverters.lakeformation.cedar.AwsContext;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for RangerLFServiceAdapter.
 *
 * Feature: cedar-policy-abstraction, Property 5: Resource Level Determines Cedar Entity Type
 * **Validates: Requirements 4.3, 4.4, 4.5**
 */
class RangerLFServiceAdapterPropertyTest {

    private static final AwsContext AWS_CONTEXT = new AwsContext("us-east-1", "123456789012", "123456789012");
    private final RangerLFServiceAdapter adapter = new RangerLFServiceAdapter(AWS_CONTEXT);

    @Property(tries = 100)
    void databaseLevelProducesDataCatalogDatabase(
            @ForAll("resourceNames") String database
    ) {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("database", database, null, null, null);
        assertTrue(ref.getEntityType().startsWith("DataCatalog::Database"),
                "Expected entity type starting with DataCatalog::Database but got: " + ref.getEntityType());
    }

    @Property(tries = 100)
    void tableLevelProducesDataCatalogTable(
            @ForAll("resourceNames") String database,
            @ForAll("resourceNames") String table
    ) {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("table", database, table, null, null);
        assertTrue(ref.getEntityType().startsWith("DataCatalog::Table"),
                "Expected entity type starting with DataCatalog::Table but got: " + ref.getEntityType());
    }

    @Property(tries = 100)
    void columnLevelProducesDataCatalogColumn(
            @ForAll("resourceNames") String database,
            @ForAll("resourceNames") String table,
            @ForAll("resourceNames") String column
    ) {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("column", database, table, column, null);
        assertTrue(ref.getEntityType().startsWith("DataCatalog::Column"),
                "Expected entity type starting with DataCatalog::Column but got: " + ref.getEntityType());
    }

    @Property(tries = 100)
    void datalocationLevelProducesDataCatalogDataLocation(
            @ForAll("resourceNames") String dataLocation
    ) {
        CedarEntityRef ref = adapter.buildEntityRefFromValues("datalocation", null, null, null, dataLocation);
        assertTrue(ref.getEntityType().startsWith("DataCatalog::DataLocation"),
                "Expected entity type starting with DataCatalog::DataLocation but got: " + ref.getEntityType());
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> resourceNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_')
                .ofMinLength(1)
                .ofMaxLength(50);
    }
}
