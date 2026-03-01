package com.cheetah.racer.common.publisher;

import com.cheetah.racer.common.config.RacerProperties;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Holds a {@link RacerChannelPublisher} instance for every channel alias declared
 * in {@code racer.channels.*} properties, plus one for the default channel.
 *
 * <h3>Look-up order used by {@code @RacerPublisher} and {@code @PublishResult}</h3>
 * <ol>
 *   <li>If an alias is given, look it up in the registry.</li>
 *   <li>If not found (or no alias), fall back to the default channel publisher.</li>
 * </ol>
 */
@Slf4j
public class RacerPublisherRegistry {

    static final String DEFAULT_ALIAS = "__default__";

    private final RacerProperties properties;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    @Nullable
    private final RacerMetrics racerMetrics;
    @Nullable
    private final RacerSchemaRegistry schemaRegistry;

    /** alias → publisher */
    private final Map<String, RacerChannelPublisher> registry = new HashMap<>();

    public RacerPublisherRegistry(RacerProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper) {
        this(properties, redisTemplate, objectMapper, Optional.empty(), Optional.empty());
    }

    public RacerPublisherRegistry(RacerProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper,
                                  Optional<RacerMetrics> metricsOpt) {
        this(properties, redisTemplate, objectMapper, metricsOpt, Optional.empty());
    }

    public RacerPublisherRegistry(RacerProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper,
                                  Optional<RacerMetrics> metricsOpt,
                                  Optional<RacerSchemaRegistry> schemaRegistryOpt) {
        this.properties     = properties;
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.racerMetrics   = metricsOpt.orElse(null);
        this.schemaRegistry = schemaRegistryOpt.orElse(null);
    }

    @PostConstruct
    public void init() {
        // Register the default channel
        registry.put(DEFAULT_ALIAS, new RacerChannelPublisherImpl(
                redisTemplate, objectMapper,
                properties.getDefaultChannel(), DEFAULT_ALIAS, "racer", racerMetrics, schemaRegistry));
        log.info("[racer] Default channel registered: '{}'", properties.getDefaultChannel());

        // Register each named channel
        properties.getChannels().forEach((alias, channelProps) -> {
            if (channelProps.getName() == null || channelProps.getName().isBlank()) {
                log.warn("[racer] Channel alias '{}' has no 'name' configured — skipping.", alias);
                return;
            }
            registry.put(alias, new RacerChannelPublisherImpl(
                    redisTemplate, objectMapper,
                    channelProps.getName(), alias, channelProps.getSender(), racerMetrics, schemaRegistry));
            log.info("[racer] Channel '{}' registered → '{}'", alias, channelProps.getName());
        });
    }

    /**
     * Returns the publisher for the given alias, or the default publisher if the alias
     * is {@code null}, empty, or not found in the registry.
     */
    public RacerChannelPublisher getPublisher(String alias) {
        if (alias == null || alias.isBlank()) {
            return registry.get(DEFAULT_ALIAS);
        }
        RacerChannelPublisher publisher = registry.get(alias);
        if (publisher == null) {
            log.warn("[racer] Unknown channel alias '{}' — falling back to default channel.", alias);
            return registry.get(DEFAULT_ALIAS);
        }
        return publisher;
    }

    /** Returns all registered aliases (including {@code __default__}). */
    public Map<String, RacerChannelPublisher> getAll() {
        return Map.copyOf(registry);
    }
}
