package com.example.ranger.lakeformation.simulator.validator;

import java.util.List;

@FunctionalInterface
public interface TableExpander {
    /**
     * Given a database name and a table pattern (may contain wildcards),
     * return the list of matching table names.
     */
    List<String> expand(String database, String tablePattern);
}
