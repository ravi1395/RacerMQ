package com.cheetah.racer.aspect;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.annotation.PublishResults;
import com.cheetah.racer.annotation.RacerPriority;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AOP aspect that intercepts methods annotated with {@link PublishResult} and
 * publishes their return value to the configured Redis channel or Stream.
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code Mono<T>} — taps into the reactive pipeline; every emitted item is published
 *       without blocking the caller.</li>
 *   <li>{@code Flux<T>} — same as Mono but for every element in the stream.</li>
 *   <li>Any other type — the returned value is published synchronously or asynchronously
 *       after the method returns, depending on {@link PublishResult#async()}.</li>
 * </ul>
 *
 * <h3>Durable publishing</h3>
 * When {@code @PublishResult(durable = true)} the value is written to a Redis Stream
 * via {@link RacerStreamPublisher} instead of Pub/Sub, providing at-least-once delivery
 * to consumer groups.
 */
@Aspect
@Slf4j
public class PublishResultAspect {

    private final RacerPublisherRegistry registry;
    private final RacerStreamPublisher streamPublisher;
    @Nullable
    private final RacerProperties properties;
    @Nullable
    private final RacerPriorityPublisher priorityPublisher;

    /** Cache of @RacerPriority lookups per method — avoids reflection on every invocation. */
    private final java.util.concurrent.ConcurrentHashMap<java.lang.reflect.Method, java.util.Optional<RacerPriority>> priorityCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Constructor used by tests and legacy wiring (no channel-config fallback). */
    public PublishResultAspect(RacerPublisherRegistry registry,
                                RacerStreamPublisher streamPublisher) {
        this(registry, streamPublisher, null, null);
    }

    public PublishResultAspect(RacerPublisherRegistry registry,
                                RacerStreamPublisher streamPublisher,
                                @Nullable RacerProperties properties) {
        this(registry, streamPublisher, properties, null);
    }

    public PublishResultAspect(RacerPublisherRegistry registry,
                                RacerStreamPublisher streamPublisher,
                                @Nullable RacerProperties properties,
                                @Nullable RacerPriorityPublisher priorityPublisher) {
        this.registry          = registry;
        this.streamPublisher   = streamPublisher;
        this.properties        = properties;
        this.priorityPublisher = priorityPublisher;
    }

    /** Intercepts a single {@code @PublishResult} annotation. */
    @Around("@annotation(publishResult)")
    public Object intercept(ProceedingJoinPoint pjp, PublishResult publishResult) throws Throwable {
        return dispatchResult(pjp.proceed(), new PublishResult[]{publishResult}, resolveRacerPriority(pjp));
    }

    /**
     * Intercepts the {@link PublishResults} container — fires when two or more
     * {@code @PublishResult} annotations are present on the same method.
     * The method body is executed exactly once; the return value is published
     * to every declared channel in declaration order.
     */
    @Around("@annotation(publishResults)")
    public Object interceptMulti(ProceedingJoinPoint pjp, PublishResults publishResults) throws Throwable {
        return dispatchResult(pjp.proceed(), publishResults.value(), resolveRacerPriority(pjp));
    }

    /**
     * Core fan-out: publishes {@code result} to each channel declared by {@code annotations}.
     * For reactive return types the side-effects are chained non-destructively so the
     * original element passes through unchanged to the caller.
     */
    private Object dispatchResult(Object result, PublishResult[] annotations,
                                   @Nullable RacerPriority racerPriority) {
        if (result instanceof Mono) {
            //noinspection unchecked
            Mono<?> mono = (Mono<?>) result;
            for (PublishResult ann : annotations) {
                mono = applyMonoDispatch(mono, ann, effectivePriorityLevel(ann, racerPriority));
            }
            return mono;
        }

        if (result instanceof Flux) {
            //noinspection unchecked
            Flux<?> flux = (Flux<?>) result;
            for (PublishResult ann : annotations) {
                flux = applyFluxDispatch(flux, ann, effectivePriorityLevel(ann, racerPriority));
            }
            return flux;
        }

        if (result != null) {
            for (PublishResult ann : annotations) {
                publishForAnnotation(result, ann, effectivePriorityLevel(ann, racerPriority));
            }
        }
        return result;
    }

    /**
     * Determines the effective priority level for a single {@code @PublishResult} annotation.
     *
     * <ul>
     *   <li>If the annotation's own {@link PublishResult#priority()} is non-empty,
     *       it takes precedence (per-annotation override).</li>
     *   <li>Otherwise, the method-level {@link RacerPriority#defaultLevel()} is used
     *       as fallback (backward-compatible).</li>
     *   <li>If neither is set, returns {@code null} — standard (non-priority) publish.</li>
     * </ul>
     */
    @Nullable
    private String effectivePriorityLevel(PublishResult ann, @Nullable RacerPriority methodPriority) {
        if (priorityPublisher == null) return null;
        if (!ann.priority().isBlank()) return ann.priority().trim().toUpperCase();
        if (methodPriority != null)    return methodPriority.defaultLevel().trim().toUpperCase();
        return null;
    }

    private @Nullable RacerPriority resolveRacerPriority(ProceedingJoinPoint pjp) {
        if (priorityPublisher == null) return null;
        java.lang.reflect.Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return priorityCache.computeIfAbsent(method,
                m -> java.util.Optional.ofNullable(m.getAnnotation(RacerPriority.class))).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Mono<?> applyMonoDispatch(Mono<?> mono, PublishResult ann,
                                       @Nullable String priorityLevel) {
        String channel   = resolveChannel(ann);
        String sender    = resolveSender(ann, ann.channelRef());
        boolean async    = resolveAsync(ann, ann.channelRef());
        boolean durable  = ann.durable();
        String streamKey = resolveStreamKey(ann, channel);
        return mono
                .doOnNext(value -> publishValue(value, channel, sender, async, durable, streamKey, priorityLevel))
                .doOnError(ex -> log.debug("[racer] @PublishResult({}) skipped — upstream Mono error: {}",
                        channel, ex.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Flux<?> applyFluxDispatch(Flux<?> flux, PublishResult ann,
                                       @Nullable String priorityLevel) {
        String channel   = resolveChannel(ann);
        String sender    = resolveSender(ann, ann.channelRef());
        boolean async    = resolveAsync(ann, ann.channelRef());
        boolean durable  = ann.durable();
        String streamKey = resolveStreamKey(ann, channel);

        if (ann.mode() == ConcurrencyMode.CONCURRENT) {
            int effectiveConcurrency = Math.max(1, ann.concurrency());
            return flux
                    .flatMap(value ->
                            publishValueReactive(value, channel, sender, durable, streamKey, priorityLevel)
                                    .thenReturn(value),
                            effectiveConcurrency)
                    .doOnError(ex -> log.debug(
                            "[racer] @PublishResult(CONCURRENT,{}) skipped — upstream Flux error: {}",
                            channel, ex.getMessage()));
        }

        return flux
                .doOnNext(value -> publishValue(value, channel, sender, async, durable, streamKey, priorityLevel))
                .doOnError(ex -> log.debug("[racer] @PublishResult({}) skipped — upstream Flux error: {}",
                        channel, ex.getMessage()));
    }

    private void publishForAnnotation(Object value, PublishResult ann,
                                       @Nullable String priorityLevel) {
        String channel = resolveChannel(ann);
        publishValue(value, channel,
                resolveSender(ann, ann.channelRef()),
                resolveAsync(ann, ann.channelRef()),
                ann.durable(),
                resolveStreamKey(ann, channel),
                priorityLevel);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the sender: annotation value wins if non-empty; otherwise falls back
     * to {@code racer.channels.<alias>.sender}; ultimate fallback is {@code "racer-publisher"}.
     */
    private String resolveSender(PublishResult annotation, String channelRef) {
        if (!annotation.sender().isBlank()) {
            return annotation.sender();
        }
        if (properties != null && !channelRef.isBlank()) {
            RacerProperties.ChannelProperties channelProps = properties.getChannels().get(channelRef);
            if (channelProps != null && channelProps.getSender() != null
                    && !channelProps.getSender().isBlank()) {
                return channelProps.getSender();
            }
        }
        return "racer-publisher";
    }

    /**
     * Resolves the async flag: when {@code channelRef} maps to a configured channel,
     * that channel's {@code async} setting governs the publish; otherwise falls back
     * to the annotation attribute.
     */
    private boolean resolveAsync(PublishResult annotation, String channelRef) {
        if (properties != null && !channelRef.isBlank()) {
            RacerProperties.ChannelProperties channelProps = properties.getChannels().get(channelRef);
            if (channelProps != null) {
                return channelProps.isAsync();
            }
        }
        return annotation.async();
    }

    private String resolveChannel(PublishResult annotation) {
        if (!annotation.channel().isBlank()) {
            return annotation.channel();
        }
        if (!annotation.channelRef().isBlank()) {
            RacerChannelPublisher publisher = registry.getPublisher(annotation.channelRef());
            return publisher.getChannelName();
        }
        return registry.getPublisher(null).getChannelName();
    }

    private String resolveStreamKey(PublishResult annotation, String channel) {
        if (!annotation.streamKey().isBlank()) {
            return annotation.streamKey();
        }
        // default: <channel>:stream
        return channel + ":stream";
    }

    /**
     * Reactive variant used in {@link ConcurrencyMode#CONCURRENT} mode.
     * Always uses the async (non-blocking) path so that {@code flatMap} can
     * control the actual in-flight concurrency.
     */
    private Mono<Void> publishValueReactive(Object value, String channel, String sender,
                                             boolean durable, String streamKey,
                                             @Nullable String priorityLevel) {
        if (durable) {
            return streamPublisher.publishToStream(streamKey, value, sender)
                    .doOnSuccess(id -> log.debug("[racer] @PublishResult(durable,concurrent) → stream '{}' id={}", streamKey, id))
                    .doOnError(err -> log.error("[racer] @PublishResult(durable,concurrent) failed on '{}': {}", streamKey, err.getMessage()))
                    .then();
        }
        if (priorityLevel != null && priorityPublisher != null) {
            String level = resolvePriorityLevel(value, priorityLevel);
            return priorityPublisher.publish(channel, value, sender, level)
                    .doOnSuccess(count -> log.debug("[racer] @PublishResult+priority(concurrent) → '{}:priority:{}' ({} subscriber(s))", channel, level, count))
                    .doOnError(err -> log.error("[racer] @PublishResult+priority(concurrent) failed to '{}:priority:{}': {}", channel, level, err.getMessage()))
                    .then();
        }
        return publisherForChannel(channel)
                .publishAsync(value, sender)
                .doOnSuccess(count -> log.debug("[racer] @PublishResult(concurrent) → '{}' ({} subscriber(s))", channel, count))
                .doOnError(err -> log.error("[racer] @PublishResult(concurrent) publish failed to '{}': {}", channel, err.getMessage()))
                .then();
    }

    private void publishValue(Object value, String channel, String sender,
                               boolean async, boolean durable, String streamKey,
                               @Nullable String priorityLevel) {
        if (durable) {
            // ── Durable path: write to Redis Stream ────────────────────────
            Mono<?> durablePublish = streamPublisher.publishToStream(streamKey, value, sender);
            if (async) {
                durablePublish.subscribe(
                        id  -> log.debug("[racer] @PublishResult(durable) → stream '{}' id={}", streamKey, id),
                        err -> log.error("[racer] @PublishResult(durable) failed on '{}': {}", streamKey, err.getMessage()));
            } else {
                try {
                    durablePublish.block(java.time.Duration.ofSeconds(10));
                    log.debug("[racer] @PublishResult(durable,sync) → stream '{}'", streamKey);
                } catch (Exception ex) {
                    log.error("[racer] @PublishResult(durable,sync) failed on '{}': {}", streamKey, ex.getMessage());
                }
            }
            return;
        }

        // ── Priority Pub/Sub path ──────────────────────────────────────────
        if (priorityLevel != null && priorityPublisher != null) {
            String level = resolvePriorityLevel(value, priorityLevel);
            Mono<Long> publish = priorityPublisher.publish(channel, value, sender, level);
            if (async) {
                publish.subscribe(
                        count -> log.debug("[racer] @PublishResult+priority → '{}:priority:{}' ({} subscriber(s))", channel, level, count),
                        err   -> log.error("[racer] @PublishResult+priority publish failed to '{}:priority:{}': {}", channel, level, err.getMessage()));
            } else {
                try {
                    Long count = publish.block(java.time.Duration.ofSeconds(10));
                    log.debug("[racer] @PublishResult+priority (sync) → '{}:priority:{}' ({} subscriber(s))", channel, level, count);
                } catch (Exception ex) {
                    log.error("[racer] @PublishResult+priority (sync) failed to '{}:priority:{}': {}", channel, level, ex.getMessage());
                }
            }
            return;
        }

        // ── Standard Pub/Sub path ──────────────────────────────────────────
        RacerChannelPublisher publisher = publisherForChannel(channel);
        Mono<Long> publish = publisher.publishAsync(value, sender);

        if (async) {
            publish.subscribe(
                    count -> log.debug("[racer] @PublishResult → '{}' ({} subscriber(s))", channel, count),
                    err   -> log.error("[racer] @PublishResult publish failed to '{}': {}", channel, err.getMessage()));
        } else {
            try {
                Long count = publish.block(java.time.Duration.ofSeconds(10));
                log.debug("[racer] @PublishResult (sync) → '{}' ({} subscriber(s))", channel, count);
            } catch (Exception ex) {
                log.error("[racer] @PublishResult (sync) failed to '{}': {}", channel, ex.getMessage());
            }
        }
    }

    /**
     * Extracts the effective priority level for routing. For {@link RacerMessage} payloads
     * the message's own {@code priority} field takes precedence; for all other types the
     * supplied default level is used.
     */
    private String resolvePriorityLevel(Object value, String defaultLevel) {
        if (value instanceof RacerMessage) {
            String p = ((RacerMessage) value).getPriority();
            if (p != null && !p.isBlank()) {
                return p.toUpperCase();
            }
        }
        return defaultLevel;
    }

    private RacerChannelPublisher publisherForChannel(String channelName) {
        return registry.getAll().values().stream()
                .filter(p -> p.getChannelName().equals(channelName))
                .findFirst()
                .orElseGet(() -> registry.getPublisher(null));
    }
}

