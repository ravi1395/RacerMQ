package com.cheetah.racer.metrics;

import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * No-op implementation of {@link RacerMetricsPort}.
 *
 * <p>Used as a zero-overhead placeholder when Micrometer ({@code spring-boot-starter-actuator})
 * is not on the classpath. Injected automatically by {@code RacerAutoConfiguration} in place
 * of {@code null}, so that all Racer components can call metrics methods unconditionally.
 */
public final class NoOpRacerMetrics implements RacerMetricsPort {

    @Override public void recordPublished(String channel, String transport) {}
    @Override public void recordConsumed(String channel, String mode) {}
    @Override public void recordFailed(String channel, String exceptionClass) {}
    @Override public void recordDlqReprocessed() {}
    @Override public void registerDlqSizeGauge(Supplier<Number> sizeSupplier) {}
    @Override public Timer.Sample startRequestReplyTimer() { return Timer.start(io.micrometer.core.instrument.Clock.SYSTEM); }
    @Override public void stopRequestReplyTimer(Timer.Sample sample, String transport) {}
    @Override public void registerThreadPoolGauges(ThreadPoolExecutor executor) {}
    @Override public void registerAutoConcurrencyGauge(String listenerId, Supplier<Number> concurrencySupplier) {}
    @Override public void registerStreamConsumerLagGauge(String streamKey, Supplier<Number> lagSupplier) {}
    @Override public void registerCircuitBreakerStateGauge(String listenerId, Supplier<Number> stateSupplier) {}
    @Override public void recordCircuitBreakerTransition(String listenerId, String fromState, String toState) {}
    @Override public void recordCircuitBreakerRejection(String listenerId) {}
    @Override public void registerBackPressureActiveGauge(Supplier<Number> activeSupplier) {}
    @Override public void recordBackPressureEvent(String state) {}
    @Override public void recordBackPressureDrop(String listenerId) {}
    @Override public void recordDedupDuplicate(String listenerId) {}
    @Override public void initializeDedupCounter(String listenerId) {}
}
