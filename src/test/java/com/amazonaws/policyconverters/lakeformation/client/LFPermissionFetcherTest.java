package com.amazonaws.policyconverters.lakeformation.client;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.PermissionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lakeformation.model.AccessDeniedException;
import software.amazon.awssdk.services.lakeformation.model.CatalogResource;
import software.amazon.awssdk.services.lakeformation.model.ColumnWildcard;
import software.amazon.awssdk.services.lakeformation.model.DataLakePrincipal;
import software.amazon.awssdk.services.lakeformation.model.DataLocationResource;
import software.amazon.awssdk.services.lakeformation.model.DatabaseResource;
import software.amazon.awssdk.services.lakeformation.model.LFTagPolicyResource;
import software.amazon.awssdk.services.lakeformation.model.LakeFormationException;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsRequest;
import software.amazon.awssdk.services.lakeformation.model.ListPermissionsResponse;
import software.amazon.awssdk.services.lakeformation.model.Permission;
import software.amazon.awssdk.services.lakeformation.model.PrincipalResourcePermissions;
import software.amazon.awssdk.services.lakeformation.model.Resource;
import software.amazon.awssdk.services.lakeformation.model.TableResource;
import software.amazon.awssdk.services.lakeformation.model.TableWildcard;
import software.amazon.awssdk.services.lakeformation.model.TableWithColumnsResource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LFPermissionFetcher}.
 * Validates: Requirements 1.1–1.7, 2.1–2.6, 7.1, 7.2, 7.5
 */
@ExtendWith(MockitoExtension.class)
class LFPermissionFetcherTest {

    private static final String CATALOG_ID = "123456789012";
    private static final String PRINCIPAL_ARN = "arn:aws:iam::123456789012:role/TestRole";

    @Mock
    private software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient;

    private LFPermissionFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new LFPermissionFetcher(awsClient);
    }

    // --- Helper methods ---

    private PrincipalResourcePermissions makeDbEntry(String dbName, Permission... perms) {
        return PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .database(DatabaseResource.builder()
                                .catalogId(CATALOG_ID).name(dbName).build())
                        .build())
                .permissions(Arrays.asList(perms))
                .permissionsWithGrantOption(Collections.emptyList())
                .build();
    }

    private PrincipalResourcePermissions makeTableEntry(String dbName, String tableName, Permission... perms) {
        return PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .table(TableResource.builder()
                                .catalogId(CATALOG_ID).databaseName(dbName).name(tableName).build())
                        .build())
                .permissions(Arrays.asList(perms))
                .permissionsWithGrantOption(Collections.emptyList())
                .build();
    }

    private PrincipalResourcePermissions makeColumnEntry(String dbName, String tableName,
                                                          List<String> columns, Permission... perms) {
        return PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .tableWithColumns(TableWithColumnsResource.builder()
                                .catalogId(CATALOG_ID).databaseName(dbName).name(tableName)
                                .columnNames(columns).build())
                        .build())
                .permissions(Arrays.asList(perms))
                .permissionsWithGrantOption(Collections.emptyList())
                .build();
    }

    private PrincipalResourcePermissions makeDataLocationEntry(String resourceArn, Permission... perms) {
        return PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .dataLocation(DataLocationResource.builder()
                                .resourceArn(resourceArn).build())
                        .build())
                .permissions(Arrays.asList(perms))
                .permissionsWithGrantOption(Collections.emptyList())
                .build();
    }

    // --- Single-page response with all resource types ---

    @Test
    void fetchPermissions_singlePage_allResourceTypes() throws Exception {
        PrincipalResourcePermissions dbEntry = makeDbEntry("mydb", Permission.DESCRIBE, Permission.ALTER);
        PrincipalResourcePermissions tableEntry = makeTableEntry("mydb", "mytable", Permission.SELECT);
        PrincipalResourcePermissions colEntry = makeColumnEntry("mydb", "mytable",
                Arrays.asList("col1", "col2"), Permission.SELECT);
        PrincipalResourcePermissions dlEntry = makeDataLocationEntry(
                "arn:aws:s3:::my-bucket/path", Permission.DATA_LOCATION_ACCESS);

        ListPermissionsResponse response = ListPermissionsResponse.builder()
                .principalResourcePermissions(dbEntry, tableEntry, colEntry, dlEntry)
                .nextToken(null)
                .build();

        when(awsClient.listPermissions(any(ListPermissionsRequest.class))).thenReturn(response);

        List<LFPermissionOperation> results = fetcher.fetchPermissions(CATALOG_ID, null);

        assertEquals(4, results.size());

        // Database resource
        LFPermissionOperation dbOp = results.get(0);
        assertEquals(CATALOG_ID, dbOp.getResource().getCatalogId());
        assertEquals("mydb", dbOp.getResource().getDatabaseName());
        assertNull(dbOp.getResource().getTableName());
        assertTrue(dbOp.getPermissions().contains(LFPermission.DESCRIBE));
        assertTrue(dbOp.getPermissions().contains(LFPermission.ALTER));

        // Table resource
        LFPermissionOperation tableOp = results.get(1);
        assertEquals("mytable", tableOp.getResource().getTableName());

        // Column resource
        LFPermissionOperation colOp = results.get(2);
        assertNotNull(colOp.getResource().getColumnNames());
        assertTrue(colOp.getResource().getColumnNames().contains("col1"));
        assertTrue(colOp.getResource().getColumnNames().contains("col2"));

        // Data location resource
        LFPermissionOperation dlOp = results.get(3);
        assertEquals("arn:aws:s3:::my-bucket/path", dlOp.getResource().getDataLocationPath());

        verify(awsClient, times(1)).listPermissions(any(ListPermissionsRequest.class));
    }

    // --- Multi-page pagination ---

    @Test
    void fetchPermissions_multiPagePagination_threePages() throws Exception {
        PrincipalResourcePermissions entry1 = makeDbEntry("db1", Permission.DESCRIBE);
        PrincipalResourcePermissions entry2 = makeTableEntry("db1", "t1", Permission.SELECT);
        PrincipalResourcePermissions entry3 = makeDbEntry("db2", Permission.ALTER);

        ListPermissionsResponse page1 = ListPermissionsResponse.builder()
                .principalResourcePermissions(entry1)
                .nextToken("token-page2")
                .build();
        ListPermissionsResponse page2 = ListPermissionsResponse.builder()
                .principalResourcePermissions(entry2)
                .nextToken("token-page3")
                .build();
        ListPermissionsResponse page3 = ListPermissionsResponse.builder()
                .principalResourcePermissions(entry3)
                .nextToken(null)
                .build();

        when(awsClient.listPermissions(any(ListPermissionsRequest.class)))
                .thenReturn(page1)
                .thenReturn(page2)
                .thenReturn(page3);

        List<LFPermissionOperation> results = fetcher.fetchPermissions(CATALOG_ID, null);

        assertEquals(3, results.size());
        assertEquals("db1", results.get(0).getResource().getDatabaseName());
        assertEquals("t1", results.get(1).getResource().getTableName());
        assertEquals("db2", results.get(2).getResource().getDatabaseName());
        verify(awsClient, times(3)).listPermissions(any(ListPermissionsRequest.class));
    }

    // --- AccessDeniedException throws immediately ---

    @Test
    void fetchPermissions_accessDenied_throwsImmediately() {
        when(awsClient.listPermissions(any(ListPermissionsRequest.class)))
                .thenThrow(AccessDeniedException.builder()
                        .message("User is not authorized").build());

        LakeFormationClientException ex = assertThrows(LakeFormationClientException.class,
                () -> fetcher.fetchPermissions(CATALOG_ID, null));

        assertTrue(ex.getMessage().contains("Access denied"));
        assertTrue(ex.getCause() instanceof AccessDeniedException);
        verify(awsClient, times(1)).listPermissions(any(ListPermissionsRequest.class));
    }

    // --- LakeFormationException wraps into LakeFormationClientException ---

    @Test
    void fetchPermissions_lakeFormationException_wrapsIntoClientException() {
        LakeFormationException serverError = (LakeFormationException) LakeFormationException.builder()
                .message("Internal Server Error")
                .statusCode(500)
                .build();

        when(awsClient.listPermissions(any(ListPermissionsRequest.class)))
                .thenThrow(serverError);

        LakeFormationClientException ex = assertThrows(LakeFormationClientException.class,
                () -> fetcher.fetchPermissions(CATALOG_ID, null));

        assertTrue(ex.getMessage().contains("ListPermissions failed"));
        assertTrue(ex.getCause() instanceof LakeFormationException);
        verify(awsClient, times(1)).listPermissions(any(ListPermissionsRequest.class));
    }

    // --- CatalogResource entries are skipped ---

    @Test
    void normalizeEntry_catalogResource_skippedWithWarning() {
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .catalog(CatalogResource.builder().build())
                        .build())
                .permissions(Permission.CREATE_DATABASE)
                .permissionsWithGrantOption(Collections.emptyList())
                .build();

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        assertTrue(ops.isEmpty(), "CatalogResource entries should be skipped");
    }

    // --- LFTagPolicyResource entries are skipped ---

    @Test
    void normalizeEntry_lfTagPolicyResource_skippedWithWarning() {
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .lfTagPolicy(LFTagPolicyResource.builder()
                                .catalogId(CATALOG_ID)
                                .resourceType("DATABASE")
                                .build())
                        .build())
                .permissions(Permission.DESCRIBE)
                .permissionsWithGrantOption(Collections.emptyList())
                .build();

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        assertTrue(ops.isEmpty(), "LFTagPolicyResource entries should be skipped");
    }

    // --- ColumnWildcard maps to table-level resource ---

    @Test
    void reverseMapResource_columnWildcard_mapsToTableLevel() {
        Resource sdkResource = Resource.builder()
                .tableWithColumns(TableWithColumnsResource.builder()
                        .catalogId(CATALOG_ID)
                        .databaseName("mydb")
                        .name("mytable")
                        .columnWildcard(ColumnWildcard.builder().build())
                        .build())
                .build();

        LFResource result = fetcher.reverseMapResource(sdkResource);

        assertNotNull(result);
        assertEquals(CATALOG_ID, result.getCatalogId());
        assertEquals("mydb", result.getDatabaseName());
        assertEquals("mytable", result.getTableName());
        assertNull(result.getColumnNames(), "ColumnWildcard should map to null columnNames (table-level)");
    }

    // --- TableWildcard maps to wildcard table indicator ---

    @Test
    void reverseMapResource_tableWildcard_mapsToWildcardIndicator() {
        Resource sdkResource = Resource.builder()
                .table(TableResource.builder()
                        .catalogId(CATALOG_ID)
                        .databaseName("mydb")
                        .tableWildcard(TableWildcard.builder().build())
                        .build())
                .build();

        LFResource result = fetcher.reverseMapResource(sdkResource);

        assertNotNull(result);
        assertEquals(CATALOG_ID, result.getCatalogId());
        assertEquals("mydb", result.getDatabaseName());
        assertEquals("*", result.getTableName(), "TableWildcard should map to tableName='*'");
    }

    // --- Unrecognized permission values are skipped ---

    @Test
    void reverseMapPermissions_unrecognizedValues_skipped() {
        List<Permission> sdkPerms = Arrays.asList(
                Permission.SELECT,
                Permission.fromValue("UNKNOWN_FUTURE_PERM"),
                Permission.DESCRIBE
        );

        Set<LFPermission> result = fetcher.reverseMapPermissions(sdkPerms);

        assertEquals(2, result.size());
        assertTrue(result.contains(LFPermission.SELECT));
        assertTrue(result.contains(LFPermission.DESCRIBE));
    }

    // --- Entry with all unrecognized permissions is skipped entirely ---

    @Test
    void normalizeEntry_allUnrecognizedPermissions_skippedEntirely() {
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .database(DatabaseResource.builder()
                                .catalogId(CATALOG_ID).name("mydb").build())
                        .build())
                .permissions(Permission.fromValue("UNKNOWN_PERM_1"), Permission.fromValue("UNKNOWN_PERM_2"))
                .permissionsWithGrantOption(Collections.emptyList())
                .build();

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        assertTrue(ops.isEmpty(), "Entry with all unrecognized permissions should be skipped");
    }

    // --- PermissionsWithGrantOption creates separate grantable operation ---

    @Test
    void normalizeEntry_permissionsWithGrantOption_createsSeparateGrantableOp() {
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .table(TableResource.builder()
                                .catalogId(CATALOG_ID).databaseName("mydb").name("mytable").build())
                        .build())
                .permissions(Permission.SELECT, Permission.INSERT)
                .permissionsWithGrantOption(Permission.SELECT)
                .build();

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        assertEquals(2, ops.size());

        // First op: regular permissions (not grantable)
        LFPermissionOperation regularOp = ops.get(0);
        assertEquals(LFPermissionOperation.OperationType.GRANT, regularOp.getOperationType());
        assertTrue(regularOp.getPermissions().contains(LFPermission.SELECT));
        assertTrue(regularOp.getPermissions().contains(LFPermission.INSERT));
        assertEquals(false, regularOp.isGrantable());

        // Second op: grantable permissions
        LFPermissionOperation grantableOp = ops.get(1);
        assertEquals(LFPermissionOperation.OperationType.GRANT, grantableOp.getOperationType());
        assertTrue(grantableOp.getPermissions().contains(LFPermission.SELECT));
        assertEquals(1, grantableOp.getPermissions().size());
        assertEquals(true, grantableOp.isGrantable());
    }

    // --- sourcePolicyId is null for all returned operations ---

    @Test
    void normalizeEntry_sourcePolicyId_alwaysNull() {
        PrincipalResourcePermissions entry = makeTableEntry("mydb", "mytable",
                Permission.SELECT, Permission.INSERT);

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        assertEquals(1, ops.size());
        assertNull(ops.get(0).getSourcePolicyId(),
                "sourcePolicyId should be null for fetched permissions");
    }

    @Test
    void fetchPermissions_allReturnedOps_haveNullSourcePolicyId() throws Exception {
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .table(TableResource.builder()
                                .catalogId(CATALOG_ID).databaseName("mydb").name("mytable").build())
                        .build())
                .permissions(Permission.SELECT)
                .permissionsWithGrantOption(Permission.SELECT)
                .build();

        ListPermissionsResponse response = ListPermissionsResponse.builder()
                .principalResourcePermissions(entry)
                .nextToken(null)
                .build();

        when(awsClient.listPermissions(any(ListPermissionsRequest.class))).thenReturn(response);

        List<LFPermissionOperation> results = fetcher.fetchPermissions(CATALOG_ID, null);

        assertEquals(2, results.size());
        for (LFPermissionOperation op : results) {
            assertNull(op.getSourcePolicyId(), "sourcePolicyId should be null for all fetched operations");
        }
    }

    // --- Filter excludes matching principals ---

    @Test
    void fetchPermissions_filterExcludesMatchingPrincipals() throws Exception {
        String excludedPrincipal = "arn:aws:iam::123456789012:role/ExcludedRole";
        String includedPrincipal = "arn:aws:iam::123456789012:role/IncludedRole";

        PrincipalResourcePermissions excludedEntry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(excludedPrincipal).build())
                .resource(Resource.builder()
                        .database(DatabaseResource.builder()
                                .catalogId(CATALOG_ID).name("db1").build())
                        .build())
                .permissions(Permission.DESCRIBE)
                .permissionsWithGrantOption(Collections.emptyList())
                .build();

        PrincipalResourcePermissions includedEntry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(includedPrincipal).build())
                .resource(Resource.builder()
                        .database(DatabaseResource.builder()
                                .catalogId(CATALOG_ID).name("db2").build())
                        .build())
                .permissions(Permission.DESCRIBE)
                .permissionsWithGrantOption(Collections.emptyList())
                .build();

        ListPermissionsResponse response = ListPermissionsResponse.builder()
                .principalResourcePermissions(excludedEntry, includedEntry)
                .nextToken(null)
                .build();

        when(awsClient.listPermissions(any(ListPermissionsRequest.class))).thenReturn(response);

        Set<String> excludedPrincipals = new HashSet<>();
        excludedPrincipals.add(excludedPrincipal);
        PermissionFilter filter = new PermissionFilter(null, null, excludedPrincipals, null);

        List<LFPermissionOperation> results = fetcher.fetchPermissions(CATALOG_ID, filter);

        assertEquals(1, results.size());
        assertEquals(includedPrincipal, results.get(0).getPrincipalArn());
    }

    // --- All operations have OperationType GRANT ---

    @Test
    void normalizeEntry_allOperations_haveGrantType() {
        PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                .principal(DataLakePrincipal.builder()
                        .dataLakePrincipalIdentifier(PRINCIPAL_ARN).build())
                .resource(Resource.builder()
                        .database(DatabaseResource.builder()
                                .catalogId(CATALOG_ID).name("mydb").build())
                        .build())
                .permissions(Permission.DESCRIBE, Permission.ALTER)
                .permissionsWithGrantOption(Permission.DESCRIBE)
                .build();

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        assertEquals(2, ops.size());
        for (LFPermissionOperation op : ops) {
            assertEquals(LFPermissionOperation.OperationType.GRANT, op.getOperationType(),
                    "All fetched operations should have GRANT type");
        }
    }
}
