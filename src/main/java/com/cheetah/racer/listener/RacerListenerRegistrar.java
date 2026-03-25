package com.cheetah.racer.listener;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.Routed;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.CompiledRouteRule;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.router.RouteDecision;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.schema.SchemaValidationException;
import com.cheetah.racer.stream.RacerStreamUtils;
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BeanPostProcessor} that discovers methods annotated with {@link RacerListener}
 * and auto-subscribes them to their configured Redis Pub/Sub channels at startup.
 *
 * <h3>Processing pipeline (per message)</h3>
 * <ol>
 *   <li>Deserialize the raw Redis body into a {@link RacerMessage}.</li>
 *   <li>Apply content-based routing via {@link RacerRouterService} (if present) — if a
 *       routing rule matches the message is re-published and local dispatch is skipped.</li>
 *   <li>Validate the payload against the registered schema via {@link RacerSchemaRegistry}
 *       (if schema is enabled) — validation failures are sent to the DLQ.</li>
 *   <li>Resolve the method argument from the declared parameter type:
 *       <ul>
 *         <li>{@link RacerMessage} — passes the full envelope.</li>
 *         <li>{@link String} — passes the raw payload string.</li>
 *         <li>Any other type {@code T} — deserializes the payload JSON into {@code T}.</li>
 *       </ul>
 *   </li>
 *   <li>Invoke the method on {@code Schedulers.boundedElastic()} (blocking-safe).
 *       Both {@code void} and {@code Mono<?>} return types are supported.</li>
 *   <li>On success — increment processed counter + record Micrometer metrics (if present).</li>
 *   <li>On failure — increment failed counter + enqueue to DLQ (if {@link RacerDeadLetterHandler}
 *       is present) + log the error.</li>
 * </ol>
 *
 * <p>Concurrency is controlled per-listener via {@link RacerListener#mode()} and
 * {@link RacerListener#concurrency()}:
 * <ul>
 *   <li>{@link ConcurrencyMode#SEQUENTIAL} — effective concurrency is always 1.</li>
 *   <li>{@link ConcurrencyMode#CONCURRENT} — effective concurrency is {@code max(1, concurrency)}.</li>
 * </ul>
 *
 * <p>All active subscriptions are disposed cleanly on application shutdown via {@link org.springframework.context.SmartLifecycle}.
 *
 * @see RacerListener
 * @see ConcurrencyMode
 * @see RacerDeadLetterHandler
 */
@Slf4j
public class RacerListenerRegistrar extends AbstractRacerRegistrar {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final Scheduler listenerScheduler;

    /**
     * Used when {@code racer.channels.<alias>.durable=true} to set up XREADGROUP consumers.
     * {@code null} when constructed via the legacy test constructor.
     */
    @Nullable
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private final RacerMetricsPort racerMetrics;

    @Nullable
    private final RacerSchemaRegistry racerSchemaRegistry;

    @Nullable
    private final RacerRouterService racerRouterService;

    /** Adaptive tuners for AUTO-mode listeners — shut down on application stop. */
    private final Map<String, AdaptiveConcurrencyTuner> tuners = new ConcurrentHashMap<>();

    /** Per-listener schedulers — isolates thread pools so a slow listener cannot starve others. */
    private final Map<String, Scheduler> perListenerSchedulers = new ConcurrentHashMap<>();

    // ── Phase 3 optional extensions ─────────────────────────────────────────

    /** Injected when {@code racer.dedup.enabled=true}. */
    @Nullable
    private volatile RacerDedupService dedupService;
    @Nullable
    private ObjectProvider<RacerDedupService> dedupServiceProvider;

    /** Injected when {@code racer.circuit-breaker.enabled=true}. */
    @Nullable
    private volatile RacerCircuitBreakerRegistry circuitBreakerRegistry;
    @Nullable
    private ObjectProvider<RacerCircuitBreakerRegistry> circuitBreakerRegistryProvider;

    /** Set by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}. */
    private final java.util.concurrent.atomic.AtomicBoolean backPressureActive = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Pre-compiled per-listener routing rules (from method-level {@code @RacerRoute}). */
    private final Map<String, List<CompiledRouteRule>> perListenerRules = new ConcurrentHashMap<>();

    /**
     * Per-listener dedup field names set via {@link RacerListener#dedupKey()}.
     * When present the named field is extracted from the payload JSON and used as the
     * Redis dedup key instead of the auto-generated envelope UUID.
     */
    private final Map<String, String> perListenerDedupKeys = new ConcurrentHashMap<>();

    /** Ordered list of message interceptors applied before every handler invocation. */
    private volatile List<RacerMessageInterceptor> interceptors = Collections.emptyList();

    public void setDedupService(RacerDedupService dedupService) {
        this.dedupService = dedupService;
    }

    public void setDedupServiceProvider(ObjectProvider<RacerDedupService> provider) {
        this.dedupServiceProvider = provider;
    }

    private RacerDedupService getDedupService() {
        if (dedupService == null && dedupServiceProvider != null) {
            dedupService = dedupServiceProvider.getIfAvailable();
        }
        return dedupService;
    }

    public void setCircuitBreakerRegistry(RacerCircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public void setCircuitBreakerRegistryProvider(ObjectProvider<RacerCircuitBreakerRegistry> provider) {
        this.circuitBreakerRegistryProvider = provider;
    }

    private RacerCircuitBreakerRegistry getCircuitBreakerRegistry() {
        if (circuitBreakerRegistry == null && circuitBreakerRegistryProvider != null) {
            circuitBreakerRegistry = circuitBreakerRegistryProvider.getIfAvailable();
        }
        return circuitBreakerRegistry;
    }

    /**
     * Injects the ordered list of {@link RacerMessageInterceptor} beans.
     * Called by {@link com.cheetah.racer.config.RacerAutoConfiguration} after the
     * application context has collected all interceptor beans.
     */
    public void setInterceptors(List<RacerMessageInterceptor> interceptors) {
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
    }

    /**
     * Called by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor} when the
     * thread-pool queue fill ratio crosses the configured threshold.
     * While {@code active=true} all incoming Pub/Sub messages are silently dropped.
     */
    public void setBackPressureActive(boolean active) {
        boolean previous = backPressureActive.getAndSet(active);
        if (active != previous) {
            log.info("[RACER-LISTENER] Back-pressure {}", active ? "ACTIVATED — dropping pub/sub messages" : "RELIEVED — resuming pub/sub dispatch");
        }
    }

    /** Constructor used by tests and legacy code — falls back to {@code boundedElastic()}. */
    public RacerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            @Nullable RacerMetrics racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerRouterService racerRouterService,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        this(listenerContainer, objectMapper, racerProperties, Schedulers.boundedElastic(),
                null, racerMetrics, racerSchemaRegistry, racerRouterService, deadLetterHandler);
    }

    /** Production constructor — uses the dedicated Racer listener thread pool. */
    public RacerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            Scheduler listenerScheduler,
            @Nullable ReactiveRedisTemplate<String, String> redisTemplate,
            @Nullable RacerMetrics racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerRouterService racerRouterService,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        super(racerProperties, deadLetterHandler);
        this.listenerContainer   = listenerContainer;
        this.objectMapper        = objectMapper;
        this.listenerScheduler   = listenerScheduler;
        this.redisTemplate       = redisTemplate;
        this.racerMetrics        = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
        this.racerSchemaRegistry = racerSchemaRegistry;
        this.racerRouterService  = racerRouterService;
    }

    @Override
    protected String logPrefix() { return "RACER-LISTENER"; }

    @Override
    protected void additionalDisposeCleanup() {
        tuners.values().forEach(AdaptiveConcurrencyTuner::shutdown);
        perListenerSchedulers.values().forEach(Scheduler::dispose);
        perListenerSchedulers.clear();
    }

    /**
     * Returns (creating on first access) a bounded-elastic scheduler dedicated to
     * the given listener.  Thread count is capped at the listener's effective
     * concurrency so that a slow handler cannot monopolize the global pool.
     */
    private Scheduler schedulerForListener(String listenerId, int concurrency) {
        return perListenerSchedulers.computeIfAbsent(listenerId, id ->
                Schedulers.newBoundedElastic(
                        Math.max(1, concurrency),
                        Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                        "racer-" + id,
                        60,
                        true));
    }

    // ── BeanPostProcessor ────────────────────────────────────────────────────

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            RacerListener ann = method.getAnnotation(RacerListener.class);
            if (ann != null) {
                registerListener(bean, method, ann, beanName);
            }
        }
        return bean;
    }

    // ── SmartLifecycle (delegated to AbstractRacerRegistrar) ─────────────────

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerListener(Object bean, Method method, RacerListener ann, String beanName) {
        // --- resolve channel name ---
        String channel    = resolve(ann.channel());
        String channelRef = resolve(ann.channelRef());
        String resolvedChannel = RacerChannelResolver.resolveChannel(
                channel, channelRef, racerProperties, logPrefix());

        if (resolvedChannel.isEmpty()) {
            log.warn("[RACER-LISTENER] {}.{}() has @RacerListener but no channel/channelRef — skipped.",
                    beanName, method.getName());
            return;
        }

        // --- resolve listener id for logging/metrics ---
        String listenerId = resolveListenerId(
                ann.id().isEmpty() ? "" : resolve(ann.id()), beanName, method);

        // --- concurrency + subscription ---
        method.setAccessible(true);

        registerListenerStats(listenerId);

        // Per-listener @RacerRoute on the handler method
        RacerRoute methodRoute = method.getAnnotation(RacerRoute.class);
        if (methodRoute != null && racerRouterService != null) {
            List<CompiledRouteRule> compiled = racerRouterService.compile(methodRoute);
            perListenerRules.put(listenerId, compiled);
            log.info("[RACER-LISTENER] Per-listener route rules for '{}': {} rule(s)",
                    listenerId, compiled.size());
        }

        final boolean dedupEnabled = ann.dedup();

        // Store dedupKey field name for use in dispatch(); pre-register counter at 0
        if (dedupEnabled) {
            if (!ann.dedupKey().isEmpty()) {
                perListenerDedupKeys.put(listenerId, resolve(ann.dedupKey()));
            }
            racerMetrics.initializeDedupCounter(listenerId);
        }
        RacerProperties.ChannelProperties chProps = channelRef.isBlank()
                ? null : racerProperties.getChannels().get(channelRef);
        if (chProps != null && chProps.isDurable()) {
            if (redisTemplate == null) {
                log.warn("[RACER-LISTENER] Channel '{}' is durable but no ReactiveRedisTemplate " +
                        "is available — falling back to Pub/Sub for {}.{}()",
                        channelRef, beanName, method.getName());
            } else {
                registerDurableListener(bean, method, ann, beanName, listenerId,
                        resolvedChannel, channelRef, chProps);
                return;
            }
        }

        if (ann.mode() == ConcurrencyMode.AUTO) {
            AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner(
                    listenerId,
                    processedCounts.get(listenerId),
                    failedCounts.get(listenerId),
                    racerProperties.getThreadPool().getMaxSize());
            tuners.put(listenerId, tuner);

            log.info("[RACER-LISTENER] Registered {}.{}() <- channel '{}' (mode=AUTO, initial-concurrency={}, dedup={})",
                    beanName, method.getName(), resolvedChannel, tuner.getConcurrency(), dedupEnabled);

            // flatMap has no reactive cap (Integer.MAX_VALUE); the semaphore inside
            // AdaptiveConcurrencyTuner is the sole throttle and is dynamically resized.
            final Scheduler scheduler = schedulerForListener(listenerId,
                    racerProperties.getThreadPool().getMaxSize());
            Disposable sub = listenerContainer
                    .receive(ChannelTopic.of(resolvedChannel))
                    .flatMap(msg ->
                            Mono.fromCallable(() -> { tuner.acquireSlot(); return msg; })
                                    .subscribeOn(scheduler)
                                    .flatMap(m ->
                                            dispatch(bean, method, m, listenerId, resolvedChannel, dedupEnabled)
                                                    .doFinally(signal -> tuner.releaseSlot()))
                                    .onErrorResume(ex -> {
                                        if (ex instanceof InterruptedException) {
                                            Thread.currentThread().interrupt();
                                        }
                                        log.warn("[RACER-LISTENER] AUTO '{}' skipping message after error: {}",
                                                listenerId, ex.getMessage());
                                        return Mono.empty();
                                    }),
                            Integer.MAX_VALUE)
                    .subscribe(
                            v  -> {},
                            ex -> log.error("[RACER-LISTENER] Fatal subscription error on listener '{}': {}",
                                    listenerId, ex.getMessage(), ex));

            subscriptions.add(sub);
        } else {
            int effectiveConcurrency = ann.mode() == ConcurrencyMode.SEQUENTIAL
                    ? 1
                    : Math.max(1, ann.concurrency());

            log.info("[RACER-LISTENER] Registered {}.{}() <- channel '{}' (mode={}, concurrency={}, dedup={})",
                    beanName, method.getName(), resolvedChannel,
                    ann.mode(), effectiveConcurrency, dedupEnabled);

            final Scheduler scheduler = schedulerForListener(listenerId, effectiveConcurrency);
            Disposable sub = listenerContainer
                    .receive(ChannelTopic.of(resolvedChannel))
                    .flatMap(msg ->
                            Mono.fromCallable(() -> msg)
                                    .subscribeOn(scheduler)
                                    .flatMap(m ->
                                            dispatch(bean, method, m, listenerId, resolvedChannel, dedupEnabled))
                                    .onErrorResume(ex -> {
                                        if (ex instanceof InterruptedException) {
                                            Thread.currentThread().interrupt();
                                        }
                                        log.warn("[RACER-LISTENER] '{}' skipping message after error: {}",
                                                listenerId, ex.getMessage());
                                        return Mono.empty();
                                    }),
                            effectiveConcurrency)
                    .subscribe(
                            v  -> {},
                            ex -> log.error("[RACER-LISTENER] Fatal subscription error on listener '{}': {}",
                                    listenerId, ex.getMessage(), ex));

            subscriptions.add(sub);
        }
    }

    // ── Durable (stream-backed) listener ────────────────────────────────────

    /**
     * Registers a Redis Stream (XREADGROUP) poll loop for the given listener method.
     * Called when {@code racer.channels.<alias>.durable=true}.
     */
    private void registerDurableListener(Object bean, Method method, RacerListener ann,
                                          String beanName, String listenerId,
                                          String resolvedChannel, String channelRef,
                                          RacerProperties.ChannelProperties chProps) {
        String streamKey = chProps.getStreamKey().isBlank()
                ? resolvedChannel + ":stream" : chProps.getStreamKey();
        String group = chProps.getDurableGroup().isBlank()
                ? channelRef + "-group" : chProps.getDurableGroup();

        int effectiveConcurrency = ann.mode() == ConcurrencyMode.AUTO ? 4
                : ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1
                : Math.max(1, ann.concurrency());

        final boolean dedupEnabled = ann.dedup();
        final Duration pollInterval = Duration.ofMillis(200);
        final String consumerName = listenerId.replace('.', '-') + "-0";

        final ReactiveRedisTemplate<String, String> template = redisTemplate;

        // Ensure the consumer group exists (ignores BUSYGROUP error if already present)
        RacerStreamUtils.ensureGroup(template, streamKey, group)
                .onErrorResume(e -> Mono.empty())
                .subscribe();

        Disposable sub = Flux.defer(() ->
                        pollOnceDurable(template, bean, method, streamKey, group, consumerName,
                                listenerId, resolvedChannel, dedupEnabled, effectiveConcurrency))
                .repeatWhen(flux -> flux.flatMap(v -> Mono.delay(pollInterval)))
                .onErrorContinue((ex, o) ->
                        log.error("[RACER-LISTENER] Durable '{}' poll error: {}", listenerId, ex.getMessage()))
                .subscribe(
                        v -> {},
                        ex -> log.error("[RACER-LISTENER] Durable '{}' fatal error: {}",
                                listenerId, ex.getMessage(), ex));

        subscriptions.add(sub);
        log.info("[RACER-LISTENER] Registered (DURABLE) {}.{}() ← stream='{}' group='{}' concurrency={}",
                beanName, method.getName(), streamKey, group, effectiveConcurrency);
    }

    private Flux<Void> pollOnceDurable(ReactiveRedisTemplate<String, String> template,
                                        Object bean, Method method,
                                        String streamKey, String group, String consumerName,
                                        String listenerId, String channel,
                                        boolean dedupEnabled, int concurrency) {
        StreamReadOptions readOptions = StreamReadOptions.empty()
                .count(racerProperties.getConsumer().getPollBatchSize());
        Consumer consumer = Consumer.from(group, consumerName);
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());

        return template.opsForStream()
                .read(consumer, readOptions, offset)
                .onErrorResume(ex -> {
                    log.debug("[RACER-LISTENER] XREADGROUP on '{}' error: {}", streamKey, ex.getMessage());
                    return Flux.empty();
                })
                .flatMap(record -> {
                    if (isStopping()) {
                        return RacerStreamUtils.ackRecord(template, streamKey, group, record.getId()).then();
                    }
                    Object raw = record.getValue().get("data");
                    if (raw == null) {
                        log.warn("[RACER-LISTENER] Record {} on '{}' missing 'data' field — acking and skipping",
                                record.getId(), streamKey);
                        return RacerStreamUtils.ackRecord(template, streamKey, group, record.getId()).then();
                    }
                    String rawJson = raw.toString();
                    return dispatch(bean, method, rawJson, listenerId, channel, dedupEnabled)
                            .then(RacerStreamUtils.ackRecord(template, streamKey, group, record.getId()))
                            .then();
                }, concurrency);
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    /** Pub/Sub convenience overload — delegates to the raw-JSON overload. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatch(Object bean, Method method,
                                ReactiveSubscription.Message<String, String> redisMsg,
                                String listenerId, String channel, boolean dedupEnabled) {
        return dispatch(bean, method, redisMsg.getMessage(), listenerId, channel, dedupEnabled);
    }

    /** Core dispatch — deserialization, dedup, circuit-breaker, routing, and invocation. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatch(Object bean, Method method,
                                String rawJson,
                                String listenerId, String channel, boolean dedupEnabled) {
        // Shutdown gate — reject new messages once graceful stop has been initiated
        if (isStopping()) {
            return Mono.empty();
        }
        return Mono.<Void>defer(() -> {

            // 0a. Back-pressure gate — route to DLQ when queue is saturated
            if (backPressureActive.get()) {
                log.warn("[RACER-LISTENER] '{}' back-pressure active — routing message to DLQ", listenerId);
                racerMetrics.recordBackPressureDrop(listenerId);
                RacerMessage bpMessage;
                try {
                    bpMessage = objectMapper.readValue(rawJson, RacerMessage.class);
                } catch (Exception parseEx) {
                    log.error("[RACER-LISTENER] '{}' back-pressure drop — cannot deserialize for DLQ: {}",
                            listenerId, parseEx.getMessage());
                    return Mono.empty();
                }
                return enqueueDeadLetter(bpMessage,
                        new IllegalStateException("Back-pressure active on listener '" + listenerId + "'"))
                        .then();
            }

            // 0b. Circuit breaker gate
            RacerCircuitBreaker cb = getCircuitBreakerRegistry() != null
                    ? getCircuitBreakerRegistry().getOrCreate(listenerId) : null;
            if (cb != null && !cb.isCallPermitted()) {
                log.debug("[RACER-LISTENER] '{}' circuit {} — skipping message",
                        listenerId, cb.getState());
                racerMetrics.recordCircuitBreakerRejection(listenerId);
                return Mono.empty();
            }

            RacerMessage message;
            try {
                message = objectMapper.readValue(rawJson, RacerMessage.class);
            } catch (Exception e) {
                log.error("[RACER-LISTENER] '{}' — failed to deserialize message from channel '{}': {}",
                        listenerId, channel, e.getMessage());
                return Mono.empty();
            }

            log.debug("[RACER-LISTENER] '{}' received message id={}", listenerId, message.getId());

            // 1. Deduplication check
            if (dedupEnabled && getDedupService() != null) {
                String effectiveDedupId = resolveEffectiveDedupId(
                        message, perListenerDedupKeys.get(listenerId), channel);
                return getDedupService().checkAndMarkProcessed(effectiveDedupId, listenerId)
                        .flatMap(shouldProcess -> {
                            if (!shouldProcess) {
                                log.debug("[RACER-LISTENER] '{}' duplicate dedupId={} — skipped",
                                        listenerId, effectiveDedupId);
                                return Mono.<Void>empty();
                            }
                            return dispatchChecked(bean, method, message, listenerId, channel, cb);
                        });
            }

            return dispatchChecked(bean, method, message, listenerId, channel, cb);
        })
        .doOnSubscribe(s -> incrementInFlight())
        .doFinally(s -> decrementInFlight());
    }

    // ── Dedup key resolution ─────────────────────────────────────────────────

    /**
     * Resolves a stable dedup key for {@code message}:
     * <ol>
     *   <li>If {@code fieldKey} is non-null/non-empty, attempts to read that field from
     *       the payload JSON (which is always a String since {@code MessageEnvelopeBuilder}
     *       pre-serializes all payloads) and returns {@code channel:<fieldValue>} so that
     *       messages sharing the same business ID are always deduplicated, regardless of the
     *       auto-generated envelope UUID.</li>
     *   <li>Otherwise falls back to {@code message.getId()} — the envelope UUID.  This
     *       correctly suppresses at-most-once re-delivery of the same envelope (e.g. Redis
     *       re-publish on retry) but does NOT deduplicate distinct publishes of logically
     *       identical events.  Callers that require cross-publish business dedup should
     *       either specify {@code dedupKey} or pass a stable ID via
     *       {@link com.cheetah.racer.publisher.RacerChannelPublisher#publishAsync(Object, String, String)}.</li>
     * </ol>
     */
    private String resolveEffectiveDedupId(RacerMessage message,
                                            @Nullable String fieldKey,
                                            String channel) {
        if (fieldKey != null && !fieldKey.isEmpty()) {
            String payloadStr = message.getPayload() != null ? message.getPayload() : "";
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payloadStr);
                com.fasterxml.jackson.databind.JsonNode field = node.get(fieldKey);
                if (field != null && !field.isNull()) {
                    return channel + ":" + field.asText();
                }
                log.warn("[RACER-LISTENER] dedupKey '{}' not found in payload for channel '{}' — "
                        + "falling back to envelope id. Check that the field name is correct.",
                        fieldKey, channel);
            } catch (Exception e) {
                log.warn("[RACER-LISTENER] Could not extract dedupKey '{}' from payload: {} — "
                        + "falling back to envelope id.", fieldKey, e.getMessage());
            }
        }
        // Default: use the envelope UUID.
        // When publishers use publishAsync(payload, sender, stableBusinessId) the
        // envelope id IS the stable business key and dedup works correctly.
        return message.getId();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatchChecked(Object bean, Method method,
                                       RacerMessage message,
                                       String listenerId, String channel,
                                       @Nullable RacerCircuitBreaker cb) {
        // 2. Routing — per-listener rules first, then global (fully reactive: errors → DLQ)
        Mono<RouteDecision> routeStep;
        List<CompiledRouteRule> listenerRules = perListenerRules.get(listenerId);
        if (racerRouterService == null) {
            routeStep = Mono.just(RouteDecision.PASS);
        } else if (listenerRules != null) {
            routeStep = racerRouterService.evaluateReactive(message, listenerRules)
                    .flatMap(d -> d == RouteDecision.PASS
                            ? racerRouterService.routeReactive(message)
                            : Mono.just(d));
        } else {
            routeStep = racerRouterService.routeReactive(message);
        }

        return routeStep.flatMap(routeDecision -> {
            if (routeDecision == RouteDecision.DROPPED) {
                log.debug("[RACER-LISTENER] '{}' message id={} dropped by routing rule",
                        listenerId, message.getId());
                return Mono.empty();
            }
            if (routeDecision == RouteDecision.DROPPED_TO_DLQ) {
                log.debug("[RACER-LISTENER] '{}' message id={} dropped to DLQ by routing rule",
                        listenerId, message.getId());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message, new IllegalStateException("Dropped to DLQ by routing rule"))
                        .doOnTerminate(() -> incrementFailed(listenerId)).then();
            }
            if (routeDecision == RouteDecision.FORWARDED) {
                log.debug("[RACER-LISTENER] '{}' message id={} forwarded — skipping local dispatch",
                        listenerId, message.getId());
                return Mono.empty();
            }

            final boolean wasForwarded = routeDecision == RouteDecision.FORWARDED_AND_PROCESS;

            // 3. Interceptor chain → invoke local handler
            return buildInterceptorChain(message, listenerId, channel, method)
                    .flatMap(intercepted -> invokeLocal(bean, method, intercepted, listenerId, channel, cb, wasForwarded));
        });
    }

    /** Builds the ordered interceptor chain around a single message. */
    private Mono<RacerMessage> buildInterceptorChain(RacerMessage message,
                                                      String listenerId, String channel, Method method) {
        if (interceptors.isEmpty()) return Mono.just(message);
        InterceptorContext ctx = new InterceptorContext(listenerId, channel, method);
        Mono<RacerMessage> chain = Mono.just(message);
        for (RacerMessageInterceptor interceptor : interceptors) {
            chain = chain.flatMap(msg -> interceptor.intercept(msg, ctx));
        }
        return chain;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> invokeLocal(Object bean, Method method,
                                   RacerMessage message,
                                   String listenerId, String channel,
                                   @Nullable RacerCircuitBreaker cb,
                                   boolean wasForwarded) {
        // 4. Schema validation
        if (racerSchemaRegistry != null) {
            try {
                racerSchemaRegistry.validateForConsume(channel, message.getPayload());
            } catch (SchemaValidationException e) {
                log.warn("[RACER-LISTENER] '{}' schema validation failed for id={}: {}",
                        listenerId, message.getId(), e.getMessage());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message, e)
                        .doOnTerminate(() -> incrementFailed(listenerId))
                        .then();
            }
        }

        // 5. Resolve method arguments
        Object[] args;
        try {
            args = resolveArguments(method, message, wasForwarded);
        } catch (Exception e) {
            log.error("[RACER-LISTENER] '{}' — cannot resolve arguments for id={}: {}",
                    listenerId, message.getId(), e.getMessage());
            if (cb != null) cb.onFailure();
            return enqueueDeadLetter(message, e)
                    .doOnTerminate(() -> incrementFailed(listenerId))
                    .then();
        }

        // 6. Invoke and handle return type
        final Object[] resolvedArgs = args;
        final RacerMessage captured = message;

        Mono<Void> invocation = Mono
                .fromCallable(() -> method.invoke(bean, resolvedArgs))
                .subscribeOn(perListenerSchedulers.getOrDefault(listenerId, listenerScheduler))
                .flatMap(result -> {
                    if (result instanceof Mono<?> mono) {
                        return mono.then();
                    }
                    return Mono.<Void>empty();
                });

        return invocation
                .doOnSuccess(v -> {
                    incrementProcessed(listenerId);
                    if (cb != null) cb.onSuccess();
                    racerMetrics.recordConsumed(channel,
                            listenerId.contains(".") ? listenerId.split("\\.")[1] : listenerId);
                    log.debug("[RACER-LISTENER] '{}' processed id={}",
                            listenerId, captured.getId());
                })
                .onErrorResume((Throwable ex) -> {
                    incrementFailed(listenerId);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cb != null) cb.onFailure();
                    log.error("[RACER-LISTENER] '{}' failed processing id={}: {}",
                            listenerId, captured.getId(), cause.getMessage(), cause);
                    racerMetrics.recordFailed(channel, cause.getClass().getSimpleName());
                    return enqueueDeadLetter(captured, cause).then();
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves the array of arguments to pass to the listener method.
     *
     * <p>Supported parameter patterns:
     * <ul>
     *   <li>Zero parameters — returns an empty array.</li>
     *   <li>One primary parameter ({@link RacerMessage}, {@link String}, or any POJO)
     *       — resolved from the message.</li>
     *   <li>A {@code boolean} parameter annotated with {@link Routed}
     *       — injected with {@code wasForwarded}, indicating that the message matched a
     *       {@code FORWARD_AND_PROCESS} rule.</li>
     * </ul>
     */
    private Object[] resolveArguments(Method method, RacerMessage message,
                                      boolean wasForwarded) throws Exception {
        int count = method.getParameterCount();
        if (count == 0) return new Object[0];

        Object[] args = new Object[count];
        boolean primaryHandled = false;

        for (int i = 0; i < count; i++) {
            Parameter param = method.getParameters()[i];
            if (param.isAnnotationPresent(Routed.class)) {
                args[i] = wasForwarded;
            } else if (!primaryHandled) {
                args[i] = resolvePrimaryArgument(param.getType(), message);
                primaryHandled = true;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported parameter at index " + i + " in " + method);
            }
        }
        return args;
    }

    private Object resolvePrimaryArgument(Class<?> paramType, RacerMessage message) throws Exception {
        if (RacerMessage.class.isAssignableFrom(paramType)) return message;
        if (String.class.equals(paramType))               return message.getPayload();
        return objectMapper.readValue(message.getPayload(), paramType);
    }

    /**
     * Resolves Spring {@code ${...}} placeholders in annotation attribute values.
     */
    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try {
            return environment.resolvePlaceholders(value);
        } catch (Exception e) {
            return value;
        }
    }
}
