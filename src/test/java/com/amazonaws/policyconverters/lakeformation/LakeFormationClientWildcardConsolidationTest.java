package com.amazonaws.policyconverters.lakeformation;

import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code consolidateOperations} and table-wildcard resources.
 *
 * <p>A table-wildcard resource is distinguished from a database-level one <em>only</em> by the
 * {@code allTables} flag: both carry a null {@code tableName}. {@code MergedOp} rebuilt the
 * resource field by field and dropped that flag, so every wildcard grant reaching the live
 * client was downgraded to a {@code DatabaseResource}. Lake Formation then rejected it with
 * "Permissions modification is invalid." for any table-level permission, because SELECT and
 * INSERT are not valid database-level permissions — so no wildcard grant could ever be applied,
 * and the failure repeated every sync cycle without healing.
 *
 * <p>Neither the Ranger→Cedar nor the Cedar→LF stage was at fault, and dry-run runs were clean
 * because {@code DryRunLakeFormationClient} never calls {@code consolidateOperations}.
 */
class LakeFormationClientWildcardConsolidationTest {

    private static final String CATALOG = "111122223333";
    private static final String PRINCIPAL = "arn:aws:iam::111122223333:role/Analyst";

    private static LFPermissionOperation wildcardGrant(String db, Set<LFPermission> perms) {
        return new LFPermissionOperation(OperationType.GRANT, "hive:1", "user:alice", PRINCIPAL,
                LFResource.allTablesResource(CATALOG, db), perms, false);
    }

    private static LFPermissionOperation databaseGrant(String db, Set<LFPermission> perms) {
        return new LFPermissionOperation(OperationType.GRANT, "hive:1", "user:alice", PRINCIPAL,
                new LFResource(CATALOG, db, null, null, null), perms, false);
    }

    @Test
    void consolidationPreservesTableWildcard() {
        List<LFPermissionOperation> result = LakeFormationClient.consolidateOperations(
                List.of(wildcardGrant("staging", EnumSet.of(LFPermission.SELECT, LFPermission.DROP))));

        assertEquals(1, result.size());
        LFResource resource = result.get(0).getResource();
        assertTrue(resource.isAllTables(),
                "allTables must survive consolidation — it is the only thing distinguishing a "
                        + "table wildcard from a database-level grant, and losing it makes LF "
                        + "reject the grant outright");
        assertEquals("staging", resource.getDatabaseName());
        assertEquals(EnumSet.of(LFPermission.SELECT, LFPermission.DROP),
                result.get(0).getPermissions());
    }

    @Test
    void consolidationMergesPermissionsWithinOneWildcardResource() {
        List<LFPermissionOperation> result = LakeFormationClient.consolidateOperations(List.of(
                wildcardGrant("staging", EnumSet.of(LFPermission.SELECT)),
                wildcardGrant("staging", EnumSet.of(LFPermission.INSERT)),
                wildcardGrant("staging", EnumSet.of(LFPermission.ALTER))));

        assertEquals(1, result.size(), "same wildcard resource — should merge to one entry");
        assertTrue(result.get(0).getResource().isAllTables());
        assertEquals(EnumSet.of(LFPermission.SELECT, LFPermission.INSERT, LFPermission.ALTER),
                result.get(0).getPermissions());
    }

    @Test
    void wildcardAndDatabaseGrantsDoNotMergeTogether() {
        // Both resources have a null tableName, so a consolidation key that ignores allTables
        // treats them as identical and unions their permissions onto one resource — producing a
        // database-level grant carrying SELECT, which LF always rejects.
        List<LFPermissionOperation> result = LakeFormationClient.consolidateOperations(List.of(
                wildcardGrant("staging", EnumSet.of(LFPermission.SELECT)),
                databaseGrant("staging", EnumSet.of(LFPermission.DROP))));

        assertEquals(2, result.size(),
                "a table-wildcard grant and a database-level grant are different resources and "
                        + "must stay separate");

        LFPermissionOperation wildcard = result.stream()
                .filter(o -> o.getResource().isAllTables()).findFirst().orElseThrow();
        assertEquals(EnumSet.of(LFPermission.SELECT), wildcard.getPermissions(),
                "the wildcard entry must not absorb the database entry's permissions");

        LFPermissionOperation database = result.stream()
                .filter(o -> !o.getResource().isAllTables()).findFirst().orElseThrow();
        assertEquals(EnumSet.of(LFPermission.DROP), database.getPermissions());
        assertFalse(database.getPermissions().contains(LFPermission.SELECT),
                "SELECT is not a valid database-level LF permission and must never land here");
    }

    @Test
    void consolidationPreservesOriginalPrincipal() {
        List<LFPermissionOperation> result = LakeFormationClient.consolidateOperations(
                List.of(wildcardGrant("staging", EnumSet.of(LFPermission.SELECT))));

        assertEquals(1, result.size());
        assertEquals("user:alice", result.get(0).getOriginalPrincipal(),
                "the Ranger principal name is what makes the output reviewable; rebuilding the "
                        + "operation must not discard it");
    }

    @Test
    void wildcardResourceBuildsTableWildcardNotDatabaseResource() {
        // Guards the end of the chain: even with allTables preserved, buildResource must emit a
        // Table resource carrying TableWildcard rather than a DatabaseResource.
        LakeFormationClient client = new LakeFormationClient(null, null);
        software.amazon.awssdk.services.lakeformation.model.Resource resource =
                client.buildResource(LFResource.allTablesResource(CATALOG, "staging"));

        assertNotNull(resource.table(), "a wildcard resource must map to Table, not Database");
        assertNotNull(resource.table().tableWildcard(), "TableWildcard must be set");
        assertEquals("staging", resource.table().databaseName());
        assertEquals(null, resource.database(),
                "emitting a DatabaseResource is what LF rejected for table-level permissions");
    }
}
