package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import static org.junit.jupiter.api.Assertions.*;

class LFPermissionsFetcherTest {

    private LFPermissionsFetcher fetcher;

    @BeforeEach
    void setUp() {
        // Pass null client — only the helper methods are tested here (no AWS call made)
        fetcher = new LFPermissionsFetcher(null, "123456789012");
    }

    @Test
    void tableResourceType() {
        Resource r = Resource.builder()
                .table(TableResource.builder().databaseName("db").name("tbl").build())
                .build();
        assertEquals("TABLE", fetcher.resolveResourceType(r));
        assertEquals("db.tbl", fetcher.resolveResourceId(r));
    }

    @Test
    void tableResource_nullName_usesWildcard() {
        Resource r = Resource.builder()
                .table(TableResource.builder().databaseName("db").build())
                .build();
        assertEquals("TABLE", fetcher.resolveResourceType(r));
        assertEquals("db.*", fetcher.resolveResourceId(r));
    }

    @Test
    void databaseResourceType() {
        Resource r = Resource.builder()
                .database(DatabaseResource.builder().name("mydb").build())
                .build();
        assertEquals("DATABASE", fetcher.resolveResourceType(r));
        assertEquals("mydb", fetcher.resolveResourceId(r));
    }

    @Test
    void dataLocationResourceType() {
        Resource r = Resource.builder()
                .dataLocation(DataLocationResource.builder().resourceArn("arn:aws:s3:::bucket").build())
                .build();
        assertEquals("DATA_LOCATION", fetcher.resolveResourceType(r));
        assertEquals("arn:aws:s3:::bucket", fetcher.resolveResourceId(r));
    }

    @Test
    void tableWithColumnsResourceType() {
        Resource r = Resource.builder()
                .tableWithColumns(TableWithColumnsResource.builder()
                        .databaseName("mydb")
                        .name("mytable")
                        .build())
                .build();
        assertEquals("TABLE_WITH_COLUMNS", fetcher.resolveResourceType(r));
        assertEquals("mydb.mytable", fetcher.resolveResourceId(r));
    }

    @Test
    void nullResourceReturnsNull() {
        assertNull(fetcher.resolveResourceType(null));
        assertNull(fetcher.resolveResourceId(null));
    }
}
