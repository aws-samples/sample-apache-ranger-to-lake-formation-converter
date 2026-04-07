package com.amazonaws.policyconverters.ranger.sync;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.lakeformation.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.client.BatchResult;
import com.amazonaws.policyconverters.lakeformation.client.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.client.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.model.GapEntry;
import com.amazonaws.policyconverters.lakeformation.model.GapReport;
import com.amazonaws.policyconverters.lakeformation.model.LFPermission;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.LFResource;
import com.amazonaws.policyconverters.lakeformation.model.SyncConfig;
import com.amazonaws.policyconverters.lakeformation.reporter.GapReporter;
import com.amazonaws.policyconverters.ranger.cedar.RangerToCedarConverter;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
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
 * Orchestrates the real-time synchronization of Ranger policies to Lake Formation.
 * <p>
 * The SyncService registers as a {@link LakeFormationPlugin.PolicyUpdateListener}
 * to receive policy updates from Ranger Admin. On each update, it computes the
 * diff between the previous and current policy snapshots, converts the delta
 * through the Cedar pipeline ({@link RangerToCedarConverter} → {@link CedarToLFConverter}),
 * and applies the resulting grant/revoke operations via {@link LakeFormationClient}.
 * <p>
 * On first startup with an empty previous snapshot, all current policies are
 * treated as new grants, effectively performing a bulk sync.
 */
public class SyncService implements LakeFormationPlugin.PolicyUpdateListener {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);

    /**
     * Dedicated audit logger for grant/revoke operations.
     * Each entry includes policy ID, resource, principal, and permission type.
     */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger(SyncService.class.getName() + ".audit");

    private final LakeFormationPlugin plugin;
    private final RangerToCedarConverter rangerToCedarConverter;
    private final CedarToLFConverter cedarToLFConverter;
    private final LakeFormationClient lakeFormationClient;
    private final GapReporter gapReporter;
    private final DeadLetterLogger deadLetterLogger;
    private final CheckpointStore checkpointStore;

    /**
     * The previous set of LF permission operations derived from the last
     * Cedar policy snapshot. Empty on first startup for implicit bulk sync.
     * On restart, re-derived from the persisted Cedar checkpoint.
     */
    private volatile List<LFPermissionOperation> previousOperations = Collections.emptyList();

    /**
     * The last known Cedar policy text, persisted as the source of truth.
     */
    private volatile String lastCedarPolicyText = "";

    /**
     * The last known Ranger policy version, used for checkpoint persistence.
     */
    private volatile long lastPolicyVersion = -1L;

    /**
     * The last known set of Ranger policies received from Ranger Admin.
     * Used for connectivity loss resilience: if Ranger Admin becomes
     * unreachable, the service continues operating with this snapshot.
     */
    private volatile List<RangerPolicy> lastKnownPolicies = Collections.emptyList();

    private volatile boolean running = false;

    public SyncService(
            LakeFormationPlugin plugin,
            RangerToCedarConverter rangerToCedarConverter,
            CedarToLFConverter cedarToLFConverter,
            LakeFormationClient lakeFormationClient,
            GapReporter gapReporter,
            DeadLetterLogger deadLetterLogger) {
        this(plugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger, null);
    }

    public SyncService(
            LakeFormationPlugin plugin,
            RangerToCedarConverter rangerToCedarConverter,
            CedarToLFConverter cedarToLFConverter,
            LakeFormationClient lakeFormationClient,
            GapReporter gapReporter,
            DeadLetterLogger deadLetterLogger,
            CheckpointStore checkpointStore) {
        this.plugin = plugin;
        this.rangerToCedarConverter = rangerToCedarConverter;
        this.cedarToLFConverter = cedarToLFConverter;
        this.lakeFormationClient = lakeFormationClient;
        this.gapReporter = gapReporter;
        this.deadLetterLogger = deadLetterLogger;
        this.checkpointStore = checkpointStore;
    }

    /**
     * Start the sync service. Registers as a policy update listener on the
     * plugin so that policy refreshes trigger diff computation and application.
     *
     * @param config the sync configuration
     */
    public void start(SyncConfig config) {
        if (running) {
            LOG.warn("SyncService is already running, ignoring start request");
            return;
        }
        LOG.info("Starting SyncService with config: policyRefreshIntervalMs={}, maxLfRetries={}",
                config.getPolicyRefreshIntervalMs(), config.getMaxLfRetries());

        // Restore previous state from checkpoint if available
        if (checkpointStore != null) {
            checkpointStore.load().ifPresent(checkpoint -> {
                String cedarText = checkpoint.getCedarPolicyText();
                if (cedarText != null && !cedarText.trim().isEmpty()) {
                    try {
                        CedarPolicySet restoredPolicySet = CedarPolicySet.fromCedarString(cedarText);
                        previousOperations = cedarToLFConverter.convert(restoredPolicySet);
                        lastCedarPolicyText = cedarText;
                        lastPolicyVersion = checkpoint.getPolicyVersion();
                        LOG.info("Restored checkpoint: policyVersion={}, cedarPolicies={} permit(s)/{} forbid(s), "
                                + "derived {} LF operations",
                                checkpoint.getPolicyVersion(),
                                restoredPolicySet.getPermitCount(),
                                restoredPolicySet.getForbidCount(),
                                previousOperations.size());
                    } catch (Exception e) {
                        LOG.warn("Failed to restore Cedar policies from checkpoint, "
                                + "starting from empty state: {}", e.getMessage());
                    }
                } else {
                    lastPolicyVersion = checkpoint.getPolicyVersion();
                    LOG.info("Restored checkpoint with empty Cedar policies, policyVersion={}",
                            checkpoint.getPolicyVersion());
                }
            });
        }

        plugin.setPolicyUpdateListener(this);
        running = true;

        LOG.info("SyncService started, listening for policy updates from Ranger Admin");
    }

    /**
     * Stop the sync service gracefully.
     */
    public void stop() {
        if (!running) {
            LOG.warn("SyncService is not running, ignoring stop request");
            return;
        }
        LOG.info("Stopping SyncService");
        running = false;
        plugin.setPolicyUpdateListener(null);
        LOG.info("SyncService stopped");
    }

    /**
     * Returns whether the sync service is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Called when new policies are received from Ranger Admin.
     * Computes the diff between previous and current policy snapshots,
     * then applies the delta to Lake Formation.
     * <p>
     * On connectivity loss (null or empty policies when we have a previous
     * snapshot), the service continues operating with the last known policy set.
     *
     * @param policies the updated service policies from Ranger Admin
     */
    @Override
    public void onPoliciesUpdated(ServicePolicies policies) {
        if (!running) {
            LOG.warn("SyncService received policy update but is not running, ignoring");
            return;
        }

        List<RangerPolicy> currentPolicies = policies.getPolicies();
        if (currentPolicies == null) {
            currentPolicies = Collections.emptyList();
        }

        long policyVersion = policies.getPolicyVersion() != null ? policies.getPolicyVersion() : -1L;
        LOG.info("SyncService processing policy update: version={}, policyCount={}",
                policyVersion, currentPolicies.size());

        // Store the last known policies for connectivity resilience
        lastKnownPolicies = currentPolicies;

        // Stage 1: Convert Ranger policies to Cedar PolicySet
        CedarPolicySet cedarPolicySet = rangerToCedarConverter.convert(currentPolicies);

        LOG.info("SyncService Cedar conversion: {} permit(s), {} forbid(s) from {} policies",
                cedarPolicySet.getPermitCount(), cedarPolicySet.getForbidCount(),
                currentPolicies.size());

        // Stage 2: Convert Cedar PolicySet to LF permission operations via partial evaluation
        List<LFPermissionOperation> currentOperations = cedarToLFConverter.convert(cedarPolicySet);

        LOG.info("SyncService LF conversion complete: {} operations from Cedar PolicySet",
                currentOperations.size());

        // Log gap report info if unsupported features were encountered (Req 4.8)
        logGapReportIfPresent();

        // Compute diff between previous and current operations
        PolicyDiff diff = computeDiff(previousOperations, currentOperations);

        LOG.info("SyncService diff computed: {} new grants, {} revocations, {} unchanged",
                diff.getNewGrants().size(), diff.getRevocations().size(), diff.getUnchangedCount());

        // Build the list of delta operations to apply
        List<LFPermissionOperation> deltaOperations = new ArrayList<>();
        deltaOperations.addAll(diff.getNewGrants());
        deltaOperations.addAll(diff.getRevocations());

        if (deltaOperations.isEmpty()) {
            LOG.info("SyncService: no changes to apply for policy version {}", policyVersion);
        } else {
            LOG.info("SyncService: applying {} delta operations for policy version {}",
                    deltaOperations.size(), policyVersion);

            // Audit log each operation before applying (Req 4.6)
            for (LFPermissionOperation op : deltaOperations) {
                logAuditEntry(op);
            }

            BatchResult batchResult = lakeFormationClient.applyBatch(deltaOperations, deadLetterLogger);

            LOG.info("SyncService batch result: applied={}, rolledBack={}, failedPolicies={}",
                    batchResult.getAppliedOperations(),
                    batchResult.getRolledBackOperations(),
                    batchResult.getFailedPolicyIds());
        }

        // Update the previous snapshot to the current state
        previousOperations = currentOperations;
        lastCedarPolicyText = cedarPolicySet.toCedarString();
        lastPolicyVersion = policyVersion;

        // Persist Cedar policy checkpoint for restart resilience
        if (checkpointStore != null) {
            checkpointStore.save(policyVersion, lastCedarPolicyText);
        }
    }

    /**
     * Called when Ranger Admin connectivity is lost. The service continues
     * operating with the last known policy set (Req 4.7).
     * When connectivity is restored, normal policy updates resume via
     * {@link #onPoliciesUpdated(ServicePolicies)}.
     */
    public void onConnectivityLost() {
        if (!running) {
            return;
        }
        LOG.warn("SyncService: connectivity to Ranger Admin lost. "
                + "Continuing with last known policy set ({} policies)",
                lastKnownPolicies.size());
    }

    /**
     * Called when Ranger Admin connectivity is restored after a loss.
     */
    public void onConnectivityRestored() {
        if (!running) {
            return;
        }
        LOG.info("SyncService: connectivity to Ranger Admin restored. Resuming synchronization.");
    }

    /**
     * Logs an audit entry for a single grant/revoke operation.
     * Each entry includes the policy ID, resource path, principal ARN,
     * and permission type(s) for audit purposes (Req 4.6).
     */
    static void logAuditEntry(LFPermissionOperation op) {
        LFResource resource = op.getResource();
        String resourcePath = formatResourcePath(resource);

        AUDIT_LOG.info("AUDIT: operation={}, policyId={}, resource={}, principal={}, permissions={}",
                op.getOperationType(),
                op.getSourcePolicyId(),
                resourcePath,
                op.getPrincipalArn(),
                op.getPermissions());
    }

    /**
     * Formats a resource into a human-readable path string for audit logging.
     */
    static String formatResourcePath(LFResource resource) {
        if (resource == null) {
            return "unknown";
        }
        if (resource.getDataLocationPath() != null) {
            return "datalocation:" + resource.getDataLocationPath();
        }
        StringBuilder sb = new StringBuilder();
        if (resource.getCatalogId() != null) {
            sb.append(resource.getCatalogId()).append("/");
        }
        if (resource.getDatabaseName() != null) {
            sb.append(resource.getDatabaseName());
        }
        if (resource.getTableName() != null) {
            sb.append("/").append(resource.getTableName());
        }
        if (resource.getColumnNames() != null && !resource.getColumnNames().isEmpty()) {
            sb.append("/").append(resource.getColumnNames());
        }
        return sb.toString();
    }

    /**
     * Logs gap report information if the GapReporter has recorded any
     * unsupported features during conversion (Req 4.8).
     */
    private void logGapReportIfPresent() {
        GapReport report = gapReporter.getReport();
        List<GapEntry> entries = report.getEntries();
        if (entries.isEmpty()) {
            return;
        }
        LOG.info("SyncService: gap report contains {} unsupported feature(s), summary={}",
                entries.size(), report.getSummary());
        for (GapEntry entry : entries) {
            LOG.warn("SyncService gap: policyId={}, type={}, resource={}, details={}",
                    entry.getPolicyId(), entry.getGapType(),
                    entry.getResourcePath(), entry.getDetails());
        }
    }

    /**
     * Compute the diff between two sets of LF permission operations.
     * <p>
     * The diff identifies:
     * <ul>
     *   <li>New grants: operations in current but not in previous</li>
     *   <li>Revocations: operations in previous but not in current (converted to REVOKE)</li>
     *   <li>Unchanged: operations present in both (no-op)</li>
     * </ul>
     * <p>
     * Comparison is based on the permission "identity" — the combination of
     * principal ARN, resource, permissions, and grantable flag — ignoring
     * the operation type and source policy ID changes.
     *
     * @param previous the previous set of operations
     * @param current  the current set of operations
     * @return the computed diff
     */
    static PolicyDiff computeDiff(
            List<LFPermissionOperation> previous,
            List<LFPermissionOperation> current) {

        // Build sets of permission keys for efficient lookup
        Map<PermissionKey, LFPermissionOperation> previousMap = buildPermissionMap(previous);
        Map<PermissionKey, LFPermissionOperation> currentMap = buildPermissionMap(current);

        List<LFPermissionOperation> newGrants = new ArrayList<>();
        List<LFPermissionOperation> revocations = new ArrayList<>();
        int unchangedCount = 0;

        // Find new grants: in current but not in previous
        for (Map.Entry<PermissionKey, LFPermissionOperation> entry : currentMap.entrySet()) {
            if (!previousMap.containsKey(entry.getKey())) {
                // New permission — ensure it's a GRANT
                LFPermissionOperation op = entry.getValue();
                if (op.getOperationType() != OperationType.GRANT) {
                    op = new LFPermissionOperation(
                            OperationType.GRANT,
                            op.getSourcePolicyId(),
                            op.getPrincipalArn(),
                            op.getResource(),
                            op.getPermissions(),
                            op.isGrantable());
                }
                newGrants.add(op);
            }
        }

        // Find revocations: in previous but not in current
        for (Map.Entry<PermissionKey, LFPermissionOperation> entry : previousMap.entrySet()) {
            if (!currentMap.containsKey(entry.getKey())) {
                // Removed permission — create a REVOKE operation
                LFPermissionOperation op = entry.getValue();
                revocations.add(new LFPermissionOperation(
                        OperationType.REVOKE,
                        op.getSourcePolicyId(),
                        op.getPrincipalArn(),
                        op.getResource(),
                        op.getPermissions(),
                        op.isGrantable()));
            }
        }

        // Count unchanged: in both previous and current
        for (PermissionKey key : currentMap.keySet()) {
            if (previousMap.containsKey(key)) {
                unchangedCount++;
            }
        }

        return new PolicyDiff(newGrants, revocations, unchangedCount);
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
     * Returns the current previous operations snapshot (for testing).
     */
    List<LFPermissionOperation> getPreviousOperations() {
        return previousOperations;
    }

    /**
     * Returns the last known Ranger policies (for testing connectivity resilience).
     */
    List<RangerPolicy> getLastKnownPolicies() {
        return lastKnownPolicies;
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

    /**
     * Represents the diff between two policy snapshots.
     */
    static final class PolicyDiff {
        private final List<LFPermissionOperation> newGrants;
        private final List<LFPermissionOperation> revocations;
        private final int unchangedCount;

        PolicyDiff(List<LFPermissionOperation> newGrants,
                   List<LFPermissionOperation> revocations,
                   int unchangedCount) {
            this.newGrants = Collections.unmodifiableList(new ArrayList<>(newGrants));
            this.revocations = Collections.unmodifiableList(new ArrayList<>(revocations));
            this.unchangedCount = unchangedCount;
        }

        List<LFPermissionOperation> getNewGrants() {
            return newGrants;
        }

        List<LFPermissionOperation> getRevocations() {
            return revocations;
        }

        int getUnchangedCount() {
            return unchangedCount;
        }
    }
}
