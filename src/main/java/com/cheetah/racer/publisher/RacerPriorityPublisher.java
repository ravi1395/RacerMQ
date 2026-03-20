package com.cheetah.racer.publisher;

import com.cheetah.racer.model.PriorityLevel;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes messages to priority sub-channels (R-10 — Message Priority).
 *
 * <h3>Sub-channel naming</h3>
 * Each base channel gets N priority sub-channels:
 * <pre>
 * racer:orders:priority:HIGH
 * racer:orders:priority:NORMAL
 * racer:orders:priority:LOW
 * </pre>
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer.priority.enabled=true
 * racer.priority.levels=HIGH,NORMAL,LOW
 * racer.priority.channels=orders,notifications
 * </pre>
 *
 * <h3>Publishing with priority</h3>
 * <pre>
 * // Via REST: POST /api/publish/async  { "channel": "racer:orders", "payload": "...", "priority": "HIGH" }
 *
 * // Programmatic:
 * racerPriorityPublisher.publish("racer:orders", payload, "my-service", "HIGH").subscribe();
 * </pre>
 *
 * <p>Consumers on the receiving side (e.g.
 * {@code RacerPriorityConsumerService} in {@code racer-client}) subscribe to all
 * priority sub-channels and drain them in the configured order.
 */
@Slf4j
public class RacerPriorityPublisher {

    /** Segment inserted between the base channel name and the priority level. */
    public static final String PRIORITY_SEGMENT = ":priority:";

    /** Cache of computed priority channel names to avoid repeated string concatenation. */
    private static final ConcurrentHashMap<String, String> CHANNEL_NAME_CACHE = new ConcurrentHashMap<>();

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    @org.springframework.lang.Nullable
    private final RacerSchemaRegistry schemaRegistry;

    public RacerPriorityPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                   ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, null);
    }

    public RacerPriorityPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                   ObjectMapper objectMapper,
                                   @org.springframework.lang.Nullable RacerSchemaRegistry schemaRegistry) {
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * Publishes {@code payload} to the correct priority sub-channel for {@code baseChannelName}.
     *
     * <p>If {@code priorityLevel} is {@code null} or blank, falls back to {@code NORMAL}.
     *
     * @param baseChannelName the base Redis channel key (e.g. {@code racer:orders})
     * @param payload         any object — serialized as the {@code payload} field in the envelope
     * @param sender          sender identifier embedded in the envelope
     * @param priorityLevel   priority level name (e.g. {@code "HIGH"}, {@code "NORMAL"}, {@code "LOW"})
     * @return Mono of the subscriber count
     */
    public Mono<Long> publish(String baseChannelName, Object payload,
                               String sender, String priorityLevel) {
        Mono<Void> validate = schemaRegistry != null
                ? schemaRegistry.validateForPublishReactive(baseChannelName, payload)
                : Mono.empty();
        String level   = (priorityLevel != null && !priorityLevel.isBlank())
                ? priorityLevel.trim().toUpperCase()
                : PriorityLevel.NORMAL.name();
        String channel = priorityChannelName(baseChannelName, level);
        return validate
                .then(MessageEnvelopeBuilder.buildWithPriority(objectMapper, baseChannelName, sender, level, payload))
                .flatMap(json -> redisTemplate.convertAndSend(channel, json))
                .doOnSuccess(count ->
                        log.debug("[racer-priority] Published to '{}' → {} subscriber(s)", channel, count))
                .doOnError(ex ->
                        log.error("[racer-priority] Failed to publish to '{}': {}", channel, ex.getMessage()));
    }

    /**
     * Derives the full Redis channel key for a given base channel and priority level.
     *
     * @param baseChannelName e.g. {@code racer:orders}
     * @param level           e.g. {@code HIGH}
     * @return e.g. {@code racer:orders:priority:HIGH}
     */
    public static String priorityChannelName(String baseChannelName, String level) {
        String upperLevel = level.toUpperCase();
        return CHANNEL_NAME_CACHE.computeIfAbsent(
                baseChannelName + "|" + upperLevel,
                k -> baseChannelName + PRIORITY_SEGMENT + upperLevel);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------
}
