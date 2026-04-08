package com.amazonaws.policyconverters.lakeformation.sync;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.lakeformation.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.lakeformation.client.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.client.DryRunLakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.client.LFPermissionFetcher;
import com.amazonaws.policyconverters.lakeformation.client.LakeFormationClient;
import com.amazonaws.policyconverters.lakeformation.client.LakeFormationClientException;
import com.amazonaws.policyconverters.lakeformation.model.DriftReport;
import com.amazonaws.policyconverters.lakeformation.model.DriftResult;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.model.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.model.ReverseSyncConfig;
import com.amazonaws.policyconverters.lakeformation.model.ReverseSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the reverse-sync cycle: fetches actual LakeFormation permissions,
 * computes drift against the Cedar-authoritative desired state, and applies
 * corrective GRANT/REVOKE operations.
 *
 * <p>Uses an {@link AtomicBoolean} CAS guard to reject concurrent executions.
 * Corrective operations are ordered REVOKEs-first to avoid transient
 * over-permissioning. Individual operation failures are logged to the
 * {@link DeadLetterLogger} and do not abort the remaining batch.</p>
 */
public class ReverseSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(ReverseSyncService.class);

    private final LFPermissionFetcher fetcher;
    private final DriftDetector driftDetector;
    private final LakeFormationClient lakeFormationClient;
    private final CedarToLFConverter cedarToLFConverter;
    private final DeadLetterLogger deadLetterLogger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ReverseSyncService(
            LFPermissionFetcher fetcher,
            DriftDetector driftDetector,
            LakeFormationClient lakeFormationClient,
            CedarToLFConverter cedarToLFConverter,
            DeadLetterLogger deadLetterLogger) {
        this.fetcher = fetcher;
        this.driftDetector = driftDetector;
        this.lakeFormationClient = lakeFormationClient;
        this.cedarToLFConverter = cedarToLFConverter;
        this.deadLetterLogger = deadLetterLogger;
    }

    /**
     * Execute a single reverse-sync cycle.
     *
     * @param config         the reverse sync configuration
     * @param cedarPolicySet the current Cedar-authoritative policy set
     * @return ReverseSyncResult summarizing the cycle
     * @throws LakeFormationClientException if LF retrieval fails
     * @throws IllegalStateException        if a sync cycle is already running
     */
    public ReverseSyncResult execute(ReverseSyncConfig config, CedarPolicySet cedarPolicySet)
            throws LakeFormationClientException {

        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Reverse-sync cycle is already running");
        }

        long startTime = System.currentTimeMillis();
        LOG.info("Reverse-sync cycle starting: catalogId={}, reportOnly={}, dryRun={}",
                config.getCatalogId(), config.isReportOnly(), config.isDryRun());

        try {
            // Guard: empty or null Cedar policy set → skip cycle
            if (isCedarPolicySetEmpty(cedarPolicySet)) {
                LOG.error("Cedar policy set is null or empty — skipping reverse-sync cycle to prevent mass revocation");
                long durationMs = System.currentTimeMillis() - startTime;
                DriftReport emptyReport = new DriftReport(0, 0, 0, Collections.emptyList(), Collections.emptyList());
                return new ReverseSyncResult(emptyReport, 0, 0, 0, durationMs);
            }

            // Step 1: Convert Cedar to desired state
            List<LFPermissionOperation> desired = cedarToLFConverter.convert(cedarPolicySet);

            // Step 2: Fetch actual LF permissions
            List<LFPermissionOperation> actual = fetcher.fetchPermissions(
                    config.getCatalogId(), config.getFilter());

            // Step 3: Compute drift
            DriftResult driftResult = driftDetector.computeDrift(
                    desired, actual, config.getFilter(), config.isReportOnly());

            DriftReport driftReport = driftResult.getReport();
            List<LFPermissionOperation> correctiveOps = driftResult.getCorrectiveOperations();

            // Step 4: Report-only mode → return result with drift report, no corrections
            if (config.isReportOnly()) {
                long durationMs = System.currentTimeMillis() - startTime;
                LOG.info("Reverse-sync cycle completed (report-only): durationMs={}, missingGrants={}, " +
                                "extraPermissions={}, inSync={}",
                        durationMs, driftReport.getMissingGrants(),
                        driftReport.getExtraPermissions(), driftReport.getInSyncCount());
                return new ReverseSyncResult(driftReport, 0, 0, 0, durationMs);
            }

            // Step 5: Dry-run mode → serialize via DryRunLakeFormationClient
            if (config.isDryRun()) {
                if (lakeFormationClient instanceof DryRunLakeFormationClient) {
                    lakeFormationClient.applyBatch(correctiveOps, deadLetterLogger);
                }
                long durationMs = System.currentTimeMillis() - startTime;
                LOG.info("Reverse-sync cycle completed (dry-run): durationMs={}, correctiveOps={}",
                        durationMs, correctiveOps.size());
                return new ReverseSyncResult(driftReport, 0, 0, 0, durationMs);
            }

            // Step 6: Apply corrective operations (REVOKEs first, then GRANTs)
            List<LFPermissionOperation> ordered = orderCorrectiveOperations(correctiveOps);

            int successfulGrants = 0;
            int successfulRevokes = 0;
            int failedCount = 0;
            List<DriftReport.FailedOperation> failedOps = new ArrayList<>();

            for (LFPermissionOperation op : ordered) {
                try {
                    if (op.getOperationType() == OperationType.REVOKE) {
                        lakeFormationClient.revokePermission(op);
                        successfulRevokes++;
                    } else {
                        lakeFormationClient.grantPermission(op);
                        successfulGrants++;
                    }
                } catch (LakeFormationClientException e) {
                    failedCount++;
                    failedOps.add(new DriftReport.FailedOperation(op, e.getMessage()));
                    deadLetterLogger.logFailedOperation(op, e.getMessage(), 0);
                    LOG.warn("Corrective {} failed for principal={}, resource={}: {}",
                            op.getOperationType(), op.getPrincipalArn(), op.getResource(), e.getMessage());
                }
            }

            // Build final report with failed operations included
            DriftReport finalReport = new DriftReport(
                    driftReport.getMissingGrants(),
                    driftReport.getExtraPermissions(),
                    driftReport.getInSyncCount(),
                    driftReport.getSkippedPermissions(),
                    failedOps);

            long durationMs = System.currentTimeMillis() - startTime;
            LOG.info("Reverse-sync cycle completed: durationMs={}, successfulGrants={}, successfulRevokes={}, " +
                            "failedOperations={}, missingGrants={}, extraPermissions={}, inSync={}",
                    durationMs, successfulGrants, successfulRevokes, failedCount,
                    finalReport.getMissingGrants(), finalReport.getExtraPermissions(),
                    finalReport.getInSyncCount());

            return new ReverseSyncResult(finalReport, successfulGrants, successfulRevokes, failedCount, durationMs);

        } finally {
            running.set(false);
        }
    }

    /**
     * Order corrective operations so that all REVOKEs come before all GRANTs.
     * This prevents transient over-permissioning windows.
     * <p>
     * Package-private for direct testing in property tests.
     */
    List<LFPermissionOperation> orderCorrectiveOperations(List<LFPermissionOperation> operations) {
        List<LFPermissionOperation> revokes = new ArrayList<>();
        List<LFPermissionOperation> grants = new ArrayList<>();

        for (LFPermissionOperation op : operations) {
            if (op.getOperationType() == OperationType.REVOKE) {
                revokes.add(op);
            } else {
                grants.add(op);
            }
        }

        List<LFPermissionOperation> ordered = new ArrayList<>(revokes.size() + grants.size());
        ordered.addAll(revokes);
        ordered.addAll(grants);
        return ordered;
    }

    /**
     * Check if a CedarPolicySet is null or empty.
     */
    private boolean isCedarPolicySetEmpty(CedarPolicySet cedarPolicySet) {
        if (cedarPolicySet == null) {
            return true;
        }
        String cedarText = cedarPolicySet.toCedarString();
        return cedarText == null || cedarText.trim().isEmpty();
    }
}
