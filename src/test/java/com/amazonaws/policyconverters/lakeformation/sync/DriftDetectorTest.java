package com.amazonaws.policyconverters.lakeformation.sync;

import com.amazonaws.policyconverters.lakeformation.model.DriftReport;
import com.amazonaws.policyconverters.lakeformation.model.DriftResult;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.PermissionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DriftDetector}.
 * Validates: Requirements 3.1–3.7
 */
class DriftDetectorTest {

    private static final String CATALOG_ID = "123456789012";
    private static final String PRINCIPAL_A = "arn:aws:iam::123456789012:role/RoleA";
    private static final String PRINCIPAL_B = "arn:aws:iam::123456789012:role/RoleB";
    private static final String PRINCIPAL_C = "arn:aws:iam::123456789012:role/RoleC";

    private DriftDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DriftDetector();
    }

    // --- Helper methods ---

    private LFResource dbResource(String dbName) {
        return new LFResource(CATALOG_ID, dbName, null, null, null);
    }

    private LFResource tableResource(String dbName, String tableName) {
        return new LFResource(CATALOG_ID, dbName, tableName, null, null);
    }

    private LFPermissionOperation grantOp(String principal, LFResource resource,
                                           Set<LFPermission> permissions, boolean grantable) {
        return new LFPermissionOperation(OperationType.GRANT, null, principal,
                resource, permissions, grantable);
    }

    private LFPermissionOperation grantOp(String principal, LFResource resource,
                                           Set<LFPermission> permissions) {
        return grantOp(principal, resource, permissions, false);
    }

    // --- Test: identical desired and actual → zero drift, zero corrective ops ---

    @Test
    void computeDrift_identicalDesiredAndActual_zeroDrift() {
        LFPermissionOperation op1 = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation op2 = grantOp(PRINCIPAL_B, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));

        List<LFPermissionOperation> desired = Arrays.asList(op1, op2);
        List<LFPermissionOperation> actual = Arrays.asList(op1, op2);

        DriftResult result = detector.computeDrift(desired, actual, null, false);

        DriftReport report = result.getReport();
        assertEquals(0, report.getMissingGrants());
        assertEquals(0, report.getExtraPermissions());
        assertEquals(2, report.getInSyncCount());
        assertTrue(result.getCorrectiveOperations().isEmpty());
    }

    // --- Test: desired has permissions not in actual → GRANT corrective ops ---

    @Test
    void computeDrift_desiredNotInActual_producesGrantOps() {
        LFPermissionOperation desiredOp = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));

        DriftResult result = detector.computeDrift(
                Collections.singletonList(desiredOp),
                Collections.emptyList(),
                null, false);

        DriftReport report = result.getReport();
        assertEquals(1, report.getMissingGrants());
        assertEquals(0, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertEquals(1, result.getCorrectiveOperations().size());

        LFPermissionOperation corrective = result.getCorrectiveOperations().get(0);
        assertEquals(OperationType.GRANT, corrective.getOperationType());
        assertEquals(PRINCIPAL_A, corrective.getPrincipalArn());
        assertEquals(dbResource("db1"), corrective.getResource());
        assertTrue(corrective.getPermissions().contains(LFPermission.DESCRIBE));
    }

    // --- Test: actual has permissions not in desired → REVOKE corrective ops ---

    @Test
    void computeDrift_actualNotInDesired_producesRevokeOps() {
        LFPermissionOperation actualOp = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.ALTER));

        DriftResult result = detector.computeDrift(
                Collections.emptyList(),
                Collections.singletonList(actualOp),
                null, false);

        DriftReport report = result.getReport();
        assertEquals(0, report.getMissingGrants());
        assertEquals(1, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertEquals(1, result.getCorrectiveOperations().size());

        LFPermissionOperation corrective = result.getCorrectiveOperations().get(0);
        assertEquals(OperationType.REVOKE, corrective.getOperationType());
        assertEquals(PRINCIPAL_A, corrective.getPrincipalArn());
        assertEquals(dbResource("db1"), corrective.getResource());
        assertTrue(corrective.getPermissions().contains(LFPermission.ALTER));
    }

    // --- Test: mixed drift (some missing, some extra, some in-sync) ---

    @Test
    void computeDrift_mixedDrift_correctCounts() {
        LFPermissionOperation inSync = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation missingGrant = grantOp(PRINCIPAL_B, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));
        LFPermissionOperation extraPerm = grantOp(PRINCIPAL_C, dbResource("db2"),
                EnumSet.of(LFPermission.ALTER));

        List<LFPermissionOperation> desired = Arrays.asList(inSync, missingGrant);
        List<LFPermissionOperation> actual = Arrays.asList(inSync, extraPerm);

        DriftResult result = detector.computeDrift(desired, actual, null, false);

        DriftReport report = result.getReport();
        assertEquals(1, report.getMissingGrants());
        assertEquals(1, report.getExtraPermissions());
        assertEquals(1, report.getInSyncCount());
        assertEquals(2, result.getCorrectiveOperations().size());

        long grantCount = result.getCorrectiveOperations().stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT).count();
        long revokeCount = result.getCorrectiveOperations().stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE).count();
        assertEquals(1, grantCount);
        assertEquals(1, revokeCount);
    }

    // --- Test: empty desired list → all actual become REVOKE ops ---

    @Test
    void computeDrift_emptyDesired_allActualBecomeRevokes() {
        LFPermissionOperation actual1 = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation actual2 = grantOp(PRINCIPAL_B, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));

        DriftResult result = detector.computeDrift(
                Collections.emptyList(),
                Arrays.asList(actual1, actual2),
                null, false);

        DriftReport report = result.getReport();
        assertEquals(0, report.getMissingGrants());
        assertEquals(2, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertEquals(2, result.getCorrectiveOperations().size());

        for (LFPermissionOperation op : result.getCorrectiveOperations()) {
            assertEquals(OperationType.REVOKE, op.getOperationType());
        }
    }

    // --- Test: empty actual list → all desired become GRANT ops ---

    @Test
    void computeDrift_emptyActual_allDesiredBecomeGrants() {
        LFPermissionOperation desired1 = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation desired2 = grantOp(PRINCIPAL_B, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));

        DriftResult result = detector.computeDrift(
                Arrays.asList(desired1, desired2),
                Collections.emptyList(),
                null, false);

        DriftReport report = result.getReport();
        assertEquals(2, report.getMissingGrants());
        assertEquals(0, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertEquals(2, result.getCorrectiveOperations().size());

        for (LFPermissionOperation op : result.getCorrectiveOperations()) {
            assertEquals(OperationType.GRANT, op.getOperationType());
        }
    }

    // --- Test: both empty → zero drift ---

    @Test
    void computeDrift_bothEmpty_zeroDrift() {
        DriftResult result = detector.computeDrift(
                Collections.emptyList(),
                Collections.emptyList(),
                null, false);

        DriftReport report = result.getReport();
        assertEquals(0, report.getMissingGrants());
        assertEquals(0, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertTrue(result.getCorrectiveOperations().isEmpty());
    }

    // --- Test: report-only mode → DriftReport populated, correctiveOperations empty ---

    @Test
    void computeDrift_reportOnlyMode_reportPopulatedButNoCorrectiveOps() {
        LFPermissionOperation desiredOnly = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation actualOnly = grantOp(PRINCIPAL_B, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));
        LFPermissionOperation inSync = grantOp(PRINCIPAL_C, dbResource("db2"),
                EnumSet.of(LFPermission.ALTER));

        List<LFPermissionOperation> desired = Arrays.asList(desiredOnly, inSync);
        List<LFPermissionOperation> actual = Arrays.asList(actualOnly, inSync);

        DriftResult result = detector.computeDrift(desired, actual, null, true);

        DriftReport report = result.getReport();
        assertEquals(1, report.getMissingGrants());
        assertEquals(1, report.getExtraPermissions());
        assertEquals(1, report.getInSyncCount());
        assertTrue(result.getCorrectiveOperations().isEmpty(),
                "Report-only mode should produce no corrective operations");
    }

    // --- Test: exclusion filter removes matching permissions from drift ---

    @Test
    void computeDrift_exclusionFilter_removesMatchingPermissions() {
        String excludedPrincipal = "arn:aws:iam::123456789012:role/ExcludedRole";

        LFPermissionOperation excludedDesired = grantOp(excludedPrincipal, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation includedDesired = grantOp(PRINCIPAL_A, dbResource("db2"),
                EnumSet.of(LFPermission.ALTER));
        LFPermissionOperation excludedActual = grantOp(excludedPrincipal, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));
        LFPermissionOperation includedActual = grantOp(PRINCIPAL_B, dbResource("db3"),
                EnumSet.of(LFPermission.DROP));

        Set<String> excludedPrincipals = new HashSet<>();
        excludedPrincipals.add(excludedPrincipal);
        PermissionFilter filter = new PermissionFilter(null, null, excludedPrincipals, null);

        DriftResult result = detector.computeDrift(
                Arrays.asList(excludedDesired, includedDesired),
                Arrays.asList(excludedActual, includedActual),
                filter, false);

        DriftReport report = result.getReport();
        // Only non-excluded permissions should be counted
        assertEquals(1, report.getMissingGrants());   // includedDesired not in actual
        assertEquals(1, report.getExtraPermissions()); // includedActual not in desired
        assertEquals(0, report.getInSyncCount());

        // Excluded permissions should appear in skippedPermissions
        assertEquals(2, report.getSkippedPermissions().size());

        // Corrective ops should only be for non-excluded permissions
        assertEquals(2, result.getCorrectiveOperations().size());
        for (LFPermissionOperation op : result.getCorrectiveOperations()) {
            assertTrue(!op.getPrincipalArn().equals(excludedPrincipal),
                    "Excluded principal should not appear in corrective operations");
        }
    }

    // --- Test: DriftReport counts are correct (missingGrants + extraPermissions + inSyncCount = union size) ---

    @Test
    void computeDrift_reportCounts_equalUnionSize() {
        LFPermissionOperation shared1 = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE));
        LFPermissionOperation shared2 = grantOp(PRINCIPAL_B, tableResource("db1", "t1"),
                EnumSet.of(LFPermission.SELECT));
        LFPermissionOperation desiredOnly = grantOp(PRINCIPAL_A, dbResource("db2"),
                EnumSet.of(LFPermission.ALTER));
        LFPermissionOperation actualOnly1 = grantOp(PRINCIPAL_C, dbResource("db3"),
                EnumSet.of(LFPermission.DROP));
        LFPermissionOperation actualOnly2 = grantOp(PRINCIPAL_B, dbResource("db4"),
                EnumSet.of(LFPermission.CREATE_TABLE));

        List<LFPermissionOperation> desired = Arrays.asList(shared1, shared2, desiredOnly);
        List<LFPermissionOperation> actual = Arrays.asList(shared1, shared2, actualOnly1, actualOnly2);

        DriftResult result = detector.computeDrift(desired, actual, null, false);

        DriftReport report = result.getReport();
        // Union size = desired unique keys + actual unique keys - intersection
        // desired keys: 3, actual keys: 4, intersection: 2 → union = 5
        int unionSize = report.getMissingGrants() + report.getExtraPermissions() + report.getInSyncCount();
        assertEquals(5, unionSize);
        assertEquals(1, report.getMissingGrants());   // desiredOnly
        assertEquals(2, report.getExtraPermissions()); // actualOnly1, actualOnly2
        assertEquals(2, report.getInSyncCount());      // shared1, shared2
    }

    // --- Test: null desired and actual lists handled gracefully ---

    @Test
    void computeDrift_nullInputs_treatedAsEmpty() {
        DriftResult result = detector.computeDrift(null, null, null, false);

        DriftReport report = result.getReport();
        assertEquals(0, report.getMissingGrants());
        assertEquals(0, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertTrue(result.getCorrectiveOperations().isEmpty());
    }

    // --- Test: grantable flag distinguishes permissions ---

    @Test
    void computeDrift_grantableFlagDistinguishesPermissions() {
        LFPermissionOperation nonGrantable = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE), false);
        LFPermissionOperation grantable = grantOp(PRINCIPAL_A, dbResource("db1"),
                EnumSet.of(LFPermission.DESCRIBE), true);

        // desired has non-grantable, actual has grantable → they are different PermissionKeys
        DriftResult result = detector.computeDrift(
                Collections.singletonList(nonGrantable),
                Collections.singletonList(grantable),
                null, false);

        DriftReport report = result.getReport();
        assertEquals(1, report.getMissingGrants());
        assertEquals(1, report.getExtraPermissions());
        assertEquals(0, report.getInSyncCount());
        assertEquals(2, result.getCorrectiveOperations().size());
    }
}
