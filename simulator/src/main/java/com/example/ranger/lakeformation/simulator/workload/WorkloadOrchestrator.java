package com.example.ranger.lakeformation.simulator.workload;

import java.time.Instant;
import java.util.*;

/**
 * Generates weighted-random batches of MutationOperation for one simulator cycle.
 *
 * Weight table (must sum to 100):
 *   CREATE  30%
 *   UPDATE  20%
 *   DISABLE 15%
 *   ENABLE  15%
 *   DELETE  10%
 *   (remaining 10% no-op)
 *
 * Generator selection is a second weighted draw across the List<GeneratorEntry>.
 * Batch size is 1–10, chosen uniformly at random.
 */
public class WorkloadOrchestrator {
    private static final int BATCH_MIN = 2;
    private static final int BATCH_MAX = 20;
    private static final int WEIGHT_CREATE  = 30;
    private static final int WEIGHT_UPDATE  = 50;
    private static final int WEIGHT_DISABLE = 65;
    private static final int WEIGHT_ENABLE  = 80;
    private static final int WEIGHT_DELETE  = 90;

    private final List<String> existingPolicyIds;
    private final Map<String, GeneratorEntry> policyIdToGenerator;
    // Resource node (db/table, catalog/schema/table, column, data-location, ...) each policy was
    // CREATEd with. UPDATE reuses this so a policy's resource stays fixed for its lifetime —
    // an UPDATE only varies principals/accesses/enabled, mirroring how real Ranger admins edit
    // an existing policy rather than repurposing it onto an entirely different table.
    private final Map<String, Object> policyIdToResource;
    private final List<GeneratorEntry> generators;
    private final int totalWeight;
    private final Random random;
    // Shared, live view of the Glue catalog (db → tables), or null to disable Glue-table deletion.
    // When present, the orchestrator occasionally emits a DeleteGlueTable op; the driver performs
    // the actual Glue delete and removes the table from THIS map, so the oracle (which reads the
    // same map) stops expecting grants for it. Generators keep their own frozen copies and may
    // still author policies for a deleted table — which is realistic and exercises the
    // grant-on-missing-resource path in the sync service.
    private final Map<String, List<String>> liveCatalog;
    // Tables already chosen for deletion this run (db.table), so we never pick one twice.
    private final Set<String> deletedTables = new HashSet<>();
    // Probability (out of 100) that a mutation slot becomes a Glue-table deletion, carved from the
    // no-op range. Deliberately rare — each deletion permanently shrinks the finite table pool.
    private static final int GLUE_DELETE_ROLL = 90; // exactly this roll value → delete (~1%)
    // Never delete a table if it would leave the database with fewer than this many tables.
    private static final int MIN_TABLES_PER_DB = 2;

    public WorkloadOrchestrator(List<String> existingPolicyIds,
                                List<GeneratorEntry> generators, Random random) {
        this(existingPolicyIds, generators, random, null);
    }

    public WorkloadOrchestrator(List<String> existingPolicyIds,
                                List<GeneratorEntry> generators, Random random,
                                Map<String, List<String>> liveCatalog) {
        this.existingPolicyIds   = new ArrayList<>(existingPolicyIds);
        this.policyIdToGenerator = new HashMap<>();
        this.policyIdToResource  = new HashMap<>();
        this.generators          = List.copyOf(generators);
        this.totalWeight         = generators.stream().mapToInt(GeneratorEntry::weight).sum();
        this.random              = random;
        this.liveCatalog         = liveCatalog;
    }

    public List<MutationOperation> generateBatch() {
        int batchSize = BATCH_MIN + random.nextInt(BATCH_MAX - BATCH_MIN + 1);
        List<MutationOperation> batch = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            MutationOperation op = pickOperation(random.nextInt(100));
            if (op != null) batch.add(op);
        }
        return batch;
    }

    public List<String> getExistingPolicyIds() {
        return Collections.unmodifiableList(existingPolicyIds);
    }

    private MutationOperation pickOperation(int roll) {
        if (roll < WEIGHT_CREATE) {
            GeneratorEntry entry = pickGenerator();
            String newId = entry.name() + "-sim-" + Long.toUnsignedString(random.nextLong(), 36);
            Map<String, Object> payload = entry.generator().generate(newId);
            existingPolicyIds.add(newId);
            policyIdToGenerator.put(newId, entry);
            if (payload != null && payload.get("resources") != null) {
                policyIdToResource.put(newId, payload.get("resources"));
            }
            return new MutationOperation.CreatePolicy(Instant.now(), newId, payload);
        }
        if (existingPolicyIds.isEmpty()) return null;
        if (roll < WEIGHT_UPDATE) {
            String id = randomFrom(existingPolicyIds);
            GeneratorEntry entry = policyIdToGenerator.getOrDefault(id, pickGenerator());
            Map<String, Object> payload = entry.generator().generate(id);
            // Pin the resource to the one this policy was created with, so an UPDATE only
            // changes principals/accesses/enabled — not which table/database it targets.
            payload = pinResource(id, payload);
            return new MutationOperation.UpdatePolicy(Instant.now(), id, payload);
        }
        if (roll < WEIGHT_DISABLE) {
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.DisablePolicy(Instant.now(), id);
        }
        if (roll < WEIGHT_ENABLE) {
            String id = randomFrom(existingPolicyIds);
            return new MutationOperation.EnablePolicy(Instant.now(), id);
        }
        if (roll < WEIGHT_DELETE) {
            String id = randomFrom(existingPolicyIds);
            existingPolicyIds.remove(id);
            policyIdToGenerator.remove(id);
            policyIdToResource.remove(id);
            return new MutationOperation.DeletePolicy(Instant.now(), id);
        }
        // Rare: delete a Glue table out from under live Ranger policies (carved from the no-op range).
        if (liveCatalog != null && roll == GLUE_DELETE_ROLL) {
            MutationOperation glueDelete = pickGlueTableDeletion();
            if (glueDelete != null) return glueDelete;
        }
        return null;
    }

    /**
     * Choose a table to delete from the Glue catalog, or null if none is safe to delete this cycle.
     * Guards: never empties a database below {@link #MIN_TABLES_PER_DB}, never re-picks a table
     * already deleted this run. Removes the chosen table from the shared {@code liveCatalog} up
     * front so the oracle stops expecting its grants immediately (the driver performs the actual
     * Glue delete); if the delete op is later dropped this is still safe — the oracle simply won't
     * expect a table the sync can no longer grant.
     */
    private MutationOperation pickGlueTableDeletion() {
        List<String> candidateDbs = new ArrayList<>(liveCatalog.keySet());
        Collections.shuffle(candidateDbs, random);
        for (String db : candidateDbs) {
            List<String> tables = liveCatalog.get(db);
            if (tables == null || tables.size() <= MIN_TABLES_PER_DB) continue;
            List<String> pickable = new ArrayList<>();
            for (String t : tables) {
                if (!deletedTables.contains(db + "." + t)) pickable.add(t);
            }
            if (pickable.size() <= MIN_TABLES_PER_DB) continue;
            String table = pickable.get(random.nextInt(pickable.size()));
            deletedTables.add(db + "." + table);
            // Remove from the shared live catalog so the oracle no longer expects grants for it.
            tables.remove(table);
            return new MutationOperation.DeleteGlueTable(Instant.now(), db, table);
        }
        return null;
    }

    /**
     * Return a copy of {@code payload} with its {@code resources} node replaced by the resource
     * the given policy was originally created with. If no original resource is recorded (e.g. the
     * policy predates this run) or the payload has no resources, the payload is returned unchanged.
     */
    private Map<String, Object> pinResource(String id, Map<String, Object> payload) {
        Object originalResource = policyIdToResource.get(id);
        if (originalResource == null || payload == null || !payload.containsKey("resources")) {
            return payload;
        }
        Map<String, Object> pinned = new LinkedHashMap<>(payload);
        pinned.put("resources", originalResource);
        return pinned;
    }

    private GeneratorEntry pickGenerator() {
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (GeneratorEntry entry : generators) {
            cumulative += entry.weight();
            if (roll < cumulative) return entry;
        }
        return generators.getLast();
    }

    private String randomFrom(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }
}
