package com.amazonaws.policyconverters.app;

import com.amazonaws.policyconverters.model.WildcardRefreshResult;
import com.amazonaws.policyconverters.reporting.MetricsEmitter;
import com.amazonaws.policyconverters.sync.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WildcardRefreshScheduler}.
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4, 5.3
 */
@ExtendWith(MockitoExtension.class)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class WildcardRefreshSchedulerTest {

    @Mock
    private SyncService syncService;

    @Mock
    private MetricsEmitter metricsEmitter;

    private ReentrantLock cycleLock;

    @BeforeEach
    void setUp() {
        cycleLock = new ReentrantLock();
    }

    /**
     * start(0) is a no-op — no scheduler created, shutdown returns true immediately.
     */
    @Test
    void start_withZeroInterval_isNoOp() {
        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(0);

        // shutdown on a never-started scheduler should return true
        assertTrue(scheduler.shutdown(1));
        // executeWildcardRefresh should never be called
        verifyNoInteractions(syncService);
    }

    /**
     * start(-1) is also a no-op — negative intervals are treated like zero.
     */
    @Test
    void start_withNegativeInterval_isNoOp() {
        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(-1);

        assertTrue(scheduler.shutdown(1));
        verifyNoInteractions(syncService);
    }

    /**
     * start(1) schedules periodic execution; executeWildcardRefresh() is called at least once.
     */
    @Test
    void start_withPositiveInterval_schedulesExecution() throws InterruptedException {
        WildcardRefreshResult result = WildcardRefreshResult.success(50, 2, 1, 0, 1);
        CountDownLatch invoked = new CountDownLatch(1);

        when(syncService.executeWildcardRefresh()).thenAnswer(inv -> {
            invoked.countDown();
            return result;
        });

        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(1);

        assertTrue(invoked.await(5, TimeUnit.SECONDS), "executeWildcardRefresh should be called");
        verify(syncService, atLeastOnce()).executeWildcardRefresh();
        verify(metricsEmitter, atLeastOnce()).recordWildcardRefresh(result);

        scheduler.shutdown(5);
    }

    /**
     * Lock is acquired before and released after refresh execution.
     */
    @Test
    void refreshCycle_acquiresAndReleasesLock() throws InterruptedException {
        CountDownLatch insideCycle = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        when(syncService.executeWildcardRefresh()).thenAnswer(inv -> {
            // While inside the cycle, the lock should be held
            assertTrue(cycleLock.isHeldByCurrentThread(), "Lock should be held during refresh");
            insideCycle.countDown();
            proceed.await(5, TimeUnit.SECONDS);
            return WildcardRefreshResult.success(10, 1, 0, 0, 1);
        });

        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(1);

        assertTrue(insideCycle.await(5, TimeUnit.SECONDS), "Cycle should start");

        // Lock should not be available from this thread while cycle is running
        assertFalse(cycleLock.tryLock(), "Lock should be held by scheduler thread");

        proceed.countDown();

        // Wait a bit for the cycle to finish and release the lock
        Thread.sleep(200);
        assertTrue(cycleLock.tryLock(2, TimeUnit.SECONDS), "Lock should be released after cycle");
        cycleLock.unlock();

        scheduler.shutdown(5);
    }

    /**
     * Exception in executeWildcardRefresh() does not cancel future scheduled tasks.
     */
    @Test
    void refreshCycle_exceptionDoesNotCancelFutureTasks() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch secondCall = new CountDownLatch(2);

        when(syncService.executeWildcardRefresh()).thenAnswer(inv -> {
            int count = callCount.incrementAndGet();
            secondCall.countDown();
            if (count == 1) {
                throw new RuntimeException("Simulated failure");
            }
            return WildcardRefreshResult.success(10, 1, 0, 0, 1);
        });

        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(1);

        // Wait for at least 2 invocations — proves the first exception didn't cancel the schedule
        assertTrue(secondCall.await(10, TimeUnit.SECONDS),
                "Scheduler should continue after exception, got " + callCount.get() + " calls");
        assertTrue(callCount.get() >= 2, "Should have been called at least twice");

        scheduler.shutdown(5);
    }

    /**
     * shutdown() stops the scheduler and waits for in-flight cycle.
     */
    @Test
    void shutdown_stopsSchedulerAndWaitsForInflightCycle() throws InterruptedException {
        CountDownLatch cycleStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);

        when(syncService.executeWildcardRefresh()).thenAnswer(inv -> {
            cycleStarted.countDown();
            allowFinish.await(5, TimeUnit.SECONDS);
            return WildcardRefreshResult.success(100, 1, 0, 0, 1);
        });

        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(1);

        assertTrue(cycleStarted.await(5, TimeUnit.SECONDS), "Cycle should start");

        // Start shutdown in a separate thread
        Thread shutdownThread = new Thread(() -> {
            boolean terminated = scheduler.shutdown(5);
            assertTrue(terminated, "Scheduler should terminate within timeout");
        });
        shutdownThread.start();

        // Let the in-flight cycle finish
        Thread.sleep(200);
        allowFinish.countDown();

        shutdownThread.join(5000);
        assertFalse(shutdownThread.isAlive(), "Shutdown thread should have completed");
    }

    /**
     * Sync cycle and refresh cycle are mutually exclusive — they share the same lock.
     * Uses CountDownLatch coordination to prove mutual exclusion.
     */
    @Test
    void syncAndRefreshCycles_areMutuallyExclusive() throws InterruptedException {
        CountDownLatch refreshHoldsLock = new CountDownLatch(1);
        CountDownLatch syncTriedLock = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);

        when(syncService.executeWildcardRefresh()).thenAnswer(inv -> {
            // Signal that refresh is holding the lock
            refreshHoldsLock.countDown();
            // Wait until the sync thread has tried (and failed) to acquire the lock
            releaseRefresh.await(5, TimeUnit.SECONDS);
            return WildcardRefreshResult.success(10, 1, 0, 0, 1);
        });

        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        scheduler.start(1);

        // Wait for the refresh cycle to hold the lock
        assertTrue(refreshHoldsLock.await(5, TimeUnit.SECONDS), "Refresh should acquire lock");

        // Simulate a sync cycle trying to acquire the same lock
        Thread syncThread = new Thread(() -> {
            boolean acquired = cycleLock.tryLock();
            // Should NOT be able to acquire — refresh holds it
            assertFalse(acquired, "Sync should not acquire lock while refresh holds it");
            syncTriedLock.countDown();
        });
        syncThread.start();

        assertTrue(syncTriedLock.await(5, TimeUnit.SECONDS), "Sync thread should have tried the lock");

        // Now release the refresh cycle
        releaseRefresh.countDown();

        // After refresh releases, the lock should become available
        Thread.sleep(300);
        assertTrue(cycleLock.tryLock(2, TimeUnit.SECONDS),
                "Lock should be available after refresh cycle completes");
        cycleLock.unlock();

        scheduler.shutdown(5);
        syncThread.join(2000);
    }

    /**
     * shutdown() on a never-started scheduler returns true immediately.
     */
    @Test
    void shutdown_onNeverStartedScheduler_returnsTrue() {
        WildcardRefreshScheduler scheduler = new WildcardRefreshScheduler(syncService, metricsEmitter, cycleLock);
        assertTrue(scheduler.shutdown(1));
    }
}
