package org.apache.ranger.lakeformation.service;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTableResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for LakeFormationResourceLookupService.
 * Uses jqwik to verify resource lookup correctness across randomized catalog states.
 */
class LakeFormationResourceLookupPropertyTest {

    // -----------------------------------------------------------------------
    // Feature: ranger-lakeformation-sync, Property 24: Resource lookup returns matching catalog entries
    // **Validates: Requirements 5.6**
    // -----------------------------------------------------------------------

    @Property(tries = 100)
    void resourceLookupReturnsMatchingDatabases(
            @ForAll("catalogWithDatabaseLookup") CatalogLookupScenario scenario
    ) throws Exception {
        GlueClient mockGlue = mock(GlueClient.class);

        // Mock getDatabases to return the generated catalog state
        List<Database> glueDatabases = scenario.databases.stream()
                .map(name -> Database.builder().name(name).build())
                .collect(Collectors.toList());
        when(mockGlue.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(glueDatabases)
                        .build());

        LakeFormationResourceLookupService service = createService(mockGlue);

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("database");
        ctx.setUserInput(scenario.prefix);

        List<String> result = service.lookupResource(ctx);

        // Compute expected: databases matching prefix*
        Set<String> expected = computeExpectedMatches(scenario.databases, scenario.prefix);

        assertEquals(expected, new HashSet<>(result),
                "Lookup should return exactly the databases matching prefix '" + scenario.prefix + "'");
    }

    @Property(tries = 100)
    void resourceLookupReturnsMatchingTables(
            @ForAll("catalogWithTableLookup") TableLookupScenario scenario
    ) throws Exception {
        GlueClient mockGlue = mock(GlueClient.class);

        // Mock getTables to return tables for the selected database
        List<Table> glueTables = scenario.tables.stream()
                .map(name -> Table.builder().name(name).build())
                .collect(Collectors.toList());
        when(mockGlue.getTables(any(GetTablesRequest.class)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(glueTables)
                        .build());

        LakeFormationResourceLookupService service = createService(mockGlue);

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("table");
        ctx.setUserInput(scenario.prefix);
        Map<String, List<String>> resources = new HashMap<>();
        resources.put("database", Collections.singletonList(scenario.database));
        ctx.setResources(resources);

        List<String> result = service.lookupResource(ctx);

        Set<String> expected = computeExpectedMatches(scenario.tables, scenario.prefix);

        assertEquals(expected, new HashSet<>(result),
                "Lookup should return exactly the tables matching prefix '" + scenario.prefix + "'");
    }

    @Property(tries = 100)
    void resourceLookupReturnsMatchingColumns(
            @ForAll("catalogWithColumnLookup") ColumnLookupScenario scenario
    ) throws Exception {
        GlueClient mockGlue = mock(GlueClient.class);

        // Mock getTable to return columns for the selected table
        List<Column> glueColumns = scenario.columns.stream()
                .map(name -> Column.builder().name(name).type("string").build())
                .collect(Collectors.toList());
        when(mockGlue.getTable(any(GetTableRequest.class)))
                .thenReturn(GetTableResponse.builder()
                        .table(Table.builder()
                                .name(scenario.table)
                                .storageDescriptor(StorageDescriptor.builder()
                                        .columns(glueColumns)
                                        .build())
                                .build())
                        .build());

        LakeFormationResourceLookupService service = createService(mockGlue);

        ResourceLookupContext ctx = new ResourceLookupContext();
        ctx.setResourceName("column");
        ctx.setUserInput(scenario.prefix);
        Map<String, List<String>> resources = new HashMap<>();
        resources.put("database", Collections.singletonList(scenario.database));
        resources.put("table", Collections.singletonList(scenario.table));
        ctx.setResources(resources);

        List<String> result = service.lookupResource(ctx);

        Set<String> expected = computeExpectedMatches(scenario.columns, scenario.prefix);

        assertEquals(expected, new HashSet<>(result),
                "Lookup should return exactly the columns matching prefix '" + scenario.prefix + "'");
    }

    // --- Generators ---

    @Provide
    Arbitrary<CatalogLookupScenario> catalogWithDatabaseLookup() {
        Arbitrary<List<String>> databases = catalogNames().list().ofMinSize(0).ofMaxSize(15);
        Arbitrary<String> prefix = prefixes();
        return Combinators.combine(databases, prefix)
                .as(CatalogLookupScenario::new);
    }

    @Provide
    Arbitrary<TableLookupScenario> catalogWithTableLookup() {
        Arbitrary<String> database = catalogNames();
        Arbitrary<List<String>> tables = catalogNames().list().ofMinSize(0).ofMaxSize(15);
        Arbitrary<String> prefix = prefixes();
        return Combinators.combine(database, tables, prefix)
                .as(TableLookupScenario::new);
    }

    @Provide
    Arbitrary<ColumnLookupScenario> catalogWithColumnLookup() {
        Arbitrary<String> database = catalogNames();
        Arbitrary<String> table = catalogNames();
        Arbitrary<List<String>> columns = catalogNames().list().ofMinSize(0).ofMaxSize(15);
        Arbitrary<String> prefix = prefixes();
        return Combinators.combine(database, table, columns, prefix)
                .as(ColumnLookupScenario::new);
    }

    /**
     * Generates realistic catalog resource names: lowercase alphanumeric with underscores.
     */
    private Arbitrary<String> catalogNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('_', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> Character.isLetter(s.charAt(0)));
    }

    /**
     * Generates prefixes for lookup: either empty (match all) or a short alphabetic prefix.
     */
    private Arbitrary<String> prefixes() {
        return Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(1)
                        .ofMaxLength(5)
        );
    }

    // --- Helpers ---

    private LakeFormationResourceLookupService createService(GlueClient mockGlue) {
        LakeFormationResourceLookupService.GlueClientFactory factory =
                (region, credentialsProvider) -> mockGlue;
        LakeFormationResourceLookupService service = new LakeFormationResourceLookupService(factory);
        Map<String, String> configs = new HashMap<>();
        configs.put("aws.region", "us-east-1");
        configs.put("aws.access.key", "AKIAIOSFODNN7EXAMPLE");
        configs.put("aws.secret.key", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        service.setConfigs(configs);
        return service;
    }

    /**
     * Computes the expected matching entries using the same logic as the service:
     * the lookup builds a pattern of "prefix*" (or "*" if prefix is empty),
     * which CatalogResolver converts to a regex "^prefix.*$" (or "^.*$").
     */
    private Set<String> computeExpectedMatches(List<String> entries, String prefix) {
        String pattern = (prefix != null && !prefix.isEmpty()) ? prefix : "";
        return entries.stream()
                .filter(name -> name.startsWith(pattern))
                .collect(Collectors.toSet());
    }

    // --- Scenario classes ---

    static class CatalogLookupScenario {
        final List<String> databases;
        final String prefix;

        CatalogLookupScenario(List<String> databases, String prefix) {
            this.databases = databases;
            this.prefix = prefix;
        }

        @Override
        public String toString() {
            return "CatalogLookupScenario{databases=" + databases.size() + ", prefix='" + prefix + "'}";
        }
    }

    static class TableLookupScenario {
        final String database;
        final List<String> tables;
        final String prefix;

        TableLookupScenario(String database, List<String> tables, String prefix) {
            this.database = database;
            this.tables = tables;
            this.prefix = prefix;
        }

        @Override
        public String toString() {
            return "TableLookupScenario{db='" + database + "', tables=" + tables.size()
                    + ", prefix='" + prefix + "'}";
        }
    }

    static class ColumnLookupScenario {
        final String database;
        final String table;
        final List<String> columns;
        final String prefix;

        ColumnLookupScenario(String database, String table, List<String> columns, String prefix) {
            this.database = database;
            this.table = table;
            this.columns = columns;
            this.prefix = prefix;
        }

        @Override
        public String toString() {
            return "ColumnLookupScenario{db='" + database + "', table='" + table
                    + "', columns=" + columns.size() + ", prefix='" + prefix + "'}";
        }
    }
}
