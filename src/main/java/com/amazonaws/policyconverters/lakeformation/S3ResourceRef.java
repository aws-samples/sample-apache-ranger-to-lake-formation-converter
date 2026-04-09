package com.amazonaws.policyconverters.lakeformation;

import java.util.Objects;

/**
 * Parsed components of an S3 resource ARN.
 */
public final class S3ResourceRef {

    private final String bucket;
    private final String path;

    public S3ResourceRef(String bucket, String path) {
        this.bucket = bucket;
        this.path = path;
    }

    public String getBucket() {
        return bucket;
    }

    public String getPath() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        S3ResourceRef that = (S3ResourceRef) o;
        return Objects.equals(bucket, that.bucket) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucket, path);
    }

    @Override
    public String toString() {
        return "S3ResourceRef{bucket='" + bucket + "', path='" + path + "'}";
    }
}
