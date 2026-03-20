package com.cheetah.racer.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Micrometer-based operational metrics for the Racer framework.
 *
 * <p>Registered as a Spring bean only when {@code io.micrometer.core.instrument.MeterRegistry}
 * is present on the classpath (i.e. when {@code spring-boot-starter-actuator} is a dependency).
 * Services that optionally use metrics should inject {@code Optional<RacerMetrics>} or
 * declare the field with {@code @Autowired(required = false)}.
 *
 * <h3>Exposed metrics</h3>
 * <table>
 * <tr><th>Metric</th><th>Type</th><th>Tags</th></tr>
 * <tr><td>racer.messages.published</td><td>Counter</td><td>channel, transport</td></tr>
 * <tr><td>racer.messages.consumed</td><td>Counter</td><td>channel, mode</td></tr>
 * <tr><td>racer.messages.failed</td><td>Counter</td><td>channel, exception</td></tr>
 * <tr><td>racer.dlq.size</td><td>Gauge</td><td>-</td></tr>
 * <tr><td>racer.dlq.reprocessed</td><td>Counter</td><td>-</td></tr>
 * <tr><td>racer.request.reply.latency</td><td>Timer</td><td>transport</td></tr>
 * </table>
 */
@Slf4j
public class RacerMetrics implements RacerMetricsPort {

    private final MeterRegistry registry;

    public RacerMetrics(MeterRegistry registry) {
        this.registry = registry;
        log.info("[racer-metrics] Micrometer metrics enabled — {} meter registry",
                registry.getClass().getSimpleName());
    }

    // -----------------------------------------------------------------------
    // Publish metrics
    // -----------------------------------------------------------------------

    /**
     * Increments {@code racer.messages.published}.
     *
     * @param channel   Redis channel name, e.g. {@code racer:orders}
     * @param transport {@code "pubsub"} or {@code "stream"}
     */
    public void recordPublished(String channel, String transport) {
        Counter.builder("racer.messages.published")
                .description("Number of messages published by Racer")
                .tag("channel", channel)
                .tag("transport", transport)
                .register(registry)
                .increment();
    }

    // -----------------------------------------------------------------------
    // Consume metrics
    // -----------------------------------------------------------------------

    /** Increments {@code racer.messages.consumed}. */
    public void recordConsumed(String channel, String mode) {
        Counter.builder("racer.messages.consumed")
                .description("Number of messages successfully consumed")
                .tag("channel", channel)
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    /** Increments {@code racer.messages.failed}. */
    public void recordFailed(String channel, String exceptionClass) {
        Counter.builder("racer.messages.failed")
                .description("Number of messages that failed processing")
                .tag("channel", channel)
                .tag("exception", exceptionClass)
                .register(registry)
                .increment();
    }

    // -----------------------------------------------------------------------
    // DLQ metrics
    // -----------------------------------------------------------------------

    /** Increments {@code racer.dlq.reprocessed}. */
    public void recordDlqReprocessed() {
        Counter.builder("racer.dlq.reprocessed")
                .description("Number of DLQ messages that were reprocessed")
                .register(registry)
                .increment();
    }

    /**
     * Registers a gauge that tracks DLQ depth by calling {@code sizeSupplier} on each scrape.
     * Should be called once at startup.
     *
     * @param sizeSupplier supplier that returns the current DLQ size
     */
    public void registerDlqSizeGauge(Supplier<Number> sizeSupplier) {
        Gauge.builder("racer.dlq.size", sizeSupplier, s -> s.get().doubleValue())
                .description("Current number of messages in the Dead Letter Queue")
                .register(registry);
    }

    // -----------------------------------------------------------------------
    // Request-reply latency
    // -----------------------------------------------------------------------

    /**
     * Starts a latency timer sample.
     * Call {@link #stopRequestReplyTimer(Timer.Sample, String)} when the reply arrives.
     */
    public Timer.Sample startRequestReplyTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops the sample and records elapsed time to {@code racer.request.reply.latency}.
     *
     * @param sample    the sample returned by {@link #startRequestReplyTimer()}
     * @param transport {@code "pubsub"} or {@code "stream"}
     */
    public void stopRequestReplyTimer(Timer.Sample sample, String transport) {
        sample.stop(Timer.builder("racer.request.reply.latency")
                .description("Round-trip latency for Racer request-reply")
                .tag("transport", transport)
                .register(registry));
    }

    // -----------------------------------------------------------------------
    // Thread pool metrics
    // -----------------------------------------------------------------------

    /**
     * Registers gauges that expose key metrics from the Racer listener thread pool.
     * Should be called once at startup after the pool is created.
     *
     * <p>Exposed metrics:
     * <ul>
     *   <li>{@code racer.thread-pool.queue-depth} — current work-queue size</li>
     *   <li>{@code racer.thread-pool.active-threads} — threads actively executing tasks</li>
     *   <li>{@code racer.thread-pool.pool-size} — current total thread count in the pool</li>
     * </ul>
     *
     * @param executor the {@link java.util.concurrent.ThreadPoolExecutor} backing the listener scheduler
     */
    public void registerThreadPoolGauges(java.util.concurrent.ThreadPoolExecutor executor) {
        Gauge.builder("racer.thread-pool.queue-depth", executor, e -> e.getQueue().size())
                .description("Work-queue depth of the Racer listener thread pool")
                .register(registry);
        Gauge.builder("racer.thread-pool.active-threads", executor,
                        java.util.concurrent.ThreadPoolExecutor::getActiveCount)
                .description("Number of threads actively executing tasks in the Racer listener pool")
                .register(registry);
        Gauge.builder("racer.thread-pool.pool-size", executor,
                        java.util.concurrent.ThreadPoolExecutor::getPoolSize)
                .description("Current number of threads in the Racer listener thread pool")
                .register(registry);
    }

    // -----------------------------------------------------------------------
    // Adaptive concurrency (AUTO-mode) metrics
    // -----------------------------------------------------------------------

    /**
     * Registers a gauge reporting the current effective concurrency of an AUTO-mode listener.
     *
     * @param listenerId   the listener id (from {@code @RacerListener(id="…")}),
     *                     used as the {@code listener} tag value
     * @param concurrencySupplier supplies the current concurrency value on each scrape
     */
    public void registerAutoConcurrencyGauge(String listenerId,
                                              Supplier<Number> concurrencySupplier) {
        Gauge.builder("racer.auto.concurrency", concurrencySupplier, s -> s.get().doubleValue())
                .description("Current adaptive concurrency level for an AUTO-mode listener")
                .tag("listener", listenerId)
                .register(registry);
    }

    // -----------------------------------------------------------------------
    // Stream consumer-lag metric
    // -----------------------------------------------------------------------

    /**
     * Registers a gauge that reports the consumer-group lag for a Redis stream
     * (i.e. the number of pending/unacknowledged entries, as returned by XPENDING).
     *
     * @param streamKey the Redis stream key, used as the {@code stream} tag value
     * @param lagSupplier supplies the current lag on each scrape (call XPENDING count)
     */
    public void registerStreamConsumerLagGauge(String streamKey, Supplier<Number> lagSupplier) {
        Gauge.builder("racer.stream.consumer.lag", lagSupplier, s -> s.get().doubleValue())
                .description("Number of pending (unacknowledged) entries in a Racer stream consumer group")
                .tag("stream", streamKey)
                .register(registry);
    }

    // -----------------------------------------------------------------------
    // Circuit breaker metrics
    // -----------------------------------------------------------------------

    /**
     * Registers a gauge that reports the circuit breaker state for a listener.
     * Encoded as: {@code 0 = CLOSED}, {@code 1 = OPEN}, {@code 2 = HALF_OPEN}.
     *
     * <p>The gauge uses a strong reference to the supplied {@code stateSupplier} to
     * prevent premature garbage collection that would cause NaN readings.
     *
     * @param listenerId    the listener ID, used as the {@code listener} tag value
     * @param stateSupplier supplies the numeric state on each scrape
     */
    public void registerCircuitBreakerStateGauge(String listenerId, Supplier<Number> stateSupplier) {
        // Use Tags-based registration and keep a strong reference to the supplier
        // so Micrometer's weak-reference gauge does not get GC'd (which causes NaN).
        Gauge.builder("racer.circuit.breaker.state", stateSupplier, s -> {
                    Number val = s.get();
                    return val != null ? val.doubleValue() : 0.0;
                })
                .description("Circuit breaker state per listener: 0=CLOSED 1=OPEN 2=HALF_OPEN")
                .tag("listener", listenerId)
                .strongReference(true)
                .register(registry);
    }

    /**
     * Increments {@code racer.circuit.breaker.transitions} for the given listener,
     * tagged with the old and new state (e.g. CLOSED→OPEN).
     */
    @Override
    public void recordCircuitBreakerTransition(String listenerId, String fromState, String toState) {
        Counter.builder("racer.circuit.breaker.transitions")
                .description("Number of circuit breaker state transitions")
                .tag("listener", listenerId)
                .tag("from", fromState)
                .tag("to", toState)
                .register(registry)
                .increment();
    }

    /**
     * Increments {@code racer.circuit.breaker.rejected} for the given listener.
     */
    @Override
    public void recordCircuitBreakerRejection(String listenerId) {
        Counter.builder("racer.circuit.breaker.rejected")
                .description("Number of calls rejected by an open circuit breaker")
                .tag("listener", listenerId)
                .register(registry)
                .increment();
    }

    // -----------------------------------------------------------------------
    // Back-pressure metrics
    // -----------------------------------------------------------------------

    /**
     * Registers a gauge that reports whether back-pressure is currently active.
     * Value is {@code 1.0} when active and {@code 0.0} when inactive.
     * Should be called once at startup.
     *
     * @param activeSupplier supplier that returns 1 (active) or 0 (inactive)
     */
    public void registerBackPressureActiveGauge(Supplier<Number> activeSupplier) {
        Gauge.builder("racer.backpressure.active", activeSupplier, s -> s.get().doubleValue())
                .description("1 if Racer back-pressure is currently active, 0 otherwise")
                .register(registry);
    }

    /**
     * Increments {@code racer.backpressure.events} with the given state tag.
     *
     * @param state {@code "active"} when back-pressure activates,
     *              {@code "inactive"} when it is relieved
     */
    public void recordBackPressureEvent(String state) {
        Counter.builder("racer.backpressure.events")
                .description("Number of back-pressure activation / deactivation transitions")
                .tag("state", state)
                .register(registry)
                .increment();
    }

    /**
     * Increments {@code racer.backpressure.drops} — a message was routed to the DLQ
     * because the listener's thread pool was saturated.
     */
    @Override
    public void recordBackPressureDrop(String listenerId) {
        Counter.builder("racer.backpressure.drops")
                .description("Messages dropped to DLQ due to back-pressure")
                .tag("listener", listenerId)
                .register(registry)
                .increment();
    }

    // -----------------------------------------------------------------------
    // Deduplication metrics
    // -----------------------------------------------------------------------

    /**
     * Increments {@code racer.dedup.duplicates} — a message with the given listener
     * tag was suppressed because it was already processed within the dedup TTL window.
     *
     * @param listenerId the listener ID, used as the {@code listener} tag value
     */
    public void recordDedupDuplicate(String listenerId) {
        Counter.builder("racer.dedup.duplicates")
                .description("Number of duplicate messages suppressed by the dedup service")
                .tag("listener", listenerId)
                .register(registry)
                .increment();
    }

    /**
     * Pre-registers the {@code racer.dedup.duplicates} counter at zero for the given
     * listener so it is immediately visible in {@code /actuator/metrics} even before
     * the first duplicate is detected.
     *
     * @param listenerId the listener ID, used as the {@code listener} tag value
     */
    @Override
    public void initializeDedupCounter(String listenerId) {
        Counter.builder("racer.dedup.duplicates")
                .description("Number of duplicate messages suppressed by the dedup service")
                .tag("listener", listenerId)
                .register(registry);
    }
}
