package com.amazonaws.policyconverters.lakeformation;

import java.util.Objects;

/**
 * Parsed components of a Glue resource ARN.
 * tableName and columnName are nullable for database-level and table-level resources respectively.
 */
public final class GlueResourceRef {

    private final String region;
    private final String accountId;
    private final String databaseName;
    private final String tableName;
    private final String columnName;

    public GlueResourceRef(String region, String accountId, String databaseName,
                           String tableName, String columnName) {
        this.region = region;
        this.accountId = accountId;
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public String getRegion() {
        return region;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GlueResourceRef that = (GlueResourceRef) o;
        return Objects.equals(region, that.region)
                && Objects.equals(accountId, that.accountId)
                && Objects.equals(databaseName, that.databaseName)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(columnName, that.columnName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(region, accountId, databaseName, tableName, columnName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GlueResourceRef{region='").append(region)
                .append("', accountId='").append(accountId)
                .append("', databaseName='").append(databaseName).append("'");
        if (tableName != null) {
            sb.append(", tableName='").append(tableName).append("'");
        }
        if (columnName != null) {
            sb.append(", columnName='").append(columnName).append("'");
        }
        sb.append("}");
        return sb.toString();
    }
}
