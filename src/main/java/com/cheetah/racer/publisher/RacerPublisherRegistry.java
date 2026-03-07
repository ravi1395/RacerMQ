package com.cheetah.racer.publisher;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Factory and registry of {@link RacerChannelPublisher} instances.
 *
 * <p>One publisher is created for every channel alias declared under
 * {@code racer.channels.*}, plus a single publisher for the
 * {@link com.cheetah.racer.config.RacerProperties#getDefaultChannel() default channel}
 * (registered under the internal alias {@value #DEFAULT_ALIAS}).
 *
 * <p>Publishers are constructed once at application startup via {@link #init()} and
 * shared for the lifetime of the application.  Each publisher optionally delegates to
 * {@link RacerMetricsPort} for Micrometer instrumentation and to
 * {@link RacerSchemaRegistry} for outbound payload validation; both are no-ops when
 * the corresponding beans are absent from the application context.
 *
 * <h3>Look-up order (used by {@code @RacerPublisher} and {@code @PublishResult})</h3>
 * <ol>
 *   <li>If a non-blank alias is provided, return the publisher registered for that alias.</li>
 *   <li>If the alias is {@code null}, blank, or unknown, fall back to the default publisher
 *       and log a warning in the unknown-alias case.</li>
 * </ol>
 *
 * @see RacerChannelPublisherImpl
 * @see RacerMetricsPort
 * @see RacerSchemaRegistry
 */
@Slf4j
public class RacerPublisherRegistry {

    static final String DEFAULT_ALIAS = "__default__";

    private final RacerProperties properties;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RacerMetricsPort racerMetrics;
    @Nullable
    private final RacerSchemaRegistry schemaRegistry;

    /** alias → publisher; populated by {@link #init()}. */
    private final Map<String, RacerChannelPublisher> registry = new ConcurrentHashMap<>();

    /**
     * Minimal constructor — no metrics or schema validation.
     * Suitable for tests and simple applications that do not require instrumentation.
     */
    public RacerPublisherRegistry(RacerProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper) {
        this(properties, redisTemplate, objectMapper, Optional.empty(), Optional.empty());
    }

    /**
     * Constructor with optional Micrometer metrics.
     * Schema validation is disabled when this constructor is used.
     *
     * @param metricsOpt present when a {@link RacerMetrics} bean exists in the context;
     *                   empty values fall back to a no-op implementation
     */
    public RacerPublisherRegistry(RacerProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper,
                                  Optional<RacerMetrics> metricsOpt) {
        this(properties, redisTemplate, objectMapper, metricsOpt, Optional.empty());
    }

    /**
     * Full constructor used by {@link com.cheetah.racer.config.RacerAutoConfiguration}.
     *
     * @param properties        Racer configuration properties
     * @param redisTemplate     reactive Redis template for publishing
     * @param objectMapper      JSON serializer
     * @param metricsOpt        optional Micrometer metrics; absent → {@link com.cheetah.racer.metrics.NoOpRacerMetrics}
     * @param schemaRegistryOpt optional JSON-Schema validator; absent → validation skipped
     */
    public RacerPublisherRegistry(RacerProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  ObjectMapper objectMapper,
                                  Optional<RacerMetrics> metricsOpt,
                                  Optional<RacerSchemaRegistry> schemaRegistryOpt) {
        this.properties     = properties;
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.racerMetrics   = metricsOpt.<RacerMetricsPort>map(m -> m).orElseGet(NoOpRacerMetrics::new);
        this.schemaRegistry = schemaRegistryOpt.orElse(null);
    }

    /**
     * Builds and registers publishers for all configured channels.
     * Called automatically by Spring after dependency injection is complete.
     * The default channel is always registered; named channel aliases are added
     * for every entry in {@code racer.channels.*} that has a non-blank {@code name}.
     */
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
            if (channelProps.isDurable()) {
                String actualStreamKey = channelProps.getStreamKey().isBlank()
                        ? channelProps.getName() + ":stream"
                        : channelProps.getStreamKey();
                registry.put(alias, new RacerChannelPublisherImpl(
                        redisTemplate, objectMapper,
                        channelProps.getName(), alias, channelProps.getSender(),
                        true, actualStreamKey, racerMetrics, schemaRegistry));
                log.info("[racer] Channel '{}' registered → stream '{}' (durable)", alias, actualStreamKey);
            } else {
                registry.put(alias, new RacerChannelPublisherImpl(
                        redisTemplate, objectMapper,
                        channelProps.getName(), alias, channelProps.getSender(), racerMetrics, schemaRegistry));
                log.info("[racer] Channel '{}' registered → '{}'", alias, channelProps.getName());
            }
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

    /**
     * Returns an unmodifiable snapshot of all registered alias-to-publisher mappings,
     * including the internal {@value #DEFAULT_ALIAS} entry.
     */
    public Map<String, RacerChannelPublisher> getAll() {
        return Map.copyOf(registry);
    }
}
