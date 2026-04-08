package com.amazonaws.policyconverters.lakeformation.sync;

import com.amazonaws.policyconverters.lakeformation.model.DriftReport;
import com.amazonaws.policyconverters.lakeformation.model.DriftResult;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.PermissionFilter;
import net.jqwik.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link DriftDetector}.
 * Validates drift correctness, identity, convergence, report-only mode, and exclusion filter.
 */
class DriftDetectorPropertyTest {

    private final DriftDetector detector = new DriftDetector();

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 3: Drift correctness
    // **Validates: Requirements 3.2, 3.3, 3.4, 3.5**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void driftCorrectness(
            @ForAll("grantOpLists") List<LFPermissionOperation> desired,
            @ForAll("grantOpLists") List<LFPermissionOperation> actual) {

        DriftResult result = detector.computeDrift(desired, actual, null, false);
        DriftReport report = result.getReport();

        // Build PermissionKey sets for desired and actual
        Set<DriftDetector.PermissionKey> desiredKeys = desired.stream()
                .map(DriftDetector.PermissionKey::of)
                .collect(Collectors.toSet());
        Set<DriftDetector.PermissionKey> actualKeys = actual.stream()
                .map(DriftDetector.PermissionKey::of)
                .collect(Collectors.toSet());

        // Keys only in desired (missing grants)
        Set<DriftDetector.PermissionKey> onlyInDesired = new HashSet<>(desiredKeys);
        onlyInDesired.removeAll(actualKeys);

        // Keys only in actual (extra permissions)
        Set<DriftDetector.PermissionKey> onlyInActual = new HashSet<>(actualKeys);
        onlyInActual.removeAll(desiredKeys);

        // Keys in both (in-sync)
        Set<DriftDetector.PermissionKey> inBoth = new HashSet<>(desiredKeys);
        inBoth.retainAll(actualKeys);

        // Union size
        Set<DriftDetector.PermissionKey> union = new HashSet<>(desiredKeys);
        union.addAll(actualKeys);

        // Verify report counts
        assertEquals(onlyInDesired.size(), report.getMissingGrants(),
                "missingGrants should equal desired-only key count");
        assertEquals(onlyInActual.size(), report.getExtraPermissions(),
                "extraPermissions should equal actual-only key count");
        assertEquals(inBoth.size(), report.getInSyncCount(),
                "inSyncCount should equal intersection key count");
        assertEquals(union.size(),
                report.getMissingGrants() + report.getExtraPermissions() + report.getInSyncCount(),
                "missingGrants + extraPermissions + inSyncCount should equal union size");

        // Verify corrective ops: GRANT ops cover all desired-not-in-actual
        Set<DriftDetector.PermissionKey> grantKeys = result.getCorrectiveOperations().stream()
                .filter(op -> op.getOperationType() == OperationType.GRANT)
                .map(DriftDetector.PermissionKey::of)
                .collect(Collectors.toSet());
        assertEquals(onlyInDesired, grantKeys,
                "GRANT corrective ops should cover exactly desired-not-in-actual keys");

        // Verify corrective ops: REVOKE ops cover all actual-not-in-desired
        Set<DriftDetector.PermissionKey> revokeKeys = result.getCorrectiveOperations().stream()
                .filter(op -> op.getOperationType() == OperationType.REVOKE)
                .map(DriftDetector.PermissionKey::of)
                .collect(Collectors.toSet());
        assertEquals(onlyInActual, revokeKeys,
                "REVOKE corrective ops should cover exactly actual-not-in-desired keys");

        // No ops for in-sync keys
        Set<DriftDetector.PermissionKey> allCorrective = new HashSet<>(grantKeys);
        allCorrective.addAll(revokeKeys);
        for (DriftDetector.PermissionKey key : inBoth) {
            assertFalse(allCorrective.contains(key),
                    "In-sync keys should not appear in corrective operations");
        }
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 4: Identity / zero-drift
    // **Validates: Requirements 8.3**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void identityZeroDrift(@ForAll("grantOpLists") List<LFPermissionOperation> ops) {

        DriftResult result = detector.computeDrift(ops, ops, null, false);
        DriftReport report = result.getReport();

        assertEquals(0, report.getMissingGrants(),
                "Identical desired and actual should have zero missingGrants");
        assertEquals(0, report.getExtraPermissions(),
                "Identical desired and actual should have zero extraPermissions");
        assertTrue(result.getCorrectiveOperations().isEmpty(),
                "Identical desired and actual should produce zero corrective operations");
    }


    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 5: Convergence
    // **Validates: Requirements 8.4**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void convergence(
            @ForAll("grantOpLists") List<LFPermissionOperation> desired,
            @ForAll("grantOpLists") List<LFPermissionOperation> actual) {

        DriftResult result = detector.computeDrift(desired, actual, null, false);

        // Start with actual's PermissionKey set
        Set<DriftDetector.PermissionKey> state = actual.stream()
                .map(DriftDetector.PermissionKey::of)
                .collect(Collectors.toCollection(HashSet::new));

        // Apply corrective ops: remove REVOKE keys, add GRANT keys
        for (LFPermissionOperation op : result.getCorrectiveOperations()) {
            DriftDetector.PermissionKey key = DriftDetector.PermissionKey.of(op);
            if (op.getOperationType() == OperationType.REVOKE) {
                state.remove(key);
            } else if (op.getOperationType() == OperationType.GRANT) {
                state.add(key);
            }
        }

        // Result should equal desired's PermissionKey set
        Set<DriftDetector.PermissionKey> desiredKeys = desired.stream()
                .map(DriftDetector.PermissionKey::of)
                .collect(Collectors.toSet());

        assertEquals(desiredKeys, state,
                "Applying corrective ops to actual should produce desired's PermissionKey set");
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 6: Report-only mode
    // **Validates: Requirements 3.6**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void reportOnlyMode(
            @ForAll("grantOpLists") List<LFPermissionOperation> desired,
            @ForAll("grantOpLists") List<LFPermissionOperation> actual) {

        // Compute with reportOnly=false to get expected report values
        DriftResult normalResult = detector.computeDrift(desired, actual, null, false);
        // Compute with reportOnly=true
        DriftResult reportOnlyResult = detector.computeDrift(desired, actual, null, true);

        DriftReport normalReport = normalResult.getReport();
        DriftReport reportOnlyReport = reportOnlyResult.getReport();

        // Report-only should have empty corrective operations
        assertTrue(reportOnlyResult.getCorrectiveOperations().isEmpty(),
                "Report-only mode should produce no corrective operations");

        // But the DriftReport should be correctly populated (same as normal mode)
        assertEquals(normalReport.getMissingGrants(), reportOnlyReport.getMissingGrants(),
                "Report-only missingGrants should match normal mode");
        assertEquals(normalReport.getExtraPermissions(), reportOnlyReport.getExtraPermissions(),
                "Report-only extraPermissions should match normal mode");
        assertEquals(normalReport.getInSyncCount(), reportOnlyReport.getInSyncCount(),
                "Report-only inSyncCount should match normal mode");
    }

    // -----------------------------------------------------------------------
    // Feature: lf-cedar-reverse-sync, Property 7: Exclusion filter
    // **Validates: Requirements 3.7, 1.7**
    // -----------------------------------------------------------------------
    @Property(tries = 100)
    void exclusionFilter(
            @ForAll("grantOpLists") List<LFPermissionOperation> desired,
            @ForAll("grantOpLists") List<LFPermissionOperation> actual,
            @ForAll("permissionFilters") PermissionFilter filter) {

        DriftResult result = detector.computeDrift(desired, actual, filter, false);
        DriftReport report = result.getReport();

        Set<String> excludedPrincipals = filter.getExcludedPrincipals();

        // Corrective ops should not contain any excluded principals
        for (LFPermissionOperation op : result.getCorrectiveOperations()) {
            assertFalse(excludedPrincipals.contains(op.getPrincipalArn()),
                    "Excluded principal '" + op.getPrincipalArn()
                            + "' should not appear in corrective operations");
        }

        // Compute drift without filter to compare
        DriftResult unfilteredResult = detector.computeDrift(desired, actual, null, false);

        // Count how many desired/actual ops are excluded
        long excludedDesiredCount = desired.stream()
                .filter(filter::shouldExclude)
                .map(DriftDetector.PermissionKey::of)
                .distinct()
                .count();
        long excludedActualCount = actual.stream()
                .filter(filter::shouldExclude)
                .map(DriftDetector.PermissionKey::of)
                .distinct()
                .count();

        // Filtered missingGrants + extraPermissions should be <= unfiltered
        assertTrue(report.getMissingGrants() <= unfilteredResult.getReport().getMissingGrants()
                        + unfilteredResult.getReport().getInSyncCount(),
                "Filtered missingGrants should not exceed unfiltered total");
        assertTrue(report.getExtraPermissions() <= unfilteredResult.getReport().getExtraPermissions()
                        + unfilteredResult.getReport().getInSyncCount(),
                "Filtered extraPermissions should not exceed unfiltered total");

        // Skipped permissions should include excluded ops
        // (all excluded ops from both desired and actual should be in skippedPermissions)
        long skippedFromDesired = desired.stream().filter(filter::shouldExclude).count();
        long skippedFromActual = actual.stream().filter(filter::shouldExclude).count();
        assertEquals(skippedFromDesired + skippedFromActual, report.getSkippedPermissions().size(),
                "Skipped permissions should include all excluded ops from desired and actual");
    }

    // -----------------------------------------------------------------------
    // Arbitraries (generators)
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<List<LFPermissionOperation>> grantOpLists() {
        return grantOps().list().ofMinSize(0).ofMaxSize(8);
    }

    private Arbitrary<LFPermissionOperation> grantOps() {
        return Combinators.combine(principalArns(), lfResources(), permissionSets(), Arbitraries.of(true, false))
                .as((principal, resource, perms, grantable) ->
                        new LFPermissionOperation(OperationType.GRANT, null, principal,
                                resource, perms, grantable));
    }

    @Provide
    Arbitrary<PermissionFilter> permissionFilters() {
        // Generate a filter that excludes some of the principals used in generation
        Arbitrary<Set<String>> excludedPrincipals = Arbitraries.of(
                        "arn:aws:iam::123456789012:role/RoleA",
                        "arn:aws:iam::123456789012:role/RoleB",
                        "arn:aws:iam::123456789012:user/alice")
                .set().ofMinSize(1).ofMaxSize(2);

        return excludedPrincipals.map(excluded ->
                new PermissionFilter(null, null, excluded, null));
    }

    private Arbitrary<String> principalArns() {
        return Arbitraries.of(
                "arn:aws:iam::123456789012:role/RoleA",
                "arn:aws:iam::123456789012:role/RoleB",
                "arn:aws:iam::123456789012:user/alice",
                "arn:aws:iam::123456789012:role/DataAnalyst",
                "arn:aws:iam::123456789012:role/Admin");
    }

    private Arbitrary<LFResource> lfResources() {
        return Arbitraries.oneOf(
                databaseResources(),
                tableResources(),
                columnResources()
        );
    }

    private Arbitrary<LFResource> databaseResources() {
        return Combinators.combine(catalogIds(), databaseNames())
                .as((catalogId, dbName) -> new LFResource(catalogId, dbName, null, null, null));
    }

    private Arbitrary<LFResource> tableResources() {
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "transactions", "orders");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames)
                .as((catalogId, dbName, tableName) -> new LFResource(catalogId, dbName, tableName, null, null));
    }

    private Arbitrary<LFResource> columnResources() {
        Arbitrary<Set<String>> columnSets = Arbitraries.of("col1", "col2", "col3", "id", "name")
                .set().ofMinSize(1).ofMaxSize(3);
        Arbitrary<String> tableNames = Arbitraries.of("events", "users", "transactions");
        return Combinators.combine(catalogIds(), databaseNames(), tableNames, columnSets)
                .as((catalogId, dbName, tableName, cols) ->
                        new LFResource(catalogId, dbName, tableName, cols, null));
    }

    private Arbitrary<String> catalogIds() {
        return Arbitraries.of("123456789012", "987654321098");
    }

    private Arbitrary<String> databaseNames() {
        return Arbitraries.of("analytics", "finance", "hr", "sales");
    }

    private Arbitrary<Set<LFPermission>> permissionSets() {
        return Arbitraries.of(LFPermission.values())
                .set()
                .ofMinSize(1)
                .ofMaxSize(3);
    }
}
