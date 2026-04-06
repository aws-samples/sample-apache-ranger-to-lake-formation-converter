package com.amazonaws.policyconverters.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ServerLifecycleTest {

    @Mock
    private MetricsEmitter metricsEmitter;

    private ServerConfig serverConfig;

    @BeforeEach
    void setUp() {
        serverConfig = new ServerConfig(5, "INFO", "TestNamespace");
    }

    @Test
    void executeCycle_success_logsAndEmitsMetrics() {
        SyncCycleResult result = SyncCycleResult.success(100, 10, 3, 2, 1);
        SyncCycleExecutor executor = () -> result;

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);
        lifecycle.executeCycle();

        verify(metricsEmitter).recordSuccess(any(SyncCycleResult.class));
        verify(metricsEmitter, never()).recordFailure(any());
        assertEquals(1, lifecycle.getCycleCount());
    }

    @Test
    void executeCycle_failure_fromResult_logsAndEmitsFailureMetrics() {
        SyncCycleResult result = SyncCycleResult.failure(50, new RuntimeException("sync error"));
        SyncCycleExecutor executor = () -> result;

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);
        lifecycle.executeCycle();

        verify(metricsEmitter).recordFailure(any(SyncCycleResult.class));
        verify(metricsEmitter, never()).recordSuccess(any());
        assertEquals(1, lifecycle.getCycleCount());
    }

    @Test
    void executeCycle_exception_logsAndEmitsFailureMetrics() {
        SyncCycleExecutor executor = () -> { throw new RuntimeException("connection refused"); };

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);
        lifecycle.executeCycle();

        verify(metricsEmitter).recordFailure(any(SyncCycleResult.class));
        verify(metricsEmitter, never()).recordSuccess(any());
        assertEquals(1, lifecycle.getCycleCount());
    }

    @Test
    void executeCycle_incrementsCycleCounter() {
        SyncCycleResult result = SyncCycleResult.success(10, 1, 0, 0, 0);
        SyncCycleExecutor executor = () -> result;

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);
        lifecycle.executeCycle();
        lifecycle.executeCycle();
        lifecycle.executeCycle();

        assertEquals(3, lifecycle.getCycleCount());
    }

    @Test
    void shutdown_setsRunningToFalse() {
        SyncCycleExecutor executor = () -> SyncCycleResult.success(10, 1, 0, 0, 0);
        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);

        assertTrue(lifecycle.isRunning());
        boolean completed = lifecycle.shutdown();
        assertTrue(completed);
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void shutdown_returnsTrue_whenNoCycleInProgress() {
        SyncCycleExecutor executor = () -> SyncCycleResult.success(10, 1, 0, 0, 0);
        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);

        boolean completed = lifecycle.shutdown();
        assertTrue(completed, "Shutdown should return true when no cycle is in progress");
    }

    @Test
    void run_executesCyclesAndStopsOnShutdown() throws InterruptedException {
        AtomicInteger cycleCount = new AtomicInteger(0);
        CountDownLatch firstCycleDone = new CountDownLatch(1);

        SyncCycleExecutor executor = () -> {
            cycleCount.incrementAndGet();
            firstCycleDone.countDown();
            return SyncCycleResult.success(10, 1, 0, 0, 0);
        };

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 50);

        Thread runThread = new Thread(lifecycle::run);
        runThread.start();

        // Wait for at least one cycle to complete
        assertTrue(firstCycleDone.await(5, TimeUnit.SECONDS), "First cycle should complete");

        // Shutdown
        boolean completed = lifecycle.shutdown();
        assertTrue(completed);

        runThread.join(5000);
        assertFalse(runThread.isAlive(), "Run thread should have exited");
        assertTrue(cycleCount.get() >= 1, "At least one cycle should have executed");
    }

    @Test
    void shutdown_waitsForCurrentCycleToComplete() throws InterruptedException {
        CountDownLatch cycleStarted = new CountDownLatch(1);
        CountDownLatch allowCycleFinish = new CountDownLatch(1);
        AtomicReference<SyncCycleResult> capturedResult = new AtomicReference<>();

        SyncCycleExecutor executor = () -> {
            cycleStarted.countDown();
            try {
                allowCycleFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            SyncCycleResult r = SyncCycleResult.success(200, 5, 2, 1, 0);
            capturedResult.set(r);
            return r;
        };

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 50);

        Thread runThread = new Thread(lifecycle::run);
        runThread.start();

        // Wait for cycle to start
        assertTrue(cycleStarted.await(5, TimeUnit.SECONDS), "Cycle should start");

        // Request shutdown while cycle is in progress
        Thread shutdownThread = new Thread(() -> lifecycle.shutdown());
        shutdownThread.start();

        // Give shutdown a moment to set running=false
        Thread.sleep(100);
        assertFalse(lifecycle.isRunning(), "Running should be false after shutdown request");

        // Let the cycle finish
        allowCycleFinish.countDown();

        shutdownThread.join(5000);
        runThread.join(5000);

        assertFalse(runThread.isAlive());
        assertNotNull(capturedResult.get(), "Cycle should have completed");
        verify(metricsEmitter, atLeastOnce()).recordSuccess(any(SyncCycleResult.class));
    }

    @Test
    void shutdown_timeout_returnsFalse() throws InterruptedException {
        // Use a very short timeout
        ServerConfig shortTimeout = new ServerConfig(1, "INFO", "TestNamespace");
        CountDownLatch cycleStarted = new CountDownLatch(1);

        SyncCycleExecutor executor = () -> {
            cycleStarted.countDown();
            try {
                // Sleep longer than the shutdown timeout
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SyncCycleResult.success(5000, 1, 0, 0, 0);
        };

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, shortTimeout, 50);

        Thread runThread = new Thread(lifecycle::run);
        runThread.start();

        // Wait for cycle to start
        assertTrue(cycleStarted.await(5, TimeUnit.SECONDS));

        // Shutdown should timeout
        boolean completed = lifecycle.shutdown();
        assertFalse(completed, "Shutdown should return false when timeout exceeded");

        // Clean up: interrupt the run thread
        runThread.interrupt();
        runThread.join(5000);
    }

    @Test
    void run_stopsImmediatelyWhenShutdownBeforeFirstCycle() throws InterruptedException {
        AtomicInteger cycleCount = new AtomicInteger(0);
        SyncCycleExecutor executor = () -> {
            cycleCount.incrementAndGet();
            return SyncCycleResult.success(10, 1, 0, 0, 0);
        };

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 50);

        // Shutdown before run
        lifecycle.shutdown();

        Thread runThread = new Thread(lifecycle::run);
        runThread.start();
        runThread.join(3000);

        assertFalse(runThread.isAlive(), "Run thread should exit immediately");
        assertEquals(0, cycleCount.get(), "No cycles should execute after pre-shutdown");
    }

    @Test
    void executeCycle_metricsEmitterException_doesNotCrash() {
        SyncCycleResult result = SyncCycleResult.success(100, 5, 2, 1, 0);
        SyncCycleExecutor executor = () -> result;

        doThrow(new RuntimeException("CloudWatch unavailable"))
                .when(metricsEmitter).recordSuccess(any());

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 1000);

        // Should not throw even if metricsEmitter fails
        assertDoesNotThrow(() -> lifecycle.executeCycle());
        assertEquals(1, lifecycle.getCycleCount());
    }

    @Test
    void multipleCycles_allEmitMetrics() throws InterruptedException {
        AtomicInteger cycleCount = new AtomicInteger(0);
        CountDownLatch threeCycles = new CountDownLatch(3);

        SyncCycleExecutor executor = () -> {
            cycleCount.incrementAndGet();
            threeCycles.countDown();
            return SyncCycleResult.success(10, 1, 0, 0, 0);
        };

        ServerLifecycle lifecycle = new ServerLifecycle(executor, metricsEmitter, serverConfig, 10);

        Thread runThread = new Thread(lifecycle::run);
        runThread.start();

        assertTrue(threeCycles.await(5, TimeUnit.SECONDS), "Three cycles should complete");
        lifecycle.shutdown();
        runThread.join(5000);

        assertTrue(cycleCount.get() >= 3);
        verify(metricsEmitter, atLeast(3)).recordSuccess(any(SyncCycleResult.class));
    }
}
