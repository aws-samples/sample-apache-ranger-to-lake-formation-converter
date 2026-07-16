package com.example.ranger.lakeformation.simulator.workload;

import java.time.Instant;

public sealed interface MutationOperation permits
        MutationOperation.CreatePolicy,
        MutationOperation.UpdatePolicy,
        MutationOperation.DisablePolicy,
        MutationOperation.EnablePolicy,
        MutationOperation.DeletePolicy,
        MutationOperation.DeleteGlueTable {

    Instant timestamp();

    record CreatePolicy(Instant timestamp, String policyId, Object policyPayload) implements MutationOperation {}
    record UpdatePolicy(Instant timestamp, String policyId, Object policyPayload) implements MutationOperation {}
    record DisablePolicy(Instant timestamp, String policyId) implements MutationOperation {}
    record EnablePolicy(Instant timestamp, String policyId) implements MutationOperation {}
    record DeletePolicy(Instant timestamp, String policyId) implements MutationOperation {}

    /**
     * Deletes a table from the Glue Data Catalog while its Ranger policies remain. Exercises the
     * "deleted GDC resource" path: Lake Formation auto-removes the table's permissions, and the
     * sync service must skip the now-impossible re-grant benignly (no dead-letter storm) rather
     * than retry it forever. The table is removed from the simulator's shared catalog so generators
     * stop targeting it and the oracle stops expecting grants for it.
     */
    record DeleteGlueTable(Instant timestamp, String database, String table) implements MutationOperation {}
}
