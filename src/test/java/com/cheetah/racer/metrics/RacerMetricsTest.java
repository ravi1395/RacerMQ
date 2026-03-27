package com.cheetah.racer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RacerMetrics}.
 */
class RacerMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private RacerMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new RacerMetrics(meterRegistry);
    }

    // ── Publish ──────────────────────────────────────────────────────────────

    @Test
    void recordPublished_incrementsCounter() {
        metrics.recordPublished("racer:orders", "pubsub");
        metrics.recordPublished("racer:orders", "pubsub");

        Counter counter = meterRegistry.find("racer.messages.published")
                .tag("channel", "racer:orders")
                .tag("transport", "pubsub")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    // ── Consume ──────────────────────────────────────────────────────────────

    @Test
    void recordConsumed_incrementsCounter() {
        metrics.recordConsumed("racer:orders", "pubsub");

        Counter counter = meterRegistry.find("racer.messages.consumed")
                .tag("channel", "racer:orders")
                .tag("mode", "pubsub")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordFailed_incrementsCounter() {
        metrics.recordFailed("racer:orders", "NullPointerException");

        Counter counter = meterRegistry.find("racer.messages.failed")
                .tag("channel", "racer:orders")
                .tag("exception", "NullPointerException")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── DLQ ──────────────────────────────────────────────────────────────────

    @Test
    void recordDlqReprocessed_incrementsCounter() {
        metrics.recordDlqReprocessed();
        metrics.recordDlqReprocessed();

        Counter counter = meterRegistry.find("racer.dlq.reprocessed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void registerDlqSizeGauge_tracksValue() {
        AtomicLong size = new AtomicLong(42);
        metrics.registerDlqSizeGauge(size::get);

        Gauge gauge = meterRegistry.find("racer.dlq.size").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);

        size.set(100);
        assertThat(gauge.value()).isEqualTo(100.0);
    }

    // ── Request-reply latency ────────────────────────────────────────────────

    @Test
    void requestReplyTimer_recordsLatency() {
        Timer.Sample sample = metrics.startRequestReplyTimer();
        metrics.stopRequestReplyTimer(sample, "pubsub");

        Timer timer = meterRegistry.find("racer.request.reply.latency")
                .tag("transport", "pubsub")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    // ── Thread pool gauges ───────────────────────────────────────────────────

    @Test
    void registerThreadPoolGauges_exposesMetrics() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));

        metrics.registerThreadPoolGauges(executor);

        Gauge queueDepth = meterRegistry.find("racer.thread-pool.queue-depth").gauge();
        Gauge activeThreads = meterRegistry.find("racer.thread-pool.active-threads").gauge();
        Gauge poolSize = meterRegistry.find("racer.thread-pool.pool-size").gauge();

        assertThat(queueDepth).isNotNull();
        assertThat(activeThreads).isNotNull();
        assertThat(poolSize).isNotNull();

        executor.shutdown();
    }

    // ── Adaptive concurrency gauge ───────────────────────────────────────────

    @Test
    void registerAutoConcurrencyGauge_tracksValue() {
        AtomicLong concurrency = new AtomicLong(8);
        metrics.registerAutoConcurrencyGauge("my-listener", concurrency::get);

        Gauge gauge = meterRegistry.find("racer.auto.concurrency")
                .tag("listener", "my-listener")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(8.0);
    }

    // ── Consumer lag gauge ───────────────────────────────────────────────────

    @Test
    void registerStreamConsumerLagGauge_tracksValue() {
        AtomicLong lag = new AtomicLong(500);
        metrics.registerStreamConsumerLagGauge("stream:orders/group1", lag::get);

        Gauge gauge = meterRegistry.find("racer.stream.consumer.lag")
                .tag("stream", "stream:orders/group1")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(500.0);
    }

    // ── Circuit breaker metrics ──────────────────────────────────────────────

    @Test
    void registerCircuitBreakerStateGauge_tracksValue() {
        AtomicLong state = new AtomicLong(0);
        metrics.registerCircuitBreakerStateGauge("listener1", state::get);

        Gauge gauge = meterRegistry.find("racer.circuit.breaker.state")
                .tag("listener", "listener1")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);

        state.set(1);
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void recordCircuitBreakerTransition_incrementsCounter() {
        metrics.recordCircuitBreakerTransition("listener1", "CLOSED", "OPEN");

        Counter counter = meterRegistry.find("racer.circuit.breaker.transitions")
                .tag("listener", "listener1")
                .tag("from", "CLOSED")
                .tag("to", "OPEN")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordCircuitBreakerRejection_incrementsCounter() {
        metrics.recordCircuitBreakerRejection("listener1");

        Counter counter = meterRegistry.find("racer.circuit.breaker.rejected")
                .tag("listener", "listener1")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── Back-pressure metrics ────────────────────────────────────────────────

    @Test
    void registerBackPressureActiveGauge_tracksValue() {
        AtomicLong active = new AtomicLong(1);
        metrics.registerBackPressureActiveGauge(active::get);

        Gauge gauge = meterRegistry.find("racer.backpressure.active").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void recordBackPressureEvent_incrementsCounter() {
        metrics.recordBackPressureEvent("active");

        Counter counter = meterRegistry.find("racer.backpressure.events")
                .tag("state", "active")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordBackPressureDrop_incrementsCounter() {
        metrics.recordBackPressureDrop("listener1");

        Counter counter = meterRegistry.find("racer.backpressure.drops")
                .tag("listener", "listener1")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── Dedup metrics ────────────────────────────────────────────────────────

    @Test
    void recordDedupDuplicate_incrementsCounter() {
        metrics.recordDedupDuplicate("listener1");

        Counter counter = meterRegistry.find("racer.dedup.duplicates")
                .tag("listener", "listener1")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void initializeDedupCounter_preRegistersAtZero() {
        metrics.initializeDedupCounter("listener1");

        Counter counter = meterRegistry.find("racer.dedup.duplicates")
                .tag("listener", "listener1")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }
}
