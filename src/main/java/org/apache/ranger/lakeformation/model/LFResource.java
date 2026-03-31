package org.apache.ranger.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a Lake Formation resource target (database, table, column, row filter,
 * or data location).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LFResource {

    private final String catalogId;
    private final String databaseName;
    private final String tableName;
    private final Set<String> columnNames;
    private final String rowFilterExpression;
    private final String dataLocationPath;

    @JsonCreator
    public LFResource(
            @JsonProperty("catalogId") String catalogId,
            @JsonProperty("databaseName") String databaseName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("columnNames") Set<String> columnNames,
            @JsonProperty("rowFilterExpression") String rowFilterExpression) {
        this(catalogId, databaseName, tableName, columnNames, rowFilterExpression, null);
    }

    public LFResource(String catalogId, String databaseName, String tableName,
                      Set<String> columnNames, String rowFilterExpression,
                      String dataLocationPath) {
        this.catalogId = catalogId;
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.columnNames = columnNames != null
                ? Collections.unmodifiableSet(new HashSet<>(columnNames))
                : null;
        this.rowFilterExpression = rowFilterExpression;
        this.dataLocationPath = dataLocationPath;
    }

    public String getCatalogId() {
        return catalogId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public Set<String> getColumnNames() {
        return columnNames;
    }

    public String getRowFilterExpression() {
        return rowFilterExpression;
    }

    public String getDataLocationPath() {
        return dataLocationPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LFResource that = (LFResource) o;
        return Objects.equals(catalogId, that.catalogId)
                && Objects.equals(databaseName, that.databaseName)
                && Objects.equals(tableName, that.tableName)
                && Objects.equals(columnNames, that.columnNames)
                && Objects.equals(rowFilterExpression, that.rowFilterExpression)
                && Objects.equals(dataLocationPath, that.dataLocationPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalogId, databaseName, tableName, columnNames, rowFilterExpression, dataLocationPath);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LFResource{");
        sb.append("catalogId='").append(catalogId).append('\'');
        sb.append(", databaseName='").append(databaseName).append('\'');
        if (tableName != null) {
            sb.append(", tableName='").append(tableName).append('\'');
        }
        if (columnNames != null && !columnNames.isEmpty()) {
            sb.append(", columnNames=").append(columnNames);
        }
        if (rowFilterExpression != null) {
            sb.append(", rowFilterExpression='").append(rowFilterExpression).append('\'');
        }
        if (dataLocationPath != null) {
            sb.append(", dataLocationPath='").append(dataLocationPath).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
