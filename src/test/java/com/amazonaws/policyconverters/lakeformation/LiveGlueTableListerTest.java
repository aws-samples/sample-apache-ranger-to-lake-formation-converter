package com.amazonaws.policyconverters.lakeformation;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class LiveGlueTableListerTest {

    @Test
    void listAll_paginatesDatabasesAndTables() throws Exception {
        GlueClient glue = mock(GlueClient.class);

        when(glue.getDatabases((GetDatabasesRequest) any())).thenReturn(
                GetDatabasesResponse.builder()
                        .databaseList(
                                Database.builder().name("db1").build(),
                                Database.builder().name("db2").build())
                        .nextToken(null)
                        .build());

        // db1: two tables across two pages
        when(glue.getTables((GetTablesRequest) argThat(
                (GetTablesRequest r) -> r != null && "db1".equals(r.databaseName()) && r.nextToken() == null)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(Table.builder().name("t1").build())
                        .nextToken("tok1")
                        .build());
        when(glue.getTables((GetTablesRequest) argThat(
                (GetTablesRequest r) -> r != null && "db1".equals(r.databaseName()) && "tok1".equals(r.nextToken()))))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(Table.builder().name("t2").build())
                        .nextToken(null)
                        .build());

        // db2: one table
        when(glue.getTables((GetTablesRequest) argThat(
                (GetTablesRequest r) -> r != null && "db2".equals(r.databaseName()) && r.nextToken() == null)))
                .thenReturn(GetTablesResponse.builder()
                        .tableList(Table.builder().name("t3").build())
                        .nextToken(null)
                        .build());

        Map<String, List<String>> result = new LiveGlueTableLister(glue).listAll();

        assertEquals(2, result.size());
        assertEquals(List.of("t1", "t2"), result.get("db1"));
        assertEquals(List.of("t3"), result.get("db2"));
    }

    @Test
    void listAll_handlesEmptyCatalog() throws Exception {
        GlueClient glue = mock(GlueClient.class);
        when(glue.getDatabases((GetDatabasesRequest) any())).thenReturn(
                GetDatabasesResponse.builder()
                        .databaseList(Collections.emptyList())
                        .nextToken(null)
                        .build());

        assertTrue(new LiveGlueTableLister(glue).listAll().isEmpty());
    }

    @Test
    void listAll_databaseWithNoTables_includedWithEmptyList() throws Exception {
        GlueClient glue = mock(GlueClient.class);
        when(glue.getDatabases((GetDatabasesRequest) any())).thenReturn(
                GetDatabasesResponse.builder()
                        .databaseList(Database.builder().name("empty_db").build())
                        .nextToken(null)
                        .build());
        when(glue.getTables((GetTablesRequest) any())).thenReturn(
                GetTablesResponse.builder()
                        .tableList(Collections.emptyList())
                        .nextToken(null)
                        .build());

        Map<String, List<String>> result = new LiveGlueTableLister(glue).listAll();

        assertTrue(result.containsKey("empty_db"));
        assertTrue(result.get("empty_db").isEmpty());
    }

    @Test
    void listAll_entityNotFoundOnTables_skipsDatabase() throws Exception {
        GlueClient glue = mock(GlueClient.class);
        when(glue.getDatabases((GetDatabasesRequest) any())).thenReturn(
                GetDatabasesResponse.builder()
                        .databaseList(Database.builder().name("gone_db").build())
                        .nextToken(null)
                        .build());
        when(glue.getTables((GetTablesRequest) any()))
                .thenThrow(EntityNotFoundException.builder().message("not found").build());

        Map<String, List<String>> result = new LiveGlueTableLister(glue).listAll();

        assertTrue(result.containsKey("gone_db"));
        assertTrue(result.get("gone_db").isEmpty());
    }

    @Test
    void listAll_propagatesGlueException() {
        GlueClient glue = mock(GlueClient.class);
        when(glue.getDatabases((GetDatabasesRequest) any()))
                .thenThrow(new RuntimeException("Glue unavailable"));

        assertThrows(Exception.class, () -> new LiveGlueTableLister(glue).listAll());
    }

    @Test
    void listAll_paginatesDatabases() throws Exception {
        GlueClient glue = mock(GlueClient.class);

        when(glue.getDatabases((GetDatabasesRequest) argThat(
                (GetDatabasesRequest r) -> r != null && r.nextToken() == null))).thenReturn(
                GetDatabasesResponse.builder()
                        .databaseList(Database.builder().name("db1").build())
                        .nextToken("page2")
                        .build());
        when(glue.getDatabases((GetDatabasesRequest) argThat(
                (GetDatabasesRequest r) -> r != null && "page2".equals(r.nextToken())))).thenReturn(
                GetDatabasesResponse.builder()
                        .databaseList(Database.builder().name("db2").build())
                        .nextToken(null)
                        .build());
        when(glue.getTables((GetTablesRequest) any())).thenReturn(
                GetTablesResponse.builder()
                        .tableList(Collections.emptyList())
                        .nextToken(null)
                        .build());

        Map<String, List<String>> result = new LiveGlueTableLister(glue).listAll();

        assertEquals(2, result.size());
        assertTrue(result.containsKey("db1"));
        assertTrue(result.containsKey("db2"));
    }
}
