package com.amazonaws.policyconverters.lakeformation.cedar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArnParserTest {

    // --- isArn ---

    @Test
    void isArnReturnsTrueForValidArns() {
        assertTrue(ArnParser.isArn("arn:aws:glue:us-east-1:123456789012:database/mydb"));
        assertTrue(ArnParser.isArn("arn:aws:s3:::my-bucket/path"));
        assertTrue(ArnParser.isArn("arn:aws:iam::123456789012:role/MyRole"));
    }

    @Test
    void isArnReturnsFalseForNonArns() {
        assertFalse(ArnParser.isArn("urn:databricks:unity:ws:catalog/c"));
        assertFalse(ArnParser.isArn("just-a-string"));
        assertFalse(ArnParser.isArn(""));
        assertFalse(ArnParser.isArn(null));
    }

    // --- parseDatabaseArn ---

    @Test
    void parseDatabaseArnValid() {
        GlueResourceRef ref = ArnParser.parseDatabaseArn("arn:aws:glue:us-east-1:123456789012:database/analytics_db");
        assertEquals("us-east-1", ref.getRegion());
        assertEquals("123456789012", ref.getAccountId());
        assertEquals("analytics_db", ref.getDatabaseName());
        assertNull(ref.getTableName());
        assertNull(ref.getColumnName());
    }

    @Test
    void parseDatabaseArnThrowsForTableArn() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseDatabaseArn("arn:aws:glue:us-east-1:123456789012:table/db/tbl"));
    }

    @Test
    void parseDatabaseArnThrowsForEmptyDbName() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseDatabaseArn("arn:aws:glue:us-east-1:123456789012:database/"));
    }

    // --- parseTableArn ---

    @Test
    void parseTableArnValid() {
        GlueResourceRef ref = ArnParser.parseTableArn("arn:aws:glue:eu-west-1:999888777666:table/mydb/orders");
        assertEquals("eu-west-1", ref.getRegion());
        assertEquals("999888777666", ref.getAccountId());
        assertEquals("mydb", ref.getDatabaseName());
        assertEquals("orders", ref.getTableName());
        assertNull(ref.getColumnName());
    }

    @Test
    void parseTableArnThrowsForMissingTable() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseTableArn("arn:aws:glue:us-east-1:123456789012:table/dbonly"));
    }

    // --- parseColumnArn ---

    @Test
    void parseColumnArnValid() {
        GlueResourceRef ref = ArnParser.parseColumnArn("arn:aws:glue:ap-southeast-1:111222333444:column/db/tbl/email");
        assertEquals("ap-southeast-1", ref.getRegion());
        assertEquals("111222333444", ref.getAccountId());
        assertEquals("db", ref.getDatabaseName());
        assertEquals("tbl", ref.getTableName());
        assertEquals("email", ref.getColumnName());
    }

    @Test
    void parseColumnArnThrowsForMissingColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseColumnArn("arn:aws:glue:us-east-1:123456789012:column/db/tbl"));
    }

    // --- parseCatalogArn ---

    @Test
    void parseCatalogArnValidBare() {
        GlueResourceRef ref = ArnParser.parseCatalogArn("arn:aws:glue:us-east-1:123456789012:catalog");
        assertEquals("us-east-1", ref.getRegion());
        assertEquals("123456789012", ref.getAccountId());
        assertNull(ref.getDatabaseName());
    }

    @Test
    void parseCatalogArnValidNamed() {
        GlueResourceRef ref = ArnParser.parseCatalogArn("arn:aws:glue:us-east-1:123456789012:catalog/myCatalog");
        assertEquals("us-east-1", ref.getRegion());
        assertEquals("123456789012", ref.getAccountId());
        assertNull(ref.getDatabaseName());
    }

    // --- parseS3Arn ---

    @Test
    void parseS3ArnValid() {
        S3ResourceRef ref = ArnParser.parseS3Arn("arn:aws:s3:::my-bucket/data/path");
        assertEquals("my-bucket", ref.getBucket());
        assertEquals("data/path", ref.getPath());
    }

    @Test
    void parseS3ArnBucketOnly() {
        S3ResourceRef ref = ArnParser.parseS3Arn("arn:aws:s3:::my-bucket");
        assertEquals("my-bucket", ref.getBucket());
        assertEquals("", ref.getPath());
    }

    @Test
    void parseS3ArnThrowsForWrongService() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseS3Arn("arn:aws:glue:us-east-1:123456789012:database/db"));
    }

    @Test
    void parseS3ArnThrowsForEmptyBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseS3Arn("arn:aws:s3:::/path"));
    }

    // --- parseGlueArn auto-detection ---

    @Test
    void parseGlueArnAutoDetectsDatabase() {
        GlueResourceRef ref = ArnParser.parseGlueArn("arn:aws:glue:us-east-1:123456789012:database/mydb");
        assertEquals("mydb", ref.getDatabaseName());
        assertNull(ref.getTableName());
    }

    @Test
    void parseGlueArnAutoDetectsTable() {
        GlueResourceRef ref = ArnParser.parseGlueArn("arn:aws:glue:us-east-1:123456789012:table/db/tbl");
        assertEquals("db", ref.getDatabaseName());
        assertEquals("tbl", ref.getTableName());
    }

    @Test
    void parseGlueArnAutoDetectsColumn() {
        GlueResourceRef ref = ArnParser.parseGlueArn("arn:aws:glue:us-east-1:123456789012:column/db/tbl/col");
        assertEquals("col", ref.getColumnName());
    }

    @Test
    void parseGlueArnAutoDetectsCatalog() {
        GlueResourceRef ref = ArnParser.parseGlueArn("arn:aws:glue:us-east-1:123456789012:catalog");
        assertNull(ref.getDatabaseName());
    }

    @Test
    void parseGlueArnThrowsForUnknownResourceType() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseGlueArn("arn:aws:glue:us-east-1:123456789012:unknown/foo"));
    }

    // --- Malformed ARN handling ---

    @Test
    void throwsForNullArn() {
        assertThrows(IllegalArgumentException.class, () -> ArnParser.parseDatabaseArn(null));
    }

    @Test
    void throwsForNonArnString() {
        assertThrows(IllegalArgumentException.class, () -> ArnParser.parseDatabaseArn("not-an-arn"));
    }

    @Test
    void throwsForTooFewParts() {
        assertThrows(IllegalArgumentException.class, () -> ArnParser.parseDatabaseArn("arn:aws:glue"));
    }

    @Test
    void throwsForWrongService() {
        assertThrows(IllegalArgumentException.class,
                () -> ArnParser.parseDatabaseArn("arn:aws:s3:::bucket/path"));
    }
}
