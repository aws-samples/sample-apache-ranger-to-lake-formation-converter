package com.amazonaws.policyconverters.lakeformation;

import java.util.List;
import java.util.Map;

/**
 * Seam for enumerating the Glue catalog (databases and their tables).
 * The initial {@link LiveGlueTableLister} implementation calls the Glue API
 * directly. A future {@code GlueCatalogCache} implementation can be swapped
 * in without changing any callers.
 */
public interface TableLister {

    /**
     * Returns all databases and their tables.
     * Databases with no tables are included with an empty list.
     *
     * @return map of databaseName → list of tableNames, never null
     * @throws Exception if the underlying catalog call fails
     */
    Map<String, List<String>> listAll() throws Exception;
}
