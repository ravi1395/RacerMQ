package com.cheetah.racer.client.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.model.DeadLetterMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic retention / pruning service for Racer Redis data structures.
 *
 * <p>Runs on a configurable cron schedule to:
 * <ul>
 *   <li>Trim Redis Streams to a max entry count using {@code XTRIM … MAXLEN ~ N}</li>
 *   <li>Evict Dead Letter Queue entries older than a configured age</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer.retention.stream.max-len=10000
 * racer.retention.dlq.max-age-hours=48
 * racer.retention.schedule.cron=0 0 * * * *
 * </pre>
 *
 * <p>Manual trim is also available via {@code POST /api/dlq/trim} and
 * {@code GET /api/dlq/retention-config}.
 */
@Slf4j
@Service
public class RacerRetentionService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DeadLetterQueueService dlqService;
    private final ObjectMapper objectMapper;

    @Value("${racer.retention.stream-max-len:10000}")
    private long streamMaxLen;

    @Value("${racer.retention.dlq-max-age-hours:72}")
    private long dlqMaxAgeHours;

    private final AtomicLong totalStreamTrimmed = new AtomicLong(0);
    private final AtomicLong totalDlqPruned     = new AtomicLong(0);

    public RacerRetentionService(ReactiveRedisTemplate<String, String> redisTemplate,
                                 DeadLetterQueueService dlqService,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.dlqService    = dlqService;
        this.objectMapper  = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Scheduled job
    // -----------------------------------------------------------------------

    /**
     * Scheduled retention run. Default cron: top of every hour.
     * Override with {@code racer.retention.schedule.cron} in properties.
     */
    @Scheduled(cron = "${racer.retention.schedule-cron:0 0 * * * *}")
    public void runRetention() {
        log.info("[racer-retention] Starting scheduled retention run (streamMaxLen={}, dlqMaxAgeHours={})",
                streamMaxLen, dlqMaxAgeHours);
        trimStreams();
        pruneDlq().subscribe();
    }

    // -----------------------------------------------------------------------
    // Stream trimming
    // -----------------------------------------------------------------------

    /**
     * Trims all known Racer Redis Streams to at most {@code racer.retention.stream.max-len} entries.
     * Uses approximate trimming (XTRIM … MAXLEN ~) to avoid blocking the Redis server.
     * Can be called on-demand from the DLQ controller.
     */
    public void trimStreams() {
        List<String> streamKeys = List.of(
                RedisChannels.REQUEST_STREAM
                // Add durable @PublishResult stream keys here as they are introduced
        );

        for (String streamKey : streamKeys) {
            redisTemplate.opsForStream()
                    .trim(streamKey, streamMaxLen, true)
                    .subscribe(
                            trimmed -> {
                                if (trimmed > 0) {
                                    totalStreamTrimmed.addAndGet(trimmed);
                                    log.info("[racer-retention] Trimmed {} entries from stream '{}'",
                                            trimmed, streamKey);
                                }
                            },
                            ex -> log.warn("[racer-retention] Cannot trim stream '{}': {}",
                                    streamKey, ex.getMessage())
                    );
        }
    }

    // -----------------------------------------------------------------------
    // DLQ pruning
    // -----------------------------------------------------------------------

    /**
     * Evicts DLQ entries whose {@code failedAt} is older than
     * {@code racer.retention.dlq.max-age-hours} hours.
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean isExpired(DeadLetterMessage dlm, Instant cutoff) {
        return dlm.getFailedAt() != null && dlm.getFailedAt().isBefore(cutoff);
    }

    /**
     * Removes a specific DLQ entry by serialising it back to JSON and calling
     * {@code LREM key 1 value} (removes the first occurrence in the list).
     */
    private Mono<Long> removeEntryByValue(DeadLetterMessage dlm) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(dlm))
                .flatMap(json -> redisTemplate
                        .opsForList()
                        .remove(RedisChannels.DEAD_LETTER_QUEUE, 1, json))
                .onErrorReturn(0L);
    }

    // -----------------------------------------------------------------------
    // Observability helpers (used by REST endpoint)
    // -----------------------------------------------------------------------

    /** Returns the current retention configuration and counters for the REST endpoint. */
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
