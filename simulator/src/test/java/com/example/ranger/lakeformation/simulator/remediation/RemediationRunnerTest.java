package com.example.ranger.lakeformation.simulator.remediation;

import com.example.ranger.lakeformation.simulator.status.CycleTimeoutException;
import com.example.ranger.lakeformation.simulator.status.CycleWaiter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RemediationRunnerTest {

    // ---------------------------------------------------------------------------
    // Stubs
    // ---------------------------------------------------------------------------

    /** Returns a fixed value and records the argument it was called with. */
    static class ReturningStub extends CycleWaiter {
        private final long returnValue;
        long capturedArg = -1;

        ReturningStub(long returnValue) {
            super(null, Duration.ZERO);
            this.returnValue = returnValue;
        }

        @Override
        public long waitForCycleAfter(long targetCycle)
                throws IOException, InterruptedException, CycleTimeoutException {
            this.capturedArg = targetCycle;
            return returnValue;
        }
    }

    /** Always throws CycleTimeoutException. */
    static class TimeoutStub extends CycleWaiter {
        TimeoutStub() {
            super(null, Duration.ZERO);
        }

        @Override
        public long waitForCycleAfter(long targetCycle)
                throws IOException, InterruptedException, CycleTimeoutException {
            throw new CycleTimeoutException("timeout");
        }
    }

    /** Always throws IOException. */
    static class IOExceptionStub extends CycleWaiter {
        IOExceptionStub() {
            super(null, Duration.ZERO);
        }

        @Override
        public long waitForCycleAfter(long targetCycle)
                throws IOException, InterruptedException, CycleTimeoutException {
            throw new IOException("network");
        }
    }

    /** Always throws InterruptedException. */
    static class InterruptedStub extends CycleWaiter {
        InterruptedStub() {
            super(null, Duration.ZERO);
        }

        @Override
        public long waitForCycleAfter(long targetCycle)
                throws IOException, InterruptedException, CycleTimeoutException {
            throw new InterruptedException();
        }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void returnsNewCycleFromWaiter() throws Exception {
        ReturningStub stub = new ReturningStub(6L);
        RemediationRunner runner = new RemediationRunner(stub);

        long result = runner.waitForRemediation(5L);

        assertEquals(6L, result, "waitForRemediation should return the cycle number from the waiter");
        assertEquals(5L, stub.capturedArg, "waitForCycleAfter should have been called with the violation cycle");
    }

    @Test
    void propagatesCycleTimeoutException() {
        RemediationRunner runner = new RemediationRunner(new TimeoutStub());

        assertThrows(CycleTimeoutException.class, () -> runner.waitForRemediation(3L));
    }

    @Test
    void propagatesIOException() {
        RemediationRunner runner = new RemediationRunner(new IOExceptionStub());

        assertThrows(IOException.class, () -> runner.waitForRemediation(3L));
    }

    @Test
    void propagatesInterruptedException() {
        RemediationRunner runner = new RemediationRunner(new InterruptedStub());

        assertThrows(InterruptedException.class, () -> runner.waitForRemediation(3L));
    }
}
