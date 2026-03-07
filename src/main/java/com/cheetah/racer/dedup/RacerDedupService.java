package com.cheetah.racer.dedup;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Idempotency-key deduplication service backed by Redis.
 *
 * <p>Uses {@code SET NX EX} (SETNX + TTL in one atomic operation) to track recently
 * processed message IDs. The first time a given ID is seen the key is created and the
 * message is allowed through; subsequent arrivals within the TTL window are detected as
 * duplicates and silently dropped.
 *
 * <p>Activated when {@code racer.dedup.enabled=true}.  Opt-in per listener via
 * {@code @RacerListener(dedup = true)}.
 *
 * <pre>
 * # application.properties
 * racer.dedup.enabled=true
 * racer.dedup.ttl-seconds=300
 * racer.dedup.key-prefix=racer:dedup:
 * </pre>
 *
 * @see com.cheetah.racer.annotation.RacerListener#dedup()
 */
@Slf4j
public class RacerDedupService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RacerProperties racerProperties;
    private final RacerMetricsPort racerMetrics;

    /** Backward-compatible constructor (no metrics). */
    public RacerDedupService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            RacerProperties racerProperties) {
        this(redisTemplate, racerProperties, null);
    }

    public RacerDedupService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            RacerProperties racerProperties,
            @Nullable RacerMetrics racerMetrics) {
        this.redisTemplate   = redisTemplate;
        this.racerProperties = racerProperties;
        this.racerMetrics    = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
    }

    /**
     * Atomically checks and marks a message ID as processed.
     * Equivalent to {@link #checkAndMarkProcessed(String, String)} with {@code listenerId = null}.
     */
    public Mono<Boolean> checkAndMarkProcessed(String messageId) {
        return checkAndMarkProcessed(messageId, null);
    }

    /**
     * Atomically checks and marks a message ID as processed.
     *
     * <p>Returns {@code Mono.just(true)} if the message ID was not yet seen (caller should
     * process it). Returns {@code Mono.just(false)} if the ID was already recorded in the
     * dedup window (caller should skip it as a duplicate).
     *
     * <p>On Redis errors the method fails-open: the message is allowed through so that
     * a temporary Redis connectivity issue does not halt message processing.
     *
     * @param messageId  the {@link com.cheetah.racer.model.RacerMessage#getId()} value
     * @param listenerId optional listener ID used for the {@code racer.dedup.duplicates} counter tag
     * @return {@code Mono<true>} = process, {@code Mono<false>} = skip (duplicate)
     */
    public Mono<Boolean> checkAndMarkProcessed(String messageId, @Nullable String listenerId) {
        if (messageId == null || messageId.isEmpty()) {
            log.debug("[RACER-DEDUP] Message has no id — skipping dedup check, allowing through");
            return Mono.just(true);
        }

        RacerProperties.DedupProperties dedup = racerProperties.getDedup();
        String key = dedup.getKeyPrefix() + messageId;
        Duration ttl = Duration.ofSeconds(dedup.getTtlSeconds());

        return redisTemplate.opsForValue()
                .setIfAbsent(key, "1", ttl)
                .defaultIfEmpty(false)
                .doOnNext(isNew -> {
                    if (!isNew && listenerId != null) {
                        racerMetrics.recordDedupDuplicate(listenerId);
                    }
                })
                .onErrorResume(ex -> {
                    log.warn("[RACER-DEDUP] Redis error during dedup check for id={}: {} — failing open",
                            messageId, ex.getMessage());
                    return Mono.just(true); // fail-open: allow processing on Redis error
                });
    }
}
