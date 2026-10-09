package com.amazonaws.policyconverters.sync;

import com.amazonaws.policyconverters.cedar.CedarPolicySet;
import com.amazonaws.policyconverters.cedar.CedarToLFConverter;
import com.amazonaws.policyconverters.cedar.CedarToS3AccessGrantsConverter;
import com.amazonaws.policyconverters.lakeformation.BatchResult;
import com.amazonaws.policyconverters.lakeformation.TagMetadataSyncer;
import com.amazonaws.policyconverters.sync.DeadLetterLogger;
import com.amazonaws.policyconverters.lakeformation.LakeFormationClient;
import com.amazonaws.policyconverters.model.GapEntry;
import com.amazonaws.policyconverters.model.GapReport;
import com.amazonaws.policyconverters.model.TagSyncResult;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation;
import com.amazonaws.policyconverters.lakeformation.LFPermissionOperation.OperationType;
import com.amazonaws.policyconverters.lakeformation.LFPermission;
import com.amazonaws.policyconverters.lakeformation.LFResource;
import com.amazonaws.policyconverters.config.SyncConfig;
import com.amazonaws.policyconverters.reporting.GapReporter;
import com.amazonaws.policyconverters.model.WildcardRefreshResult;
import com.amazonaws.policyconverters.ranger.GlobPatternDetector;
import com.amazonaws.policyconverters.ranger.RangerPlugin;
import com.amazonaws.policyconverters.ranger.RangerToCedarConverter;
import com.amazonaws.policyconverters.ranger.service.BaseRangerService;
import com.amazonaws.policyconverters.ranger.service.RangerTagService;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantOperation;
import com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantsClient;
import org.apache.ranger.plugin.util.ServiceTags;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Orchestrates the real-time synchronization of Ranger policies to Lake Formation.
 * <p>
 * The SyncService supports two modes of operation:
 * <ul>
 *   <li><b>Single-plugin mode (backward compatible):</b> Registers as a
 *       {@link RangerPlugin.PolicyUpdateListener} to receive policy updates
 *       from a single Ranger Admin plugin.</li>
 *   <li><b>Multi-service mode:</b> Holds a {@code List<BaseRangerService>} and
 *       fetches policies from all configured services via {@link #executeSyncCycle()}.
 *       Policies are merged into a single list before conversion.</li>
 * </ul>
 * <p>
 * On each update, it computes the diff between the previous and current policy
 * snapshots, converts the delta through the Cedar pipeline
 * ({@link RangerToCedarConverter} → {@link CedarToLFConverter}),
 * and applies the resulting grant/revoke operations via {@link LakeFormationClient}.
 * <p>
 * On first startup with an empty previous snapshot, all current policies are
 * treated as new grants, effectively performing a bulk sync.
 */
public class SyncService implements RangerPlugin.PolicyUpdateListener {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);

    /**
     * Dedicated audit logger for grant/revoke operations.
     * Each entry includes policy ID, resource, principal, and permission type.
     */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger(SyncService.class.getName() + ".audit");

    /** Legacy single-plugin reference, kept for backward compatibility. */
    private final RangerPlugin plugin;

    /** Multi-service list of Ranger services. Empty in single-plugin mode. */
    private final List<BaseRangerService> rangerServices;

    /** Tag sync components — null when tagSync.enabled=false. */
    private RangerTagService rangerTagService;
    private TagMetadataSyncer tagMetadataSyncer;
    private SyncConfig syncConfig;
    private long lastTagSyncMs = 0L;

    private final RangerToCedarConverter rangerToCedarConverter;
    private final CedarToLFConverter cedarToLFConverter;
    private final LakeFormationClient lakeFormationClient;
    private final GapReporter gapReporter;
    private final DeadLetterLogger deadLetterLogger;
    private final CheckpointStore checkpointStore;
    private final S3AccessGrantsClient s3AccessGrantsClient;  // nullable
    private final CedarToS3AccessGrantsConverter s3AgConverter; // null when client is null
    private volatile List<S3AccessGrantOperation> previousS3AgOperations = Collections.emptyList();
    // Optional CloudWatch metrics emitter; null in SyncServiceMain (no CloudWatch). Used to publish
    // the MissingResourceGrants gauge — the count of Ranger-policy grants that currently target a
    // Glue resource that no longer exists — so operators can clean up stale Ranger policies.
    private volatile com.amazonaws.policyconverters.reporting.MetricsEmitter metricsEmitter;

    /** Wire the CloudWatch metrics emitter. Optional — no-op metrics when left null. */
    public void setMetricsEmitter(com.amazonaws.policyconverters.reporting.MetricsEmitter metricsEmitter) {
        this.metricsEmitter = metricsEmitter;
    }

    /**
     * Tracks which services have completed at least one successful policy fetch.
     * Used for the first-sync gate: diff-and-apply is deferred until all services
     * have initialized (Req 7.4).
     */
    private final Set<String> initializedServices = ConcurrentHashMap.newKeySet();

    /**
     * Per-service policy version tracking for checkpoint persistence (Req 10.1).
     * Maps service type to the latest policy version fetched from that service.
     */
    private final Map<String, Long> serviceVersions = new ConcurrentHashMap<>();

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
     * The last known Ranger policy version, used for checkpoint persistence
     * in single-plugin mode.
     */
    private volatile long lastPolicyVersion = -1L;

    /**
     * The last known set of Ranger policies received from Ranger Admin.
     * Used for connectivity loss resilience: if Ranger Admin becomes
     * unreachable, the service continues operating with this snapshot.
     * In multi-service mode, this is the merged list from all services.
     */
    private volatile List<RangerPolicy> lastKnownPolicies = Collections.emptyList();

    private volatile boolean running = false;

    /** Monotonic counter incremented after each fully completed {@link #executeSyncCycle()}. */
    private final AtomicLong lastCompletedCycle = new AtomicLong(0);

    /**
     * Tracks active TABLE/TWC conflicts by key "(principalArn|catalogId|db|table)".
     * New conflicts are logged to the dead-letter once; cleared when the conflict resolves.
     */
    private final Set<String> activeConflicts = ConcurrentHashMap.newKeySet();

    /** Caps the gap de-duplication set so a long run cannot grow it without bound. */
    private static final int MAX_LOGGED_GAP_KEYS = 10_000;

    /**
     * Gap keys already logged at WARN, so an unchanged policy set is not re-logged every cycle.
     * Keyed by "(policyId|gapType|resourcePath)".
     */
    private final Set<String> loggedGaps = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------
    // Single-plugin constructors (backward compatible)
    // ---------------------------------------------------------------

    public SyncService(
            RangerPlugin plugin,
            RangerToCedarConverter rangerToCedarConverter,
            CedarToLFConverter cedarToLFConverter,
            LakeFormationClient lakeFormationClient,
            GapReporter gapReporter,
            DeadLetterLogger deadLetterLogger) {
        this(plugin, rangerToCedarConverter, cedarToLFConverter,
                lakeFormationClient, gapReporter, deadLetterLogger, null);
    }

    public SyncService(
            RangerPlugin plugin,
            RangerToCedarConverter rangerToCedarConverter,
            CedarToLFConverter cedarToLFConverter,
            LakeFormationClient lakeFormationClient,
            GapReporter gapReporter,
            DeadLetterLogger deadLetterLogger,
            CheckpointStore checkpointStore) {
        this.plugin = plugin;
        this.rangerServices = Collections.emptyList();
        this.rangerToCedarConverter = rangerToCedarConverter;
        this.cedarToLFConverter = cedarToLFConverter;
        this.lakeFormationClient = lakeFormationClient;
        this.gapReporter = gapReporter;
        this.deadLetterLogger = deadLetterLogger;
        this.checkpointStore = checkpointStore;
        this.s3AccessGrantsClient = null;
        this.s3AgConverter = null;
    }

    // ---------------------------------------------------------------
    // Multi-service constructor (Req 7.1, 7.2, 7.3)
    // ---------------------------------------------------------------

    /**
     * Creates a SyncService in multi-service mode, fetching policies from
     * all provided {@link BaseRangerService} instances.
     *
     * @param rangerServices         the list of Ranger services to fetch policies from
     * @param rangerToCedarConverter the Ranger-to-Cedar converter
     * @param cedarToLFConverter     the Cedar-to-LF converter
     * @param lakeFormationClient    the Lake Formation client
     * @param gapReporter            the gap reporter
     * @param deadLetterLogger       the dead letter logger
     * @param checkpointStore        the checkpoint store (nullable)
     * @param s3AccessGrantsClient   the S3 Access Grants client (nullable)
     */
    public SyncService(
            List<BaseRangerService> rangerServices,
            RangerToCedarConverter rangerToCedarConverter,
            CedarToLFConverter cedarToLFConverter,
            LakeFormationClient lakeFormationClient,
            GapReporter gapReporter,
            DeadLetterLogger deadLetterLogger,
            CheckpointStore checkpointStore,
            S3AccessGrantsClient s3AccessGrantsClient) {
        this.plugin = null;
        this.rangerServices = rangerServices != null
                ? Collections.unmodifiableList(new ArrayList<>(rangerServices))
                : Collections.emptyList();
        this.rangerToCedarConverter = rangerToCedarConverter;
        this.cedarToLFConverter = cedarToLFConverter;
        this.lakeFormationClient = lakeFormationClient;
        this.gapReporter = gapReporter;
        this.deadLetterLogger = deadLetterLogger;
        this.checkpointStore = checkpointStore;
        this.s3AccessGrantsClient = s3AccessGrantsClient;
        this.s3AgConverter = s3AccessGrantsClient != null ? new CedarToS3AccessGrantsConverter() : null;
    }

    /**
     * Configure optional tag metadata sync components.
     * Must be called before {@link #start(SyncConfig)} to take effect.
     */
    public void setTagSync(RangerTagService rangerTagService, TagMetadataSyncer tagMetadataSyncer) {
        this.rangerTagService = rangerTagService;
        this.tagMetadataSyncer = tagMetadataSyncer;
    }

    /**
     * Returns whether this SyncService is operating in multi-service mode.
     */
    public boolean isMultiServiceMode() {
        return !rangerServices.isEmpty();
    }

    /**
     * Start the sync service. In single-plugin mode, registers as a policy
     * update listener on the plugin. In multi-service mode, restores state
     * from checkpoint and marks the service as running.
     *
     * @param config the sync configuration
     */
    public void start(SyncConfig config) {
        if (running) {
            LOG.warn("SyncService is already running, ignoring start request");
            return;
        }
        this.syncConfig = config;
        LOG.info("Starting SyncService with config: policyRefreshIntervalMs={}, maxLfRetries={}, multiServiceMode={}",
                config.getPolicyRefreshIntervalMs(), config.getMaxLfRetries(), isMultiServiceMode());

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

                // Restore per-service versions from checkpoint (Req 10.2)
                Map<String, Long> restoredVersions = checkpoint.getServiceVersions();
                if (restoredVersions != null) {
                    serviceVersions.putAll(restoredVersions);
                    LOG.info("Restored per-service versions from checkpoint: {}", serviceVersions);
                }

                if (s3AccessGrantsClient != null) {
                    previousS3AgOperations = checkpoint.getS3AgOperations();
                }
            });
        }

        if (plugin != null) {
            plugin.setPolicyUpdateListener(this);
        }
        running = true;

        if (isMultiServiceMode()) {
            LOG.info("SyncService started in multi-service mode with {} services: {}",
                    rangerServices.size(), getServiceNames());
        } else {
            LOG.info("SyncService started, listening for policy updates from Ranger Admin");
        }
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
        if (plugin != null) {
            plugin.setPolicyUpdateListener(null);
        }
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

        // Detect TABLE/TWC conflicts and remove losing operations before diffing.
        currentOperations = detectAndGapTableTwcConflicts(currentOperations);

        // Compute diff between previous and current operations
        LOG.debug("SyncService diff input: previousOperations={}, currentOperations={}",
                previousOperations.size(), currentOperations.size());
        PolicyDiff diff = computeDiff(previousOperations, currentOperations);

        LOG.info("SyncService diff computed: {} new grants, {} revocations, {} unchanged",
                diff.getNewGrants().size(), diff.getRevocations().size(), diff.getUnchangedCount());

        // Debug: log each new grant so we can trace wildcard-derived operations
        for (LFPermissionOperation op : diff.getNewGrants()) {
            LOG.debug("SyncService new grant: policyId={}, principal={}, resource={}, permissions={}",
                    op.getSourcePolicyId(), op.getPrincipalArn(), op.getResource(), op.getPermissions());
        }
        // Debug: log each unchanged operation when we'd expect grants but see none
        if (diff.getNewGrants().isEmpty() && diff.getUnchangedCount() > 0) {
            for (LFPermissionOperation op : previousOperations) {
                LOG.debug("SyncService unchanged: policyId={}, principal={}, resource={}, permissions={}",
                        op.getSourcePolicyId(), op.getPrincipalArn(), op.getResource(), op.getPermissions());
            }
        }

        // Build the list of delta operations to apply
        List<LFPermissionOperation> deltaOperations = new ArrayList<>();
        deltaOperations.addAll(diff.getNewGrants());
        deltaOperations.addAll(diff.getRevocations());

        if (deltaOperations.isEmpty()) {
            LOG.info("SyncService: no changes to apply for policy version {}", policyVersion);
            previousOperations = currentOperations;
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

            previousOperations = advanceCheckpointOperations(currentOperations, batchResult);
        }

        lastCedarPolicyText = cedarPolicySet.toCedarString();
        lastPolicyVersion = policyVersion;

        // Persist Cedar policy checkpoint for restart resilience
        if (checkpointStore != null) {
            checkpointStore.save(policyVersion, lastCedarPolicyText);
        }

        lastCompletedCycle.incrementAndGet();
    }

    // ---------------------------------------------------------------
    // Multi-service sync cycle (Req 7.1, 7.2, 7.3, 7.4, 7.5, 7.6)
    // ---------------------------------------------------------------

    /**
     * Execute a multi-service sync cycle: fetch policies from all configured
     * {@link BaseRangerService} instances, merge them, convert through the
     * Cedar pipeline, compute diff, and apply to Lake Formation.
     * <p>
     * This method implements:
     * <ul>
     *   <li>Per-service policy fetching with fault tolerance (Req 7.1, 7.5, 7.6)</li>
     *   <li>First-sync gate: defers diff-and-apply until all services have
     *       completed at least one successful fetch (Req 7.4)</li>
     *   <li>Per-service version tracking for checkpoint persistence (Req 10.1)</li>
     * </ul>
     */
    public void executeSyncCycle() {
        if (!running) {
            LOG.warn("SyncService.executeSyncCycle called but service is not running, ignoring");
            return;
        }

        if (rangerServices.isEmpty()) {
            LOG.warn("SyncService.executeSyncCycle called but no Ranger services configured");
            return;
        }

        LOG.debug("SyncService executing multi-service sync cycle for {} services", rangerServices.size());

        // Stage 1: Fetch policies from all services, merging into a single list
        List<RangerPolicy> mergedPolicies = new ArrayList<>();

        for (BaseRangerService service : rangerServices) {
            String serviceKey = service.getServiceType();
            try {
                ServicePolicies sp = service.getLatestPolicies();
                if (sp != null && sp.getPolicies() != null) {
                    List<RangerPolicy> policies = sp.getPolicies();
                    mergedPolicies.addAll(policies);

                    // Track per-service version
                    long version = sp.getPolicyVersion() != null ? sp.getPolicyVersion() : -1L;
                    serviceVersions.put(serviceKey, version);

                    // Mark service as initialized on first successful fetch
                    initializedServices.add(serviceKey);

                    LOG.info("SyncService fetched policies: serviceType={}, version={}, count={}",
                            serviceKey, version, policies.size());
                } else {
                    // Null response — use last-known-good (Req 7.5, 7.6)
                    List<RangerPolicy> fallback = service.getLastKnownGoodPolicies();
                    mergedPolicies.addAll(fallback);
                    LOG.warn("SyncService: null policies from serviceType={}, using last-known-good ({} policies)",
                            serviceKey, fallback.size());
                }
            } catch (Exception e) {
                // Fetch failure — use last-known-good (Req 7.5, 7.6)
                List<RangerPolicy> fallback = service.getLastKnownGoodPolicies();
                mergedPolicies.addAll(fallback);
                LOG.error("SyncService: failed to fetch policies from serviceType={}, "
                        + "using last-known-good ({} policies): {}",
                        serviceKey, fallback.size(), e.getMessage());
            }
        }

        // First-sync gate: defer diff-and-apply until all services initialized (Req 7.4)
        if (initializedServices.size() < rangerServices.size()) {
            List<String> pending = new ArrayList<>();
            for (BaseRangerService svc : rangerServices) {
                if (!initializedServices.contains(svc.getServiceType())) {
                    pending.add(svc.getServiceType());
                }
            }
            LOG.warn("SyncService: waiting for first successful fetch from {} service(s): {}. "
                    + "Deferring diff-and-apply until all services are initialized.",
                    pending.size(), pending);
            return;
        }

        // Store merged policies for connectivity resilience and wildcard refresh
        lastKnownPolicies = mergedPolicies;

        LOG.info("SyncService multi-service merge: {} total policies from {} services",
                mergedPolicies.size(), rangerServices.size());

        // Stage 2: Convert merged Ranger policies to Cedar PolicySet
        CedarPolicySet cedarPolicySet = rangerToCedarConverter.convert(mergedPolicies);

        LOG.info("SyncService Cedar conversion: {} permit(s), {} forbid(s) from {} merged policies",
                cedarPolicySet.getPermitCount(), cedarPolicySet.getForbidCount(),
                mergedPolicies.size());

        // Stage 3: Convert Cedar PolicySet to LF permission operations
        List<LFPermissionOperation> currentOperations = cedarToLFConverter.convert(cedarPolicySet);

        LOG.info("SyncService LF conversion complete: {} operations from Cedar PolicySet",
                currentOperations.size());

        // Log gap report info if unsupported features were encountered
        logGapReportIfPresent();

        // Detect TABLE/TWC conflicts and remove losing operations before diffing.
        currentOperations = detectAndGapTableTwcConflicts(currentOperations);

        // Compute diff between previous and current operations
        LOG.debug("SyncService diff input: previousOperations={}, currentOperations={}",
                previousOperations.size(), currentOperations.size());
        PolicyDiff diff = computeDiff(previousOperations, currentOperations);

        LOG.info("SyncService diff computed: {} new grants, {} revocations, {} unchanged",
                diff.getNewGrants().size(), diff.getRevocations().size(), diff.getUnchangedCount());

        // Debug: log each new grant so we can trace wildcard-derived operations
        for (LFPermissionOperation op : diff.getNewGrants()) {
            LOG.debug("SyncService new grant: policyId={}, principal={}, resource={}, permissions={}",
                    op.getSourcePolicyId(), op.getPrincipalArn(), op.getResource(), op.getPermissions());
        }
        // Debug: log each unchanged operation when we'd expect grants but see none
        if (diff.getNewGrants().isEmpty() && diff.getUnchangedCount() > 0) {
            for (LFPermissionOperation op : previousOperations) {
                LOG.debug("SyncService unchanged: policyId={}, principal={}, resource={}, permissions={}",
                        op.getSourcePolicyId(), op.getPrincipalArn(), op.getResource(), op.getPermissions());
            }
        }

        // Build the list of delta operations to apply
        List<LFPermissionOperation> deltaOperations = new ArrayList<>();
        deltaOperations.addAll(diff.getNewGrants());
        deltaOperations.addAll(diff.getRevocations());

        if (deltaOperations.isEmpty()) {
            LOG.info("SyncService: no changes to apply in multi-service sync cycle");
            previousOperations = currentOperations;
        } else {
            LOG.info("SyncService: applying {} delta operations in multi-service sync cycle",
                    deltaOperations.size());

            for (LFPermissionOperation op : deltaOperations) {
                logAuditEntry(op);
            }

            BatchResult batchResult = lakeFormationClient.applyBatch(deltaOperations, deadLetterLogger);

            previousOperations = advanceCheckpointOperations(currentOperations, batchResult);
        }
        lastCedarPolicyText = cedarPolicySet.toCedarString();

        // Persist checkpoint with per-service version map (Req 10.1, 10.2)
        if (checkpointStore != null) {
            checkpointStore.save(new HashMap<>(serviceVersions), lastCedarPolicyText);
        }

        if (s3AccessGrantsClient != null) {
            List<S3AccessGrantOperation> currentS3AgOps = s3AgConverter.convert(cedarPolicySet);
            S3AgDiff s3AgDiff = computeS3AgDiff(previousS3AgOperations, currentS3AgOps);
            List<S3AccessGrantOperation> opsToApply = new ArrayList<>();
            opsToApply.addAll(s3AgDiff.newGrants());
            opsToApply.addAll(s3AgDiff.revocations());
            if (!opsToApply.isEmpty()) {
                s3AccessGrantsClient.applyBatch(opsToApply, syncConfig.getMaxLfRetries(), syncConfig.getLfRetryBackoffMs());
            }
            previousS3AgOperations = currentS3AgOps;
            if (checkpointStore != null) {
                long s3AgVersion = serviceVersions.values().stream()
                        .mapToLong(Long::longValue).max().orElse(0L);
                checkpointStore.saveS3AgOperations(s3AgVersion, currentS3AgOps);
            }
        }

        // Tag metadata sync runs after policy sync; failure does not affect policy sync result
        executeTagMetadataSync();

        lastCompletedCycle.incrementAndGet();
    }

    /**
     * Execute one tag metadata sync cycle, if tag sync is configured and the interval has elapsed.
     * Returns null if tag sync is disabled or skipped.
     */
    public TagSyncResult executeTagMetadataSync() {
        if (rangerTagService == null || tagMetadataSyncer == null) {
            return null;
        }
        if (syncConfig != null && !syncConfig.getTagSync().isEnabled()) {
            return null;
        }

        long now = System.currentTimeMillis();
        long interval = resolveTagSyncInterval();
        if (interval > 0 && (now - lastTagSyncMs) < interval) {
            LOG.debug("SyncService: tag sync interval not yet elapsed, skipping");
            return null;
        }

        long start = now;
        ServiceTags tags = rangerTagService.getLatestTags();
        if (tags == null) {
            LOG.error("SyncService: RangerTagService returned null — no tag data available, skipping tag sync");
            return TagSyncResult.failure(System.currentTimeMillis() - start,
                    new IllegalStateException("No tag data available from Ranger"));
        }

        Set<String> managedTags = checkpointStore != null
                ? checkpointStore.load()
                        .map(SyncCheckpoint::getLastKnownRangerTagNames)
                        .orElse(Collections.emptySet())
                : Collections.emptySet();

        TagSyncResult result = tagMetadataSyncer.sync(tags, managedTags);

        // Persist tag version and managed tag names on any outcome except total fetch failure
        if (checkpointStore != null) {
            Set<String> newManagedTags = buildTagNames(tags);
            checkpointStore.saveTagState(rangerTagService.getLastKnownTagVersion(), newManagedTags);
        }

        lastTagSyncMs = System.currentTimeMillis();

        if (!result.isSuccess()) {
            LOG.error("SyncService: tag sync failed: {}", result.getErrorMessage());
        } else if (result.getFailed() > 0) {
            LOG.warn("SyncService: tag sync partial failure — {} operation(s) failed", result.getFailed());
        } else {
            LOG.info("SyncService: tag sync complete — tagsCreated={}, tagsDeleted={}, "
                    + "attachmentsAdded={}, attachmentsRemoved={}, durationMs={}",
                    result.getTagsCreated(), result.getTagsDeleted(),
                    result.getAttachmentsAdded(), result.getAttachmentsRemoved(), result.getDurationMs());
        }
        return result;
    }

    private long resolveTagSyncInterval() {
        if (syncConfig == null) return 0L;
        long configured = syncConfig.getTagSync().getTagSyncIntervalMs();
        return configured > 0 ? configured : syncConfig.getPolicyRefreshIntervalMs();
    }

    private static Set<String> buildTagNames(ServiceTags tags) {
        if (tags == null || tags.getTagDefinitions() == null) return Collections.emptySet();
        Set<String> names = new HashSet<>();
        for (org.apache.ranger.plugin.model.RangerTagDef def : tags.getTagDefinitions().values()) {
            if (def.getName() != null) names.add(def.getName());
        }
        return names;
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
     * Execute a wildcard refresh cycle: re-expand glob-containing policies
     * against the current Glue catalog, compute diff, and apply delta.
     *
     * @return WildcardRefreshResult with counts of grants, revocations, and policies evaluated
     */
    public WildcardRefreshResult executeWildcardRefresh() {
        long startTime = System.currentTimeMillis();
        try {
            // Step 1: Filter lastKnownPolicies to those containing glob patterns
            List<RangerPolicy> globPolicies = GlobPatternDetector.filterGlobPolicies(lastKnownPolicies);
            LOG.info("Wildcard refresh: re-evaluating {} glob-containing policies out of {} total policies",
                    globPolicies.size(), lastKnownPolicies.size());

            if (globPolicies.isEmpty()) {
                long duration = System.currentTimeMillis() - startTime;
                LOG.info("Wildcard refresh: no glob policies found, no changes detected");
                return WildcardRefreshResult.success(duration, 0, 0, 0, 0);
            }

            // Log per-service breakdown of glob policies (Req 11.2)
            Map<String, Integer> globCountByService = new HashMap<>();
            for (RangerPolicy policy : globPolicies) {
                String serviceType = policy.getService() != null ? policy.getService() : "lakeformation";
                globCountByService.merge(serviceType, 1, Integer::sum);
            }
            LOG.info("Wildcard refresh: glob policies by serviceType: {}", globCountByService);

            // Step 2: Collect the source policy IDs of glob-containing policies
            // Use service-type-prefixed IDs to match the @source annotation format
            Set<String> globPolicyIds = new HashSet<>();
            for (RangerPolicy policy : globPolicies) {
                String policyId = policy.getId() != null ? String.valueOf(policy.getId()) : "unknown";
                String serviceType = policy.getService() != null ? policy.getService() : "lakeformation";
                // Match the @source("serviceType:policyId") format used in Cedar namespace isolation
                globPolicyIds.add(serviceType + ":" + policyId);
                LOG.debug("Wildcard refresh: re-expanding policy {} from serviceType={}",
                        policyId, serviceType);
            }

            // Step 3: Re-run conversion pipeline on glob policies
            CedarPolicySet globCedarPolicySet = rangerToCedarConverter.convert(globPolicies);
            List<LFPermissionOperation> reExpandedGlobOps = cedarToLFConverter.convert(globCedarPolicySet);

            // Step 4: Build merged operation set
            // Keep non-glob operations from previousOperations (those whose source policy ID
            // does not belong to a glob-containing policy)
            List<LFPermissionOperation> mergedOperations = new ArrayList<>();
            for (LFPermissionOperation op : previousOperations) {
                if (!globPolicyIds.contains(op.getSourcePolicyId())) {
                    mergedOperations.add(op);
                }
            }
            // Add re-expanded glob operations
            mergedOperations.addAll(reExpandedGlobOps);

            // Detect TABLE/TWC conflicts and remove losing operations before diffing.
            mergedOperations = detectAndGapTableTwcConflicts(mergedOperations);

            // Step 5: Compute diff against previousOperations
            PolicyDiff diff = computeDiff(previousOperations, mergedOperations);

            int newGrants = diff.getNewGrants().size();
            int revocations = diff.getRevocations().size();
            int unchanged = diff.getUnchangedCount();

            LOG.info("Wildcard refresh diff: {} new grants, {} revocations, {} unchanged",
                    newGrants, revocations, unchanged);

            // Step 6: Apply delta if non-empty
            List<LFPermissionOperation> deltaOperations = new ArrayList<>();
            deltaOperations.addAll(diff.getNewGrants());
            deltaOperations.addAll(diff.getRevocations());

            if (deltaOperations.isEmpty()) {
                LOG.info("Wildcard refresh: no changes detected");
            } else {
                LOG.info("Wildcard refresh: applying {} delta operations", deltaOperations.size());

                // Audit log each operation (includes service type via @source prefix)
                for (LFPermissionOperation op : deltaOperations) {
                    logAuditEntry(op);
                }

                lakeFormationClient.applyBatch(deltaOperations, deadLetterLogger);
            }

            // Step 7: Update state and persist checkpoint
            previousOperations = mergedOperations;

            // Build the full Cedar text: re-convert ALL lastKnownPolicies to get complete Cedar text
            CedarPolicySet fullCedarPolicySet = rangerToCedarConverter.convert(lastKnownPolicies);
            lastCedarPolicyText = fullCedarPolicySet.toCedarString();

            // Persist checkpoint: use per-service version map in multi-service mode
            if (checkpointStore != null) {
                if (isMultiServiceMode()) {
                    checkpointStore.save(new HashMap<>(serviceVersions), lastCedarPolicyText);
                } else {
                    checkpointStore.save(lastPolicyVersion, lastCedarPolicyText);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            LOG.info("Wildcard refresh completed: duration={}ms, policiesEvaluated={}, "
                    + "newGrants={}, revocations={}, unchanged={}, serviceTypes={}",
                    duration, globPolicies.size(), newGrants, revocations, unchanged,
                    globCountByService.keySet());

            return WildcardRefreshResult.success(duration, globPolicies.size(),
                    newGrants, revocations, unchanged);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LOG.error("Wildcard refresh failed: {}", e.getMessage(), e);
            // Leave previousOperations unchanged on failure
            return WildcardRefreshResult.failure(duration, e);
        }
    }

    /**
     * Compute the next checkpoint snapshot after applying a batch, and publish operational signals.
     * <p>
     * Two classes of policy are kept OUT of the snapshot so the next cycle retries them:
     * <ul>
     *   <li><b>failed</b> policies — real LF errors; logged at WARN and dead-lettered by the client.</li>
     *   <li><b>missing-resource</b> policies — GRANTs against a Glue table/database that no longer
     *       exists. Lake Formation already removed the permissions when the resource was deleted, so
     *       there is nothing to grant. These are benign and expected: not dead-lettered, logged at
     *       INFO only, and counted via the {@code MissingResourceGrants} metric so operators can
     *       clean up stale Ranger policies. They are retried each cycle and self-heal if the
     *       resource is recreated.</li>
     * </ul>
     */
    private List<LFPermissionOperation> advanceCheckpointOperations(
            List<LFPermissionOperation> currentOperations, BatchResult batchResult) {
        LOG.info("SyncService batch result: applied={}, rolledBack={}, failedPolicies={}, missingResourcePolicies={}",
                batchResult.getAppliedOperations(), batchResult.getRolledBackOperations(),
                batchResult.getFailedPolicyIds(), batchResult.getMissingResourcePolicyIds());

        Set<String> failedIds = new HashSet<>(batchResult.getFailedPolicyIds());
        if (!failedIds.isEmpty()) {
            LOG.warn("SyncService: {} failed policy IDs excluded from snapshot — will retry next cycle: {}",
                    failedIds.size(), failedIds);
        }

        List<String> missingIds = batchResult.getMissingResourcePolicyIds();
        if (!missingIds.isEmpty()) {
            LOG.info("SyncService: {} policy grant(s) target a Glue resource that no longer exists — "
                    + "kept out of checkpoint and retried (self-heals if recreated). "
                    + "Consider cleaning up these Ranger policies: {}", missingIds.size(), missingIds);
        }
        // Publish the gauge every cycle (including 0) so the metric reflects current state, not a
        // monotonic counter. This is the operator's signal to prune stale Ranger policies.
        if (metricsEmitter != null) {
            metricsEmitter.recordMissingResourceGrants(missingIds.size());
        }

        // Exclude BOTH failed and missing-resource policies from the checkpoint so both are retried.
        Set<String> excluded = new HashSet<>(failedIds);
        excluded.addAll(missingIds);
        return excluded.isEmpty()
                ? currentOperations
                : currentOperations.stream()
                        .filter(op -> !excluded.contains(op.getSourcePolicyId()))
                        .collect(Collectors.toList());
    }

    /**
     * Logs an audit entry for a single grant/revoke operation.
     * Each entry includes the service type, policy ID, resource path, principal ARN,
     * and permission type(s) for audit purposes (Req 4.6, Req 12.1, Req 12.2).
     * <p>
     * The service type is parsed from the source policy ID, which uses the format
     * {@code "serviceType:policyId"} (e.g., "hive:42", "lakeformation:5").
     */
    static void logAuditEntry(LFPermissionOperation op) {
        LFResource resource = op.getResource();
        String resourcePath = formatResourcePath(resource);
        String serviceType = parseServiceType(op.getSourcePolicyId());

        AUDIT_LOG.info("AUDIT: serviceType={}, operation={}, policyId={}, resource={}, principal={}, permissions={}",
                serviceType,
                op.getOperationType(),
                op.getSourcePolicyId(),
                resourcePath,
                op.getPrincipalArn(),
                op.getPermissions());
    }

    /**
     * Parses the service type prefix from a source policy ID.
     * The expected format is {@code "serviceType:policyId"} (e.g., "hive:42").
     * Returns "unknown" if the source policy ID is null or does not contain a colon.
     *
     * @param sourcePolicyId the source policy ID with optional service type prefix
     * @return the service type, or "unknown" if not present
     */
    static String parseServiceType(String sourcePolicyId) {
        if (sourcePolicyId == null || sourcePolicyId.isEmpty()) {
            return "unknown";
        }
        int colonIndex = sourcePolicyId.indexOf(':');
        if (colonIndex > 0) {
            return sourcePolicyId.substring(0, colonIndex);
        }
        return "unknown";
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
     * Detects TABLE/TWC conflicts in the desired-state operation list and removes the losing
     * operation, logging a permanent gap to the dead-letter the first time a conflict appears.
     *
     * <p>LF rejects a TABLE_WITH_COLUMNS grant when a TABLE grant already exists for the same
     * (principal, table), and vice versa. When both appear in the desired state simultaneously
     * the intent is ambiguous — we cannot apply both. The lower numeric policy ID wins (created
     * first). The losing operation is removed from the returned list so it never reaches the diff
     * or the apply phase.
     *
     * <p>The {@code activeConflicts} set prevents dead-letter flooding: a conflict key is added on
     * first detection and removed when it no longer appears. Steady-state re-logs are suppressed.
     *
     * @param operations the full desired-state list from the Cedar converter
     * @return a new list with conflicting losers removed
     */
    private List<LFPermissionOperation> detectAndGapTableTwcConflicts(
            List<LFPermissionOperation> operations) {

        // Group GRANT operations by (principalArn, catalogId, databaseName, tableName).
        // Only table-level grants can conflict; database, data-location, and column-wildcard
        // resources have no cross-type exclusivity constraint.
        Map<String, List<LFPermissionOperation>> grouped = new HashMap<>();
        for (LFPermissionOperation op : operations) {
            if (op.getOperationType() != OperationType.GRANT) {
                continue;
            }
            LFResource res = op.getResource();
            if (res.getTableName() == null || res.isAllTables()) {
                continue;
            }
            String key = op.getPrincipalArn() + "|"
                    + (res.getCatalogId() != null ? res.getCatalogId() : "") + "|"
                    + (res.getDatabaseName() != null ? res.getDatabaseName() : "") + "|"
                    + res.getTableName();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(op);
        }

        // Operations to remove, collected by object identity so that suppressing a losing TWC
        // does not affect a same-policy grant on a different table. (A single Ranger policy can
        // produce grants for many tables; only the conflicting ones must be dropped.)
        Set<LFPermissionOperation> losingOps = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> currentConflictKeys = new HashSet<>();

        for (Map.Entry<String, List<LFPermissionOperation>> entry : grouped.entrySet()) {
            List<LFPermissionOperation> group = entry.getValue();
            List<LFPermissionOperation> tableOps = new ArrayList<>();
            List<LFPermissionOperation> twcOps = new ArrayList<>();
            for (LFPermissionOperation op : group) {
                Set<String> cols = op.getResource().getColumnNames();
                if (cols == null || cols.isEmpty()) {
                    tableOps.add(op);
                } else {
                    twcOps.add(op);
                }
            }
            if (tableOps.isEmpty() || twcOps.isEmpty()) {
                continue; // no cross-type coexistence → nothing to resolve
            }

            // Rule B (subsumption): a TABLE-level SELECT grants SELECT on every column, which fully
            // covers any column-restricted TWC SELECT (TWC grants are SELECT-only). So if any table
            // op already carries SELECT, the TWC adds no access — drop it silently as redundant, not
            // as a conflict. All table permissions are retained (they merge naturally via the diff).
            boolean tableHasSelect = tableOps.stream()
                    .anyMatch(o -> o.getPermissions().contains(LFPermission.SELECT));
            if (tableHasSelect) {
                losingOps.addAll(twcOps);
                continue;
            }

            // Rule A (genuine conflict): no table-level SELECT exists, so TABLE and TWC grants are
            // irreconcilable. The lowest active policy id decides the winning TYPE (created first):
            // if it is a TWC grant, TWC wins and all table grants are rejected; otherwise the TWC
            // grants are rejected and all table grants win. All ops of the winning type are kept.
            String conflictKey = entry.getKey();
            currentConflictKeys.add(conflictKey);

            boolean twcWins = lowestPolicyId(twcOps) < lowestPolicyId(tableOps);
            List<LFPermissionOperation> losers = twcWins ? tableOps : twcOps;
            String winningType = twcWins ? "TABLE_WITH_COLUMNS" : "TABLE";
            losingOps.addAll(losers);

            boolean isNew = activeConflicts.add(conflictKey);
            if (isNew) {
                for (LFPermissionOperation op : losers) {
                    String msg = "CONFLICTING_LF_RESOURCE_TYPE: TABLE and TABLE_WITH_COLUMNS"
                            + " grants cannot coexist for the same (principal, table) when no"
                            + " table-level SELECT is present. Winning type=" + winningType
                            + ". Resolve by removing one of the conflicting Ranger policies.";
                    deadLetterLogger.logGapOperation(op, msg);
                    LOG.warn("TABLE/TWC conflict detected: principal={}, table={}/{}, "
                            + "losingPolicyId={}, winningType={}",
                            op.getPrincipalArn(),
                            op.getResource().getDatabaseName(),
                            op.getResource().getTableName(),
                            op.getSourcePolicyId(), winningType);
                }
            }
        }

        // Remove keys that are no longer conflicting (conflict resolved in Ranger).
        Set<String> resolved = new HashSet<>(activeConflicts);
        resolved.removeAll(currentConflictKeys);
        for (String key : resolved) {
            activeConflicts.remove(key);
            LOG.info("TABLE/TWC conflict resolved: {}", key);
        }

        if (losingOps.isEmpty()) {
            return operations;
        }

        List<LFPermissionOperation> filtered = new ArrayList<>(operations.size());
        for (LFPermissionOperation op : operations) {
            if (!losingOps.contains(op)) {
                filtered.add(op);
            }
        }
        return filtered;
    }

    /**
     * Lowest numeric source-policy id among the given operations. A source id is
     * {@code "serviceType:policyId"} or a bare id; non-numeric ids sort after all numeric ones
     * (return {@link Long#MAX_VALUE}) so a numeric id always wins the tie-break. Returns
     * {@code Long.MAX_VALUE} for an empty list.
     */
    private static long lowestPolicyId(List<LFPermissionOperation> ops) {
        long lowest = Long.MAX_VALUE;
        for (LFPermissionOperation op : ops) {
            String rawId = op.getSourcePolicyId();
            String idPart = rawId != null && rawId.contains(":")
                    ? rawId.substring(rawId.lastIndexOf(':') + 1) : rawId;
            long numeric;
            try {
                numeric = Long.parseLong(idPart);
            } catch (NumberFormatException e) {
                numeric = Long.MAX_VALUE;
            }
            if (numeric < lowest) {
                lowest = numeric;
            }
        }
        return lowest;
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
        // Log each distinct gap once, not once per cycle. Gaps are a property of the policy set,
        // so an unchanged policy set re-emitted them every refresh interval: an hour-long run
        // produced a 25 GB log in which a single DENY_POLICY line appeared ~16000 times per 20 MB,
        // burying everything else and risking disk exhaustion. Repeats go to DEBUG so they remain
        // available when explicitly wanted. Mirrors the activeConflicts de-duplication used for
        // TABLE/TWC conflicts.
        for (GapEntry entry : entries) {
            String gapKey = entry.getPolicyId() + "|" + entry.getGapType() + "|"
                    + entry.getResourcePath();
            if (loggedGaps.add(gapKey)) {
                LOG.warn("SyncService gap: policyId={}, type={}, resource={}, details={}",
                        entry.getPolicyId(), entry.getGapType(),
                        entry.getResourcePath(), entry.getDetails());
            } else {
                LOG.debug("SyncService gap (repeat): policyId={}, type={}, resource={}",
                        entry.getPolicyId(), entry.getGapType(), entry.getResourcePath());
            }
        }
        // Bound the memory the de-duplication set can hold: the simulator churns policy ids
        // indefinitely, so without this the set would grow without limit on a long run.
        if (loggedGaps.size() > MAX_LOGGED_GAP_KEYS) {
            LOG.info("Gap log de-duplication set exceeded {} keys; clearing it", MAX_LOGGED_GAP_KEYS);
            loggedGaps.clear();
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

        // Diff at the individual-permission level, NOT at the whole-permission-set level.
        //
        // Two different Ranger policies may grant overlapping-but-different permission sets to
        // the same (principal, resource) — e.g. policy A grants {SELECT, DESCRIBE} and policy B
        // grants {SELECT, INSERT}. Lake Formation keys grants by (principal, resource, permission)
        // with no policy attribution, so if policy B is deleted we must revoke only INSERT: SELECT
        // must survive because policy A still grants it. Treating each policy's permission set as
        // an atomic diff unit would emit a REVOKE for B's entire set (including SELECT) and
        // collaterally destroy the SELECT that policy A still requires.
        Map<PermissionAtom, LFPermissionOperation> previousAtoms = explodeToAtoms(previous);
        Map<PermissionAtom, LFPermissionOperation> currentAtoms = explodeToAtoms(current);

        // New grants: atoms in current but not in previous.
        Map<GrantGroupKey, GrantGroup> grantGroups = new LinkedHashMap<>();
        for (Map.Entry<PermissionAtom, LFPermissionOperation> entry : currentAtoms.entrySet()) {
            PermissionAtom atom = entry.getKey();
            if (!previousAtoms.containsKey(atom)) {
                addToGroup(grantGroups, atom, entry.getValue());
            }
        }

        // Revocations: atoms in previous but not in current.
        Map<GrantGroupKey, GrantGroup> revokeGroups = new LinkedHashMap<>();
        int unchangedCount = 0;
        for (Map.Entry<PermissionAtom, LFPermissionOperation> entry : previousAtoms.entrySet()) {
            PermissionAtom atom = entry.getKey();
            if (currentAtoms.containsKey(atom)) {
                unchangedCount++;
            } else {
                addToGroup(revokeGroups, atom, entry.getValue());
            }
        }

        List<LFPermissionOperation> newGrants = materialize(grantGroups, OperationType.GRANT);
        List<LFPermissionOperation> revocations = materialize(revokeGroups, OperationType.REVOKE);

        return new PolicyDiff(newGrants, revocations, unchangedCount);
    }

    /**
     * Explode each operation into one {@link PermissionAtom} per individual permission so the diff
     * can reason about permissions independently of which policy contributed them. When the same
     * atom is produced by multiple operations, the first-seen operation is retained as the
     * representative (used only for its sourcePolicyId when re-materializing operations).
     */
    private static Map<PermissionAtom, LFPermissionOperation> explodeToAtoms(
            List<LFPermissionOperation> operations) {
        Map<PermissionAtom, LFPermissionOperation> atoms = new LinkedHashMap<>();
        for (LFPermissionOperation op : operations) {
            for (LFPermission permission : op.getPermissions()) {
                PermissionAtom atom = new PermissionAtom(
                        op.getPrincipalArn(), op.getResource(), permission, op.isGrantable());
                atoms.putIfAbsent(atom, op);
            }
        }
        return atoms;
    }

    /** Accumulate an atom into the group sharing its (principal, resource, grantable). */
    private static void addToGroup(Map<GrantGroupKey, GrantGroup> groups,
                                   PermissionAtom atom, LFPermissionOperation representative) {
        GrantGroupKey key = new GrantGroupKey(atom.principalArn(), atom.resource(), atom.grantable());
        GrantGroup group = groups.get(key);
        if (group == null) {
            group = new GrantGroup(representative.getSourcePolicyId(),
                    representative.getOriginalPrincipal());
            groups.put(key, group);
        }
        group.permissions.add(atom.permission());
    }

    /** Re-materialize grouped atoms back into consolidated operations of the given type. */
    private static List<LFPermissionOperation> materialize(
            Map<GrantGroupKey, GrantGroup> groups, OperationType type) {
        List<LFPermissionOperation> operations = new ArrayList<>(groups.size());
        for (Map.Entry<GrantGroupKey, GrantGroup> entry : groups.entrySet()) {
            GrantGroupKey key = entry.getKey();
            GrantGroup group = entry.getValue();
            operations.add(new LFPermissionOperation(
                    type, group.sourcePolicyId, group.originalPrincipal, key.principalArn(),
                    key.resource(), group.permissions, key.grantable()));
        }
        return operations;
    }

    /**
     * Compute the diff between two sets of S3 Access Grant operations.
     * <p>
     * The diff identifies:
     * <ul>
     *   <li>New grants: operations in current but not in previous</li>
     *   <li>Revocations: operations in previous but not in current (as REVOKE ops)</li>
     * </ul>
     * <p>
     * Comparison is based on the grant "identity" — the combination of
     * principalArn, s3Prefix, and permission — ignoring grantId and type.
     *
     * @param previous the previous set of operations
     * @param current  the current set of operations
     * @return the computed S3AG diff
     */
    static S3AgDiff computeS3AgDiff(
            List<S3AccessGrantOperation> previous,
            List<S3AccessGrantOperation> current) {

        // Key: (principalArn, s3Prefix, permission) — ignore grantId and type
        record S3AgKey(String principalArn, String s3Prefix, com.amazonaws.policyconverters.s3accessgrants.S3AccessGrantPermission permission) {}

        Map<S3AgKey, S3AccessGrantOperation> previousMap = new HashMap<>();
        for (S3AccessGrantOperation op : previous) {
            previousMap.put(new S3AgKey(op.principalArn(), op.s3Prefix(), op.permission()), op);
        }

        Map<S3AgKey, S3AccessGrantOperation> currentMap = new HashMap<>();
        for (S3AccessGrantOperation op : current) {
            currentMap.put(new S3AgKey(op.principalArn(), op.s3Prefix(), op.permission()), op);
        }

        List<S3AccessGrantOperation> newGrants = new ArrayList<>();
        List<S3AccessGrantOperation> revocations = new ArrayList<>();

        // Find new grants: in current but not in previous
        for (Map.Entry<S3AgKey, S3AccessGrantOperation> entry : currentMap.entrySet()) {
            if (!previousMap.containsKey(entry.getKey())) {
                S3AccessGrantOperation op = entry.getValue();
                if (op.type() != com.amazonaws.policyconverters.s3accessgrants.OperationType.GRANT) {
                    op = new S3AccessGrantOperation(
                            com.amazonaws.policyconverters.s3accessgrants.OperationType.GRANT,
                            op.principalArn(), op.s3Prefix(), op.permission(), op.grantId(),
                            op.sourcePolicyId());
                }
                newGrants.add(op);
            }
        }

        // Find revocations: in previous but not in current
        for (Map.Entry<S3AgKey, S3AccessGrantOperation> entry : previousMap.entrySet()) {
            if (!currentMap.containsKey(entry.getKey())) {
                S3AccessGrantOperation op = entry.getValue();
                revocations.add(new S3AccessGrantOperation(
                        com.amazonaws.policyconverters.s3accessgrants.OperationType.REVOKE,
                        op.principalArn(), op.s3Prefix(), op.permission(), op.grantId(),
                        op.sourcePolicyId()));
            }
        }

        return new S3AgDiff(newGrants, revocations);
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
     * Returns the last known Cedar policy set, reconstructed from the persisted Cedar text.
     * Returns null if no Cedar policies have been processed yet.
     */
    public CedarPolicySet getLastCedarPolicySet() {
        String cedarText = lastCedarPolicyText;
        if (cedarText == null || cedarText.trim().isEmpty()) {
            return null;
        }
        try {
            return CedarPolicySet.fromCedarString(cedarText);
        } catch (Exception e) {
            LOG.warn("Failed to reconstruct CedarPolicySet from last known text: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the last known Ranger policies (for testing connectivity resilience).
     */
    List<RangerPolicy> getLastKnownPolicies() {
        return lastKnownPolicies;
    }

    /**
     * Returns the set of service types that have completed at least one
     * successful policy fetch (for testing the first-sync gate).
     */
    Set<String> getInitializedServices() {
        return Collections.unmodifiableSet(new HashSet<>(initializedServices));
    }

    /**
     * Returns the per-service version map (for testing checkpoint persistence).
     */
    Map<String, Long> getServiceVersions() {
        return Collections.unmodifiableMap(new HashMap<>(serviceVersions));
    }

    /**
     * Returns the monotonic counter of fully completed {@link #executeSyncCycle()} calls.
     */
    public AtomicLong getLastCompletedCycle() {
        return lastCompletedCycle;
    }

    /**
     * Returns the list of configured Ranger services (for testing).
     */
    List<BaseRangerService> getRangerServices() {
        return rangerServices;
    }

    /**
     * Returns a comma-separated list of service type names for logging.
     */
    private String getServiceNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rangerServices.size(); i++) {
            if (i > 0) sb.append(", ");
            BaseRangerService svc = rangerServices.get(i);
            sb.append(svc.getServiceType()).append(":").append(svc.getServiceInstanceName());
        }
        return sb.toString();
    }

    /**
     * Identity of a single permission for atom-level diffing: one principal, one resource,
     * one individual permission, and the grantable flag. Unlike {@link PermissionKey}, the
     * permission is a single value rather than a set, so overlapping grants from different
     * policies can be reconciled per-permission.
     */
    record PermissionAtom(String principalArn, LFResource resource,
                          LFPermission permission, boolean grantable) {}

    /** Groups atoms back together by everything except the individual permission. */
    record GrantGroupKey(String principalArn, LFResource resource, boolean grantable) {}

    /** Mutable accumulator of permissions for a {@link GrantGroupKey} during re-materialization. */
    private static final class GrantGroup {
        private final String sourcePolicyId;
        private final String originalPrincipal;
        private final EnumSet<LFPermission> permissions = EnumSet.noneOf(LFPermission.class);

        GrantGroup(String sourcePolicyId, String originalPrincipal) {
            this.sourcePolicyId = sourcePolicyId;
            this.originalPrincipal = originalPrincipal;
        }
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
     * Represents the diff between two S3 Access Grants operation snapshots.
     */
    record S3AgDiff(
        List<S3AccessGrantOperation> newGrants,
        List<S3AccessGrantOperation> revocations
    ) {}

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
