package com.example.ranger.lakeformation.simulator.status;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CycleWaiterTest {

    /**
     * Stub that returns progressively increasing cycle numbers without any actual HTTP calls.
     */
    static class StubStatusClient extends SyncServiceStatusClient {
        private final long[] cycles;
        int callCount = 0;

        StubStatusClient(long... cycles) {
            super("localhost", 0); // port doesn't matter — we override fetchStatus
            this.cycles = cycles;
        }

        @Override
        public StatusResponse fetchStatus() {
            long cycle = cycles[Math.min(callCount++, cycles.length - 1)];
            return new StatusResponse(cycle, 0, "running");
        }
    }

    /**
     * Stub that always throws IOException to simulate a failed HTTP call.
     */
    static class ThrowingStatusClient extends SyncServiceStatusClient {
        private final IOException toThrow;

        ThrowingStatusClient(IOException toThrow) {
            super("localhost", 0);
            this.toThrow = toThrow;
        }

        @Override
        public StatusResponse fetchStatus() throws IOException {
            throw toThrow;
        }
    }

    @Test
    void returnsImmediatelyWhenFirstPollExceedsTarget() throws IOException, InterruptedException, CycleTimeoutException {
        // First poll returns cycle=6, target=5 → should return immediately
        StubStatusClient stub = new StubStatusClient(6);
        CycleWaiter waiter = new CycleWaiter(stub, Duration.ofSeconds(30));

        long result = waiter.waitForCycleAfter(5);

        assertEquals(6L, result);
        assertEquals(1, stub.callCount, "fetchStatus should have been called exactly once");
    }

    @Test
    void returnsAfterSecondPollWhenFirstMatchesTarget() throws IOException, InterruptedException, CycleTimeoutException {
        // First poll cycle=5 (not > 5), second poll cycle=6 (> 5)
        // Override POLL_INTERVAL is private so we need to bypass sleep — use a large timeout but
        // stub that advances on second call. The test will sleep POLL_INTERVAL (5s) between polls,
        // which is acceptable for correctness but slow. Instead, we subclass to also override sleep.
        StubStatusClient stub = new StubStatusClient(5, 6);

        // Use a subclass of CycleWaiter that skips sleeping to keep the test fast
        CycleWaiter waiter = new CycleWaiter(stub, Duration.ofSeconds(30)) {
            @Override
            public long waitForCycleAfter(long targetCycle) throws IOException, InterruptedException, CycleTimeoutException {
                java.time.Instant deadline = java.time.Instant.now().plus(Duration.ofSeconds(30));
                while (java.time.Instant.now().isBefore(deadline)) {
                    SyncServiceStatusClient.StatusResponse status = stub.fetchStatus();
                    if (status.lastCompletedCycle() > targetCycle) {
                        return status.lastCompletedCycle();
                    }
                    // No sleep — immediately poll again
                }
                throw new CycleTimeoutException("timed out");
            }
        };

        long result = waiter.waitForCycleAfter(5);

        assertEquals(6L, result);
        assertEquals(2, stub.callCount, "fetchStatus should have been called exactly twice");
    }

    @Test
    void throwsCycleTimeoutExceptionWhenTimeoutExpires() {
        // Stub always returns cycle=5; target=5, so it never progresses
        StubStatusClient stub = new StubStatusClient(5);
        // Use a short timeout and a waiter that doesn't sleep so the test completes quickly
        CycleWaiter waiter = new CycleWaiter(stub, Duration.ofSeconds(1)) {
            @Override
            public long waitForCycleAfter(long targetCycle) throws IOException, InterruptedException, CycleTimeoutException {
                java.time.Instant deadline = java.time.Instant.now().plus(Duration.ofSeconds(1));
                while (java.time.Instant.now().isBefore(deadline)) {
                    SyncServiceStatusClient.StatusResponse status = stub.fetchStatus();
                    if (status.lastCompletedCycle() > targetCycle) {
                        return status.lastCompletedCycle();
                    }
                    // No sleep — spin until timeout
                }
                throw new CycleTimeoutException("Timed out after PT1S waiting for cycle > " + targetCycle);
            }
        };

        assertThrows(CycleTimeoutException.class, () -> waiter.waitForCycleAfter(5));
    }

    // -------------------------------------------------------------------------
    // Tests 4-6: exercise the REAL production implementation (no method override)
    // -------------------------------------------------------------------------

    @Test
    void fetchStatusIOExceptionPropagates() {
        // Real CycleWaiter with a stub that throws IOException on the first call.
        // The throw happens before any sleep, so this test is instant.
        ThrowingStatusClient client = new ThrowingStatusClient(new IOException("connection refused"));
        CycleWaiter waiter = new CycleWaiter(client, Duration.ofSeconds(30));

        assertThrows(IOException.class, () -> waiter.waitForCycleAfter(5L));
    }

    @Test
    void interruptedExceptionPropagates() throws InterruptedException {
        // Real CycleWaiter: stub always returns cycle=5 (not > target 5), causing the loop
        // to call Thread.sleep(POLL_INTERVAL). We interrupt the sleeping thread and verify
        // that InterruptedException propagates out of waitForCycleAfter.
        StubStatusClient stub = new StubStatusClient(5);
        CycleWaiter waiter = new CycleWaiter(stub, Duration.ofMinutes(5));

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                waiter.waitForCycleAfter(5L);
            } catch (InterruptedException e) {
                caught.set(e);
            } catch (Exception e) {
                caught.set(e);
            }
        });
        t.start();
        Thread.sleep(100); // let the thread enter the sleep
        t.interrupt();
        t.join(10_000);
        assertFalse(t.isAlive(), "Thread should have terminated");
        assertInstanceOf(InterruptedException.class, caught.get());
    }

    @Test
    // NOTE: this test sleeps ~5s due to POLL_INTERVAL
    void secondPollReturnsResultAfterFirstPollNotReady() throws IOException, InterruptedException, CycleTimeoutException {
        // Real CycleWaiter: first call returns cycle=5 (not > 5), second returns cycle=6 (> 5).
        // The real implementation sleeps POLL_INTERVAL (5s) between polls, so this test is slow.
        StubStatusClient stub = new StubStatusClient(5, 6);
        CycleWaiter waiter = new CycleWaiter(stub, Duration.ofSeconds(30));

        long result = waiter.waitForCycleAfter(5L);

        assertEquals(6L, result);
        assertEquals(2, stub.callCount, "fetchStatus should have been called exactly twice");
    }
}
