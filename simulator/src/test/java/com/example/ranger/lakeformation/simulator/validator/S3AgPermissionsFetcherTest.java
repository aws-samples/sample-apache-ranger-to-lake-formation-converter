package com.example.ranger.lakeformation.simulator.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3control.model.Grantee;
import software.amazon.awssdk.services.s3control.model.GranteeType;

import static org.junit.jupiter.api.Assertions.*;

class S3AgPermissionsFetcherTest {

    private S3AgPermissionsFetcher fetcher;

    @BeforeEach
    void setUp() {
        // Pass null client — only the helper method is tested here (no AWS call made)
        fetcher = new S3AgPermissionsFetcher(null, "123456789012",
                "arn:aws:s3:us-east-1:123456789012:access-grants/default");
    }

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
}
