package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.AccessGrantsLocationConfiguration;
import software.amazon.awssdk.services.s3control.model.Grantee;
import software.amazon.awssdk.services.s3control.model.GranteeType;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantEntry;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsRequest;
import software.amazon.awssdk.services.s3control.model.ListAccessGrantsResponse;
import software.amazon.awssdk.services.s3control.model.Permission;
import software.amazon.awssdk.services.s3control.model.S3ControlException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class S3AgPermissionsFetcherTest {

    private static final String ACCOUNT_ID = "123456789012";
    private static final String INSTANCE_ARN = "arn:aws:s3:us-east-1:123456789012:access-grants/default";
    private static final String ALICE_ARN = "arn:aws:iam::123456789012:role/alice";
    private static final String BOB_ARN = "arn:aws:iam::123456789012:role/bob";

    private S3AgPermissionsFetcher fetcher;

    @BeforeEach
    void setUp() {
        // Pass null client — only the helper method is tested here (no AWS call made)
        fetcher = new S3AgPermissionsFetcher(null, ACCOUNT_ID, INSTANCE_ARN);
    }

    // -----------------------------------------------------------------------
    // extractGranteeArn tests (pre-existing)
    // -----------------------------------------------------------------------

    @Test
    void extractGranteeArn_returnsIdentifier() {
        Grantee g = Grantee.builder()
                .granteeType(GranteeType.IAM)
                .granteeIdentifier("arn:aws:iam::123:role/analyst")
                .build();
        assertEquals("arn:aws:iam::123:role/analyst", fetcher.extractGranteeArn(g));
    }

    @Test
    void extractGranteeArn_nullGrantee_returnsNull() {
        assertNull(fetcher.extractGranteeArn(null));
    }

    @Test
    void extractGranteeArn_nullIdentifier_returnsNull() {
        Grantee g = Grantee.builder().build();
        assertNull(fetcher.extractGranteeArn(g));
    }

    // -----------------------------------------------------------------------
    // fetchAll tests
    // -----------------------------------------------------------------------

    @Test
    void fetchAll_singlePageSingleGrant() {
        ListAccessGrantsResponse page = ListAccessGrantsResponse.builder()
                .accessGrantsList(grantEntry(ALICE_ARN, "s3://bucket/data/", "READ"))
                .build();

        S3AgPermissionsFetcher f = new S3AgPermissionsFetcher(stubClient(page), ACCOUNT_ID, INSTANCE_ARN);
        Set<SimulatorPermission> result = f.fetchAll();

        assertEquals(1, result.size());
        SimulatorPermission perm = result.iterator().next();
        assertEquals(ALICE_ARN, perm.principalArn());
        assertEquals("S3_PREFIX", perm.resourceType());
        assertEquals("s3://bucket/data/", perm.resourceId());
        assertEquals("READ", perm.permission());
        assertFalse(perm.grantable());
    }

    @Test
    void fetchAll_paginationAcrossTwoPages() {
        ListAccessGrantsResponse page1 = ListAccessGrantsResponse.builder()
                .accessGrantsList(grantEntry(ALICE_ARN, "s3://bucket/alice/", "READ"))
                .nextToken("tok1")
                .build();
        ListAccessGrantsResponse page2 = ListAccessGrantsResponse.builder()
                .accessGrantsList(grantEntry(BOB_ARN, "s3://bucket/bob/", "WRITE"))
                .build();

        S3AgPermissionsFetcher f = new S3AgPermissionsFetcher(stubClient(page1, page2), ACCOUNT_ID, INSTANCE_ARN);
        Set<SimulatorPermission> result = f.fetchAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(p -> p.principalArn().equals(ALICE_ARN) && p.permission().equals("READ")));
        assertTrue(result.stream().anyMatch(p -> p.principalArn().equals(BOB_ARN) && p.permission().equals("WRITE")));
    }

    @Test
    void fetchAll_returns404GracefullyAsEmptySet() {
        S3ControlException ex = (S3ControlException) S3ControlException.builder()
                .statusCode(404).message("Not Found").build();

        S3ControlClient throwing404 = new S3ControlClient() {
            @Override
            public ListAccessGrantsResponse listAccessGrants(ListAccessGrantsRequest r) {
                throw ex;
            }
            @Override public String serviceName() { return "s3control"; }
            @Override public void close() {}
        };

        S3AgPermissionsFetcher f = new S3AgPermissionsFetcher(throwing404, ACCOUNT_ID, INSTANCE_ARN);
        Set<SimulatorPermission> result = f.fetchAll();

        assertTrue(result.isEmpty());
    }

    @Test
    void fetchAll_nonFourOhFourExceptionRethrown() {
        S3ControlException ex = (S3ControlException) S3ControlException.builder()
                .statusCode(500).message("Internal Server Error").build();

        S3ControlClient throwing500 = new S3ControlClient() {
            @Override
            public ListAccessGrantsResponse listAccessGrants(ListAccessGrantsRequest r) {
                throw ex;
            }
            @Override public String serviceName() { return "s3control"; }
            @Override public void close() {}
        };

        S3AgPermissionsFetcher f = new S3AgPermissionsFetcher(throwing500, ACCOUNT_ID, INSTANCE_ARN);
        assertThrows(S3ControlException.class, f::fetchAll);
    }

    @Test
    void fetchAll_nullLocationConfigurationUsesUnknownResourceId() {
        ListAccessGrantEntry entryWithoutLocation = ListAccessGrantEntry.builder()
                .grantee(Grantee.builder()
                        .granteeType(GranteeType.IAM)
                        .granteeIdentifier(ALICE_ARN)
                        .build())
                .permission(Permission.READ)
                .build();

        ListAccessGrantsResponse page = ListAccessGrantsResponse.builder()
                .accessGrantsList(entryWithoutLocation)
                .build();

        S3AgPermissionsFetcher f = new S3AgPermissionsFetcher(stubClient(page), ACCOUNT_ID, INSTANCE_ARN);
        Set<SimulatorPermission> result = f.fetchAll();

        assertEquals(1, result.size());
        assertEquals("unknown", result.iterator().next().resourceId());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private S3ControlClient stubClient(ListAccessGrantsResponse... pages) {
        return new S3ControlClient() {
            int call = 0;
            @Override
            public ListAccessGrantsResponse listAccessGrants(ListAccessGrantsRequest r) {
                return pages[Math.min(call++, pages.length - 1)];
            }
            @Override public String serviceName() { return "s3control"; }
            @Override public void close() {}
        };
    }

    private ListAccessGrantEntry grantEntry(String granteeArn, String subPrefix, String permission) {
        return ListAccessGrantEntry.builder()
                .grantee(Grantee.builder()
                        .granteeType(GranteeType.IAM)
                        .granteeIdentifier(granteeArn)
                        .build())
                .accessGrantsLocationConfiguration(
                        AccessGrantsLocationConfiguration.builder()
                                .s3SubPrefix(subPrefix)
                                .build())
                .permission(Permission.fromValue(permission))
                .build();
    }
}
