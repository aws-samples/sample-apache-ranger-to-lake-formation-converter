package com.amazonaws.policyconverters.ranger;

import java.util.Collections;
import java.util.List;

/**
 * A no-op {@link CatalogResolver} that returns the literal pattern without
 * querying Glue. Used by the assessment tool when AWS credentials are not
 * provided, so wildcard resources are treated as-is rather than expanded.
 */
public class PassthroughCatalogResolver extends CatalogResolver {

    public PassthroughCatalogResolver() {
        super(null);
    }

    @Override
    public List<String> expandDatabases(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(pattern);
    }

    @Override
    public List<String> expandTables(String database, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(pattern);
    }

    @Override
    public List<String> expandColumns(String database, String table, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(pattern);
    }
}
