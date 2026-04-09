package com.amazonaws.policyconverters.lakeformation;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for ARN construction round-trip.
 *
 * Feature: cedar-policy-abstraction, Property 1: ARN Construction Round-Trip
 * **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.7**
 */
class ArnRoundTripPropertyTest {

    private static final String[] VALID_REGIONS = {
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-west-2", "eu-central-1",
            "ap-southeast-1", "ap-southeast-2", "ap-northeast-1"
    };

    @Property(tries = 100)
    void databaseArnRoundTrip(
            @ForAll("regions") String region,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String databaseName
    ) {
        String arn = "arn:aws:glue:" + region + ":" + accountId + ":database/" + databaseName;
        GlueResourceRef ref = ArnParser.parseDatabaseArn(arn);

        assertEquals(region, ref.getRegion());
        assertEquals(accountId, ref.getAccountId());
        assertEquals(databaseName, ref.getDatabaseName());
        assertNull(ref.getTableName());
        assertNull(ref.getColumnName());
    }

    @Property(tries = 100)
    void tableArnRoundTrip(
            @ForAll("regions") String region,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String databaseName,
            @ForAll("resourceNames") String tableName
    ) {
        String arn = "arn:aws:glue:" + region + ":" + accountId + ":table/" + databaseName + "/" + tableName;
        GlueResourceRef ref = ArnParser.parseTableArn(arn);

        assertEquals(region, ref.getRegion());
        assertEquals(accountId, ref.getAccountId());
        assertEquals(databaseName, ref.getDatabaseName());
        assertEquals(tableName, ref.getTableName());
        assertNull(ref.getColumnName());
    }

    @Property(tries = 100)
    void columnArnRoundTrip(
            @ForAll("regions") String region,
            @ForAll("accountIds") String accountId,
            @ForAll("resourceNames") String databaseName,
            @ForAll("resourceNames") String tableName,
            @ForAll("resourceNames") String columnName
    ) {
        String arn = "arn:aws:glue:" + region + ":" + accountId
                + ":column/" + databaseName + "/" + tableName + "/" + columnName;
        GlueResourceRef ref = ArnParser.parseColumnArn(arn);

        assertEquals(region, ref.getRegion());
        assertEquals(accountId, ref.getAccountId());
        assertEquals(databaseName, ref.getDatabaseName());
        assertEquals(tableName, ref.getTableName());
        assertEquals(columnName, ref.getColumnName());
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> regions() {
        return Arbitraries.of(VALID_REGIONS);
    }

    @Provide
    Arbitrary<String> accountIds() {
        return Arbitraries.strings().numeric().ofLength(12);
    }

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
