package com.cheetah.racer.common.web;

import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only REST controller that exposes the registered Racer channel registry.
 *
 * <p>Only registered when {@code racer.web.channels-enabled=true}.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/channels} — lists every channel registered in the Racer registry</li>
 * </ul>
 *
 * <p>This controller intentionally exposes no publish endpoints: the end-user
 * application is responsible for triggering publishes via {@code @PublishResult},
 * {@code @RacerPublisher} injected fields, or the low-level {@code RacerPublisherRegistry}.
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelRegistryController {

    private final RacerPublisherRegistry registry;

    /**
     * GET /api/channels
     * Lists every channel registered in the Racer registry with its Redis key.
     */
    @GetMapping
    public Mono<Map<String, Object>> listChannels() {
        Map<String, Object> result = registry.getAll().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Map.of("channel", e.getValue().getChannelName())));
        return Mono.just(result);
    }
}
