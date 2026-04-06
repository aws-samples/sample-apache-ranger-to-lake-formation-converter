package com.amazonaws.policyconverters.lakeformation.cedar;

/**
 * Parses AWS ARN-formatted Cedar entity identifiers to extract
 * region, account, database, table, column, and S3 path components.
 */
public final class ArnParser {

    private ArnParser() {
        // static utility class
    }

    /**
     * Check if a string is a valid AWS ARN (starts with "arn:").
     */
    public static boolean isArn(String identifier) {
        return identifier != null && identifier.startsWith("arn:");
    }

    /**
     * Parse a Glue database ARN: arn:aws:glue:{region}:{account}:database/{db}
     */
    public static GlueResourceRef parseDatabaseArn(String arn) {
        String[] parts = splitArnParts(arn, "glue");
        String resource = parts[5];
        if (!resource.startsWith("database/")) {
            throw new IllegalArgumentException("Expected database ARN, got: " + arn);
        }
        String dbName = resource.substring("database/".length());
        if (dbName.isEmpty()) {
            throw new IllegalArgumentException("Database name is empty in ARN: " + arn);
        }
        return new GlueResourceRef(parts[3], parts[4], dbName, null, null);
    }

    /**
     * Parse a Glue table ARN: arn:aws:glue:{region}:{account}:table/{db}/{table}
     */
    public static GlueResourceRef parseTableArn(String arn) {
        String[] parts = splitArnParts(arn, "glue");
        String resource = parts[5];
        if (!resource.startsWith("table/")) {
            throw new IllegalArgumentException("Expected table ARN, got: " + arn);
        }
        String path = resource.substring("table/".length());
        String[] segments = path.split("/", 2);
        if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
            throw new IllegalArgumentException("Malformed table ARN (expected table/{db}/{table}): " + arn);
        }
        return new GlueResourceRef(parts[3], parts[4], segments[0], segments[1], null);
    }

    /**
     * Parse a Glue column ARN: arn:aws:glue:{region}:{account}:column/{db}/{table}/{col}
     */
    public static GlueResourceRef parseColumnArn(String arn) {
        String[] parts = splitArnParts(arn, "glue");
        String resource = parts[5];
        if (!resource.startsWith("column/")) {
            throw new IllegalArgumentException("Expected column ARN, got: " + arn);
        }
        String path = resource.substring("column/".length());
        String[] segments = path.split("/", 3);
        if (segments.length < 3 || segments[0].isEmpty() || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new IllegalArgumentException("Malformed column ARN (expected column/{db}/{table}/{col}): " + arn);
        }
        return new GlueResourceRef(parts[3], parts[4], segments[0], segments[1], segments[2]);
    }

    /**
     * Parse a Glue catalog ARN: arn:aws:glue:{region}:{account}:catalog
     * or arn:aws:glue:{region}:{account}:catalog/{name}
     */
    public static GlueResourceRef parseCatalogArn(String arn) {
        String[] parts = splitArnParts(arn, "glue");
        String resource = parts[5];
        if (!resource.equals("catalog") && !resource.startsWith("catalog/")) {
            throw new IllegalArgumentException("Expected catalog ARN, got: " + arn);
        }
        // Catalog ARN has no database/table/column — return with nulls
        return new GlueResourceRef(parts[3], parts[4], null, null, null);
    }

    /**
     * Parse an S3 ARN: arn:aws:s3:::{bucket}/{path}
     */
    public static S3ResourceRef parseS3Arn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            throw new IllegalArgumentException("Not a valid ARN: " + arn);
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            throw new IllegalArgumentException("Malformed ARN (too few parts): " + arn);
        }
        if (!"s3".equals(parts[2])) {
            throw new IllegalArgumentException("Expected S3 ARN (service=s3), got: " + arn);
        }
        String resource = parts[5];
        int slashIdx = resource.indexOf('/');
        if (slashIdx < 0) {
            // bucket only, no path
            if (resource.isEmpty()) {
                throw new IllegalArgumentException("Bucket name is empty in S3 ARN: " + arn);
            }
            return new S3ResourceRef(resource, "");
        }
        String bucket = resource.substring(0, slashIdx);
        String path = resource.substring(slashIdx + 1);
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("Bucket name is empty in S3 ARN: " + arn);
        }
        return new S3ResourceRef(bucket, path);
    }

    /**
     * Auto-detect Glue resource type from ARN and parse accordingly.
     * Supports database, table, column, and catalog ARNs.
     */
    public static GlueResourceRef parseGlueArn(String arn) {
        String[] parts = splitArnParts(arn, "glue");
        String resource = parts[5];
        if (resource.startsWith("database/")) {
            return parseDatabaseArn(arn);
        } else if (resource.startsWith("table/")) {
            return parseTableArn(arn);
        } else if (resource.startsWith("column/")) {
            return parseColumnArn(arn);
        } else if (resource.equals("catalog") || resource.startsWith("catalog/")) {
            return parseCatalogArn(arn);
        }
        throw new IllegalArgumentException("Unrecognized Glue resource type in ARN: " + arn);
    }

    /**
     * Split an ARN into its 6 colon-delimited parts and validate the service.
     */
    private static String[] splitArnParts(String arn, String expectedService) {
        if (arn == null || !arn.startsWith("arn:")) {
            throw new IllegalArgumentException("Not a valid ARN: " + arn);
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            throw new IllegalArgumentException("Malformed ARN (too few parts): " + arn);
        }
        if (!expectedService.equals(parts[2])) {
            throw new IllegalArgumentException(
                    "Expected service '" + expectedService + "', got '" + parts[2] + "' in ARN: " + arn);
        }
        return parts;
    }
}
