package com.cheetah.racer.common.web;

import com.cheetah.racer.common.model.DeadLetterMessage;
import com.cheetah.racer.common.service.DeadLetterQueueService;
import com.cheetah.racer.common.service.DlqReprocessorService;
import com.cheetah.racer.common.service.RacerRetentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for managing the Dead Letter Queue.
 *
 * <p>Only registered when {@code racer.web.dlq-enabled=true} and the application
 * is a reactive web application.  Controlled via {@link RacerWebAutoConfiguration}.
 */
@RestController
@RequestMapping("/api/dlq")
@RequiredArgsConstructor
public class DlqController {

    private final DeadLetterQueueService dlqService;
    private final DlqReprocessorService reprocessorService;
    private final RacerRetentionService retentionService;

    /**
     * GET /api/dlq/messages
     * Lists all messages currently in the DLQ.
     */
    @GetMapping("/messages")
    public Flux<DeadLetterMessage> getMessages() {
        return dlqService.peekAll();
    }

    /**
     * GET /api/dlq/size
     * Returns the current DLQ size.
     */
    @GetMapping("/size")
    public Mono<ResponseEntity<Map<String, Object>>> getSize() {
        return dlqService.size()
                .map(size -> ResponseEntity.ok(Map.of("dlqSize", (Object) size)));
    }

    /**
     * POST /api/dlq/republish/one
     * Republishes the oldest DLQ message back to its original Pub/Sub channel.
     */
    @PostMapping("/republish/one")
    public Mono<ResponseEntity<Map<String, Object>>> republishOne() {
        return reprocessorService.republishOne()
                .map(subscribers -> ResponseEntity.ok(Map.of(
                        "republished", true,
                        "subscribers", (Object) subscribers
                )));
    }

    /**
     * POST /api/dlq/republish/all
     * Republishes all DLQ messages back to their original Pub/Sub channels.
     */
    @PostMapping("/republish/all")
    public Mono<ResponseEntity<Map<String, Object>>> republishAll() {
        return reprocessorService.republishAll()
                .map(count -> ResponseEntity.ok(Map.of(
                        "republishedCount", (Object) count
                )));
    }

    /**
     * DELETE /api/dlq/clear
     * Clears all messages from the DLQ.
     */
    @DeleteMapping("/clear")
    public Mono<ResponseEntity<Map<String, Object>>> clear() {
        return dlqService.clear()
                .map(cleared -> ResponseEntity.ok(Map.of("cleared", (Object) cleared)));
    }

    /**
     * GET /api/dlq/stats
     * Returns DLQ processing statistics.
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> getStats() {
        return dlqService.size()
                .map(size -> ResponseEntity.ok(Map.of(
                        "queueSize",        (Object) size,
                        "totalRepublished", reprocessorService.getRepublishedCount(),
                        "permanentlyFailed", reprocessorService.getPermanentlyFailedCount()
                )));
    }

    /**
     * POST /api/dlq/trim
     * Immediately runs a retention trim: truncates all configured durable streams
     * and removes DLQ entries older than the configured max-age.
     */
    @PostMapping("/trim")
    public Mono<ResponseEntity<Map<String, Object>>> trim() {
        retentionService.trimStreams();
        return retentionService.pruneDlq()
                .map(pruned -> ResponseEntity.ok(Map.of(
                        "status",    "trimmed",
                        "dlqPruned", (Object) pruned
                )));
    }
}
