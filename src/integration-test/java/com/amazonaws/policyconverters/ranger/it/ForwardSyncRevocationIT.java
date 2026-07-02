package com.amazonaws.policyconverters.ranger.it;

import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.model.DryRunOutput;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for forward-sync revocation correctness within a single continuous
 * {@link com.amazonaws.policyconverters.sync.SyncService} session (single-service pipeline).
 *
 * <p>Verifies that when a Ranger policy is deleted, the incremental diff emits the REVOKE and the
 * Lake Formation grant does not outlive its policy — including the sequential-regrant case
 * (grant → delete → re-grant the identical tuple via a new policy → delete) that the multi-service
 * simulator's random workload exercises heavily.
 *
 * <p>Context: the simulator initially appeared to show orphaned over-grants surviving policy
 * deletion. These tests were written to reproduce that as a forward-sync bug — but they PASS,
 * showing the single-service revocation path is correct. The apparent "orphans" were traced to a
 * simulator-oracle blind spot (a source service missing from the expected-permissions
 * computation), not a sync defect. Retained as a permanent regression guard.
 *
 * <p>Because the dry-run client records per-cycle delta operations (not net LF state), each
 * test reconstructs the net set of live LF permissions by folding every cycle's GRANTs and
 * REVOKEs in order, then asserts what Lake Formation would actually hold.
 */
public class ForwardSyncRevocationIT extends DryRunPipelineIT {

    /** A single live LF permission: (principal, db, table, column-set, permission). */
    private record LivePerm(String principalArn, String db, String table,
                            Set<String> columns, LFPermission permission) {}

    /**
     * Fold all dry-run cycles (in file order) into the net set of live LF permissions:
     * a GRANT adds each atom, a REVOKE removes it. This mirrors what Lake Formation
     * actually holds after the sequence of batch operations.
     */
    private Set<LivePerm> reconstructLiveState() throws Exception {
        Set<LivePerm> live = new HashSet<>();
        List<DryRunOutput> outputs = readDryRunOutputs();
        for (DryRunOutput out : outputs) {
            for (LFPermissionOperation op : out.getOperations()) {
                if (op.getResource() == null) continue;
                Set<String> cols = op.getResource().getColumnNames() == null
                        ? Set.of() : new TreeSet<>(op.getResource().getColumnNames());
                for (LFPermission perm : op.getPermissions()) {
                    LivePerm lp = new LivePerm(
                            op.getPrincipalArn(),
                            op.getResource().getDatabaseName(),
                            op.getResource().getTableName(),
                            cols,
                            perm);
                    if (op.getOperationType() == OperationType.GRANT) {
                        live.add(lp);
                    } else {
                        live.remove(lp);
                    }
                }
            }
        }
        return live;
    }

    /**
     * Live permissions for a principal, scoped to a specific database. Scoping by db keeps the
     * test hermetic on a shared Ranger instance that may hold unrelated (e.g. simulator) policies
     * for the same principal in other databases.
     */
    private Set<LivePerm> liveFor(Set<LivePerm> live, String principalArn, String db) {
        return live.stream()
                .filter(p -> principalArn.equals(p.principalArn()))
                .filter(p -> db.equals(p.db()))
                .collect(Collectors.toSet());
    }

    private String tablePolicy(Integer id, String name, String user, String db, String table,
                               String... accesses) {
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < accesses.length; i++) {
            if (i > 0) acc.append(",");
            acc.append("{\"type\":\"").append(accesses[i]).append("\",\"isAllowed\":true}");
        }
        return "{"
                + (id != null ? "\"id\":" + id + "," : "")
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"" + db + "\"],\"isRecursive\":false},"
                + "  \"table\":{\"values\":[\"" + table + "\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"" + user + "\"],"
                + "  \"accesses\":[" + acc + "]"
                + "}]"
                + "}";
    }

    /**
     * Baseline: a single policy granted then deleted must leave zero live permissions.
     * (Sanity check that the harness + single-policy revoke path work.)
     *
     * <p>Uses pure TABLE-level actions (drop/alter) — not SELECT — so the scenario isolates the
     * revocation diff and does not go through the TABLE/TABLE_WITH_COLUMNS conflict path.
     */
    @Test
    void singlePolicyDeletion_revokesGrant() throws Exception {
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        int id = createAndTrackPolicy(tablePolicy(null, "leak-single", "analyst",
                "test_db", "events", "drop", "alter"));
        triggerSync();

        deletePolicyAndUntrack(id);
        triggerSync();

        Set<LivePerm> live = reconstructLiveState();
        assertTrue(liveFor(live, analystArn, "test_db").isEmpty(),
                "After deleting the only policy, analyst must retain NO live LF permissions. Leaked: "
                        + liveFor(live, analystArn, "test_db"));
    }

    /**
     * Regrant scenario matching the exact simulator trace:
     * GRANT(policyA) -> delete A (REVOKE) -> GRANT(policyB, same tuple) -> delete B.
     * The final deletion must revoke the tuple; a surviving grant is the forward-sync leak.
     *
     * <p>Ranger forbids two enabled policies on the same resource, so the same (principal,
     * resource, permission) tuple can only recur across policies SEQUENTIALLY — which is exactly
     * what the simulator's random create/delete workload produced. This test replays that.
     */
    @Test
    void grantRevokeRegrantDelete_revokesFully() throws Exception {
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        // Cycle 1: policy A grants (analyst, test_db.orders, DROP+ALTER).
        int idA = createAndTrackPolicy(tablePolicy(null, "leak-seq-a", "analyst",
                "test_db", "orders", "drop", "alter"));
        triggerSync();

        // Cycle 2: delete A — tuple revoked.
        deletePolicyAndUntrack(idA);
        triggerSync();

        // Cycle 3: policy B re-grants the identical tuple.
        int idB = createAndTrackPolicy(tablePolicy(null, "leak-seq-b", "analyst",
                "test_db", "orders", "drop", "alter"));
        triggerSync();

        // Cycle 4: delete B — tuple must be revoked again.
        deletePolicyAndUntrack(idB);
        triggerSync();

        Set<LivePerm> live = reconstructLiveState();
        assertTrue(liveFor(live, analystArn, "test_db").isEmpty(),
                "After grant->revoke->regrant->delete, analyst must retain NO live LF permissions. "
                        + "A surviving grant is the forward-sync revocation leak. Leaked: "
                        + liveFor(live, analystArn, "test_db"));
    }

    /**
     * Database-level variant of the regrant scenario — mirrors the simulator sequence that first
     * looked like a leak (analyst DATABASE default_sim CREATE_TABLE/DROP: granted by
     * lakeformation:414, revoked, re-granted by hive:433). This single-service replay revokes
     * correctly; the cross-service replay lives in {@link CrossServiceRevocationIT}.
     */
    @Test
    void grantRevokeRegrantDelete_databaseLevel_revokesFully() throws Exception {
        String analystArn = "arn:aws:iam::" + TEST_ACCOUNT_ID + ":role/analyst";

        int idA = createAndTrackPolicy(dbPolicy(null, "leak-db-a", "analyst", "test_db",
                "create_table", "drop"));
        triggerSync();
        deletePolicyAndUntrack(idA);
        triggerSync();

        int idB = createAndTrackPolicy(dbPolicy(null, "leak-db-b", "analyst", "test_db",
                "create_table", "drop"));
        triggerSync();
        deletePolicyAndUntrack(idB);
        triggerSync();

        Set<LivePerm> live = reconstructLiveState();
        assertTrue(liveFor(live, analystArn, "test_db").isEmpty(),
                "After DB-level grant->revoke->regrant->delete, analyst must retain NO live LF "
                        + "permissions. Leaked: " + liveFor(live, analystArn, "test_db"));
    }

    private String dbPolicy(Integer id, String name, String user, String db, String... accesses) {
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < accesses.length; i++) {
            if (i > 0) acc.append(",");
            acc.append("{\"type\":\"").append(accesses[i]).append("\",\"isAllowed\":true}");
        }
        return "{"
                + (id != null ? "\"id\":" + id + "," : "")
                + "\"name\":\"" + name + "\","
                + "\"service\":\"lakeformation\","
                + "\"isEnabled\":true,"
                + "\"policyType\":0,"
                + "\"resources\":{"
                + "  \"database\":{\"values\":[\"" + db + "\"],\"isRecursive\":false}"
                + "},"
                + "\"policyItems\":[{"
                + "  \"users\":[\"" + user + "\"],"
                + "  \"accesses\":[" + acc + "]"
                + "}]"
                + "}";
    }
}
