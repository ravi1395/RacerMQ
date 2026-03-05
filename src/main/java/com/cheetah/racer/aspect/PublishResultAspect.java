package com.cheetah.racer.aspect;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
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

    /** Constructor used by tests and legacy wiring (no channel-config fallback). */
    public PublishResultAspect(RacerPublisherRegistry registry,
                                RacerStreamPublisher streamPublisher) {
        this(registry, streamPublisher, null);
    }

    public PublishResultAspect(RacerPublisherRegistry registry,
                                RacerStreamPublisher streamPublisher,
                                @Nullable RacerProperties properties) {
        this.registry        = registry;
        this.streamPublisher = streamPublisher;
        this.properties      = properties;
    }

    @Around("@annotation(publishResult)")
    public Object intercept(ProceedingJoinPoint pjp, PublishResult publishResult) throws Throwable {
        Object result = pjp.proceed();

        String channel = resolveChannel(publishResult);
        String channelRef = publishResult.channelRef();
        String sender  = resolveSender(publishResult, channelRef);
        boolean async  = resolveAsync(publishResult, channelRef);
        boolean durable = publishResult.durable();

        if (result instanceof Mono) {
            //noinspection unchecked
            return ((Mono<?>) result)
                    .doOnNext(value -> publishValue(value, channel, sender, async, durable,
                            resolveStreamKey(publishResult, channel)))
                    .doOnError(ex -> log.debug("[racer] @PublishResult skipped — upstream Mono error: {}",
                            ex.getMessage()));
        }

        if (result instanceof Flux) {
            //noinspection unchecked
            Flux<?> source = (Flux<?>) result;

            if (publishResult.mode() == ConcurrencyMode.CONCURRENT) {
                // ── Concurrent mode: up to N publishes in-flight simultaneously ──
                int effectiveConcurrency = Math.max(1, publishResult.concurrency());
                String streamKey = resolveStreamKey(publishResult, channel);
                return source
                        .flatMap(value ->
                                publishValueReactive(value, channel, sender, durable, streamKey)
                                        .thenReturn(value),
                                effectiveConcurrency)
                        .doOnError(ex -> log.debug(
                                "[racer] @PublishResult(CONCURRENT) skipped — upstream Flux error: {}",
                                ex.getMessage()));
            }

            // ── Sequential mode (default): fire-and-forget side-effect per element ──
            return source
                    .doOnNext(value -> publishValue(value, channel, sender, async, durable,
                            resolveStreamKey(publishResult, channel)))
                    .doOnError(ex -> log.debug("[racer] @PublishResult skipped — upstream Flux error: {}",
                            ex.getMessage()));
        }

        if (result != null) {
            publishValue(result, channel, sender, async, durable,
                    resolveStreamKey(publishResult, channel));
        }

        return result;
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
                                             boolean durable, String streamKey) {
        if (durable) {
            return streamPublisher.publishToStream(streamKey, value, sender)
                    .doOnSuccess(id -> log.debug("[racer] @PublishResult(durable,concurrent) → stream '{}' id={}", streamKey, id))
                    .doOnError(err -> log.error("[racer] @PublishResult(durable,concurrent) failed on '{}': {}", streamKey, err.getMessage()))
                    .then();
        }
        return publisherForChannel(channel)
                .publishAsync(value, sender)
                .doOnSuccess(count -> log.debug("[racer] @PublishResult(concurrent) → '{}' ({} subscriber(s))", channel, count))
                .doOnError(err -> log.error("[racer] @PublishResult(concurrent) publish failed to '{}': {}", channel, err.getMessage()))
                .then();
    }

    private void publishValue(Object value, String channel, String sender,
                               boolean async, boolean durable, String streamKey) {
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

    private RacerChannelPublisher publisherForChannel(String channelName) {
        return registry.getAll().values().stream()
                .filter(p -> p.getChannelName().equals(channelName))
                .findFirst()
                .orElseGet(() -> registry.getPublisher(null));
    }
}

