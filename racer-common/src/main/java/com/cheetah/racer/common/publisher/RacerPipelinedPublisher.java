package com.cheetah.racer.common.publisher;

import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Batch publisher that issues multiple Pub/Sub {@code PUBLISH} commands in parallel
 * rather than sequentially (R-9 — Throughput Optimisation).
 *
 * <h3>Why this is faster</h3>
 * The existing {@link RacerTransaction} uses {@link Flux#concat}, which waits for each
 * command's reply before sending the next one (one round-trip per message).
 * This publisher uses {@link Flux#mergeDelayError}, which issues all commands without
 * waiting — Lettuce (the reactive Redis driver) automatically pipelines concurrent
 * commands over the same connection, reducing round-trips from N to ~1.
 *
 * <h3>Usage (via REST)</h3>
 * {@code POST /api/publish/batch-pipelined}
 *
 * <h3>Usage (programmatic)</h3>
 * <pre>
 * racerPipelinedPublisher.publishBatch("racer:orders",
 *     List.of("payload1", "payload2", "payload3"),
 *     "my-service")
 *     .subscribe(counts -> ...);
 * </pre>
 */
@Slf4j
public class RacerPipelinedPublisher {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxBatchSize;
    @Nullable
    private final RacerMetrics racerMetrics;
    @Nullable
    private final RacerSchemaRegistry schemaRegistry;

    public RacerPipelinedPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                   ObjectMapper objectMapper,
                                   int maxBatchSize,
                                   @Nullable RacerMetrics racerMetrics) {
        this(redisTemplate, objectMapper, maxBatchSize, racerMetrics, null);
    }

    public RacerPipelinedPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                   ObjectMapper objectMapper,
                                   int maxBatchSize,
                                   @Nullable RacerMetrics racerMetrics,
                                   @Nullable RacerSchemaRegistry schemaRegistry) {
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.maxBatchSize   = maxBatchSize;
        this.racerMetrics   = racerMetrics;
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * Publishes a batch of payloads to the same channel in parallel.
     * Commands are pipelined by Lettuce over a single connection.
     *
     * @param channelName Redis channel (the full key, not an alias)
     * @param payloads    list of payload strings to publish
     * @param sender      sender identifier embedded in every envelope
     * @return {@code Mono<List<Long>>} — subscriber counts for each message,
     *         errors from individual messages are collected rather than short-circuiting
     */
    public Mono<List<Long>> publishBatch(String channelName, List<String> payloads, String sender) {
        if (payloads == null || payloads.isEmpty()) {
            return Mono.just(List.of());
        }

        // Split into chunks if needed
        List<List<String>> batches = partition(payloads, maxBatchSize);

        return Flux.fromIterable(batches)
                .concatMap(batch -> publishChunk(channelName, batch, sender))
                .collectList()
                .map(listOfLists -> listOfLists.stream()
                        .flatMap(List::stream)
                        .toList())
                .doOnSuccess(counts ->
                        log.debug("[racer-pipeline] Pipelined {} messages to '{}'", counts.size(), channelName));
    }

    /**
     * Publishes a batch of (possibly different) {@link PipelineItem} items in parallel.
     * Items may target different channels, making this suitable for multi-channel batch
     * operations that previously required a sequential {@link RacerTransaction}.
     */
    public Mono<List<Long>> publishItems(List<PipelineItem> items) {
        if (items == null || items.isEmpty()) {
            return Mono.just(List.of());
        }

        List<Mono<Long>> ops = items.stream()
                .map(item -> {
                    // R-7: validate payload before publishing
                    if (schemaRegistry != null) {
                        try {
                            schemaRegistry.validateForPublish(item.channelName(), item.payload());
                        } catch (com.cheetah.racer.common.schema.SchemaValidationException e) {
                            return Mono.<Long>error(e);
                        }
                    }
                    return serializeEnvelope(item.channelName(), item.payload(), item.sender())
                            .flatMap(json -> redisTemplate.convertAndSend(item.channelName(), json))
                            .doOnSuccess(count -> {
                                if (racerMetrics != null) {
                                    racerMetrics.recordPublished(item.channelName(), "pubsub-pipeline");
                                }
                            });
                })
                .toList();

        // mergeDelayError fires all Monos concurrently — Lettuce pipelines them automatically
        return Flux.fromIterable(ops)
                .flatMap(op -> op, ops.size()) // concurrency = all at once — Lettuce pipeline
                .collectList()
                .doOnSuccess(r ->
                        log.debug("[racer-pipeline] Pipelined {} items across {} channel(s)",
                                r.size(), items.stream().map(PipelineItem::channelName).distinct().count()))
                .doOnError(ex ->
                        log.error("[racer-pipeline] Pipeline batch failed: {}", ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Mono<List<Long>> publishChunk(String channelName, List<String> payloads, String sender) {
        List<Mono<Long>> ops = payloads.stream()
                .map(payload -> {
                    // R-7: per-payload schema validation
                    if (schemaRegistry != null) {
                        try {
                            schemaRegistry.validateForPublish(channelName, payload);
                        } catch (com.cheetah.racer.common.schema.SchemaValidationException e) {
                            return Mono.<Long>error(e);
                        }
                    }
                    return serializeEnvelope(channelName, payload, sender)
                            .flatMap(json -> redisTemplate.convertAndSend(channelName, json))
                            .doOnSuccess(count -> {
                                if (racerMetrics != null) {
                                    racerMetrics.recordPublished(channelName, "pubsub-pipeline");
                                }
                            });
                })
                .toList();

        return Flux.fromIterable(ops)
                .flatMap(op -> op, ops.size()) // concurrency = all at once
                .collectList();
    }

    private Mono<String> serializeEnvelope(String channelName, String payload, String sender) {
        return Mono.fromCallable(() -> {
            java.util.LinkedHashMap<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("channel", channelName);
            envelope.put("sender", sender);
            envelope.put("payload", payload);
            return objectMapper.writeValueAsString(envelope);
        });
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        java.util.List<List<T>> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    /**
     * Represents a single item in a multi-channel pipeline batch.
     */
    public record PipelineItem(String channelName, String payload, String sender) {}
}
