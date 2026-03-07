package com.cheetah.racer.stream;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.AbstractRacerRegistrar;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
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
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
public class RacerStreamListenerRegistrar extends AbstractRacerRegistrar {

    private static final String DEFAULT_DATA_FIELD = "data";
    private static final int    GROUP_CREATION_RETRIES = 5;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final RacerMetricsPort            racerMetrics;
    @Nullable private final RacerSchemaRegistry   racerSchemaRegistry;

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

    public void setDedupService(RacerDedupService dedupService) {
        this.dedupService = dedupService;
    }

    public void setCircuitBreakerRegistry(RacerCircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
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
        super(racerProperties, deadLetterHandler);
        this.redisTemplate      = redisTemplate;
        this.objectMapper       = objectMapper;
        this.racerMetrics       = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
        this.racerSchemaRegistry = racerSchemaRegistry;
    }

    @Override
    protected String logPrefix() { return "RACER-STREAM-LISTENER"; }

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

    // ── SmartLifecycle (delegated to AbstractRacerRegistrar) ────────────────

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerStreamListener(Object bean, Method method, RacerStreamListener ann, String beanName) {
        String rawStreamKey = resolve(ann.streamKey());
        String streamKeyRef = resolve(ann.streamKeyRef());
        String streamKey    = RacerChannelResolver.resolveStreamKey(
                rawStreamKey, streamKeyRef, racerProperties, logPrefix());

        if (streamKey.isEmpty()) {
            log.warn("[RACER-STREAM-LISTENER] {}.{}() has @RacerStreamListener but no streamKey/streamKeyRef — skipped.",
                    beanName, method.getName());
            return;
        }

        String listenerId = resolveListenerId(
                ann.id().isEmpty() ? "" : resolve(ann.id()), beanName, method);
        String group      = ann.group();
        int    concurrencyN = ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1 : Math.max(1, ann.concurrency());
        int    batchSize    = ann.batchSize();
        Duration pollInterval = Duration.ofMillis(ann.pollIntervalMs());

        method.setAccessible(true);
        registerListenerStats(listenerId);

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
                    if (isStopping()) {
                        return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, record.getId());
                    }
                    incrementInFlight();
                    return processRecord(bean, method, streamKey, group, record, listenerId)
                            .doFinally(s -> decrementInFlight());
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
            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
        }

        // The data field carries the serialized RacerMessage envelope
        Object raw = fields.get(DEFAULT_DATA_FIELD);
        if (raw == null) {
            log.warn("[RACER-STREAM-LISTENER] Record {} on '{}' missing '{}' field — skipped", recordId, streamKey, DEFAULT_DATA_FIELD);
            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
        }

        String envelopeJson = raw.toString();
        RacerMessage message;
        try {
            message = objectMapper.readValue(envelopeJson, RacerMessage.class);
        } catch (Exception e) {
            log.error("[RACER-STREAM-LISTENER] '{}' — failed to deserialize entry {}: {}", listenerId, recordId, e.getMessage());
            incrementFailed(listenerId);
            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
        }

        // 0b. Deduplication check
        if (dedupService != null) {
            return dedupService.checkAndMarkProcessed(message.getId(), listenerId)
                    .flatMap(shouldProcess -> {
                        if (!shouldProcess) {
                            log.debug("[RACER-STREAM-LISTENER] '{}' duplicate id={} — skipping record {}",
                                    listenerId, message.getId(), recordId);
                            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
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

        // Schema validation
        if (racerSchemaRegistry != null) {
            try {
                racerSchemaRegistry.validateForConsume(streamKey, message.getPayload());
            } catch (Exception e) {
                log.warn("[RACER-STREAM-LISTENER] '{}' schema validation failed for {}: {}", listenerId, recordId, e.getMessage());
                if (cb != null) cb.onFailure();
                return enqueueDeadLetter(message, e)
                        .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                        .doOnTerminate(() -> incrementFailed(listenerId));
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
                    .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                    .doOnTerminate(() -> incrementFailed(listenerId));
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
                .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                .doOnSuccess(v -> {
                    incrementProcessed(listenerId);
                    if (cb != null) cb.onSuccess();
                    racerMetrics.recordConsumed(streamKey, listenerId);
                    log.debug("[RACER-STREAM-LISTENER] '{}' processed entry {}", listenerId, recordId);
                })
                .onErrorResume(ex -> {
                    incrementFailed(listenerId);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cb != null) cb.onFailure();
                    log.error("[RACER-STREAM-LISTENER] '{}' failed entry {}: {}", listenerId, recordId, cause.getMessage(), cause);
                    return enqueueDeadLetter(captured, cause)
                            .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId));
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Mono<Void> ensureGroup(String streamKey, String group) {
        return RacerStreamUtils.ensureGroup(redisTemplate, streamKey, group);
    }

    private Object resolveArgument(Method method, RacerMessage message) throws Exception {
        if (method.getParameterCount() == 0) return null;
        Class<?> paramType = method.getParameterTypes()[0];
        if (RacerMessage.class.isAssignableFrom(paramType)) return message;
        if (String.class.equals(paramType)) return message.getPayload();
        return objectMapper.readValue(message.getPayload(), paramType);
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try { return environment.resolvePlaceholders(value); } catch (Exception e) { return value; }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public long getProcessedCount(String listenerId) {
        return super.getProcessedCount(listenerId);
    }

    public long getFailedCount(String listenerId) {
        return super.getFailedCount(listenerId);
    }
}
