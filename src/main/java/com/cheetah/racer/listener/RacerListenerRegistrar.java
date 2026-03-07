package com.cheetah.racer.listener;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.RacerRouterService;
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

    public void setDedupService(RacerDedupService dedupService) {
        this.dedupService = dedupService;
    }

    public void setCircuitBreakerRegistry(RacerCircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
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
        // 2. Content-based routing — re-publish & skip local dispatch if a rule matches
        if (racerRouterService != null && racerRouterService.route(message)) {
            log.debug("[RACER-LISTENER] '{}' message id={} routed — skipping local dispatch",
                    listenerId, message.getId());
            return Mono.empty();
        }

        // 3. Schema validation
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

        // 4. Resolve method argument
        Object arg;
        try {
            arg = resolveArgument(method, message);
        } catch (Exception e) {
            log.error("[RACER-LISTENER] '{}' — cannot resolve argument for id={}: {}",
                    listenerId, message.getId(), e.getMessage());
            if (cb != null) cb.onFailure();
            return enqueueDeadLetter(message, e)
                    .doOnTerminate(() -> incrementFailed(listenerId))
                    .then();
        }

        // 5. Invoke and handle return type
        final Object resolvedArg = arg;
        final boolean isNoArg = method.getParameterCount() == 0;
        final RacerMessage captured = message;

        Mono<Void> invocation = Mono
                .fromCallable(() -> isNoArg
                        ? method.invoke(bean)
                        : method.invoke(bean, resolvedArg))
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
     * Resolves the value of a method argument from the incoming {@link RacerMessage}
     * based solely on the declared parameter type.
     */
    private Object resolveArgument(Method method, RacerMessage message) throws Exception {
        if (method.getParameterCount() == 0) {
            return null; // no-arg receiver — unusual but supported
        }
        Class<?> paramType = method.getParameterTypes()[0];

        if (RacerMessage.class.isAssignableFrom(paramType)) {
            return message;
        }
        if (String.class.equals(paramType)) {
            return message.getPayload();
        }
        // Flexible deserialization: parse payload JSON into the declared POJO type
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
