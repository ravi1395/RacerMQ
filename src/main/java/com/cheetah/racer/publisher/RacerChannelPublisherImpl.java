package com.cheetah.racer.publisher;

import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Default implementation of {@link RacerChannelPublisher}.
 *
 * <p>Wraps payload objects in a lightweight envelope:
 * <pre>
 * {
 *   "channel": "racer:orders",
 *   "sender":  "order-service",
 *   "payload": { ...original object... }
 * }
 * </pre>
 * then publishes the JSON string to the Redis channel.
 */
@Slf4j
public class RacerChannelPublisherImpl implements RacerChannelPublisher {

    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(10);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final String channelName;
    private final String channelAlias;
    private final String defaultSender;
    private final RacerMetricsPort racerMetrics;
    @Nullable
    private final RacerSchemaRegistry schemaRegistry;

    public RacerChannelPublisherImpl(ReactiveRedisTemplate<String, String> redisTemplate,
                                     ObjectMapper objectMapper,
                                     String channelName,
                                     String channelAlias,
                                     String defaultSender) {
        this(redisTemplate, objectMapper, channelName, channelAlias, defaultSender, null, null);
    }

    public RacerChannelPublisherImpl(ReactiveRedisTemplate<String, String> redisTemplate,
                                     ObjectMapper objectMapper,
                                     String channelName,
                                     String channelAlias,
                                     String defaultSender,
                                     @Nullable RacerMetricsPort racerMetrics,
                                     @Nullable RacerSchemaRegistry schemaRegistry) {
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.channelName    = channelName;
        this.channelAlias   = channelAlias;
        this.defaultSender  = defaultSender;
        this.racerMetrics   = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
        this.schemaRegistry = schemaRegistry;
    }

    @Override
    public Mono<Long> publishAsync(Object payload) {
        return publishAsync(payload, defaultSender);
    }

    @Override
    public Mono<Long> publishAsync(Object payload, String sender) {
        Mono<Void> validate = schemaRegistry != null
                ? schemaRegistry.validateForPublishReactive(channelName, payload)
                : Mono.empty();
        return validate
                .then(MessageEnvelopeBuilder.build(objectMapper, channelName, sender, payload))
                .flatMap(json -> redisTemplate.convertAndSend(channelName, json))
                .doOnSuccess(count -> {
                    log.debug("[racer] Published to '{}' → {} subscriber(s)", channelName, count);
                    racerMetrics.recordPublished(channelName, "pubsub");
                })
                .doOnError(ex ->
                        log.error("[racer] Failed to publish to '{}': {}", channelName, ex.getMessage()));
    }

    @Override
    public Long publishSync(Object payload) {
        return publishAsync(payload, defaultSender)
                .block(SYNC_TIMEOUT);
    }

    @Override
    public String getChannelName() {
        return channelName;
    }

    @Override
    public String getChannelAlias() {
        return channelAlias;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------
}
