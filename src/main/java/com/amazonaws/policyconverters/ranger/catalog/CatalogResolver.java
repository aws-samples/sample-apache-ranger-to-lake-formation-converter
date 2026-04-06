package com.amazonaws.policyconverters.ranger.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTableResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves wildcard patterns against the AWS Glue Data Catalog.
 * <p>
 * Since Lake Formation does not support wildcard grants, this class expands
 * Ranger wildcard patterns (e.g., "db_*", "events_?") into explicit resource
 * names by querying the Glue Data Catalog.
 * <p>
 * Wildcard conversion rules:
 * <ul>
 *   <li>{@code *} → {@code .*} (match any sequence of characters)</li>
 *   <li>{@code ?} → {@code .} (match any single character)</li>
 * </ul>
 * <p>
 * On any AWS exception, the error is logged and an empty list is returned
 * (graceful failure per design: "Catalog Resolution Failure → log error, skip").
 */
public class CatalogResolver {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogResolver.class);

    private final GlueClient glueClient;

    public CatalogResolver(GlueClient glueClient) {
        this.glueClient = glueClient;
    }

    /**
     * Expand a wildcard database pattern to matching database names.
     * <p>
     * Queries all databases from the Glue Data Catalog (handling pagination)
     * and filters by the given Ranger wildcard pattern.
     *
     * @param pattern Ranger wildcard pattern (e.g., "analytics_*", "*", "prod_db")
     * @return list of matching database names, or empty list on failure
     */
    public List<String> expandDatabases(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }

        Pattern regex = toRegexPattern(pattern);

        try {
            List<String> allDatabases = new ArrayList<>();
            String nextToken = null;

            do {
                GetDatabasesRequest.Builder requestBuilder = GetDatabasesRequest.builder();
                if (nextToken != null) {
                    requestBuilder.nextToken(nextToken);
                }

                GetDatabasesResponse response = glueClient.getDatabases(requestBuilder.build());

                for (Database db : response.databaseList()) {
                    allDatabases.add(db.name());
                }

                nextToken = response.nextToken();
            } while (nextToken != null);

            return allDatabases.stream()
                    .filter(name -> regex.matcher(name).matches())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.error("Failed to expand database pattern '{}': {}", pattern, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Expand a wildcard table pattern within a database to matching table names.
     * <p>
     * Queries all tables in the specified database from the Glue Data Catalog
     * (handling pagination) and filters by the given Ranger wildcard pattern.
     *
     * @param database the database to query tables from
     * @param pattern  Ranger wildcard pattern (e.g., "events_*", "*")
     * @return list of matching table names, or empty list on failure
     */
    public List<String> expandTables(String database, String pattern) {
        if (database == null || database.isEmpty() || pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }

        Pattern regex = toRegexPattern(pattern);

        try {
            List<String> allTables = new ArrayList<>();
            String nextToken = null;

            do {
                GetTablesRequest.Builder requestBuilder = GetTablesRequest.builder()
                        .databaseName(database);
                if (nextToken != null) {
                    requestBuilder.nextToken(nextToken);
                }

                GetTablesResponse response = glueClient.getTables(requestBuilder.build());

                for (Table table : response.tableList()) {
                    allTables.add(table.name());
                }

                nextToken = response.nextToken();
            } while (nextToken != null);

            return allTables.stream()
                    .filter(name -> regex.matcher(name).matches())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.error("Failed to expand table pattern '{}' in database '{}': {}",
                    pattern, database, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Expand a wildcard column pattern within a table to matching column names.
     * <p>
     * Queries the specified table from the Glue Data Catalog, extracts columns
     * from the StorageDescriptor, and filters by the given Ranger wildcard pattern.
     *
     * @param database the database containing the table
     * @param table    the table to query columns from
     * @param pattern  Ranger wildcard pattern (e.g., "user_*", "*")
     * @return list of matching column names, or empty list on failure
     */
    public List<String> expandColumns(String database, String table, String pattern) {
        if (database == null || database.isEmpty()
                || table == null || table.isEmpty()
                || pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }

        Pattern regex = toRegexPattern(pattern);

        try {
            GetTableRequest request = GetTableRequest.builder()
                    .databaseName(database)
                    .name(table)
                    .build();

            GetTableResponse response = glueClient.getTable(request);
            Table glueTable = response.table();

            List<String> columnNames = new ArrayList<>();

            if (glueTable.storageDescriptor() != null
                    && glueTable.storageDescriptor().columns() != null) {
                for (Column col : glueTable.storageDescriptor().columns()) {
                    columnNames.add(col.name());
                }
            }

            // Also include partition keys as columns
            if (glueTable.partitionKeys() != null) {
                for (Column col : glueTable.partitionKeys()) {
                    columnNames.add(col.name());
                }
            }

            return columnNames.stream()
                    .filter(name -> regex.matcher(name).matches())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            LOG.error("Failed to expand column pattern '{}' in table '{}.{}': {}",
                    pattern, database, table, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Convert a Ranger wildcard pattern to a Java regex Pattern.
     * <p>
     * Conversion rules:
     * <ul>
     *   <li>All regex special characters are escaped first</li>
     *   <li>{@code *} → {@code .*} (match any sequence of characters)</li>
     *   <li>{@code ?} → {@code .} (match any single character)</li>
     * </ul>
     *
     * @param wildcardPattern the Ranger wildcard pattern
     * @return compiled regex Pattern
     */
    static Pattern toRegexPattern(String wildcardPattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < wildcardPattern.length(); i++) {
            char c = wildcardPattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '\\':
                case '^':
                case '$':
                case '|':
                case '+':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }
}
