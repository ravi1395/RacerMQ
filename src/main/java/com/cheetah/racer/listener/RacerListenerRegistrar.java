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
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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

    private final RacerMetricsPort racerMetrics;

    @Nullable
    private final RacerSchemaRegistry racerSchemaRegistry;

    @Nullable
    private final RacerRouterService racerRouterService;

    /** Adaptive tuners for AUTO-mode listeners — shut down on application stop. */
    private final Map<String, AdaptiveConcurrencyTuner> tuners = new ConcurrentHashMap<>();

    // ── Phase 3 optional extensions ─────────────────────────────────────────

    /** Injected when {@code racer.dedup.enabled=true}. */
    @Nullable
    private RacerDedupService dedupService;

    /** Injected when {@code racer.circuit-breaker.enabled=true}. */
    @Nullable
    private RacerCircuitBreakerRegistry circuitBreakerRegistry;

    /** Set by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}. */
    private final java.util.concurrent.atomic.AtomicBoolean backPressureActive = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Pre-compiled per-listener routing rules (from method-level {@code @RacerRoute}). */
    private final Map<String, List<CompiledRouteRule>> perListenerRules = new ConcurrentHashMap<>();

    /** Ordered list of message interceptors applied before every handler invocation. */
    private volatile List<RacerMessageInterceptor> interceptors = Collections.emptyList();

    public void setDedupService(RacerDedupService dedupService) {
        this.dedupService = dedupService;
    }

    public void setCircuitBreakerRegistry(RacerCircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
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
                racerMetrics, racerSchemaRegistry, racerRouterService, deadLetterHandler);
    }

    /** Production constructor — uses the dedicated Racer listener thread pool. */
    public RacerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            Scheduler listenerScheduler,
            @Nullable RacerMetrics racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerRouterService racerRouterService,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        super(racerProperties, deadLetterHandler);
        this.listenerContainer   = listenerContainer;
        this.objectMapper        = objectMapper;
        this.listenerScheduler   = listenerScheduler;
        this.racerMetrics        = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
        this.racerSchemaRegistry = racerSchemaRegistry;
        this.racerRouterService  = racerRouterService;
    }

    @Override
    protected String logPrefix() { return "RACER-LISTENER"; }

    @Override
    protected void additionalDisposeCleanup() {
        tuners.values().forEach(AdaptiveConcurrencyTuner::shutdown);
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
            Disposable sub = listenerContainer
                    .receive(ChannelTopic.of(resolvedChannel))
                    .flatMap(msg ->
                            Mono.fromCallable(() -> { tuner.acquireSlot(); return msg; })
                                    .subscribeOn(listenerScheduler)
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

            Disposable sub = listenerContainer
                    .receive(ChannelTopic.of(resolvedChannel))
                    .flatMap(
                            msg -> dispatch(bean, method, msg, listenerId, resolvedChannel, dedupEnabled),
                            effectiveConcurrency)
                    .subscribe(
                            v  -> {},
                            ex -> log.error("[RACER-LISTENER] Fatal subscription error on listener '{}': {}",
                                    listenerId, ex.getMessage(), ex));

            subscriptions.add(sub);
        }
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatch(Object bean, Method method,
                                ReactiveSubscription.Message<String, String> redisMsg,
                                String listenerId, String channel, boolean dedupEnabled) {
        // Shutdown gate — reject new messages once graceful stop has been initiated
        if (isStopping()) {
            return Mono.empty();
        }
        return Mono.<Void>defer(() -> {

            // 0a. Back-pressure gate — silently drop when queue is saturated
            if (backPressureActive.get()) {
                log.debug("[RACER-LISTENER] '{}' back-pressure active — dropping message", listenerId);
                return Mono.empty();
            }

            // 0b. Circuit breaker gate
            RacerCircuitBreaker cb = circuitBreakerRegistry != null
                    ? circuitBreakerRegistry.getOrCreate(listenerId) : null;
            if (cb != null && !cb.isCallPermitted()) {
                log.debug("[RACER-LISTENER] '{}' circuit {} — skipping message",
                        listenerId, cb.getState());
                return Mono.empty();
            }

            RacerMessage message;
            try {
                message = objectMapper.readValue(redisMsg.getMessage(), RacerMessage.class);
            } catch (Exception e) {
                log.error("[RACER-LISTENER] '{}' — failed to deserialize message from channel '{}': {}",
                        listenerId, channel, e.getMessage());
                return Mono.empty();
            }

            log.debug("[RACER-LISTENER] '{}' received message id={}", listenerId, message.getId());

            // 1. Deduplication check
            if (dedupEnabled && dedupService != null) {
                return dedupService.checkAndMarkProcessed(message.getId(), listenerId)
                        .flatMap(shouldProcess -> {
                            if (!shouldProcess) {
                                log.debug("[RACER-LISTENER] '{}' duplicate id={} — skipped",
                                        listenerId, message.getId());
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatchChecked(Object bean, Method method,
                                       RacerMessage message,
                                       String listenerId, String channel,
                                       @Nullable RacerCircuitBreaker cb) {
        // 2. Routing — per-listener rules first, then global
        RouteDecision routeDecision = RouteDecision.PASS;
        List<CompiledRouteRule> listenerRules = perListenerRules.get(listenerId);
        if (listenerRules != null && racerRouterService != null) {
            routeDecision = racerRouterService.evaluate(message, listenerRules);
        }
        if (routeDecision == RouteDecision.PASS && racerRouterService != null) {
            routeDecision = racerRouterService.route(message);
        }

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
                .subscribeOn(listenerScheduler)
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
