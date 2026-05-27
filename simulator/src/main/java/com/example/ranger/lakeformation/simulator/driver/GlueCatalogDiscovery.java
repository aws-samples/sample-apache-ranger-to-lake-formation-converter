package com.example.ranger.lakeformation.simulator.driver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers databases and tables from the Glue catalog at startup.
 * Used to build the resource map for HivePolicyGenerator so the simulator
 * always operates on tables that actually exist, without any hardcoded lists.
 */
public class GlueCatalogDiscovery {
    private static final Logger LOG = LoggerFactory.getLogger(GlueCatalogDiscovery.class);

    private final GlueClient glueClient;

    public GlueCatalogDiscovery(GlueClient glueClient) {
        this.glueClient = glueClient;
    }

    /**
     * Returns a map of database name → list of table names for all databases visible
     * to the caller in the Glue catalog. Databases with no tables are included with
     * an empty list so wildcard policies can still target them.
     */
    public Map<String, List<String>> discover() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> databases = listDatabases();
        LOG.info("Glue catalog discovery: found {} databases", databases.size());
        for (String db : databases) {
            List<String> tables = listTables(db);
            result.put(db, tables);
            LOG.info("  {}: {} tables", db, tables.size());
        }
        return result;
    }

    private List<String> listDatabases() {
        List<String> names = new ArrayList<>();
        String nextToken = null;
        do {
            GetDatabasesRequest.Builder req = GetDatabasesRequest.builder();
            if (nextToken != null) req.nextToken(nextToken);
            GetDatabasesResponse resp = glueClient.getDatabases(req.build());
            resp.databaseList().forEach(db -> names.add(db.name()));
            nextToken = resp.nextToken();
        } while (nextToken != null);
        return names;
    }

    private List<String> listTables(String database) {
        List<String> names = new ArrayList<>();
        String nextToken = null;
        try {
            do {
                GetTablesRequest.Builder req = GetTablesRequest.builder().databaseName(database);
                if (nextToken != null) req.nextToken(nextToken);
                GetTablesResponse resp = glueClient.getTables(req.build());
                resp.tableList().forEach(t -> names.add(t.name()));
                nextToken = resp.nextToken();
            } while (nextToken != null);
        } catch (EntityNotFoundException e) {
            LOG.warn("Database '{}' not found in Glue catalog; skipping", database);
        }
        return names;
    }
}
