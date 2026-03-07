package com.cheetah.racer.requestreply;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerResponder;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerReply;
import com.cheetah.racer.model.RacerRequest;
import com.cheetah.racer.stream.RacerStreamUtils;
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link BeanPostProcessor} that discovers methods annotated with {@link RacerResponder}
 * and wires them up as request-reply handlers for either Redis Pub/Sub or Redis Streams.
 *
 * <h3>Pub/Sub path</h3>
 * Subscribes to the configured channel via the listener container. Incoming messages are
 * tentatively deserialized as {@link RacerRequest}. If the message has a non-blank
 * {@code replyTo} field, it is treated as a request; the annotated method is invoked
 * and the result is published to the {@code replyTo} channel as a {@link RacerReply}.
 * Fire-and-forget messages (no {@code replyTo}) are silently ignored.
 *
 * <h3>Stream path</h3>
 * Creates a consumer group on the configured stream, polls via {@code XREADGROUP},
 * invokes the method, writes the reply to the ephemeral response stream specified in
 * the request's {@code replyTo} field, then ACKs the entry.
 *
 * <h3>Error handling</h3>
 * Exceptions thrown by the annotated method result in a {@link RacerReply#failure} reply
 * (rather than dropping the request silently), giving callers actionable error information.
 *
 * @see RacerResponder
 * @see RacerRequest
 * @see RacerReply
 */
@Slf4j
public class RacerResponderRegistrar implements BeanPostProcessor, EnvironmentAware {

    private static final String CONSUMER_GROUP = "racer-responder-group";
    private static final int    GROUP_CREATION_RETRIES = 5;

    @Nullable private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RacerProperties racerProperties;
    private final RacerMetricsPort racerMetrics;

    private Environment environment;

    private final List<Disposable>          subscriptions  = new ArrayList<>();
    private final Map<String, AtomicLong>   repliedCounts  = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong>   failedCounts   = new ConcurrentHashMap<>();

    public RacerResponderRegistrar(
            @Nullable ReactiveRedisMessageListenerContainer listenerContainer,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            @Nullable RacerMetrics racerMetrics) {
        this.listenerContainer = listenerContainer;
        this.redisTemplate     = redisTemplate;
        this.objectMapper      = objectMapper;
        this.racerProperties   = racerProperties;
        this.racerMetrics      = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            RacerResponder ann = method.getAnnotation(RacerResponder.class);
            if (ann != null) {
                registerResponder(bean, method, ann, beanName);
            }
        }
        return bean;
    }

    @PreDestroy
    public void stop() {
        int disposed = 0;
        for (Disposable d : subscriptions) {
            if (!d.isDisposed()) { d.dispose(); disposed++; }
        }
        log.info("[RACER-RESPONDER] Stopped {} subscription(s).", disposed);
        repliedCounts.forEach((id, cnt) ->
                log.info("[RACER-RESPONDER] Responder '{}': replied={} failed={}",
                        id, cnt.get(), failedCounts.getOrDefault(id, new AtomicLong()).get()));
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerResponder(Object bean, Method method, RacerResponder ann, String beanName) {
        String rawId      = ann.id().isEmpty() ? "" : resolve(ann.id());
        String responderId = rawId.isEmpty() ? beanName + "." + method.getName() : rawId;

        method.setAccessible(true);
        repliedCounts.put(responderId, new AtomicLong(0));
        failedCounts.put(responderId,  new AtomicLong(0));

        boolean hasPubSub = !ann.channel().isEmpty() || !ann.channelRef().isEmpty();
        boolean hasStream = !ann.stream().isEmpty() || !ann.streamRef().isEmpty();

        if (hasPubSub) {
            registerPubSubResponder(bean, method, ann, responderId);
        } else if (hasStream) {
            registerStreamResponder(bean, method, ann, responderId);
        } else {
            log.warn("[RACER-RESPONDER] {}.{}() has @RacerResponder but no channel/stream — skipped.",
                    beanName, method.getName());
        }
    }

    // ── Pub/Sub responder ─────────────────────────────────────────────────────

    private void registerPubSubResponder(Object bean, Method method, RacerResponder ann, String responderId) {
        if (listenerContainer == null) {
            log.warn("[RACER-RESPONDER] '{}' uses Pub/Sub mode but no ReactiveRedisMessageListenerContainer is available — skipped.", responderId);
            return;
        }

        String channel = RacerChannelResolver.resolveChannel(resolve(ann.channel()), resolve(ann.channelRef()), racerProperties);
        int effectiveConcurrency = ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1 : Math.max(1, ann.concurrency());

        log.info("[RACER-RESPONDER] Registering Pub/Sub responder '{}' <- channel '{}'", responderId, channel);

        Disposable sub = listenerContainer
                .receive(ChannelTopic.of(channel))
                .flatMap(
                        msg -> handlePubSubMessage(bean, method, msg.getMessage(), responderId),
                        effectiveConcurrency)
                .subscribe(
                        v  -> {},
                        ex -> log.error("[RACER-RESPONDER] Fatal subscription error on '{}': {}", responderId, ex.getMessage(), ex));
        subscriptions.add(sub);
    }

    private Mono<Void> handlePubSubMessage(Object bean, Method method, String json, String responderId) {
        return Mono.defer(() -> {
            // Try to parse as a RacerRequest
            RacerRequest request;
            try {
                request = objectMapper.readValue(json, RacerRequest.class);
            } catch (Exception e) {
                // Not a valid RacerRequest — silently ignore (fire-and-forget message)
                return Mono.empty();
            }

            // Must have replyTo to be a request-reply message
            if (request.getReplyTo() == null || request.getReplyTo().isBlank()
                    || request.getCorrelationId() == null || request.getCorrelationId().isBlank()) {
                return Mono.empty();
            }

            return invokeAndReply(bean, method, request, responderId)
                    .flatMap(reply -> publishPubSubReply(request.getReplyTo(), reply));
        });
    }

    private Mono<Void> publishPubSubReply(String replyChannel, RacerReply reply) {
        try {
            String json = objectMapper.writeValueAsString(reply);
            return redisTemplate.convertAndSend(replyChannel, json).then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    // ── Stream responder ──────────────────────────────────────────────────────

    private void registerStreamResponder(Object bean, Method method, RacerResponder ann, String responderId) {
        String streamKey = RacerChannelResolver.resolveStreamKey(resolve(ann.stream()), resolve(ann.streamRef()), racerProperties);
        String group     = ann.group();
        int concurrencyN = ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1 : Math.max(1, ann.concurrency());

        log.info("[RACER-RESPONDER] Registering Stream responder '{}' <- stream '{}' group='{}'", responderId, streamKey, group);

        RacerStreamUtils.ensureGroup(redisTemplate, streamKey, group)
                .retryWhen(Retry.backoff(GROUP_CREATION_RETRIES, Duration.ofSeconds(2))
                        .doBeforeRetry(rs -> log.warn("[RACER-RESPONDER] Retrying group creation on '{}' (attempt {})", streamKey, rs.totalRetries() + 1)))
                .doOnSuccess(v -> {
                    for (int i = 0; i < concurrencyN; i++) {
                        String consumerName = responderId + "-" + i;
                        Disposable d = buildStreamPollLoop(bean, method, streamKey, group, consumerName, responderId)
                                .subscribe(
                                        n -> {},
                                        ex -> log.error("[RACER-RESPONDER] Stream consumer '{}' errored: {}", consumerName, ex.getMessage()));
                        subscriptions.add(d);
                    }
                })
                .doOnError(e -> log.error("[RACER-RESPONDER] Failed to init group '{}' on '{}': {}", group, streamKey, e.getMessage()))
                .subscribe();
    }

    private Flux<Void> buildStreamPollLoop(Object bean, Method method,
                                            String streamKey, String group, String consumer, String responderId) {
        return Flux.defer(() -> pollStreamOnce(bean, method, streamKey, group, consumer, responderId))
                .repeatWhen(c -> c.delayElements(Duration.ofMillis(100)))
                .onErrorContinue((ex, o) ->
                        log.error("[RACER-RESPONDER] Poll error on '{}': {}", streamKey, ex.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Flux<Void> pollStreamOnce(Object bean, Method method,
                                       String streamKey, String group, String consumer, String responderId) {
        StreamReadOptions opts = StreamReadOptions.empty().count(1);
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());
        Consumer redisConsumer = Consumer.from(group, consumer);

        return redisTemplate
                .opsForStream()
                .read(redisConsumer, opts, offset)
                .onErrorResume(ex -> {
                    log.debug("[RACER-RESPONDER] XREADGROUP on '{}' error: {}", streamKey, ex.getMessage());
                    return Flux.empty();
                })
                .flatMap(record -> processStreamRecord(bean, method, record, responderId));
    }

    private Mono<Void> processStreamRecord(Object bean, Method method,
                                            MapRecord<String, Object, Object> record, String responderId) {
        RecordId recordId = record.getId();
        Map<Object, Object> fields = record.getValue();

        Object correlationIdObj = fields.get("correlationId");
        Object replyToObj       = fields.get("replyTo");
        Object payloadObj       = fields.get("payload");

        if (correlationIdObj == null || replyToObj == null) {
            log.warn("[RACER-RESPONDER] Stream record {} missing correlationId/replyTo — skipping", recordId);
            return RacerStreamUtils.ackRecord(redisTemplate, record.getStream(), CONSUMER_GROUP, recordId);
        }

        RacerRequest request = new RacerRequest();
        request.setCorrelationId(correlationIdObj.toString());
        request.setReplyTo(replyToObj.toString());
        if (payloadObj != null) request.setPayload(payloadObj.toString());

        return invokeAndReply(bean, method, request, responderId)
                .flatMap(reply -> writeStreamReply(request.getReplyTo(), reply))
                .then(RacerStreamUtils.ackRecord(redisTemplate, record.getStream(), CONSUMER_GROUP, recordId))
                .doOnError(ex -> log.error("[RACER-RESPONDER] Stream record {} processing failed: {}", recordId, ex.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> writeStreamReply(String responseStreamKey, RacerReply reply) {
        try {
            String json = objectMapper.writeValueAsString(reply);
            MapRecord<String, String, String> entry = MapRecord.create(responseStreamKey,
                    Map.of("correlationId", reply.getCorrelationId(), "payload", json));
            return redisTemplate.opsForStream().add(entry).then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    // ── Method invocation ─────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<RacerReply> invokeAndReply(Object bean, Method method, RacerRequest request, String responderId) {
        Object arg;
        try {
            arg = resolveArgument(method, request);
        } catch (Exception e) {
            failedCounts.get(responderId).incrementAndGet();
            return Mono.just(RacerReply.failure(request.getCorrelationId(), e.getMessage(), responderId));
        }

        final Object resolvedArg = arg;
        final boolean isNoArg    = method.getParameterCount() == 0;

        Mono<Object> invocation = Mono
                .fromCallable(() -> isNoArg ? method.invoke(bean) : method.invoke(bean, resolvedArg))
                .subscribeOn(Schedulers.boundedElastic());

        return invocation
                .flatMap(result -> {
                    if (result instanceof Mono<?> mono) return (Mono<Object>) mono;
                    return Mono.justOrEmpty(result);
                })
                .map(value -> {
                    String payload;
                    try {
                        payload = value instanceof String s ? s : objectMapper.writeValueAsString(value);
                    } catch (Exception e) {
                        payload = value.toString();
                    }
                    repliedCounts.get(responderId).incrementAndGet();
                    return RacerReply.success(request.getCorrelationId(), payload, responderId);
                })
                .onErrorResume(ex -> {
                    failedCounts.get(responderId).incrementAndGet();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.error("[RACER-RESPONDER] '{}' invocation failed for correlationId={}: {}",
                            responderId, request.getCorrelationId(), cause.getMessage(), cause);
                    return Mono.just(RacerReply.failure(request.getCorrelationId(), cause.getMessage(), responderId));
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Object resolveArgument(Method method, RacerRequest request) throws Exception {
        if (method.getParameterCount() == 0) return null;
        Class<?> paramType = method.getParameterTypes()[0];
        if (RacerRequest.class.isAssignableFrom(paramType)) return request;
        if (String.class.equals(paramType)) return request.getPayload();
        if (request.getPayload() == null || request.getPayload().isBlank()) return null;
        return objectMapper.readValue(request.getPayload(), paramType);
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try { return environment.resolvePlaceholders(value); } catch (Exception e) { return value; }
    }
}
