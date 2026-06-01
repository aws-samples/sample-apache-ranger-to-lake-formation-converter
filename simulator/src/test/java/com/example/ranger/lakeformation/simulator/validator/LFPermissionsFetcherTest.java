package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lakeformation.LakeFormationClient;
import software.amazon.awssdk.services.lakeformation.model.DataLakePrincipal;
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.PrincipalResourcePermissions;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LFPermissionsFetcherTest {

    private LFPermissionsFetcher fetcher;

    @BeforeEach
    void setUp() {
        // Pass null client — only the helper methods are tested here (no AWS call made)
        fetcher = new LFPermissionsFetcher(null, "123456789012");
    }

    // -----------------------------------------------------------------------
    // Stub helpers for fetchAll() tests
    // -----------------------------------------------------------------------

    private LakeFormationClient stubClient(ListPermissionsResponse... pages) {
        return new LakeFormationClient() {
            int call = 0;
            @Override
            public ListPermissionsResponse listPermissions(ListPermissionsRequest r) {
                return pages[Math.min(call++, pages.length - 1)];
            }
            @Override public String serviceName() { return "lakeformation"; }
            @Override public void close() {}
        };
    }

    private ListPermissionsResponse singlePage(List<PrincipalResourcePermissions> entries) {
        return ListPermissionsResponse.builder()
                .principalResourcePermissions(entries)
                .build();
    }

    private PrincipalResourcePermissions entry(String arn, Resource resource,
            List<Permission> perms, List<Permission> grantablePerms) {
        PrincipalResourcePermissions.Builder b = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(arn).build())
                .resource(resource)
                .permissions(perms);
        if (grantablePerms != null) {
            b.permissionsWithGrantOption(grantablePerms);
        }
        return b.build();
    }

    private Resource tableResource(String db, String table) {
        return Resource.builder()
                .table(TableResource.builder().databaseName(db).name(table).build())
                .build();
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

    // -----------------------------------------------------------------------
    // fetchAll() tests
    // -----------------------------------------------------------------------

    @Test
    void fetchAll_singlePageSinglePermission() {
        String alice = "arn:aws:iam::123456789012:role/alice";
        Resource res = tableResource("mydb", "events");
        PrincipalResourcePermissions e = entry(alice, res,
                Collections.singletonList(Permission.SELECT), Collections.emptyList());
        LakeFormationClient client = stubClient(singlePage(Collections.singletonList(e)));

        Set<SimulatorPermission> result = new LFPermissionsFetcher(client, "123456789012").fetchAll();

        assertEquals(1, result.size());
        SimulatorPermission sp = result.iterator().next();
        assertEquals(alice, sp.principalArn());
        assertEquals("TABLE", sp.resourceType());
        assertEquals("mydb.events", sp.resourceId());
        assertEquals("SELECT", sp.permission());
        assertFalse(sp.grantable());
    }

    @Test
    void fetchAll_paginationAcrossTwoPages() {
        String alice = "arn:aws:iam::123456789012:role/alice";
        Resource eventsRes = tableResource("mydb", "events");
        Resource ordersRes = tableResource("mydb", "orders");

        PrincipalResourcePermissions page1Entry = entry(alice, eventsRes,
                Collections.singletonList(Permission.SELECT), Collections.emptyList());
        PrincipalResourcePermissions page2Entry = entry(alice, ordersRes,
                Collections.singletonList(Permission.INSERT), Collections.emptyList());

        ListPermissionsResponse page1 = ListPermissionsResponse.builder()
                .principalResourcePermissions(Collections.singletonList(page1Entry))
                .nextToken("tok1")
                .build();
        ListPermissionsResponse page2 = ListPermissionsResponse.builder()
                .principalResourcePermissions(Collections.singletonList(page2Entry))
                .build();

        LakeFormationClient client = stubClient(page1, page2);

        Set<SimulatorPermission> result = new LFPermissionsFetcher(client, "123456789012").fetchAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(sp ->
                "mydb.events".equals(sp.resourceId()) && "SELECT".equals(sp.permission())));
        assertTrue(result.stream().anyMatch(sp ->
                "mydb.orders".equals(sp.resourceId()) && "INSERT".equals(sp.permission())));
    }

    @Test
    void fetchAll_nullPrincipalSkipped() {
        // Entry with null principal identifier — should be skipped
        PrincipalResourcePermissions nullPrincipalEntry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder().build())  // no identifier → null
                .resource(tableResource("mydb", "events"))
                .permissions(Collections.singletonList(Permission.SELECT))
                .build();

        String alice = "arn:aws:iam::123456789012:role/alice";
        PrincipalResourcePermissions validEntry = entry(alice, tableResource("mydb", "events"),
                Collections.singletonList(Permission.SELECT), Collections.emptyList());

        LakeFormationClient client = stubClient(
                singlePage(Arrays.asList(nullPrincipalEntry, validEntry)));

        Set<SimulatorPermission> result = new LFPermissionsFetcher(client, "123456789012").fetchAll();

        assertEquals(1, result.size());
        assertEquals(alice, result.iterator().next().principalArn());
    }

    @Test
    void fetchAll_grantablePermission() {
        String alice = "arn:aws:iam::123456789012:role/alice";
        Resource res = tableResource("mydb", "events");
        PrincipalResourcePermissions e = entry(alice, res,
                Collections.singletonList(Permission.SELECT),
                Collections.singletonList(Permission.SELECT));
        LakeFormationClient client = stubClient(singlePage(Collections.singletonList(e)));

        Set<SimulatorPermission> result = new LFPermissionsFetcher(client, "123456789012").fetchAll();

        assertEquals(1, result.size());
        assertTrue(result.iterator().next().grantable());
    }

    @Test
    void fetchAll_unknownResourceTypeSkipped() {
        // Entry with null resource → resolveResourceType returns null → skipped
        PrincipalResourcePermissions nullResourceEntry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier("arn:aws:iam::123456789012:role/bob").build())
                .resource((Resource) null)
                .permissions(Collections.singletonList(Permission.SELECT))
                .build();

        String alice = "arn:aws:iam::123456789012:role/alice";
        PrincipalResourcePermissions validEntry = entry(alice, tableResource("mydb", "events"),
                Collections.singletonList(Permission.SELECT), Collections.emptyList());

        LakeFormationClient client = stubClient(
                singlePage(Arrays.asList(nullResourceEntry, validEntry)));

        Set<SimulatorPermission> result = new LFPermissionsFetcher(client, "123456789012").fetchAll();

        assertEquals(1, result.size());
        assertEquals(alice, result.iterator().next().principalArn());
    }
}
