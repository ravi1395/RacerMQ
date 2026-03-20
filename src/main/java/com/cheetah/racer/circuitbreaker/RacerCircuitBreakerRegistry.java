package com.cheetah.racer.circuitbreaker;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory and cache for per-listener {@link RacerCircuitBreaker} instances.
 *
 * <p>One breaker is created lazily per listener ID on the first call to
 * {@link #getOrCreate(String)}. All settings are sourced from
 * {@code racer.circuit-breaker.*} properties and are shared across all
 * breakers created by this registry.
 *
 * <p>Registered as a bean when {@code racer.circuit-breaker.enabled=true}.
 */
@Slf4j
public class RacerCircuitBreakerRegistry {

    private final RacerProperties racerProperties;
    private final RacerMetricsPort racerMetrics;
    private final ConcurrentHashMap<String, RacerCircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** Backward-compatible constructor (no metrics). */
    public RacerCircuitBreakerRegistry(RacerProperties racerProperties) {
        this(racerProperties, null);
    }

    public RacerCircuitBreakerRegistry(RacerProperties racerProperties, @Nullable RacerMetrics racerMetrics) {
        this.racerProperties = racerProperties;
        this.racerMetrics    = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
    }

    /**
     * Returns the circuit breaker for the given listener ID, creating it if necessary.
     * When {@link RacerMetrics} is available a {@code racer.circuit.breaker.state} gauge
     * (tagged {@code listener=listenerId}) is registered for the new breaker.
     * State encoding: {@code 0 = CLOSED}, {@code 1 = OPEN}, {@code 2 = HALF_OPEN}.
     *
     * @param listenerId the listener ID (from {@code @RacerListener(id="…")} or
     *                   {@code "<beanName>.<methodName>"})
     * @return the circuit breaker, never {@code null}
     */
    public RacerCircuitBreaker getOrCreate(String listenerId) {
        return breakers.computeIfAbsent(listenerId, id -> {
            RacerProperties.CircuitBreakerProperties cfg = racerProperties.getCircuitBreaker();
            log.info("[CIRCUIT-BREAKER] Creating breaker for '{}' — threshold={}% window={} waitSeconds={}",
                    id, cfg.getFailureRateThreshold(), cfg.getSlidingWindowSize(),
                    cfg.getWaitDurationInOpenStateSeconds());
            RacerCircuitBreaker cb = new RacerCircuitBreaker(
                    id,
                    cfg.getSlidingWindowSize(),
                    cfg.getFailureRateThreshold(),
                    cfg.getWaitDurationInOpenStateSeconds() * 1000L,
                    cfg.getPermittedCallsInHalfOpenState());
            // State ordinals: CLOSED=0, OPEN=1, HALF_OPEN=2
            racerMetrics.registerCircuitBreakerStateGauge(id, () -> cb.getState().ordinal());
            // Expose additional counters for observability
            // Transition and rejection counters are recorded inline by the breaker itself
            // and surfaced via Micrometer by periodic recording in the caller's dispatch path.
            // For callers using the metrics port directly, the breaker exposes
            //   getTransitionCount() and getRejectedCount() for gauge-style scraping.
            return cb;
        });
    }

    /**
     * Returns all registered circuit breakers.
     * Useful for health-check and JMX exposure.
     */
    public Collection<RacerCircuitBreaker> getAll() {
        return breakers.values();
    }
}
