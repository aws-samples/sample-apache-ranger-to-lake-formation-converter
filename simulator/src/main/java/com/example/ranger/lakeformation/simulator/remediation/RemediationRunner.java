package com.example.ranger.lakeformation.simulator.remediation;

import com.example.ranger.lakeformation.simulator.status.CycleTimeoutException;
import com.example.ranger.lakeformation.simulator.status.CycleWaiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class RemediationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RemediationRunner.class);

    private final CycleWaiter cycleWaiter;

    public RemediationRunner(CycleWaiter cycleWaiter) {
        this.cycleWaiter = cycleWaiter;
    }

    /**
     * Wait for one full sync cycle past the violation cycle.
     *
     * @param violationCycle the cycle number at which the violation was detected
     * @return the cycle number after remediation
     * @throws CycleTimeoutException if the sync service doesn't complete a new cycle in time
     * @throws IOException if status polling fails
     * @throws InterruptedException if interrupted
     */
    public long waitForRemediation(long violationCycle)
            throws CycleTimeoutException, IOException, InterruptedException {
        LOG.info("Waiting for remediation cycle after cycle {}", violationCycle);
        long newCycle = cycleWaiter.waitForCycleAfter(violationCycle);
        LOG.info("Remediation cycle {} completed", newCycle);
        return newCycle;
    }
}
