package com.amazonaws.policyconverters.lakeformation.client;

import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.PermissionFilter;
import com.amazonaws.policyconverters.lakeformation.model.RetryConfig;
import net.jqwik.api.*;
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

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link LFPermissionFetcher}.
 * Validates round-trip consistency, null sourcePolicyId invariant, and pagination completeness.
 */
class LFPermissionFetcherPropertyTest {

    private static final String CATALOG_ID = "123456789012";

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 1: LFResource round-trip
    // **Validates: Requirements 8.1, 1.3, 2.1**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void lfResourceRoundTrip(@ForAll("validLFResources") LFResource original) {
        LakeFormationClient client = new LakeFormationClient(
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class),
                new RetryConfig());
        LFPermissionFetcher fetcher = new LFPermissionFetcher(
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class));

        // Forward: LFResource → SDK Resource
        Resource sdkResource = client.buildResource(original);
        // Reverse: SDK Resource → LFResource
        LFResource roundTripped = fetcher.reverseMapResource(sdkResource);

        assertNotNull(roundTripped, "reverseMapResource should not return null for a valid resource");
        assertEquals(original, roundTripped,
                "Round-trip LFResource → buildResource → reverseMapResource should produce equal LFResource");
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 2: LFPermission round-trip
    // **Validates: Requirements 8.2, 2.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void lfPermissionRoundTrip(@ForAll("nonEmptyPermissionSets") Set<LFPermission> original) {
        LakeFormationClient client = new LakeFormationClient(
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class),
                new RetryConfig());
        LFPermissionFetcher fetcher = new LFPermissionFetcher(
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class));

        // Forward: Set<LFPermission> → List<Permission> (SDK)
        List<Permission> sdkPermissions = client.toLfPermissions(original);
        // Reverse: List<Permission> → Set<LFPermission>
        Set<LFPermission> roundTripped = fetcher.reverseMapPermissions(sdkPermissions);

        assertEquals(original, roundTripped,
                "Round-trip LFPermission set → toLfPermissions → reverseMapPermissions should produce equal set");
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 12: Fetched operations have null sourcePolicyId
    // **Validates: Requirements 2.4**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void fetchedOperationsHaveNullSourcePolicyId(
            @ForAll("validPrincipalResourcePermissions") PrincipalResourcePermissions entry) {
        LFPermissionFetcher fetcher = new LFPermissionFetcher(
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class));

        List<LFPermissionOperation> ops = fetcher.normalizeEntry(entry);

        for (LFPermissionOperation op : ops) {
            assertNull(op.getSourcePolicyId(),
                    "All operations produced by normalizeEntry must have null sourcePolicyId");
        }
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 13: Pagination completeness
    // **Validates: Requirements 1.2**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void paginationCompleteness(@ForAll("pageCounts") int numPages) throws Exception {
        software.amazon.awssdk.services.lakeformation.LakeFormationClient awsClient =
                mock(software.amazon.awssdk.services.lakeformation.LakeFormationClient.class);
        LFPermissionFetcher fetcher = new LFPermissionFetcher(
                awsClient);

        // Build N pages, each with one database entry
        List<PrincipalResourcePermissions> allEntries = new ArrayList<>();
        List<ListPermissionsResponse> pages = new ArrayList<>();

        for (int i = 0; i < numPages; i++) {
            String dbName = "db_page_" + i;
            PrincipalResourcePermissions entry = PrincipalResourcePermissions.builder()
                    .principal(DataLakePrincipal.builder()
                            .dataLakePrincipalIdentifier("arn:aws:iam::123456789012:role/TestRole").build())
                    .resource(Resource.builder()
                            .database(DatabaseResource.builder()
                                    .catalogId(CATALOG_ID).name(dbName).build())
                            .build())
                    .permissions(Permission.DESCRIBE)
                    .permissionsWithGrantOption(Collections.emptyList())
                    .build();
            allEntries.add(entry);

            String nextToken = (i < numPages - 1) ? "token-page-" + (i + 1) : null;
            ListPermissionsResponse page = ListPermissionsResponse.builder()
                    .principalResourcePermissions(entry)
                    .nextToken(nextToken)
                    .build();
            pages.add(page);
        }

        // Set up mock to return pages in sequence
        var stubbing = when(awsClient.listPermissions(any(ListPermissionsRequest.class)));
        for (ListPermissionsResponse page : pages) {
            stubbing = stubbing.thenReturn(page);
        }

        List<LFPermissionOperation> results = fetcher.fetchPermissions(CATALOG_ID, null);

        // Verify we got exactly one operation per page (one entry per page, one permission each)
        assertEquals(numPages, results.size(),
                "fetchPermissions should return entries from all " + numPages + " pages");

        // Verify the entries match in order
        for (int i = 0; i < numPages; i++) {
            assertEquals("db_page_" + i, results.get(i).getResource().getDatabaseName(),
                    "Entry " + i + " should come from page " + i);
        }
    }

    // -----------------------------------------------------------------------
    // Arbitraries (generators)
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<LFResource> validLFResources() {
        return Arbitraries.oneOf(
                databaseResources(),
                tableResources(),
                columnResources(),
                dataLocationResources()
        );
    }

    /**
     * Database-level: catalogId + databaseName, no table/columns/dataLocation.
     */
    private Arbitrary<LFResource> databaseResources() {
        return Combinators.combine(catalogIds(), databaseNames())
                .as((catalogId, dbName) -> new LFResource(catalogId, dbName, null, null, null));
    }

    /**
     * Table-level: catalogId + databaseName + tableName, no columns.
     * Includes wildcard tableName="*".
     */
    private Arbitrary<LFResource> tableResources() {
        Arbitrary<String> tableNames = Arbitraries.oneOf(
                Arbitraries.of("events", "users", "transactions", "reports", "orders"),
                Arbitraries.just("*")
        );
        return Combinators.combine(catalogIds(), databaseNames(), tableNames)
                .as((catalogId, dbName, tableName) -> new LFResource(catalogId, dbName, tableName, null, null));
    }

    /**
     * Column-level: catalogId + databaseName + tableName + non-empty columnNames set.
     */
    private Arbitrary<LFResource> columnResources() {
        Arbitrary<Set<String>> columnSets = Arbitraries.of("col1", "col2", "col3", "id", "name", "value")
                .set().ofMinSize(1).ofMaxSize(4);
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "transactions", "reports");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames, columnSets)
                .as((catalogId, dbName, tableName, cols) ->
                        new LFResource(catalogId, dbName, tableName, cols, null));
    }

    /**
     * Data location: only dataLocationPath set, all others null.
     * catalogId must be null for round-trip since reverseMapResource drops it.
     */
    private Arbitrary<LFResource> dataLocationResources() {
        Arbitrary<String> paths = Arbitraries.of(
                "arn:aws:s3:::bucket-a/path1",
                "arn:aws:s3:::bucket-b/data/files",
                "arn:aws:s3:::my-lake/raw",
                "arn:aws:s3:::analytics-bucket/output"
        );
        return paths.map(path -> new LFResource(null, null, null, null, null, path));
    }

    private Arbitrary<String> catalogIds() {
        return Arbitraries.of("123456789012", "987654321098", "111222333444");
    }

    private Arbitrary<String> databaseNames() {
        return Arbitraries.of("analytics", "finance", "hr", "marketing", "sales");
    }

    @Provide
    Arbitrary<Set<LFPermission>> nonEmptyPermissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(LFPermission.values().length);
    }

    /**
     * Generate valid PrincipalResourcePermissions SDK entries with recognized resource types.
     * Used for Property 12 (null sourcePolicyId).
     */
    @Provide
    Arbitrary<PrincipalResourcePermissions> validPrincipalResourcePermissions() {
        Arbitrary<String> principals = Arbitraries.of(
                "arn:aws:iam::123456789012:role/TestRole",
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::123456789012:role/DataAnalyst"
        );
        Arbitrary<Resource> resources = sdkResources();
        Arbitrary<List<Permission>> permLists = sdkPermissionLists();
        Arbitrary<List<Permission>> grantOptionLists = Arbitraries.oneOf(
                Arbitraries.just(Collections.<Permission>emptyList()),
                sdkPermissionLists()
        );

        return Combinators.combine(principals, resources, permLists, grantOptionLists)
                .as((principal, resource, perms, grantPerms) ->
                        PrincipalResourcePermissions.builder()
                                .principal(DataLakePrincipal.builder()
                                        .dataLakePrincipalIdentifier(principal).build())
                                .resource(resource)
                                .permissions(perms)
                                .permissionsWithGrantOption(grantPerms)
                                .build());
    }

    private Arbitrary<Resource> sdkResources() {
        return Arbitraries.oneOf(
                sdkDatabaseResources(),
                sdkTableResources(),
                sdkColumnResources(),
                sdkDataLocationResources()
        );
    }

    private Arbitrary<Resource> sdkDatabaseResources() {
        return Combinators.combine(catalogIds(), databaseNames())
                .as((catalogId, dbName) -> Resource.builder()
                        .database(DatabaseResource.builder()
                                .catalogId(catalogId).name(dbName).build())
                        .build());
    }

    private Arbitrary<Resource> sdkTableResources() {
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "transactions", "reports");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames)
                .as((catalogId, dbName, tableName) -> Resource.builder()
                        .table(TableResource.builder()
                                .catalogId(catalogId).databaseName(dbName).name(tableName).build())
                        .build());
    }

    private Arbitrary<Resource> sdkColumnResources() {
        Arbitrary<List<String>> columnLists = Arbitraries.of("col1", "col2", "col3", "id", "name")
                .list().ofMinSize(1).ofMaxSize(3);
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "transactions");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames, columnLists)
                .as((catalogId, dbName, tableName, cols) -> Resource.builder()
                        .tableWithColumns(TableWithColumnsResource.builder()
                                .catalogId(catalogId).databaseName(dbName).name(tableName)
                                .columnNames(cols).build())
                        .build());
    }

    private Arbitrary<Resource> sdkDataLocationResources() {
        Arbitrary<String> arns = Arbitraries.of(
                "arn:aws:s3:::bucket-a/path1",
                "arn:aws:s3:::bucket-b/data/files",
                "arn:aws:s3:::my-lake/raw"
        );
        return arns.map(arn -> Resource.builder()
                .dataLocation(DataLocationResource.builder()
                        .resourceArn(arn).build())
                .build());
    }

    private Arbitrary<List<Permission>> sdkPermissionLists() {
        return Arbitraries.of(
                        Permission.SELECT, Permission.INSERT, Permission.DELETE,
                        Permission.DESCRIBE, Permission.ALTER, Permission.DROP,
                        Permission.CREATE_DATABASE, Permission.CREATE_TABLE,
                        Permission.DATA_LOCATION_ACCESS)
                .set().ofMinSize(1).ofMaxSize(4)
                .map(set -> new ArrayList<>(set));
    }

    @Provide
    Arbitrary<Integer> pageCounts() {
        return Arbitraries.integers().between(1, 10);
    }
}
