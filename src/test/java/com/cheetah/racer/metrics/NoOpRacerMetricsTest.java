package com.cheetah.racer.metrics;

import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link NoOpRacerMetrics}.
 * Every method must be callable without side-effects.
 */
class NoOpRacerMetricsTest {

    private final NoOpRacerMetrics noOp = new NoOpRacerMetrics();

    @Test
    void recordPublished_doesNotThrow() {
        assertThatCode(() -> noOp.recordPublished("ch", "pubsub")).doesNotThrowAnyException();
    }

    @Test
    void recordConsumed_doesNotThrow() {
        assertThatCode(() -> noOp.recordConsumed("ch", "pubsub")).doesNotThrowAnyException();
    }

    @Test
    void recordFailed_doesNotThrow() {
        assertThatCode(() -> noOp.recordFailed("ch", "NullPointerException")).doesNotThrowAnyException();
    }

    @Test
    void recordDlqReprocessed_doesNotThrow() {
        assertThatCode(() -> noOp.recordDlqReprocessed()).doesNotThrowAnyException();
    }

    @Test
    void registerDlqSizeGauge_doesNotThrow() {
        assertThatCode(() -> noOp.registerDlqSizeGauge(() -> 0)).doesNotThrowAnyException();
    }

    @Test
    void startRequestReplyTimer_returnsSample() {
        Timer.Sample sample = noOp.startRequestReplyTimer();
        assertThat(sample).isNotNull();
    }

    @Test
    void stopRequestReplyTimer_doesNotThrow() {
        Timer.Sample sample = noOp.startRequestReplyTimer();
        assertThatCode(() -> noOp.stopRequestReplyTimer(sample, "pubsub")).doesNotThrowAnyException();
    }

    @Test
    void registerThreadPoolGauges_doesNotThrow() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        assertThatCode(() -> noOp.registerThreadPoolGauges(executor)).doesNotThrowAnyException();
        executor.shutdown();
    }

    @Test
    void registerAutoConcurrencyGauge_doesNotThrow() {
        assertThatCode(() -> noOp.registerAutoConcurrencyGauge("id", () -> 1)).doesNotThrowAnyException();
    }

    @Test
    void registerStreamConsumerLagGauge_doesNotThrow() {
        assertThatCode(() -> noOp.registerStreamConsumerLagGauge("key", () -> 0)).doesNotThrowAnyException();
    }

    @Test
    void registerCircuitBreakerStateGauge_doesNotThrow() {
        assertThatCode(() -> noOp.registerCircuitBreakerStateGauge("id", () -> 0)).doesNotThrowAnyException();
    }

    @Test
    void recordCircuitBreakerTransition_doesNotThrow() {
        assertThatCode(() -> noOp.recordCircuitBreakerTransition("id", "CLOSED", "OPEN")).doesNotThrowAnyException();
    }

    @Test
    void recordCircuitBreakerRejection_doesNotThrow() {
        assertThatCode(() -> noOp.recordCircuitBreakerRejection("id")).doesNotThrowAnyException();
    }

    @Test
    void registerBackPressureActiveGauge_doesNotThrow() {
        assertThatCode(() -> noOp.registerBackPressureActiveGauge(() -> 1)).doesNotThrowAnyException();
    }

    @Test
    void recordBackPressureEvent_doesNotThrow() {
        assertThatCode(() -> noOp.recordBackPressureEvent("active")).doesNotThrowAnyException();
    }

    @Test
    void recordBackPressureDrop_doesNotThrow() {
        assertThatCode(() -> noOp.recordBackPressureDrop("id")).doesNotThrowAnyException();
    }

    @Test
    void recordDedupDuplicate_doesNotThrow() {
        assertThatCode(() -> noOp.recordDedupDuplicate("id")).doesNotThrowAnyException();
    }

    @Test
    void initializeDedupCounter_doesNotThrow() {
        assertThatCode(() -> noOp.initializeDedupCounter("id")).doesNotThrowAnyException();
    }
}
