package com.cheetah.racer.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Static factory that builds the standard Racer JSON envelope and serializes it.
 *
 * <p>Eliminates the near-identical {@code serializeEnvelope / serialize} private methods
 * that were duplicated across {@link RacerChannelPublisherImpl},
 * {@link RacerPipelinedPublisher}, and {@link RacerPriorityPublisher}.
 */
public final class MessageEnvelopeBuilder {

    private MessageEnvelopeBuilder() {}

    /**
     * Builds a standard pub/sub envelope:
     * <pre>{"channel": "...", "sender": "...", "payload": ...}</pre>
     */
    public static Mono<String> build(ObjectMapper objectMapper,
                                      String channel, String sender, Object payload) {
        return Mono.fromCallable(() -> {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("channel", channel);
            envelope.put("sender",  sender);
            envelope.put("payload", payload);
            return objectMapper.writeValueAsString(envelope);
        });
    }

    /**
     * Builds a priority envelope:
     * <pre>{"channel": "...", "sender": "...", "priority": "HIGH", "payload": ...}</pre>
     */
    public static Mono<String> buildWithPriority(ObjectMapper objectMapper,
                                                   String channel, String sender,
                                                   String priority, Object payload) {
        return Mono.fromCallable(() -> {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("channel",  channel);
            envelope.put("sender",   sender);
            envelope.put("priority", priority);
            envelope.put("payload",  payload);
            return objectMapper.writeValueAsString(envelope);
        });
    }

    /**
     * Builds a durable stream entry envelope:
     * <pre>{"id": "...", "sender": "...", "timestamp": "...", "payload": ...}</pre>
     */
    public static Mono<String> buildStream(ObjectMapper objectMapper,
                                            String sender, Object payload) {
        return Mono.fromCallable(() -> {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id",        UUID.randomUUID().toString());
            envelope.put("sender",    sender);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("payload",   payload);
            return objectMapper.writeValueAsString(envelope);
        });
    }
}
