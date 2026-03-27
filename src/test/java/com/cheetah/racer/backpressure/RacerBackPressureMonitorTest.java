package com.cheetah.racer.backpressure;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerBackPressureMonitor}.
 *
 * <p>The monitor reads the queue state from a real {@link ThreadPoolExecutor} — no mock
 * needed there. Only the downstream registrar calls are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerBackPressureMonitorTest {

    @Mock RacerListenerRegistrar        listenerRegistrar;
    @Mock RacerStreamListenerRegistrar  streamListenerRegistrar;

    /** Tiny queue (capacity 10) so we can fill it easily in tests. */
    private static final int QUEUE_CAPACITY = 10;

    private ThreadPoolExecutor executor;
    private RacerProperties    props;

    @BeforeEach
    void setUp() {
        // Use a queue that we can manually fill for threshold testing
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);

        props = new RacerProperties();
        props.getThreadPool().setQueueCapacity(QUEUE_CAPACITY);
        props.getBackpressure().setEnabled(true);
        props.getBackpressure().setQueueThreshold(0.80);        // 80% → 8 of 10
        props.getBackpressure().setCheckIntervalMs(1_000);
        props.getBackpressure().setStreamPollBackoffMs(2_000);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ── start() / stop() lifecycle ────────────────────────────────────────────

    @Test
    void start_registersGaugeAndStartsMonitorLoop() {
        RacerBackPressureMonitor monitor = buildMonitor();
        monitor.start();
        // After starting the monitor should still report inactive (queue is empty)
        assertThat(monitor.isActive()).isFalse();
        monitor.stop();
    }

    @Test
    void stop_disposesMonitorLoop_withoutThrowing() {
        RacerBackPressureMonitor monitor = buildMonitor();
        monitor.start();
        // Should not throw
        monitor.stop();
    }

    @Test
    void stop_beforeStart_doesNotThrow() {
        RacerBackPressureMonitor monitor = buildMonitor();
        // stop() before start() — monitorLoop is null, must be handled gracefully
        monitor.stop();
    }

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    void initiallyNotActive() {
        RacerBackPressureMonitor monitor = buildMonitor();
        assertThat(monitor.isActive()).isFalse();
    }

    // ── activation ────────────────────────────────────────────────────────────

    @Test
    void activates_whenQueueFillRatioMeetsThreshold() {
        // Fill 8 of 10 slots → 80% = exactly at threshold
        fillQueue(8);

        RacerBackPressureMonitor monitor = buildMonitor();
        invokeCheckAndApply(monitor);

        assertThat(monitor.isActive()).isTrue();
    }

    @Test
    void activates_notifiesBothRegistrars() {
        fillQueue(9); // 90% > 80%

        RacerBackPressureMonitor monitor = buildMonitor();
        invokeCheckAndApply(monitor);

        verify(listenerRegistrar).setBackPressureActive(true);
        verify(streamListenerRegistrar).setBackPressurePollIntervalMs(2_000);
    }

    // ── deactivation ──────────────────────────────────────────────────────────

    @Test
    void deactivates_whenQueueFallsBelowThreshold() {
        // First activation
        fillQueue(9);
        RacerBackPressureMonitor monitor = buildMonitor();
        invokeCheckAndApply(monitor);
        assertThat(monitor.isActive()).isTrue();

        // Drain the queue so ratio drops below 80%
        drainQueue();
        invokeCheckAndApply(monitor);

        assertThat(monitor.isActive()).isFalse();
    }

    @Test
    void deactivates_notifiesBothRegistrars() {
        fillQueue(9);
        RacerBackPressureMonitor monitor = buildMonitor();
        invokeCheckAndApply(monitor); // activate first

        drainQueue();
        invokeCheckAndApply(monitor); // then deactivate

        verify(listenerRegistrar).setBackPressureActive(false);
        // 0 = revert to annotation-defined interval
        verify(streamListenerRegistrar).setBackPressurePollIntervalMs(0);
    }

    // ── no duplicate notifications ────────────────────────────────────────────

    @Test
    void doesNotNotifyRepeatedly_whenStateUnchanged() {
        fillQueue(9);
        RacerBackPressureMonitor monitor = buildMonitor();

        invokeCheckAndApply(monitor); // activate
        invokeCheckAndApply(monitor); // still above threshold — no second notification
        invokeCheckAndApply(monitor);

        verify(listenerRegistrar, times(1)).setBackPressureActive(true);
    }

    // ── below threshold — never activates ─────────────────────────────────────

    @Test
    void doesNotActivate_whenQueueBelowThreshold() {
        fillQueue(5); // 50% < 80%

        RacerBackPressureMonitor monitor = buildMonitor();
        invokeCheckAndApply(monitor);

        assertThat(monitor.isActive()).isFalse();
        verifyNoInteractions(listenerRegistrar, streamListenerRegistrar);
    }

    // ── null registrars (optional beans) ─────────────────────────────────────

    @Test
    void handlesNullRegistrars_gracefully() {
        fillQueue(9);
        RacerBackPressureMonitor monitor = new RacerBackPressureMonitor(
                executor, props, null, null, null);

        // Should not throw even when both registrars are absent
        invokeCheckAndApply(monitor);

        assertThat(monitor.isActive()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RacerBackPressureMonitor buildMonitor() {
        return new RacerBackPressureMonitor(
                executor, props, listenerRegistrar, streamListenerRegistrar, null);
    }

    private void fillQueue(int count) {
        // Submit tasks that block indefinitely so they stay in the queue
        // Core pool = 1, so first task runs; remaining go to queue
        for (int i = 0; i < count + 1; i++) {
            executor.submit(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) {}
            });
        }
        // Wait briefly for tasks to reach the queue
        awaitQueueSize(count);
    }

    private void drainQueue() {
        executor.getQueue().clear();
    }

    private void awaitQueueSize(int expected) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (executor.getQueue().size() < expected && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    /**
     * Invokes the package-private {@code checkAndApply()} via reflection so that the
     * test does not depend on the Flux.interval loop timing.
     */
    private void invokeCheckAndApply(RacerBackPressureMonitor monitor) {
        try {
            var method = RacerBackPressureMonitor.class.getDeclaredMethod("checkAndApply");
            method.setAccessible(true);
            method.invoke(monitor);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke checkAndApply", e);
        }
    }
}
