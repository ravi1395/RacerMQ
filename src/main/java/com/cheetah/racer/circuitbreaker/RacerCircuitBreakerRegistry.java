package com.cheetah.racer.circuitbreaker;

import com.cheetah.racer.config.RacerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
@RequiredArgsConstructor
public class RacerCircuitBreakerRegistry {

    private final RacerProperties racerProperties;
    private final ConcurrentHashMap<String, RacerCircuitBreaker> breakers = new ConcurrentHashMap<>();

    /**
     * Returns the circuit breaker for the given listener ID, creating it if necessary.
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
            return new RacerCircuitBreaker(
                    id,
                    cfg.getSlidingWindowSize(),
                    cfg.getFailureRateThreshold(),
                    cfg.getWaitDurationInOpenStateSeconds() * 1000L,
                    cfg.getPermittedCallsInHalfOpenState());
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
