package com.cheetah.racer.common.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.model.DeadLetterMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic retention / pruning service for Racer Redis data structures.
 *
 * <p>When {@code racer.retention.enabled=true} is set, this bean is registered by
 * {@link com.cheetah.racer.common.config.RacerAutoConfiguration} with a scheduled job
 * (requires {@code @EnableScheduling} on the application context or configuration class).
 * Otherwise the bean is still available for on-demand invocation (e.g. via the
 * {@code POST /api/dlq/trim} endpoint), but the scheduled run is inactive.
 *
 * <p>Actions performed:
 * <ul>
 *   <li>Trim Redis Streams to {@code racer.retention.stream-max-len} entries
 *       (approximate, non-blocking {@code XTRIM … MAXLEN ~}).</li>
 *   <li>Evict Dead Letter Queue entries older than {@code racer.retention.dlq-max-age-hours} hours.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer:
 *   retention:
 *     enabled: true              # activates @Scheduled run (default: false)
 *     stream-max-len: 10000
 *     dlq-max-age-hours: 72
 *     schedule-cron: "0 0 * * * *"
 * </pre>
 */
@Slf4j
public class RacerRetentionService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DeadLetterQueueService dlqService;
    private final ObjectMapper objectMapper;
    private final long streamMaxLen;
    private final long dlqMaxAgeHours;

    private final AtomicLong totalStreamTrimmed = new AtomicLong(0);
    private final AtomicLong totalDlqPruned     = new AtomicLong(0);

    public RacerRetentionService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            DeadLetterQueueService dlqService,
            ObjectMapper objectMapper,
            long streamMaxLen,
            long dlqMaxAgeHours) {
        this.redisTemplate  = redisTemplate;
        this.dlqService     = dlqService;
        this.objectMapper   = objectMapper;
        this.streamMaxLen   = streamMaxLen;
        this.dlqMaxAgeHours = dlqMaxAgeHours;
    }

    // ── Scheduled job (activated when racer.retention.enabled=true) ──────────

    /**
     * Scheduled retention run. Requires {@code @EnableScheduling} on the application context.
     * Override the cron with {@code racer.retention.schedule-cron}.
     */
    @Scheduled(cron = "${racer.retention.schedule-cron:0 0 * * * *}")
    public void runRetention() {
        log.info("[racer-retention] Starting scheduled retention run (streamMaxLen={}, dlqMaxAgeHours={})",
                streamMaxLen, dlqMaxAgeHours);
        trimStreams();
        pruneDlq().subscribe();
    }

    // ── Stream trimming ───────────────────────────────────────────────────────

    /**
     * Trims all known Racer Redis Streams to at most {@code streamMaxLen} entries.
     * Can also be called on-demand from a controller.
     */
    public void trimStreams() {
        List<String> streamKeys = List.of(RedisChannels.REQUEST_STREAM);
        for (String streamKey : streamKeys) {
            redisTemplate.opsForStream()
                    .trim(streamKey, streamMaxLen, true)
                    .subscribe(
                            trimmed -> {
                                if (trimmed > 0) {
                                    totalStreamTrimmed.addAndGet(trimmed);
                                    log.info("[racer-retention] Trimmed {} entries from stream '{}'", trimmed, streamKey);
                                }
                            },
                            ex -> log.warn("[racer-retention] Cannot trim stream '{}': {}", streamKey, ex.getMessage()));
        }
    }

    // ── DLQ pruning ───────────────────────────────────────────────────────────

    /**
     * Evicts DLQ entries older than {@code dlqMaxAgeHours} hours.
     *
     * @return Mono of the number of entries removed
     */
    public Mono<Long> pruneDlq() {
        Instant cutoff = Instant.now().minus(dlqMaxAgeHours, ChronoUnit.HOURS);
        return dlqService.peekAll()
                .filter(dlm -> isExpired(dlm, cutoff))
                .flatMap(dlm -> removeEntryByValue(dlm).thenReturn(1L))
                .count()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        totalDlqPruned.addAndGet(count);
                        log.info("[racer-retention] Pruned {} expired DLQ entries (older than {} hours)",
                                count, dlqMaxAgeHours);
                    }
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isExpired(DeadLetterMessage dlm, Instant cutoff) {
        return dlm.getFailedAt() != null && dlm.getFailedAt().isBefore(cutoff);
    }

    private Mono<Long> removeEntryByValue(DeadLetterMessage dlm) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(dlm))
                .flatMap(json -> redisTemplate.opsForList()
                        .remove(RedisChannels.DEAD_LETTER_QUEUE, 1, json))
                .onErrorReturn(0L);
    }

    // ── Observability ─────────────────────────────────────────────────────────

    /** Returns current retention configuration and run counters. */
    public Map<String, Object> getConfig() {
        return Map.of(
                "streamMaxLen",       streamMaxLen,
                "dlqMaxAgeHours",     dlqMaxAgeHours,
                "totalStreamTrimmed", totalStreamTrimmed.get(),
                "totalDlqPruned",     totalDlqPruned.get()
        );
    }

    public long getTotalStreamTrimmed() { return totalStreamTrimmed.get(); }
    public long getTotalDlqPruned()     { return totalDlqPruned.get(); }
}
