package com.cheetah.racer.service;

import com.cheetah.racer.RedisChannels;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to reprocess messages from the Dead Letter Queue by republishing them back
 * to their original channel, where they will be picked up by the appropriate
 * {@code @RacerListener} or {@code @RacerStreamListener} for normal processing.
 *
 * <p>This service uses a <em>republish-only</em> strategy: instead of invoking a
 * {@code MessageProcessor} directly (which couples the reprocessor to application logic),
 * failed messages are re-injected into the Redis Pub/Sub pipeline so that all
 * annotation-driven consumers handle them naturally through the normal processing path.
 *
 * <p>Registered as a Spring bean by {@link com.cheetah.racer.config.RacerAutoConfiguration}.
 */
@Slf4j
public class DlqReprocessorService {

    private final DeadLetterQueueService dlqService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RacerMetricsPort racerMetrics;

    private final AtomicLong republishedCount       = new AtomicLong(0);
    private final AtomicLong permanentlyFailedCount = new AtomicLong(0);

    public DlqReprocessorService(
            DeadLetterQueueService dlqService,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Nullable RacerMetrics racerMetrics) {
        this.dlqService     = dlqService;
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.racerMetrics   = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
    }

    /**
     * Republish a single DLQ message back to its original Pub/Sub channel.
     * Returns the number of Pub/Sub subscribers that received the message (0 if DLQ is empty).
     */
    public Mono<Long> republishOne() {
        return dlqService.dequeue()
                .flatMap(dlm -> republishMessage(dlm))
                .defaultIfEmpty(0L);
    }

    /**
     * Republish all current DLQ messages back to their original channels.
     * Returns the total number of messages successfully republished.
     */
    public Mono<Long> republishAll() {
        return dlqService.size()
                .flatMap(size -> {
                    if (size == 0) {
                        log.info("[DLQ-REPROCESSOR] Queue is empty, nothing to republish");
                        return Mono.just(0L);
                    }
                    log.info("[DLQ-REPROCESSOR] Starting republish of {} messages", size);
                    return republishBatch(size);
                });
    }

    /** Batch size for iterative DLQ draining — avoids deeply nested Mono chains. */
    private static final int BATCH_CHUNK = 100;

    private Mono<Long> republishBatch(long remaining) {
        if (remaining <= 0) return Mono.just(0L);

        // Iterative expand: dequeue up to BATCH_CHUNK per iteration, accumulate count
        return Flux.range(0, (int) Math.min(remaining, BATCH_CHUNK))
                .concatMap(i -> dlqService.dequeue()
                        .flatMap(this::republishMessage)
                        .defaultIfEmpty(0L))
                .reduce(0L, (acc, sent) -> acc + (sent > 0 ? 1L : 0L))
                .flatMap(count -> {
                    long next = remaining - BATCH_CHUNK;
                    if (next > 0 && count > 0) {
                        return republishBatch(next).map(rest -> rest + count);
                    }
                    return Mono.just(count);
                });
    }

    private Mono<Long> republishMessage(DeadLetterMessage dlm) {
        RacerMessage message = dlm.getOriginalMessage();
        message.setRetryCount(message.getRetryCount() + 1);

        if (message.getRetryCount() > RedisChannels.MAX_RETRY_ATTEMPTS) {
            permanentlyFailedCount.incrementAndGet();
            log.error("[DLQ-REPROCESSOR] Message id={} exceeded max retries ({}). Permanently discarding.",
                    message.getId(), RedisChannels.MAX_RETRY_ATTEMPTS);
            return Mono.just(0L);
        }

        log.info("[DLQ-REPROCESSOR] Republishing message id={} to channel='{}' (attempt {}/{})",
                message.getId(), message.getChannel(),
                message.getRetryCount(), RedisChannels.MAX_RETRY_ATTEMPTS);

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(message))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(json -> redisTemplate.convertAndSend(message.getChannel(), json)
                        .doOnSuccess(count -> {
                            republishedCount.incrementAndGet();
                            log.info("[DLQ-REPROCESSOR] Republished id={} -> channel='{}' (subscribers={})",
                                    message.getId(), message.getChannel(), count);
                            racerMetrics.recordDlqReprocessed();
                        }))
                .onErrorResume(JsonProcessingException.class, e -> {
                    log.error("[DLQ-REPROCESSOR] Failed to serialize message id={}: {}", message.getId(), e.getMessage());
                    return Mono.error(e);
                });
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public long getRepublishedCount() { return republishedCount.get(); }
    public long getPermanentlyFailedCount() { return permanentlyFailedCount.get(); }
}
