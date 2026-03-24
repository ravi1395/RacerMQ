package com.cheetah.racer.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;
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
 *
 * <h3>Payload serialization contract</h3>
 * The {@code payload} argument is always embedded in the envelope as a <em>JSON string</em>
 * rather than a nested JSON object:
 * <ul>
 *   <li>If {@code payload} is already a {@link String} it is used verbatim.</li>
 *   <li>Otherwise it is serialized via {@code objectMapper.writeValueAsString(payload)}
 *       and the resulting JSON string is embedded.</li>
 * </ul>
 * This guarantees that {@link com.cheetah.racer.model.RacerMessage#getPayload()} always
 * returns a proper JSON string (or plain string) on the consumer side, which is required
 * for POJO deserialization in
 * {@link com.cheetah.racer.listener.RacerListenerRegistrar} and for
 * {@code dedupKey} field-extraction in
 * {@link com.cheetah.racer.listener.RacerListenerRegistrar#resolveEffectiveDedupId}.
 */
public final class MessageEnvelopeBuilder {

    private MessageEnvelopeBuilder() {}

    // ── Internal helper ──────────────────────────────────────────────────────

    /**
     * Serializes the payload value to a JSON string so the envelope {@code "payload"} field
     * is always a JSON string, never a nested JSON object.
     *
     * <p>String payloads (including pre-serialized JSON strings) are used verbatim.
     * All other types are serialized via {@code objectMapper}.
     */
    private static String serializePayload(ObjectMapper objectMapper, Object payload) throws Exception {
        if (payload == null) return "null";
        if (payload instanceof String) return (String) payload;
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * Builds a standard pub/sub envelope:
     * <pre>{"id": "...", "channel": "...", "sender": "...", "timestamp": "...", "payload": ...}</pre>
     */
    public static Mono<String> build(ObjectMapper objectMapper,
                                      String channel, String sender, Object payload) {
        return build(objectMapper, channel, sender, payload, false);
    }

    /**
     * Builds a standard pub/sub envelope with an optional routed flag.
     * When {@code routed} is {@code true}, the envelope includes {@code "routed": true}
     * so that downstream listeners skip routing evaluation (prevents infinite loops).
     */
    public static Mono<String> build(ObjectMapper objectMapper,
                                      String channel, String sender, Object payload,
                                      boolean routed) {
        return build(objectMapper, channel, sender, payload, routed, null);
    }

    /**
     * Builds a standard pub/sub envelope with an optional routed flag and an explicit
     * message ID for deduplication purposes.
     *
     * <p>When {@code messageId} is non-null, it is used as the envelope {@code id} field
     * directly, allowing callers to pass a stable business key so that
     * {@link com.cheetah.racer.dedup.RacerDedupService} can suppress retransmissions of
     * the same logical event.  When {@code null}, a random UUID is generated.
     */
    public static Mono<String> build(ObjectMapper objectMapper,
                                      String channel, String sender, Object payload,
                                      boolean routed, @Nullable String messageId) {
        return Mono.fromCallable(() -> {
            // 6 fields standard + 1 optional (routed) → capacity 8 avoids rehash
            Map<String, Object> envelope = new LinkedHashMap<>(8);
            envelope.put("id",        messageId != null ? messageId : UUID.randomUUID().toString());
            envelope.put("channel",   channel);
            envelope.put("sender",    sender);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("payload",   serializePayload(objectMapper, payload));
            if (routed) envelope.put("routed", true);
            return objectMapper.writeValueAsString(envelope);
        });
    }

    /**
     * Builds a priority envelope:
     * <pre>{"id": "...", "channel": "...", "sender": "...", "timestamp": "...", "priority": "HIGH", "payload": ...}</pre>
     */
    public static Mono<String> buildWithPriority(ObjectMapper objectMapper,
                                                   String channel, String sender,
                                                   String priority, Object payload) {
        return Mono.fromCallable(() -> {
            // 7 fields → capacity 10 (load-factor 0.75) avoids rehash
            Map<String, Object> envelope = new LinkedHashMap<>(10);
            envelope.put("id",        UUID.randomUUID().toString());
            envelope.put("channel",   channel);
            envelope.put("sender",    sender);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("priority",  priority);
            envelope.put("payload",   serializePayload(objectMapper, payload));
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
            // 4 fields → capacity 6 avoids rehash
            Map<String, Object> envelope = new LinkedHashMap<>(6);
            envelope.put("id",        UUID.randomUUID().toString());
            envelope.put("sender",    sender);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("payload",   serializePayload(objectMapper, payload));
            return objectMapper.writeValueAsString(envelope);
        });
    }

    // ── Phase 4.2 — Distributed Tracing ──────────────────────────────────────

    /**
     * Builds a pub/sub envelope that includes the W3C {@code traceparent} header when
     * tracing is enabled.
     *
     * <pre>{"id":"...","channel":"...","sender":"...","timestamp":"...","payload":"...","traceparent":"00-..."}</pre>
     *
     * <p>When {@code traceparent} is {@code null} the output is identical to
     * {@link #build(ObjectMapper, String, String, Object, boolean, String)}.
     *
     * @param objectMapper Jackson mapper used for serialization
     * @param channel      channel / topic name
     * @param sender       originator identifier
     * @param payload      message body
     * @param routed       whether the message was forwarded by the router
     * @param messageId    explicit message ID; {@code null} generates a random UUID
     * @param traceparent  W3C traceparent value; {@code null} omits the field
     */
    public static Mono<String> buildWithTrace(ObjectMapper objectMapper,
                                               String channel, String sender, Object payload,
                                               boolean routed, @Nullable String messageId,
                                               @Nullable String traceparent) {
        return Mono.fromCallable(() -> {
            Map<String, Object> envelope = new LinkedHashMap<>(10);
            envelope.put("id",        messageId != null ? messageId : UUID.randomUUID().toString());
            envelope.put("channel",   channel);
            envelope.put("sender",    sender);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("payload",   serializePayload(objectMapper, payload));
            if (routed)       envelope.put("routed",      true);
            if (traceparent != null && !traceparent.isBlank())
                              envelope.put("traceparent", traceparent);
            return objectMapper.writeValueAsString(envelope);
        });
    }
}
