package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.model.DriftReport;
import com.amazonaws.policyconverters.model.DriftResult;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.PermissionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the diff between Cedar-derived desired permissions and actual
 * LakeFormation permissions, producing corrective GRANT/REVOKE operations
 * and a structured {@link DriftReport}.
 *
 * <p>Uses the same {@link PermissionKey} identity pattern as
 * {@code SyncService.computeDiff} — comparison is based on
 * (principalArn, resource, permissions, grantable).</p>
 */
public class DriftDetector {

    private static final Logger LOG = LoggerFactory.getLogger(DriftDetector.class);

    /**
     * Compute drift between desired and actual LakeFormation permissions.
     *
     * @param desired    Cedar-derived desired operations (all GRANTs)
     * @param actual     LF-retrieved actual operations (all GRANTs)
     * @param filter     exclusion filter (may be null)
     * @param reportOnly if true, compute report without producing corrective ops
     * @return DriftResult containing report and optional corrective operations
     */
    public DriftResult computeDrift(
            List<LFPermissionOperation> desired,
            List<LFPermissionOperation> actual,
            PermissionFilter filter,
            boolean reportOnly) {

        List<LFPermissionOperation> safeDesired = desired != null ? desired : Collections.emptyList();
        List<LFPermissionOperation> safeActual = actual != null ? actual : Collections.emptyList();

        // Partition into included and skipped based on filter
        List<LFPermissionOperation> filteredDesired = new ArrayList<>();
        List<LFPermissionOperation> filteredActual = new ArrayList<>();
        List<LFPermissionOperation> skippedPermissions = new ArrayList<>();

        for (LFPermissionOperation op : safeDesired) {
            if (filter != null && filter.shouldExclude(op)) {
                skippedPermissions.add(op);
            } else {
                filteredDesired.add(op);
            }
        }

        for (LFPermissionOperation op : safeActual) {
            if (filter != null && filter.shouldExclude(op)) {
                skippedPermissions.add(op);
            } else {
                filteredActual.add(op);
            }
        }

        // Build PermissionKey maps for efficient lookup
        Map<PermissionKey, LFPermissionOperation> desiredMap = buildPermissionMap(filteredDesired);
        Map<PermissionKey, LFPermissionOperation> actualMap = buildPermissionMap(filteredActual);

        List<LFPermissionOperation> correctiveOps = new ArrayList<>();
        int missingGrants = 0;
        int extraPermissions = 0;
        int inSyncCount = 0;

        // Missing grants: in desired but not in actual → GRANT
        for (Map.Entry<PermissionKey, LFPermissionOperation> entry : desiredMap.entrySet()) {
            if (actualMap.containsKey(entry.getKey())) {
                inSyncCount++;
            } else {
                missingGrants++;
                if (!reportOnly) {
                    LFPermissionOperation op = entry.getValue();
                    correctiveOps.add(new LFPermissionOperation(
                            OperationType.GRANT,
                            op.getSourcePolicyId(),
                            op.getPrincipalArn(),
                            op.getResource(),
                            op.getPermissions(),
                            op.isGrantable()));
                }
            }
        }

        // Extra permissions: in actual but not in desired → REVOKE
        for (Map.Entry<PermissionKey, LFPermissionOperation> entry : actualMap.entrySet()) {
            if (!desiredMap.containsKey(entry.getKey())) {
                extraPermissions++;
                if (!reportOnly) {
                    LFPermissionOperation op = entry.getValue();
                    correctiveOps.add(new LFPermissionOperation(
                            OperationType.REVOKE,
                            op.getSourcePolicyId(),
                            op.getPrincipalArn(),
                            op.getResource(),
                            op.getPermissions(),
                            op.isGrantable()));
                }
            }
        }

        LOG.info("Drift computed: missingGrants={}, extraPermissions={}, inSync={}, skipped={}",
                missingGrants, extraPermissions, inSyncCount, skippedPermissions.size());

        DriftReport report = new DriftReport(
                missingGrants,
                extraPermissions,
                inSyncCount,
                skippedPermissions,
                Collections.emptyList());

        return new DriftResult(report, reportOnly ? Collections.emptyList() : correctiveOps);
    }

    /**
     * Build a map from permission identity keys to operations.
     * If duplicate keys exist, the last one wins.
     */
    private static Map<PermissionKey, LFPermissionOperation> buildPermissionMap(
            List<LFPermissionOperation> operations) {
        Map<PermissionKey, LFPermissionOperation> map = new HashMap<>();
        for (LFPermissionOperation op : operations) {
            map.put(PermissionKey.of(op), op);
        }
        return map;
    }

    /**
     * Represents the identity of a permission for diff comparison.
     * Two operations are considered the "same" permission if they have the
     * same principal, resource, permissions set, and grantable flag.
     * The operation type (GRANT/REVOKE) and source policy ID are excluded
     * from identity comparison.
     */
    static final class PermissionKey {
        private final String principalArn;
        private final Object resource;
        private final Set<Object> permissions;
        private final boolean grantable;

        private PermissionKey(String principalArn, Object resource,
                              Set<Object> permissions, boolean grantable) {
            this.principalArn = principalArn;
            this.resource = resource;
            this.permissions = permissions;
            this.grantable = grantable;
        }

        static PermissionKey of(LFPermissionOperation op) {
            Set<Object> perms = new HashSet<Object>(op.getPermissions());
            return new PermissionKey(
                    op.getPrincipalArn(),
                    op.getResource(),
                    perms,
                    op.isGrantable());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PermissionKey that = (PermissionKey) o;
            return grantable == that.grantable
                    && Objects.equals(principalArn, that.principalArn)
                    && Objects.equals(resource, that.resource)
                    && Objects.equals(permissions, that.permissions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(principalArn, resource, permissions, grantable);
        }
    }
}
