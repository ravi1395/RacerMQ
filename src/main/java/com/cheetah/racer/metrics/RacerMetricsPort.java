package com.cheetah.racer.metrics;

import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * Interface for Racer operational metrics.
 *
 * <p>Implemented by {@link RacerMetrics} (backed by Micrometer) and {@link NoOpRacerMetrics}
 * (no-op, used when Micrometer is absent on the classpath).
 *
 * <p>All Racer components depend on this interface rather than the concrete class,
 * eliminating {@code if (metrics != null)} null-guards at every call-site.
 */
public interface RacerMetricsPort {

    void recordPublished(String channel, String transport);

    void recordConsumed(String channel, String mode);

    void recordFailed(String channel, String exceptionClass);

    void recordDlqReprocessed();

    void registerDlqSizeGauge(Supplier<Number> sizeSupplier);

    Timer.Sample startRequestReplyTimer();

    void stopRequestReplyTimer(Timer.Sample sample, String transport);

    void registerThreadPoolGauges(ThreadPoolExecutor executor);

    void registerAutoConcurrencyGauge(String listenerId, Supplier<Number> concurrencySupplier);

    void registerStreamConsumerLagGauge(String streamKey, Supplier<Number> lagSupplier);

    void registerCircuitBreakerStateGauge(String listenerId, Supplier<Number> stateSupplier);

    void registerBackPressureActiveGauge(Supplier<Number> activeSupplier);

    void recordBackPressureEvent(String state);

    void recordDedupDuplicate(String listenerId);
}
