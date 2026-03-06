package com.cheetah.racer.listener;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.schema.SchemaValidationException;
import com.cheetah.racer.security.RacerMessageSigner;
import com.cheetah.racer.security.RacerPayloadEncryptor;
import com.cheetah.racer.security.RacerSenderFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
public class RacerListenerRegistrar implements BeanPostProcessor, EnvironmentAware, SmartLifecycle {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final RacerProperties racerProperties;
    private final Scheduler listenerScheduler;

    @Nullable
    private final RacerMetrics racerMetrics;

    @Nullable
    private final RacerSchemaRegistry racerSchemaRegistry;

    @Nullable
    private final RacerRouterService racerRouterService;

    @Nullable
    private final RacerDeadLetterHandler deadLetterHandler;

    private Environment environment;

    /** All active subscriptions keyed by listenerId for lifecycle management. */
    private final List<Disposable> subscriptions = new ArrayList<>();

    /** Per-listener message counters. Key = listenerId. */
    private final Map<String, AtomicLong> processedCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failedCounts    = new ConcurrentHashMap<>();

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
    private final AtomicBoolean backPressureActive = new AtomicBoolean(false);

    // ── Phase 4 security extensions ─────────────────────────────────────────

    /** Injected when {@code racer.security.encryption.enabled=true}. */
    @Nullable
    private RacerPayloadEncryptor payloadEncryptor;

    /** Injected when {@code racer.security.signing.enabled=true}. */
    @Nullable
    private RacerMessageSigner messageSigner;

    /** Always injected — acts as no-op when both filter lists are empty. */
    @Nullable
    private RacerSenderFilter senderFilter;

    // ── SmartLifecycle state ─────────────────────────────────────────────────
    private volatile boolean   running      = false;
    private final AtomicBoolean stopping    = new AtomicBoolean(false);
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    public void setDedupService(RacerDedupService dedupService) {
        this.dedupService = dedupService;
    }

    public void setCircuitBreakerRegistry(RacerCircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public void setPayloadEncryptor(RacerPayloadEncryptor payloadEncryptor) {
        this.payloadEncryptor = payloadEncryptor;
    }

    public void setMessageSigner(RacerMessageSigner messageSigner) {
        this.messageSigner = messageSigner;
    }

    public void setSenderFilter(RacerSenderFilter senderFilter) {
        this.senderFilter = senderFilter;
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
        this.listenerContainer   = listenerContainer;
        this.objectMapper        = objectMapper;
        this.racerProperties     = racerProperties;
        this.listenerScheduler   = listenerScheduler;
        this.racerMetrics        = racerMetrics;
        this.racerSchemaRegistry = racerSchemaRegistry;
        this.racerRouterService  = racerRouterService;
        this.deadLetterHandler   = deadLetterHandler;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
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

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        running = true;
    }

    /**
     * Graceful stop: signals shutdown, waits for in-flight messages to drain, then
     * disposes all subscriptions and invokes the Spring-provided {@code callback}.
     * The wait is bounded by {@code racer.shutdown.timeout-seconds} (default 30 s).
     */
    @Override
    public void stop(Runnable callback) {
        log.info("[RACER-LISTENER] Graceful shutdown — waiting up to {}s for in-flight messages to drain...",
                racerProperties.getShutdown().getTimeoutSeconds());
        stopping.set(true);
        long timeoutMs = racerProperties.getShutdown().getTimeoutSeconds() * 1000L;
        Mono.fromRunnable(() -> awaitDrain(timeoutMs))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doFinally(signal -> {
                    disposeAll();
                    running = false;
                    logStats();
                    callback.run();
                })
                .subscribe();
    }

    @Override
    public void stop() {
        stopping.set(true);
        awaitDrain(racerProperties.getShutdown().getTimeoutSeconds() * 1000L);
        disposeAll();
        running = false;
        logStats();
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean isAutoStartup() { return true; }

    private void awaitDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = inFlightCount.get();
        if (remaining > 0) {
            log.warn("[RACER-LISTENER] Shutdown timeout — {} message(s) still in-flight after {}ms",
                    remaining, timeoutMs);
        }
    }

    private void disposeAll() {
        int disposed = 0;
        for (Disposable sub : subscriptions) {
            if (!sub.isDisposed()) { sub.dispose(); disposed++; }
        }
        tuners.values().forEach(AdaptiveConcurrencyTuner::shutdown);
        log.info("[RACER-LISTENER] Stopped {} subscription(s).", disposed);
    }

    private void logStats() {
        processedCounts.forEach((id, cnt) ->
                log.info("[RACER-LISTENER] Listener '{}': processed={} failed={}",
                        id, cnt.get(), failedCounts.getOrDefault(id, new AtomicLong()).get()));
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerListener(Object bean, Method method, RacerListener ann, String beanName) {
        // --- resolve channel name ---
        String channel    = resolve(ann.channel());
        String channelRef = resolve(ann.channelRef());
        String resolvedChannel = resolveChannel(channel, channelRef);

        if (resolvedChannel.isEmpty()) {
            log.warn("[RACER-LISTENER] {}.{}() has @RacerListener but no channel/channelRef — skipped.",
                    beanName, method.getName());
            return;
        }

        // --- resolve listener id for logging/metrics ---
        String rawId    = ann.id().isEmpty() ? "" : resolve(ann.id());
        String listenerId = rawId.isEmpty()
                ? beanName + "." + method.getName()
                : rawId;

        // --- concurrency + subscription ---
        method.setAccessible(true);

        processedCounts.put(listenerId, new AtomicLong(0));
        failedCounts.put(listenerId,    new AtomicLong(0));

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
        if (stopping.get()) {
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
        .doOnSubscribe(s -> inFlightCount.incrementAndGet())
        .doFinally(s -> inFlightCount.decrementAndGet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatchChecked(Object bean, Method method,
                                       RacerMessage message,
                                       String listenerId, String channel,
                                       @Nullable RacerCircuitBreaker cb) {
        // S1. Sender filter — reject messages from blocked/unlisted senders
        if (senderFilter != null && senderFilter.isActive() && !senderFilter.isAllowed(message.getSender())) {
            log.warn("[RACER-LISTENER] '{}' — sender '{}' blocked by filter, dropping message id={}",
                    listenerId, message.getSender(), message.getId());
            return Mono.empty();
        }

        // S2. Signature verification — must happen BEFORE decryption (signature covers encrypted form)
        if (messageSigner != null) {
            if (!messageSigner.verify(message)) {
                log.warn("[RACER-LISTENER] '{}' — HMAC signature invalid for id={}, routing to DLQ",
                        listenerId, message.getId());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message,
                        new com.cheetah.racer.exception.RacerSecurityException(
                                "HMAC-SHA256 signature mismatch for message id=" + message.getId()))
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet())
                        .then();
            }
        }

        // S3. Payload decryption
        if (payloadEncryptor != null) {
            try {
                message.setPayload(payloadEncryptor.decrypt(message.getPayload()));
            } catch (Exception e) {
                log.warn("[RACER-LISTENER] '{}' — payload decryption failed for id={}, routing to DLQ: {}",
                        listenerId, message.getId(), e.getMessage());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message, e)
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet())
                        .then();
            }
        }

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
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet())
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
                    .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet())
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
                    processedCounts.get(listenerId).incrementAndGet();
                    if (cb != null) cb.onSuccess();
                    if (racerMetrics != null) {
                        racerMetrics.recordConsumed(channel,
                                listenerId.contains(".") ? listenerId.split("\\.")[1] : listenerId);
                    }
                    log.debug("[RACER-LISTENER] '{}' processed id={}",
                            listenerId, captured.getId());
                })
                .onErrorResume((Throwable ex) -> {
                    failedCounts.get(listenerId).incrementAndGet();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cb != null) cb.onFailure();
                    log.error("[RACER-LISTENER] '{}' failed processing id={}: {}",
                            listenerId, captured.getId(), cause.getMessage(), cause);
                    if (racerMetrics != null) {
                        racerMetrics.recordFailed(channel, cause.getClass().getSimpleName());
                    }
                    return enqueueDeadLetter(captured, cause).then();
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves the target channel name.
     * Priority: explicit channel name > channelRef lookup in {@link RacerProperties} > default channel.
     */
    private String resolveChannel(String channel, String channelRef) {
        if (!channel.isEmpty()) {
            return channel;
        }
        if (!channelRef.isEmpty()) {
            RacerProperties.ChannelProperties cp =
                    racerProperties.getChannels().get(channelRef);
            if (cp != null && cp.getName() != null && !cp.getName().isEmpty()) {
                return cp.getName();
            }
            log.warn("[RACER-LISTENER] channelRef '{}' not found in racer.channels — " +
                     "falling back to default channel.", channelRef);
        }
        return racerProperties.getDefaultChannel();
    }

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
     * Conditionally enqueues a failed message to the DLQ.
     * If no {@link RacerDeadLetterHandler} is configured, returns an empty {@code Mono}.
     */
    private Mono<?> enqueueDeadLetter(RacerMessage message, Throwable error) {
        if (deadLetterHandler != null) {
            return deadLetterHandler.enqueue(message, error)
                    .onErrorResume(dlqEx -> {
                        log.error("[RACER-LISTENER] DLQ enqueue failed for id={}: {}",
                                message.getId(), dlqEx.getMessage());
                        return Mono.empty();
                    });
        }
        log.warn("[RACER-LISTENER] No DLQ handler — dropping failed message id={}", message.getId());
        return Mono.empty();
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

    // ── Stats (visible for testing / actuator) ───────────────────────────────

    /** Returns the total processed-message count for the given listener id. */
    public long getProcessedCount(String listenerId) {
        AtomicLong c = processedCounts.get(listenerId);
        return c == null ? 0L : c.get();
    }

    /** Returns the total failed-message count for the given listener id. */
    public long getFailedCount(String listenerId) {
        AtomicLong c = failedCounts.get(listenerId);
        return c == null ? 0L : c.get();
    }
}
