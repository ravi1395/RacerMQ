package com.cheetah.racer.dedup;

import com.cheetah.racer.config.RacerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
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
@RequiredArgsConstructor
public class RacerDedupService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RacerProperties racerProperties;

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
     * @param messageId the {@link com.cheetah.racer.model.RacerMessage#getId()} value
     * @return {@code Mono<true>} = process, {@code Mono<false>} = skip (duplicate)
     */
    public Mono<Boolean> checkAndMarkProcessed(String messageId) {
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
                .onErrorResume(ex -> {
                    log.warn("[RACER-DEDUP] Redis error during dedup check for id={}: {} — failing open",
                            messageId, ex.getMessage());
                    return Mono.just(true); // fail-open: allow processing on Redis error
                });
    }
}
