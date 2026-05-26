package com.example.ranger.lakeformation.simulator.validator;

public record SimulatorPermission(
    String principalArn,    // IAM role/user ARN
    String resourceType,    // "TABLE", "DATABASE", "DATA_LOCATION", "S3_PREFIX"
    String resourceId,      // e.g. "arn:aws:glue:us-east-1:123456789012:table/db/tbl" or S3 prefix
    String permission,      // e.g. "SELECT", "INSERT", "DATA_LOCATION_ACCESS", "READ_WRITE"
    boolean grantable       // true if the permission carries grant option
) {}
