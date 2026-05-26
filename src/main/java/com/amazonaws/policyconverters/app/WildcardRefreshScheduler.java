package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.model.WildcardRefreshResult;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import com.amazonaws.policyconverters.sync.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages a {@link ScheduledExecutorService} that periodically triggers
 * wildcard refresh cycles. Each cycle acquires the shared {@code cycleLock}
 * to ensure mutual exclusion with the normal sync cycle in
 * {@link ServerLifecycle}, then delegates to
 * {@link SyncService#executeWildcardRefresh()}.
 */
public class WildcardRefreshScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(WildcardRefreshScheduler.class);

    private final SyncService syncService;
    private final MetricsEmitter metricsEmitter;
    private final ReentrantLock cycleLock;
    private ScheduledExecutorService scheduler;

    /** Monotonic counter incremented after each fully completed wildcard refresh cycle. */
    private final AtomicLong lastCompletedWildcardRefreshCycle = new AtomicLong(0);

    public WildcardRefreshScheduler(SyncService syncService,
                                    MetricsEmitter metricsEmitter,
                                    ReentrantLock cycleLock) {
        this.syncService = syncService;
        this.metricsEmitter = metricsEmitter;
        this.cycleLock = cycleLock;
    }

    /**
     * Starts periodic wildcard refresh at the given interval.
     * If {@code intervalSeconds} is zero or negative, this method is a no-op
     * and no scheduler is created.
     *
     * @param intervalSeconds seconds between refresh cycles
     */
    public void start(int intervalSeconds) {
        if (intervalSeconds <= 0) {
            LOG.info("Wildcard refresh disabled (intervalSeconds={})", intervalSeconds);
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wildcard-refresh");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::executeRefreshCycle,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        LOG.info("Wildcard refresh scheduler started with interval={}s", intervalSeconds);
    }

    /**
     * Executes a single refresh cycle. Acquires the shared lock, calls
     * {@link SyncService#executeWildcardRefresh()}, emits metrics, and
     * releases the lock. The entire body is wrapped in try-catch to
     * prevent {@link ScheduledExecutorService} from cancelling future
     * executions on uncaught exceptions.
     */
    private void executeRefreshCycle() {
        try {
            cycleLock.lock();
            try {
                WildcardRefreshResult result = syncService.executeWildcardRefresh();
                metricsEmitter.recordWildcardRefresh(result);
                lastCompletedWildcardRefreshCycle.incrementAndGet();
            } finally {
                cycleLock.unlock();
            }
        } catch (Exception e) {
            LOG.error("Wildcard refresh cycle failed unexpectedly: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the monotonic counter of fully completed wildcard refresh cycles.
     */
    public AtomicLong getLastCompletedWildcardRefreshCycle() {
        return lastCompletedWildcardRefreshCycle;
    }

    /**
     * Shuts down the scheduler and waits up to {@code timeoutSeconds} for
     * any in-flight refresh cycle to complete.
     *
     * @param timeoutSeconds maximum seconds to wait for termination
     * @return {@code true} if the scheduler terminated within the timeout
     */
    public boolean shutdown(int timeoutSeconds) {
        if (scheduler == null) {
            return true;
        }

        LOG.info("Shutting down wildcard refresh scheduler");
        scheduler.shutdown();
        try {
            boolean terminated = scheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            if (!terminated) {
                LOG.warn("Wildcard refresh scheduler did not terminate within {}s", timeoutSeconds);
            }
            return terminated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for wildcard refresh scheduler shutdown");
            return false;
        }
    }
}
