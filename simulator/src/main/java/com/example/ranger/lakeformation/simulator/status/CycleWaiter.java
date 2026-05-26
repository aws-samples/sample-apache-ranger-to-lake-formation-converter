package com.example.ranger.lakeformation.simulator.status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Polls the sync service status endpoint until a new cycle completes.
 * Throws {@link CycleTimeoutException} if the timeout is exceeded.
 */
public class CycleWaiter {
    private static final Logger LOG = LoggerFactory.getLogger(CycleWaiter.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final SyncServiceStatusClient statusClient;
    private final Duration timeout;

    public CycleWaiter(SyncServiceStatusClient statusClient, Duration timeout) {
        this.statusClient = statusClient;
        this.timeout = timeout;
    }

    /**
     * Block until {@code lastCompletedCycle > targetCycle} or timeout expires.
     *
     * @param targetCycle the cycle number to wait past
     * @return the new completed cycle number
     * @throws CycleTimeoutException if the timeout elapses before the cycle completes
     * @throws IOException if status polling fails
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    public long waitForCycleAfter(long targetCycle) throws IOException, InterruptedException, CycleTimeoutException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            SyncServiceStatusClient.StatusResponse status = statusClient.fetchStatus();
            if (status.lastCompletedCycle() > targetCycle) {
                LOG.info("Cycle {} completed (waited past {})", status.lastCompletedCycle(), targetCycle);
                return status.lastCompletedCycle();
            }
            LOG.debug("Cycle still at {}, waiting {} seconds...", status.lastCompletedCycle(), POLL_INTERVAL.getSeconds());
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new CycleTimeoutException("Timed out after " + timeout + " waiting for cycle > " + targetCycle);
    }
}
