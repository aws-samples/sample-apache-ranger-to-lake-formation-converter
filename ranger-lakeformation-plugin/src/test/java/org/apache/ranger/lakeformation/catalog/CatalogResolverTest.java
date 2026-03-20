package org.apache.ranger.lakeformation.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTableResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.GlueException;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogResolverTest {

    @Mock
    private GlueClient glueClient;

    private CatalogResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CatalogResolver(glueClient);
    }

    // --- expandDatabases tests ---

    @Test
    void expandDatabases_exactMatch() {
        when(glueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("analytics").build(),
                                Database.builder().name("production").build(),
                                Database.builder().name("staging").build())
                        .build());

        List<String> result = resolver.expandDatabases("analytics");
        assertEquals(1, result.size());
        assertEquals("analytics", result.get(0));
    }

    @Test
    void expandDatabases_wildcardStar() {
        when(glueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("analytics_prod").build(),
                                Database.builder().name("analytics_dev").build(),
                                Database.builder().name("marketing").build())
                        .build());

        List<String> result = resolver.expandDatabases("analytics_*");
        assertEquals(2, result.size());
        assertTrue(result.contains("analytics_prod"));
        assertTrue(result.contains("analytics_dev"));
    }

    @Test
    void expandDatabases_wildcardMatchAll() {
        when(glueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("db1").build(),
                                Database.builder().name("db2").build())
                        .build());

        List<String> result = resolver.expandDatabases("*");
        assertEquals(2, result.size());
    }

    @Test
    void expandDatabases_wildcardQuestion() {
        when(glueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("db1").build(),
                                Database.builder().name("db2").build(),
                                Database.builder().name("db10").build())
                        .build());

        List<String> result = resolver.expandDatabases("db?");
        assertEquals(2, result.size());
        assertTrue(result.contains("db1"));
        assertTrue(result.contains("db2"));
    }

    @Test
    void expandDatabases_pagination() {
        when(glueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenReturn(
                        GetDatabasesResponse.builder()
                                .databaseList(Database.builder().name("db1").build())
                                .nextToken("token1")
                                .build(),
                        GetDatabasesResponse.builder()
                                .databaseList(Database.builder().name("db2").build())
                                .build());

        List<String> result = resolver.expandDatabases("*");
        assertEquals(2, result.size());
        assertTrue(result.contains("db1"));
        assertTrue(result.contains("db2"));
    }

    @Test
    void expandDatabases_awsException_returnsEmpty() {
        when(glueClient.getDatabases(any(GetDatabasesRequest.class)))
                .thenThrow(GlueException.builder().message("Access denied").build());

        List<String> result = resolver.expandDatabases("*");
        assertTrue(result.isEmpty());
    }

    @Test
    void expandDatabases_nullPattern_returnsEmpty() {
        List<String> result = resolver.expandDatabases(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void expandDatabases_emptyPattern_returnsEmpty() {
        List<String> result = resolver.expandDatabases("");
        assertTrue(result.isEmpty());
    }

    // --- expandTables tests ---

    @Test
    void expandTables_exactMatch() {
        when(glueClient.getTables(any(GetTablesRequest.class)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(
                                Table.builder().name("events").build(),
                                Table.builder().name("users").build())
                        .build());

        List<String> result = resolver.expandTables("analytics", "events");
        assertEquals(1, result.size());
        assertEquals("events", result.get(0));
    }

    @Test
    void expandTables_wildcardStar() {
        when(glueClient.getTables(any(GetTablesRequest.class)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(
                                Table.builder().name("events_2023").build(),
                                Table.builder().name("events_2024").build(),
                                Table.builder().name("users").build())
                        .build());

        List<String> result = resolver.expandTables("analytics", "events_*");
        assertEquals(2, result.size());
        assertTrue(result.contains("events_2023"));
        assertTrue(result.contains("events_2024"));
    }

    @Test
    void expandTables_pagination() {
        when(glueClient.getTables(any(GetTablesRequest.class)))
                .thenReturn(
                        GetTablesResponse.builder()
                                .tableList(Table.builder().name("t1").build())
                                .nextToken("token1")
                                .build(),
                        GetTablesResponse.builder()
                                .tableList(Table.builder().name("t2").build())
                                .build());

        List<String> result = resolver.expandTables("db", "*");
        assertEquals(2, result.size());
    }

    @Test
    void expandTables_awsException_returnsEmpty() {
        when(glueClient.getTables(any(GetTablesRequest.class)))
                .thenThrow(GlueException.builder().message("Not found").build());

        List<String> result = resolver.expandTables("db", "*");
        assertTrue(result.isEmpty());
    }

    @Test
    void expandTables_nullDatabase_returnsEmpty() {
        assertTrue(resolver.expandTables(null, "*").isEmpty());
    }

    @Test
    void expandTables_emptyDatabase_returnsEmpty() {
        assertTrue(resolver.expandTables("", "*").isEmpty());
    }

    @Test
    void expandTables_nullPattern_returnsEmpty() {
        assertTrue(resolver.expandTables("db", null).isEmpty());
    }

    // --- expandColumns tests ---

    @Test
    void expandColumns_exactMatch() {
        Table table = Table.builder()
                .name("events")
                .storageDescriptor(StorageDescriptor.builder()
                        .columns(
                                Column.builder().name("user_id").type("string").build(),
                                Column.builder().name("event_type").type("string").build(),
                                Column.builder().name("timestamp").type("bigint").build())
                        .build())
                .build();

        when(glueClient.getTable(any(GetTableRequest.class)))
                .thenReturn(GetTableResponse.builder().table(table).build());

        List<String> result = resolver.expandColumns("analytics", "events", "user_id");
        assertEquals(1, result.size());
        assertEquals("user_id", result.get(0));
    }

    @Test
    void expandColumns_wildcardStar() {
        Table table = Table.builder()
                .name("events")
                .storageDescriptor(StorageDescriptor.builder()
                        .columns(
                                Column.builder().name("user_id").type("string").build(),
                                Column.builder().name("user_name").type("string").build(),
                                Column.builder().name("event_type").type("string").build())
                        .build())
                .build();

        when(glueClient.getTable(any(GetTableRequest.class)))
                .thenReturn(GetTableResponse.builder().table(table).build());

        List<String> result = resolver.expandColumns("analytics", "events", "user_*");
        assertEquals(2, result.size());
        assertTrue(result.contains("user_id"));
        assertTrue(result.contains("user_name"));
    }

    @Test
    void expandColumns_includesPartitionKeys() {
        Table table = Table.builder()
                .name("events")
                .storageDescriptor(StorageDescriptor.builder()
                        .columns(
                                Column.builder().name("data_col").type("string").build())
                        .build())
                .partitionKeys(
                        Column.builder().name("year").type("int").build(),
                        Column.builder().name("month").type("int").build())
                .build();

        when(glueClient.getTable(any(GetTableRequest.class)))
                .thenReturn(GetTableResponse.builder().table(table).build());

        List<String> result = resolver.expandColumns("db", "events", "*");
        assertEquals(3, result.size());
        assertTrue(result.contains("data_col"));
        assertTrue(result.contains("year"));
        assertTrue(result.contains("month"));
    }

    @Test
    void expandColumns_awsException_returnsEmpty() {
        when(glueClient.getTable(any(GetTableRequest.class)))
                .thenThrow(GlueException.builder().message("Table not found").build());

        List<String> result = resolver.expandColumns("db", "events", "*");
        assertTrue(result.isEmpty());
    }

    @Test
    void expandColumns_nullInputs_returnsEmpty() {
        assertTrue(resolver.expandColumns(null, "t", "*").isEmpty());
        assertTrue(resolver.expandColumns("db", null, "*").isEmpty());
        assertTrue(resolver.expandColumns("db", "t", null).isEmpty());
        assertTrue(resolver.expandColumns("", "t", "*").isEmpty());
        assertTrue(resolver.expandColumns("db", "", "*").isEmpty());
        assertTrue(resolver.expandColumns("db", "t", "").isEmpty());
    }

    @Test
    void expandColumns_noStorageDescriptor_returnsEmpty() {
        Table table = Table.builder()
                .name("events")
                .build();

        when(glueClient.getTable(any(GetTableRequest.class)))
                .thenReturn(GetTableResponse.builder().table(table).build());

        List<String> result = resolver.expandColumns("db", "events", "*");
        assertTrue(result.isEmpty());
    }

    // --- toRegexPattern tests ---

    @Test
    void toRegexPattern_star() {
        Pattern p = CatalogResolver.toRegexPattern("db_*");
        assertTrue(p.matcher("db_anything").matches());
        assertTrue(p.matcher("db_").matches());
        assertTrue(p.matcher("db_foo_bar").matches());
        assertTrue(!p.matcher("other").matches());
    }

    @Test
    void toRegexPattern_question() {
        Pattern p = CatalogResolver.toRegexPattern("db?");
        assertTrue(p.matcher("db1").matches());
        assertTrue(p.matcher("dbX").matches());
        assertTrue(!p.matcher("db").matches());
        assertTrue(!p.matcher("db12").matches());
    }

    @Test
    void toRegexPattern_escapesRegexChars() {
        Pattern p = CatalogResolver.toRegexPattern("my.db");
        assertTrue(p.matcher("my.db").matches());
        assertTrue(!p.matcher("myXdb").matches());
    }

    @Test
    void toRegexPattern_matchAll() {
        Pattern p = CatalogResolver.toRegexPattern("*");
        assertTrue(p.matcher("anything").matches());
        assertTrue(p.matcher("").matches());
    }

    @Test
    void toRegexPattern_combined() {
        Pattern p = CatalogResolver.toRegexPattern("prod_*_v?");
        assertTrue(p.matcher("prod_analytics_v1").matches());
        assertTrue(p.matcher("prod__v2").matches());
        assertTrue(!p.matcher("prod_analytics_v10").matches());
    }
}
