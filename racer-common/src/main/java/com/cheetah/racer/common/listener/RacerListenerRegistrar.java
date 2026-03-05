package com.cheetah.racer.common.listener;

import com.cheetah.racer.common.annotation.ConcurrencyMode;
import com.cheetah.racer.common.annotation.RacerListener;
import com.cheetah.racer.common.config.RacerProperties;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.model.RacerMessage;
import com.cheetah.racer.common.router.RacerRouterService;
import com.cheetah.racer.common.schema.RacerSchemaRegistry;
import com.cheetah.racer.common.schema.SchemaValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>All active subscriptions are disposed cleanly on application shutdown (via {@link PreDestroy}).
 *
 * @see RacerListener
 * @see ConcurrencyMode
 * @see RacerDeadLetterHandler
 */
@Slf4j
public class RacerListenerRegistrar implements BeanPostProcessor, EnvironmentAware {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final RacerProperties racerProperties;

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

    public RacerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            @Nullable RacerMetrics racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerRouterService racerRouterService,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        this.listenerContainer   = listenerContainer;
        this.objectMapper        = objectMapper;
        this.racerProperties     = racerProperties;
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

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @PreDestroy
    public void stop() {
        int disposed = 0;
        for (Disposable sub : subscriptions) {
            if (!sub.isDisposed()) {
                sub.dispose();
                disposed++;
            }
        }
        log.info("[RACER-LISTENER] Stopped {} subscription(s).", disposed);
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

        // --- concurrency ---
        int effectiveConcurrency = ann.mode() == ConcurrencyMode.SEQUENTIAL
                ? 1
                : Math.max(1, ann.concurrency());

        method.setAccessible(true);

        processedCounts.put(listenerId, new AtomicLong(0));
        failedCounts.put(listenerId,    new AtomicLong(0));

        log.info("[RACER-LISTENER] Registered {}.{}() <- channel '{}' (mode={}, concurrency={})",
                beanName, method.getName(), resolvedChannel,
                ann.mode(), effectiveConcurrency);

        Disposable sub = listenerContainer
                .receive(ChannelTopic.of(resolvedChannel))
                .flatMap(
                        msg -> dispatch(bean, method, msg, listenerId, resolvedChannel),
                        effectiveConcurrency)
                .subscribe(
                        v  -> { /* completions handled inside dispatch */ },
                        ex -> log.error("[RACER-LISTENER] Fatal subscription error on listener '{}': {}",
                                listenerId, ex.getMessage(), ex));

        subscriptions.add(sub);
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> dispatch(Object bean, Method method,
                                ReactiveSubscription.Message<String, String> redisMsg,
                                String listenerId, String channel) {
        return Mono.defer(() -> {
            RacerMessage message;
            try {
                message = objectMapper.readValue(redisMsg.getMessage(), RacerMessage.class);
            } catch (Exception e) {
                log.error("[RACER-LISTENER] '{}' — failed to deserialize message from channel '{}': {}",
                        listenerId, channel, e.getMessage());
                return Mono.empty();
            }

            log.debug("[RACER-LISTENER] '{}' received message id={}", listenerId, message.getId());

            // 1. Content-based routing — re-publish & skip local dispatch if a rule matches
            if (racerRouterService != null && racerRouterService.route(message)) {
                log.debug("[RACER-LISTENER] '{}' message id={} routed — skipping local dispatch",
                        listenerId, message.getId());
                return Mono.empty();
            }

            // 2. Schema validation
            if (racerSchemaRegistry != null) {
                try {
                    racerSchemaRegistry.validateForConsume(channel, message.getPayload());
                } catch (SchemaValidationException e) {
                    log.warn("[RACER-LISTENER] '{}' schema validation failed for id={}: {}",
                            listenerId, message.getId(), e.getMessage());
                    return enqueueDeadLetter(message, e)
                            .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet())
                            .then();
                }
            }

            // 3. Resolve method argument
            Object arg;
            try {
                arg = resolveArgument(method, message);
            } catch (Exception e) {
                log.error("[RACER-LISTENER] '{}' — cannot resolve argument for id={}: {}",
                        listenerId, message.getId(), e.getMessage());
                return enqueueDeadLetter(message, e)
                        .doOnTerminate(() -> failedCounts.get(listenerId).incrementAndGet())
                        .then();
            }

            // 4. Invoke and handle return type
            final Object resolvedArg = arg;
            final boolean isNoArg = method.getParameterCount() == 0;
            final RacerMessage captured = message;

            Mono<Void> invocation = Mono
                    .fromCallable(() -> isNoArg
                            ? method.invoke(bean)
                            : method.invoke(bean, resolvedArg))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(result -> {
                        if (result instanceof Mono<?> mono) {
                            return mono.then();
                        }
                        return Mono.<Void>empty();
                    });

            return invocation
                    .doOnSuccess(v -> {
                        processedCounts.get(listenerId).incrementAndGet();
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
                        log.error("[RACER-LISTENER] '{}' failed processing id={}: {}",
                                listenerId, captured.getId(), cause.getMessage(), cause);
                        if (racerMetrics != null) {
                            racerMetrics.recordFailed(channel, cause.getClass().getSimpleName());
                        }
                        return enqueueDeadLetter(captured, cause).then();
                    });
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
