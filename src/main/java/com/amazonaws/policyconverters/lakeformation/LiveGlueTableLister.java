package com.amazonaws.policyconverters.lakeformation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live {@link TableLister} implementation that pages through the Glue catalog.
 * Databases that disappear between the database-list and table-list calls are
 * silently skipped.
 */
public class LiveGlueTableLister implements TableLister {

    private static final Logger LOG = LoggerFactory.getLogger(LiveGlueTableLister.class);

    private final GlueClient glueClient;

    public LiveGlueTableLister(GlueClient glueClient) {
        this.glueClient = glueClient;
    }

    @Override
    public Map<String, List<String>> listAll() throws Exception {
        Map<String, List<String>> result = new HashMap<>();

        String nextToken = null;
        do {
            GetDatabasesRequest req = GetDatabasesRequest.builder()
                    .nextToken(nextToken)
                    .build();
            GetDatabasesResponse resp = glueClient.getDatabases(req);
            for (Database db : resp.databaseList()) {
                String dbName = db.name();
                List<String> tables = fetchTables(dbName);
                result.put(dbName, tables);
            }
            nextToken = resp.nextToken();
        } while (nextToken != null);

        LOG.info("LiveGlueTableLister: discovered {} databases", result.size());
        return result;
    }

    private List<String> fetchTables(String dbName) {
        List<String> tables = new ArrayList<>();
        try {
            String nextToken = null;
            do {
                GetTablesRequest req = GetTablesRequest.builder()
                        .databaseName(dbName)
                        .nextToken(nextToken)
                        .build();
                GetTablesResponse resp = glueClient.getTables(req);
                for (Table t : resp.tableList()) {
                    tables.add(t.name());
                }
                nextToken = resp.nextToken();
            } while (nextToken != null);
        } catch (EntityNotFoundException e) {
            LOG.warn("LiveGlueTableLister: database '{}' not found while listing tables, skipping", dbName);
        }
        return tables;
    }
}
