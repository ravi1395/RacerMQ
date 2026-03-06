package com.cheetah.racer.stream;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.schema.RacerSchemaRegistry;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link BeanPostProcessor} that discovers methods annotated with {@link RacerStreamListener}
 * and registers them as consumer-group readers for Redis Streams at application startup.
 *
 * <h3>Processing pipeline (per stream entry)</h3>
 * <ol>
 *   <li>Read up to {@code batchSize} entries per poll via {@code XREADGROUP}.</li>
 *   <li>Parse the {@code data} field of each entry into a {@link RacerMessage} envelope.</li>
 *   <li>Validate against schema if {@link RacerSchemaRegistry} is available.</li>
 *   <li>Resolve method argument by declared parameter type (same rules as {@link
 *       com.cheetah.racer.listener.RacerListenerRegistrar}).</li>
 *   <li>Invoke the annotated method on {@code boundedElastic} scheduler.</li>
 *   <li>On success: ACK the entry, increment processed counter.</li>
 *   <li>On failure: ACK the entry (prevent infinite redelivery) + enqueue to DLQ.</li>
 * </ol>
 *
 * @see RacerStreamListener
 */
@Slf4j
public class RacerStreamListenerRegistrar implements BeanPostProcessor, EnvironmentAware, SmartLifecycle {

    private static final String DEFAULT_DATA_FIELD = "data";
    private static final int    GROUP_CREATION_RETRIES = 5;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RacerProperties racerProperties;

    @Nullable private final RacerMetrics          racerMetrics;
    @Nullable private final RacerSchemaRegistry   racerSchemaRegistry;
    @Nullable private final RacerDeadLetterHandler deadLetterHandler;

    private Environment environment;

    private final List<Disposable>              subscriptions    = new ArrayList<>();
    private final Map<String, AtomicLong>       processedCounts  = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong>       failedCounts     = new ConcurrentHashMap<>();

    // ── Phase 3 optional extensions ─────────────────────────────────────────

    /** Injected when {@code racer.dedup.enabled=true}. */
    @Nullable
    private RacerDedupService dedupService;

    /** Injected when {@code racer.circuit-breaker.enabled=true}. */
    @Nullable
    private RacerCircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * When non-zero, overrides the annotation-defined poll interval for all stream listeners.
     * Set by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}.
     * Value 0 means “use the annotation value”.
     */
    private final AtomicLong backPressurePollOverrideMs = new AtomicLong(0);

    /** (streamKey, group) pairs that have been registered, used by {@link RacerConsumerLagMonitor}. */
    private final Map<String, String> trackedStreamGroups = new ConcurrentHashMap<>();
    // ── Phase 4 security extensions ───────────────────────────────────────

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
    private volatile boolean    running       = false;
    private final AtomicBoolean stopping      = new AtomicBoolean(false);
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
     * Called by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}.
     * Pass {@code 0} to revert to the annotation-defined interval.
     */
    public void setBackPressurePollIntervalMs(long ms) {
        backPressurePollOverrideMs.set(ms);
    }

    /** Returns registered (streamKey → group) pairs for consumer-lag tracking. */
    public Map<String, String> getTrackedStreamGroups() {
        return Collections.unmodifiableMap(trackedStreamGroups);
    }

    public RacerStreamListenerRegistrar(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            @Nullable RacerMetrics racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        this.redisTemplate      = redisTemplate;
        this.objectMapper       = objectMapper;
        this.racerProperties    = racerProperties;
        this.racerMetrics       = racerMetrics;
        this.racerSchemaRegistry = racerSchemaRegistry;
        this.deadLetterHandler  = deadLetterHandler;
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
            RacerStreamListener ann = method.getAnnotation(RacerStreamListener.class);
            if (ann != null) {
                registerStreamListener(bean, method, ann, beanName);
            }
        }
        return bean;
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Graceful stop: signals shutdown, waits for in-flight stream records to drain
     * (bounded by {@code racer.shutdown.timeout-seconds}), then disposes all polling loops.
     * XACK has already been issued by the time a record completes, so no entries are orphaned.
     */
    @Override
    public void stop(Runnable callback) {
        log.info("[RACER-STREAM-LISTENER] Graceful shutdown — waiting up to {}s for in-flight entries to drain...",
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
            log.warn("[RACER-STREAM-LISTENER] Shutdown timeout — {} entry(ies) still in-flight after {}ms",
                    remaining, timeoutMs);
        }
    }

    private void disposeAll() {
        int disposed = 0;
        for (Disposable sub : subscriptions) {
            if (!sub.isDisposed()) { sub.dispose(); disposed++; }
        }
        log.info("[RACER-STREAM-LISTENER] Stopped {} polling loop(s).", disposed);
    }

    private void logStats() {
        processedCounts.forEach((id, cnt) ->
                log.info("[RACER-STREAM-LISTENER] Listener '{}': processed={} failed={}",
                        id, cnt.get(), failedCounts.getOrDefault(id, new AtomicLong()).get()));
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerStreamListener(Object bean, Method method, RacerStreamListener ann, String beanName) {
        String rawStreamKey = resolve(ann.streamKey());
        String streamKeyRef = resolve(ann.streamKeyRef());
        String streamKey    = resolveStreamKey(rawStreamKey, streamKeyRef);

        if (streamKey.isEmpty()) {
            log.warn("[RACER-STREAM-LISTENER] {}.{}() has @RacerStreamListener but no streamKey/streamKeyRef — skipped.",
                    beanName, method.getName());
            return;
        }

        String rawId      = ann.id().isEmpty() ? "" : resolve(ann.id());
        String listenerId = rawId.isEmpty() ? beanName + "." + method.getName() : rawId;
        String group      = ann.group();
        int    concurrencyN = ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1 : Math.max(1, ann.concurrency());
        int    batchSize    = ann.batchSize();
        Duration pollInterval = Duration.ofMillis(ann.pollIntervalMs());

        method.setAccessible(true);
        processedCounts.put(listenerId, new AtomicLong(0));
        failedCounts.put(listenerId,    new AtomicLong(0));

        // Register for consumer-lag tracking (3.4)
        trackedStreamGroups.put(streamKey, group);

        log.info("[RACER-STREAM-LISTENER] Registering {}.{}() <- stream '{}' group='{}' mode={} concurrency={}",
                beanName, method.getName(), streamKey, group, ann.mode(), concurrencyN);

        final String finalStreamKey = streamKey;
        final String finalListenerId = listenerId;

        ensureGroup(streamKey, group)
                .retryWhen(Retry.backoff(GROUP_CREATION_RETRIES, Duration.ofSeconds(2))
                        .doBeforeRetry(rs -> log.warn(
                                "[RACER-STREAM-LISTENER] Retrying group creation on '{}' (attempt {})",
                                finalStreamKey, rs.totalRetries() + 1)))
                .doOnSuccess(v -> {
                    log.info("[RACER-STREAM-LISTENER] Group '{}' ready on '{}'", group, finalStreamKey);
                    for (int i = 0; i < concurrencyN; i++) {
                        String consumerName = listenerId + "-" + i;
                        Disposable d = buildPollLoop(bean, method, finalStreamKey, group,
                                consumerName, batchSize, pollInterval, finalListenerId)
                                .subscribe(
                                        n -> {},
                                        ex -> log.error("[RACER-STREAM-LISTENER] Consumer '{}' errored: {}",
                                                consumerName, ex.getMessage()));
                        subscriptions.add(d);
                        log.info("[RACER-STREAM-LISTENER] Consumer '{}' started on '{}'", consumerName, finalStreamKey);
                    }
                })
                .doOnError(e -> log.error("[RACER-STREAM-LISTENER] Failed to init group '{}' on '{}': {}",
                        group, finalStreamKey, e.getMessage()))
                .subscribe();
    }

    // ── Polling loop ─────────────────────────────────────────────────────────

    private Flux<Void> buildPollLoop(Object bean, Method method,
                                     String streamKey, String group, String consumer,
                                     int batchSize, Duration pollInterval, String listenerId) {
        return Flux.defer(() -> pollOnce(bean, method, streamKey, group, consumer, batchSize, listenerId))
                .repeatWhen(completed -> completed.flatMap(v -> {
                    long overrideMs = backPressurePollOverrideMs.get();
                    long delayMs = overrideMs > 0 ? overrideMs : pollInterval.toMillis();
                    return Mono.delay(Duration.ofMillis(delayMs));
                }))
                .onErrorContinue((ex, o) ->
                        log.error("[RACER-STREAM-LISTENER] Poll error on '{}': {}", streamKey, ex.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Flux<Void> pollOnce(Object bean, Method method,
                                String streamKey, String group, String consumer,
                                int batchSize, String listenerId) {
        StreamReadOptions readOptions = StreamReadOptions.empty().count(batchSize);
        StreamOffset<String> offset   = StreamOffset.create(streamKey, ReadOffset.lastConsumed());
        Consumer redisConsumer = Consumer.from(group, consumer);

        return redisTemplate
                .opsForStream()
                .read(redisConsumer, readOptions, offset)
                .onErrorResume(ex -> {
                    log.debug("[RACER-STREAM-LISTENER] XREADGROUP on '{}' returned empty or error: {}", streamKey, ex.getMessage());
                    return Flux.empty();
                })
                .flatMap(record -> {
                    if (stopping.get()) {
                        return ackRecord(streamKey, group, record.getId());
                    }
                    inFlightCount.incrementAndGet();
                    return processRecord(bean, method, streamKey, group, record, listenerId)
                            .doFinally(s -> inFlightCount.decrementAndGet());
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<Void> processRecord(Object bean, Method method,
                                     String streamKey, String group,
                                     MapRecord<String, Object, Object> record, String listenerId) {
        RecordId recordId = record.getId();
        Map<Object, Object> fields = record.getValue();

        // 0a. Circuit breaker gate
        RacerCircuitBreaker cb = circuitBreakerRegistry != null
                ? circuitBreakerRegistry.getOrCreate(listenerId) : null;
        if (cb != null && !cb.isCallPermitted()) {
            log.debug("[RACER-STREAM-LISTENER] '{}' circuit {} — skipping record {}",
                    listenerId, cb.getState(), recordId);
            // ACK to avoid building up pending entries while the circuit is open
            return ackRecord(streamKey, group, recordId);
        }

        // The data field carries the serialized RacerMessage envelope
        Object raw = fields.get(DEFAULT_DATA_FIELD);
        if (raw == null) {
            log.warn("[RACER-STREAM-LISTENER] Record {} on '{}' missing '{}' field — skipped", recordId, streamKey, DEFAULT_DATA_FIELD);
            return ackRecord(streamKey, group, recordId);
        }

        String envelopeJson = raw.toString();
        RacerMessage message;
        try {
            message = objectMapper.readValue(envelopeJson, RacerMessage.class);
        } catch (Exception e) {
            log.error("[RACER-STREAM-LISTENER] '{}' — failed to deserialize entry {}: {}", listenerId, recordId, e.getMessage());
            failedCounts.get(listenerId).incrementAndGet();
            return ackRecord(streamKey, group, recordId);
        }

        // 0b. Deduplication check
        if (dedupService != null) {
            return dedupService.checkAndMarkProcessed(message.getId(), listenerId)
                    .flatMap(shouldProcess -> {
                        if (!shouldProcess) {
                            log.debug("[RACER-STREAM-LISTENER] '{}' duplicate id={} — skipping record {}",
                                    listenerId, message.getId(), recordId);
                            return ackRecord(streamKey, group, recordId);
                        }
                        return processChecked(bean, method, streamKey, group, record, listenerId, message, cb);
                    });
        }

        return processChecked(bean, method, streamKey, group, record, listenerId, message, cb);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<Void> processChecked(Object bean, Method method,
                                      String streamKey, String group,
                                      MapRecord<String, Object, Object> record, String listenerId,
                                      RacerMessage message, @Nullable RacerCircuitBreaker cb) {
        RecordId recordId = record.getId();

        // S1. Sender filter — reject messages from blocked/unlisted senders
        if (senderFilter != null && senderFilter.isActive() && !senderFilter.isAllowed(message.getSender())) {
            log.warn("[RACER-STREAM-LISTENER] '{}' — sender '{}' blocked by filter, dropping entry {}",
                    listenerId, message.getSender(), recordId);
            return ackRecord(streamKey, group, recordId);
        }

        // S2. Signature verification — must happen BEFORE decryption (signature covers encrypted form)
        if (messageSigner != null) {
            if (!messageSigner.verify(message)) {
                log.warn("[RACER-STREAM-LISTENER] '{}' — HMAC signature invalid for entry {}, routing to DLQ",
                        listenerId, recordId);
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message,
                        new com.cheetah.racer.exception.RacerSecurityException(
                                "HMAC-SHA256 signature mismatch for entry " + recordId))
                        .then(ackRecord(streamKey, group, recordId))
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet());
            }
        }

        // S3. Payload decryption
        if (payloadEncryptor != null) {
            try {
                message.setPayload(payloadEncryptor.decrypt(message.getPayload()));
            } catch (Exception e) {
                log.warn("[RACER-STREAM-LISTENER] '{}' — payload decryption failed for entry {}: {}",
                        listenerId, recordId, e.getMessage());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message, e)
                        .then(ackRecord(streamKey, group, recordId))
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet());
            }
        }

        // Schema validation
        if (racerSchemaRegistry != null) {
            try {
                racerSchemaRegistry.validateForConsume(streamKey, message.getPayload());
            } catch (Exception e) {
                log.warn("[RACER-STREAM-LISTENER] '{}' schema validation failed for {}: {}", listenerId, recordId, e.getMessage());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message, e)
                        .then(ackRecord(streamKey, group, recordId))
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet());
            }
        }

        // Resolve argument
        Object arg;
        try {
            arg = resolveArgument(method, message);
        } catch (Exception e) {
            log.error("[RACER-STREAM-LISTENER] '{}' — cannot resolve argument for {}: {}", listenerId, recordId, e.getMessage());
            if (cb != null) cb.onFailure();
            return enqueueDeadLetter(message, e)
                    .then(ackRecord(streamKey, group, recordId))
                    .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet());
        }

        final Object resolvedArg  = arg;
        final boolean isNoArg     = method.getParameterCount() == 0;
        final RacerMessage captured = message;

        Mono<Void> invocation = Mono
                .fromCallable(() -> isNoArg
                        ? method.invoke(bean)
                        : method.invoke(bean, resolvedArg))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if (result instanceof Mono<?> mono) return mono.then();
                    return Mono.<Void>empty();
                });

        return invocation
                .then(ackRecord(streamKey, group, recordId))
                .doOnSuccess(v -> {
                    processedCounts.get(listenerId).incrementAndGet();
                    if (cb != null) cb.onSuccess();
                    if (racerMetrics != null) {
                        racerMetrics.recordConsumed(streamKey, listenerId);
                    }
                    log.debug("[RACER-STREAM-LISTENER] '{}' processed entry {}", listenerId, recordId);
                })
                .onErrorResume(ex -> {
                    failedCounts.get(listenerId).incrementAndGet();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cb != null) cb.onFailure();
                    log.error("[RACER-STREAM-LISTENER] '{}' failed entry {}: {}", listenerId, recordId, cause.getMessage(), cause);
                    return enqueueDeadLetter(captured, cause)
                            .then(ackRecord(streamKey, group, recordId));
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Mono<Void> ensureGroup(String streamKey, String group) {
        return redisTemplate.opsForStream()
                .createGroup(streamKey, ReadOffset.from("0"), group)
                .onErrorResume(ex -> {
                    if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                        log.debug("[RACER-STREAM-LISTENER] Group '{}' already exists on '{}'", group, streamKey);
                        return Mono.empty();
                    }
                    return Mono.error(ex);
                })
                .then();
    }

    private Mono<Void> ackRecord(String streamKey, String group, RecordId recordId) {
        return redisTemplate.opsForStream()
                .acknowledge(streamKey, group, recordId)
                .then();
    }

    private String resolveStreamKey(String streamKey, String streamKeyRef) {
        if (!streamKey.isEmpty()) return streamKey;
        if (!streamKeyRef.isEmpty()) {
            RacerProperties.ChannelProperties cp = racerProperties.getChannels().get(streamKeyRef);
            if (cp != null && cp.getName() != null && !cp.getName().isEmpty()) return cp.getName();
            log.warn("[RACER-STREAM-LISTENER] streamKeyRef '{}' not found in racer.channels — falling back to default channel.", streamKeyRef);
        }
        return racerProperties.getDefaultChannel();
    }

    private Object resolveArgument(Method method, RacerMessage message) throws Exception {
        if (method.getParameterCount() == 0) return null;
        Class<?> paramType = method.getParameterTypes()[0];
        if (RacerMessage.class.isAssignableFrom(paramType)) return message;
        if (String.class.equals(paramType)) return message.getPayload();
        return objectMapper.readValue(message.getPayload(), paramType);
    }

    private Mono<?> enqueueDeadLetter(RacerMessage message, Throwable error) {
        if (deadLetterHandler != null) {
            return deadLetterHandler.enqueue(message, error)
                    .onErrorResume(dlqEx -> {
                        log.error("[RACER-STREAM-LISTENER] DLQ enqueue failed for id={}: {}", message.getId(), dlqEx.getMessage());
                        return Mono.empty();
                    });
        }
        log.warn("[RACER-STREAM-LISTENER] No DLQ handler — dropping failed message id={}", message.getId());
        return Mono.empty();
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try { return environment.resolvePlaceholders(value); } catch (Exception e) { return value; }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public long getProcessedCount(String listenerId) {
        AtomicLong c = processedCounts.get(listenerId);
        return c == null ? 0L : c.get();
    }

    public long getFailedCount(String listenerId) {
        AtomicLong c = failedCounts.get(listenerId);
        return c == null ? 0L : c.get();
    }
}
