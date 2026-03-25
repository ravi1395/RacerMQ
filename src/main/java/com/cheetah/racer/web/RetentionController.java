package com.cheetah.racer.web;

import com.cheetah.racer.service.RacerRetentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for observing and triggering Racer retention operations.
 *
 * <p>Only registered when {@code racer.web.retention-enabled=true}.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/retention/config} — current retention configuration and counters</li>
 *   <li>{@code POST /api/retention/trim}   — manually trigger a retention run immediately</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/retention")
@RequiredArgsConstructor
public class RetentionController {

    private final RacerRetentionService retentionService;

    /**
     * GET /api/retention/config
     * Returns the current retention configuration values and lifetime counters.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(retentionService.getConfig());
    }

    /**
     * POST /api/retention/trim
     * Immediately runs a retention trim outside the scheduled window:
     * <ul>
     *   <li>Truncates all configured durable streams to their configured max-len.</li>
     *   <li>Removes DLQ entries older than the configured max-age.</li>
     * </ul>
     */
    @PostMapping("/trim")
    public Mono<ResponseEntity<Map<String, Object>>> trim() {
        return retentionService.trimStreams()
                .then(retentionService.pruneDlq())
                .map(pruned -> ResponseEntity.ok(Map.of(
                        "status",    "trimmed",
                        "dlqPruned", (Object) pruned
                )));
    }
}
