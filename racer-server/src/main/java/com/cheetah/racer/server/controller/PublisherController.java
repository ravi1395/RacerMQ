package com.cheetah.racer.server.controller;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.common.publisher.RacerPriorityPublisher;
import com.cheetah.racer.common.tx.RacerTransaction;
import com.cheetah.racer.server.service.PublisherService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for publishing messages to Redis channels.
 */
@RestController
@RequestMapping("/api/publish")
@RequiredArgsConstructor
public class PublisherController {

    private final PublisherService publisherService;
    private final RacerTransaction racerTransaction;
    private final RacerPipelinedPublisher racerPipelinedPublisher;

    /** Optional — only present when {@code racer.priority.enabled=true}. */
    @Nullable
    @Autowired(required = false)
    private RacerPriorityPublisher racerPriorityPublisher;

    /**
     * POST /api/publish/async
     * Publishes a message asynchronously (non-blocking).
     * Body: { "channel": "optional-channel", "payload": "message content", "sender": "server-name",
     *         "priority": "HIGH|NORMAL|LOW" (optional, R-10) }
     */
    @PostMapping("/async")
    public Mono<ResponseEntity<Map<String, Object>>> publishAsync(@RequestBody Map<String, String> request) {
        String channel  = request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        String payload  = request.get("payload");
        String sender   = request.getOrDefault("sender", "racer-server");
        String priority = request.get("priority"); // R-10: optional

        if (payload == null || payload.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "'payload' field is required")));
        }

        // Route through priority publisher when a priority level is given and the bean is active
        if (priority != null && !priority.isBlank() && racerPriorityPublisher != null) {
            return racerPriorityPublisher.publish(channel, payload, sender, priority)
                    .map(subscribers -> ResponseEntity.ok(Map.of(
                            "status",     "published",
                            "mode",       "async-priority",
                            "channel",    channel,
                            "priority",   priority.toUpperCase(),
                            "subscribers", subscribers)));
        }

        return publisherService.publishAsync(channel, payload, sender)
                .map(subscribers -> ResponseEntity.ok(Map.of(
                        "status",      "published",
                        "mode",        "async",
                        "channel",     channel,
                        "subscribers", subscribers
                )));
    }

    /**
     * POST /api/publish/sync
     * Publishes a message synchronously (blocking until confirmed).
     * Body: { "channel": "optional-channel", "payload": "message content", "sender": "server-name" }
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> publishSync(@RequestBody Map<String, String> request) {
        String channel = request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        String payload = request.get("payload");
        String sender = request.getOrDefault("sender", "racer-server");

        if (payload == null || payload.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "'payload' field is required"));
        }

        Long subscribers = publisherService.publishSync(channel, payload, sender);
        return ResponseEntity.ok(Map.of(
                "status", "published",
                "mode", "sync",
                "channel", channel,
                "subscribers", subscribers != null ? subscribers : 0
        ));
    }

    /**
     * POST /api/publish/batch
     * Publishes multiple messages asynchronously.
     * Body: { "channel": "optional", "payloads": ["msg1","msg2","msg3"], "sender": "server" }
     */
    @PostMapping("/batch")
    public Mono<ResponseEntity<Map<String, Object>>> publishBatch(@RequestBody Map<String, Object> request) {
        String channel = (String) request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        @SuppressWarnings("unchecked")
        var payloads = (java.util.List<String>) request.get("payloads");
        String sender = (String) request.getOrDefault("sender", "racer-server");

        if (payloads == null || payloads.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "'payloads' field is required and must not be empty")));
        }

        return reactor.core.publisher.Flux.fromIterable(payloads)
                .flatMap(payload -> publisherService.publishAsync(channel, payload, sender))
                .collectList()
                .map(results -> ResponseEntity.ok(Map.of(
                        "status", "published",
                        "mode", "async-batch",
                        "channel", channel,
                        "messageCount", payloads.size()
                )));
    }

    /**
     * POST /api/publish/batch-atomic
     * Publishes multiple messages to (potentially different) channels as an atomic
     * sequence using {@link RacerTransaction}. All publishes are concatenated and
     * submitted in order; if any fails the returned Mono errors out.
     *
     * <p>Request body: list of {@code { "alias": "...", "payload": "...", "sender": "..." }} items.
     * The {@code alias} field resolves to a registered channel alias (see {@code racer.channels.*}).
     *
     * <p>Example:
     * <pre>
     * [
     *   { "alias": "orders",        "payload": "order-001", "sender": "checkout" },
     *   { "alias": "notifications", "payload": "notify-001" }
     * ]
     * </pre>
     */
    @PostMapping("/batch-atomic")
    public Mono<ResponseEntity<Map<String, Object>>> publishBatchAtomic(
            @RequestBody java.util.List<Map<String, Object>> items) {

        return racerTransaction.execute(tx ->
                items.forEach(item -> {
                    String alias   = (String) item.getOrDefault("alias", "");
                    String payload = (String) item.get("payload");
                    String sender  = (String) item.getOrDefault("sender", "racer-server");
                    tx.publish(alias, payload, sender);
                })
        ).map(counts -> ResponseEntity.ok(Map.of(
                "status",       "published",
                "mode",         "atomic-batch",
                "messageCount", items.size(),
                "subscriberCounts", counts
        )));
    }

    /**
     * POST /api/publish/batch-pipelined
     * Publishes multiple payloads to a single channel using parallel reactive merging
     * (R-9 — Throughput Optimisation). Lettuce auto-pipelines concurrent commands over
     * a single connection, reducing round-trips from N to ~1.
     *
     * <p>Accepts the same body shape as {@code /api/publish/batch}:
     * <pre>
     * { "channel": "racer:orders", "payloads": ["p1","p2","p3"], "sender": "checkout" }
     * </pre>
     *
     * <p>For multi-channel pipeline batches use {@code /api/publish/batch-atomic} with
     * the {@code pipelined=true} flag.
     */
    @PostMapping("/batch-pipelined")
    public Mono<ResponseEntity<Map<String, Object>>> publishBatchPipelined(
            @RequestBody Map<String, Object> request) {

        String channel = (String) request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        @SuppressWarnings("unchecked")
        var payloads = (java.util.List<String>) request.get("payloads");
        String sender = (String) request.getOrDefault("sender", "racer-server");

        if (payloads == null || payloads.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "payloads must not be empty")));
        }

        return racerPipelinedPublisher.publishBatch(channel, payloads, sender)
                .map(counts -> ResponseEntity.ok(Map.of(
                        "status",           "published",
                        "mode",             "pipelined-batch",
                        "channel",          channel,
                        "messageCount",     payloads.size(),
                        "subscriberCounts", counts)));
    }

    /**
     * POST /api/publish/batch-atomic-pipelined
     * Same as {@code /api/publish/batch-atomic} but uses pipelined (parallel) mode
     * instead of sequential {@code Flux.concat} (R-9).
     *
     * <p>Request body: same format as {@code /api/publish/batch-atomic}.
     */
    @PostMapping("/batch-atomic-pipelined")
    public Mono<ResponseEntity<Map<String, Object>>> publishBatchAtomicPipelined(
            @RequestBody java.util.List<Map<String, Object>> items) {

        return racerTransaction.execute(tx ->
                items.forEach(item -> {
                    String alias   = (String) item.getOrDefault("alias", "");
                    String payload = (String) item.get("payload");
                    String sender  = (String) item.getOrDefault("sender", "racer-server");
                    tx.publish(alias, payload, sender);
                }), true /* pipelined */
        ).map(counts -> ResponseEntity.ok(Map.of(
                "status",           "published",
                "mode",             "atomic-batch-pipelined",
                "messageCount",     items.size(),
                "subscriberCounts", counts)));
    }
}
