package com.cheetah.racer.demo.responder;

import com.cheetah.racer.common.annotation.RacerResponder;
import com.cheetah.racer.common.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Demonstrates {@code @RacerResponder} — a method that handles incoming request messages
 * and returns a reply that Racer automatically routes back to the caller's reply channel.
 *
 * <p>The caller sends a request via {@code @RacerRequestReply} (e.g. through
 * {@link com.cheetah.racer.demo.client.DemoClient}); this responder handles it and
 * returns the response.
 */
@Component
@Slf4j
public class DemoResponder {

    /**
     * Handles ping requests on the {@code racer:demo:ping} Pub/Sub channel.
     * Returns a pong response map; Racer serialises it and sends it to the
     * {@code replyTo} channel embedded in the incoming request envelope.
     */
    @RacerResponder(channel = "racer:demo:ping", id = "demo-ping-responder")
    public Mono<Map<String, Object>> handlePing(RacerMessage request) {
        log.info("[RESPONDER] Received ping request: id={}", request.getId());
        return Mono.fromSupplier(() -> Map.of(
                "status",      "pong",
                "requestId",   request.getId(),
                "respondedAt", Instant.now().toString()
        ));
    }

    /**
     * Handles echo requests as a plain (blocking) responder.
     * Returns the payload unchanged wrapped in a response envelope.
     */
    @RacerResponder(channel = "racer:demo:echo", id = "demo-echo-responder")
    public Map<String, Object> handleEcho(RacerMessage request) {
        log.info("[RESPONDER] Echo request: id={} payload={}", request.getId(), request.getPayload());
        return Map.of(
                "echo",        request.getPayload(),
                "requestId",   request.getId(),
                "respondedAt", Instant.now().toString()
        );
    }
}
