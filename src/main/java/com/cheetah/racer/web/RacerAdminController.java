package com.cheetah.racer.web;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.ratelimit.RacerRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 4.4 — Racer Admin UI REST controller.
 *
 * <p>Exposes aggregated admin endpoints for the embedded Racer web console
 * and for monitoring / automation tooling.
 *
 * <p>Only registered when {@code racer.web.admin-enabled=true}.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/admin/overview} — system-level summary (version, timestamp, feature flags)</li>
 *   <li>{@code GET /api/admin/channels} — list of registered publish channels</li>
 *   <li>{@code GET /api/admin/circuitbreakers} — circuit breaker states (if enabled)</li>
 *   <li>{@code GET /api/admin/ratelimits} — rate-limit configuration snapshot (if enabled)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class RacerAdminController {

    private final RacerPublisherRegistry publisherRegistry;
    private final RacerProperties racerProperties;

    @Nullable
    private final RacerCircuitBreakerRegistry circuitBreakerRegistry;

    @Nullable
    private final RacerRateLimiter rateLimiter;

    // ── Overview ─────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/overview
     *
     * <p>Returns a high-level snapshot:
     * <ul>
     *   <li>timestamp</li>
     *   <li>feature flags currently active (tracing, rate-limit, circuit-breaker, sharding)</li>
     *   <li>registered channel count</li>
     * </ul>
     */
    @GetMapping("/overview")
    public Mono<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp",       Instant.now().toString());
        result.put("channelCount",    publisherRegistry.getAll().size());

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("tracing",        racerProperties.getTracing().isEnabled());
        features.put("rateLimit",      racerProperties.getRateLimit().isEnabled());
        features.put("circuitBreaker", racerProperties.getCircuitBreaker().isEnabled());
        features.put("sharding",       racerProperties.getSharding().isEnabled());
        features.put("consistentHash", racerProperties.getSharding().isConsistentHashEnabled());
        features.put("backpressure",   racerProperties.getBackpressure().isEnabled());
        features.put("dedup",          racerProperties.getDedup().isEnabled());
        features.put("consumerLag",    racerProperties.getConsumerLag().isEnabled());
        result.put("features", features);

        return Mono.just(result);
    }

    // ── Channels ─────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/channels
     *
     * <p>Lists every channel registered in the publisher registry with its alias
     * and resolved Redis channel / stream key.
     */
    @GetMapping("/channels")
    public Mono<Map<String, Object>> channels() {
        Map<String, Object> channels = publisherRegistry.getAll().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            Map<String, String> info = new LinkedHashMap<>();
                            info.put("alias",   e.getKey());
                            info.put("channel", e.getValue().getChannelName());
                            return info;
                        },
                        (a, b) -> a,
                        LinkedHashMap::new));
        return Mono.just(Map.of("channels", channels, "count", channels.size()));
    }

    // ── Circuit breakers ──────────────────────────────────────────────────────

    /**
     * GET /api/admin/circuitbreakers
     *
     * <p>Returns the current state of every registered circuit breaker.
     * When circuit breakers are not enabled, returns an empty list.
     */
    @GetMapping("/circuitbreakers")
    public Mono<Map<String, Object>> circuitBreakers() {
        if (circuitBreakerRegistry == null) {
            return Mono.just(Map.of("enabled", false, "circuitBreakers", Map.of()));
        }
        Map<String, Object> breakers = new LinkedHashMap<>();
        circuitBreakerRegistry.getAll().forEach(cb -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("state",           cb.getState().name());
            info.put("transitionCount", cb.getTransitionCount());
            info.put("rejectedCount",   cb.getRejectedCount());
            breakers.put(cb.getName(), info);
        });
        return Mono.just(Map.of("enabled", true, "circuitBreakers", breakers));
    }

    // ── Rate limits ───────────────────────────────────────────────────────────

    /**
     * GET /api/admin/ratelimits
     *
     * <p>Returns the rate-limit configuration snapshot.
     * When rate limiting is not enabled, returns the disabled status.
     */
    @GetMapping("/ratelimits")
    public Mono<Map<String, Object>> rateLimits() {
        RacerProperties.RateLimitProperties props = racerProperties.getRateLimit();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled",            props.isEnabled());
        result.put("defaultCapacity",    props.getDefaultCapacity());
        result.put("defaultRefillRate",  props.getDefaultRefillRate());
        result.put("keyPrefix",          props.getKeyPrefix());

        Map<String, Object> channels = new LinkedHashMap<>();
        props.getChannels().forEach((alias, ch) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            long effectiveCapacity   = ch.getCapacity()   > 0 ? ch.getCapacity()   : props.getDefaultCapacity();
            long effectiveRefillRate = ch.getRefillRate()  > 0 ? ch.getRefillRate() : props.getDefaultRefillRate();
            info.put("capacity",   effectiveCapacity);
            info.put("refillRate", effectiveRefillRate);
            channels.put(alias, info);
        });
        result.put("channels", channels);

        return Mono.just(result);
    }
}
