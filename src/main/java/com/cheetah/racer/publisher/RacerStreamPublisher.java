package com.cheetah.racer.publisher;

import com.cheetah.racer.security.RacerMessageSigner;
import com.cheetah.racer.security.RacerPayloadEncryptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes messages to Redis Streams, providing durable at-least-once delivery.
 *
 * <p>Unlike Pub/Sub (which drops messages if no subscriber is connected),
 * a Redis Stream retains entries until they are acknowledged by a consumer group.
 * This publisher is used by {@link com.cheetah.racer.aspect.PublishResultAspect}
 * when {@code @PublishResult(durable = true)}.
 *
 * <h3>Entry format</h3>
 * Each stream entry contains a single field {@code "data"} whose value is a JSON object:
 * <pre>
 * {
 *   "id":        "&lt;uuid&gt;",
 *   "sender":    "my-service",
 *   "timestamp": "2026-03-01T12:00:00Z",
 *   "payload":   { ...original object... }
 * }
 * </pre>
 */
@Slf4j
public class RacerStreamPublisher {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Nullable
    private RacerPayloadEncryptor payloadEncryptor;
    @Nullable
    private RacerMessageSigner messageSigner;

    public void setPayloadEncryptor(RacerPayloadEncryptor payloadEncryptor) {
        this.payloadEncryptor = payloadEncryptor;
    }

    public void setMessageSigner(RacerMessageSigner messageSigner) {
        this.messageSigner = messageSigner;
    }

    public RacerStreamPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
    }

    /**
     * Publishes {@code payload} as a new entry on the given Redis Stream key.
     *
     * @param streamKey the Redis Stream key (e.g. {@code "racer:orders:stream"})
     * @param payload   the object to serialize and write to the stream
     * @param sender    sender identifier embedded in the stream entry envelope
     * @return Mono of the assigned stream entry {@link RecordId}
     */
    public Mono<RecordId> publishToStream(String streamKey, Object payload, String sender) {
        return Mono.fromCallable(() -> {
                    String messageId = UUID.randomUUID().toString();

                    // Normalise payload to string (required for encryption and signing)
                    String payloadStr = (payload instanceof String s)
                            ? s
                            : objectMapper.writeValueAsString(payload);

                    // 1. Encrypt payload if encryptor is present
                    if (payloadEncryptor != null) {
                        payloadStr = payloadEncryptor.encrypt(payloadStr);
                    }

                    // 2. Sign the message (over the possibly-encrypted payload)
                    String signature = null;
                    if (messageSigner != null) {
                        signature = messageSigner.sign(messageId, sender, payloadStr, streamKey);
                    }

                    Map<String, Object> envelope = new LinkedHashMap<>();
                    envelope.put("id",        messageId);
                    envelope.put("channel",   streamKey);
                    envelope.put("sender",    sender);
                    envelope.put("timestamp", Instant.now().toString());
                    envelope.put("payload",   payloadStr);
                    if (signature != null) {
                        envelope.put("signature", signature);
                    }
                    return objectMapper.writeValueAsString(envelope);
                })
                .flatMap(json -> {
                    MapRecord<String, String, String> record = MapRecord.create(
                            streamKey, Map.of("data", json));
                    return redisTemplate.opsForStream().add(record);
                })
                .doOnSuccess(id ->
                        log.debug("[racer-stream] Published to '{}' → entry {}", streamKey, id))
                .doOnError(ex ->
                        log.error("[racer-stream] Failed to publish to '{}': {}", streamKey, ex.getMessage()));
    }
}
