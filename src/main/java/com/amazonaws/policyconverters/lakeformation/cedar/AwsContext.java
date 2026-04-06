package com.amazonaws.policyconverters.lakeformation.cedar;

/**
 * AWS region and account context for ARN construction.
 */
public final class AwsContext {

    private final String region;
    private final String accountId;
    private final String catalogId;

    public AwsContext(String region, String accountId, String catalogId) {
        this.region = region;
        this.accountId = accountId;
        this.catalogId = catalogId;
    }

    public String getRegion() {
        return region;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCatalogId() {
        return catalogId;
    }

    @Override
    public String toString() {
        return "AwsContext{region='" + region + "', accountId='" + accountId + "', catalogId='" + catalogId + "'}";
    }
}
