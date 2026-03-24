package com.cheetah.racer.requestreply;

import com.cheetah.racer.annotation.RacerRequestReply;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerReply;
import com.cheetah.racer.model.RacerRequest;
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FactoryBean} that creates a reactive proxy for a {@link com.cheetah.racer.annotation.RacerClient}
 * interface. Each method annotated with {@link RacerRequestReply} is wired as a request-reply
 * caller over Redis Pub/Sub or Streams.
 */
@Slf4j
public class RacerClientFactoryBean<T> implements FactoryBean<T>, EnvironmentAware, InitializingBean, DisposableBean {

    private final Class<T> clientInterface;

    @Autowired private ReactiveRedisTemplate<String, String> redisTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RacerProperties racerProperties;
    @Nullable @Autowired(required = false) private ReactiveRedisMessageListenerContainer listenerContainer;

    private Environment environment;

    /** Pending pub/sub requests waiting for a reply, keyed by correlationId. */
    private final ConcurrentHashMap<String, Sinks.One<RacerReply>> pendingReplies = new ConcurrentHashMap<>();
    @Nullable private volatile Disposable pubSubReplySubscription;

    public RacerClientFactoryBean(Class<T> clientInterface) {
        this.clientInterface = clientInterface;
    }

    /**
     * Eagerly establishes a single pattern subscription to {@code racer:reply:*} so that
     * the Redis SUBSCRIBE is confirmed well before the first request is published.
     * This eliminates the per-request SUBSCRIBE/PUBLISH race condition.
     */
    @Override
    public void afterPropertiesSet() {
        if (listenerContainer != null) {
            pubSubReplySubscription = listenerContainer
                    .receive(PatternTopic.of("racer:reply:*"))
                    .subscribe(
                            msg -> routePubSubReply(msg.getMessage()),
                            err -> log.error("[RACER-CLIENT] Pub/Sub reply listener error: {}", err.getMessage()));
            log.debug("[RACER-CLIENT] Subscribed to reply pattern 'racer:reply:*'");
        }
    }

    @Override
    public void destroy() {
        Disposable sub = pubSubReplySubscription;
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
        }
    }

    /** Routes an incoming pub/sub reply JSON to the waiting {@link Sinks.One}, if any. */
    private void routePubSubReply(String json) {
        try {
            RacerReply reply = objectMapper.readValue(json, RacerReply.class);
            String correlationId = reply.getCorrelationId();
            if (correlationId != null) {
                Sinks.One<RacerReply> sink = pendingReplies.remove(correlationId);
                if (sink != null) {
                    sink.tryEmitValue(reply);
                }
            }
        } catch (Exception e) {
            log.debug("[RACER-CLIENT] Failed to parse pub/sub reply: {}", e.getMessage());
        }
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getObject() {
        return (T) Proxy.newProxyInstance(
                clientInterface.getClassLoader(),
                new Class<?>[]{ clientInterface },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }

                    RacerRequestReply ann = method.getAnnotation(RacerRequestReply.class);
                    if (ann == null) {
                        throw new UnsupportedOperationException(
                                "Method " + method.getName() + " is not annotated with @RacerRequestReply");
                    }

                    return invokeRequestReply(method, ann, args);
                });
    }

    @SuppressWarnings("unchecked")
    private Object invokeRequestReply(Method method, RacerRequestReply ann, Object[] args) {
        Duration timeout = parseDuration(resolve(ann.timeout()), Duration.ofSeconds(5));
        boolean isStream = !ann.stream().isEmpty() || !ann.streamRef().isEmpty();

        String payload = serializeArgs(args);
        RacerRequest request = RacerRequest.create(payload, "racer-client");

        Mono<RacerReply> replyMono;
        if (isStream) {
            String streamKey = RacerChannelResolver.resolveStreamKey(resolve(ann.stream()), resolve(ann.streamRef()), racerProperties);
            request.setReplyTo("racer:stream:response:" + request.getCorrelationId());
            replyMono = sendStreamRequest(request, streamKey, timeout);
        } else {
            String channel = RacerChannelResolver.resolveChannel(resolve(ann.channel()), resolve(ann.channelRef()), racerProperties);
            request.setReplyTo("racer:reply:" + request.getCorrelationId());
            replyMono = sendPubSubRequest(request, channel, timeout);
        }

        // Determine desired return type
        Class<?> returnType = method.getReturnType();
        if (Mono.class.isAssignableFrom(returnType)) {
            // Extract generic type for deserialization
            java.lang.reflect.Type genericReturn = method.getGenericReturnType();
            java.lang.reflect.Type innerType = null;
            if (genericReturn instanceof java.lang.reflect.ParameterizedType pt) {
                innerType = pt.getActualTypeArguments()[0];
            }
            final java.lang.reflect.Type finalInner = innerType;

            return replyMono.map(reply -> {
                if (!reply.isSuccess()) {
                    throw new RacerRequestReplyException(reply.getErrorMessage());
                }
                return deserializePayload(reply.getPayload(), finalInner);
            });
        }

        // Blocking call for non-Mono return types
        RacerReply reply = replyMono.block(timeout.plusSeconds(1));
        if (reply == null || !reply.isSuccess()) {
            String err = reply != null ? reply.getErrorMessage() : "timeout";
            throw new RacerRequestReplyException(err);
        }
        if (String.class.equals(returnType)) return reply.getPayload();
        return deserializePayload(reply.getPayload(), returnType);
    }

    // ── Pub/Sub request-reply ─────────────────────────────────────────────────

    private Mono<RacerReply> sendPubSubRequest(RacerRequest request, String channel, Duration timeout) {
        if (listenerContainer == null) {
            return Mono.error(new IllegalStateException(
                    "Pub/Sub request-reply requires a ReactiveRedisMessageListenerContainer bean"));
        }

        String correlationId = request.getCorrelationId();

        // Register the sink BEFORE publishing so no reply is ever missed.
        // Sinks.one() buffers the value, so a reply that arrives before asMono() is
        // subscribed is still delivered correctly.
        Sinks.One<RacerReply> replySink = Sinks.one();
        pendingReplies.put(correlationId, replySink);

        try {
            String json = objectMapper.writeValueAsString(request);
            return redisTemplate.convertAndSend(channel, json)
                    .then(replySink.asMono())
                    .timeout(timeout)
                    .doFinally(signal -> pendingReplies.remove(correlationId));
        } catch (Exception e) {
            pendingReplies.remove(correlationId);
            return Mono.error(e);
        }
    }

    // ── Stream request-reply ──────────────────────────────────────────────────

    private Mono<RacerReply> sendStreamRequest(RacerRequest request, String streamKey, Duration timeout) {
        String responseStreamKey = request.getReplyTo();
        String correlationId     = request.getCorrelationId();

        MapRecord<String, String, String> entry = MapRecord.create(streamKey, Map.of(
                "correlationId", correlationId,
                "replyTo", responseStreamKey,
                "payload", request.getPayload() != null ? request.getPayload() : ""
        ));

        return redisTemplate.opsForStream().add(entry)
                .then(pollForStreamReply(responseStreamKey, correlationId, timeout))
                .doFinally(signal -> redisTemplate.delete(responseStreamKey).subscribe());
    }

    private Mono<RacerReply> pollForStreamReply(String responseStreamKey, String correlationId, Duration timeout) {
        long maxAttempts = timeout.toMillis() / 200;

        return Mono.defer(() -> {
            @SuppressWarnings("unchecked")
            Mono<RacerReply> one = (Mono<RacerReply>) redisTemplate
                    .opsForStream()
                    .read(StreamOffset.fromStart(responseStreamKey))
                    .next()
                    .flatMap(record -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> body = (Map<String, Object>) (Map<?, ?>) record.getValue();
                        Object payloadObj = body.get("payload");
                        if (payloadObj == null) return Mono.<RacerReply>empty();
                        try {
                            return Mono.just(objectMapper.readValue(payloadObj.toString(), RacerReply.class));
                        } catch (Exception e) {
                            return Mono.<RacerReply>error(e);
                        }
                    });
            return one;
        })
        .repeatWhenEmpty(companion ->
                companion.take(maxAttempts).delayElements(Duration.ofMillis(200)))
        .timeout(timeout);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) return "{}";
        if (args.length == 1) {
            Object arg = args[0];
            if (arg instanceof String s) return s;
            try { return objectMapper.writeValueAsString(arg); } catch (Exception e) { return arg.toString(); }
        }
        try { return objectMapper.writeValueAsString(args); } catch (Exception e) { return java.util.Arrays.toString(args); }
    }

    @SuppressWarnings("unchecked")
    private Object deserializePayload(String payload, java.lang.reflect.Type targetType) {
        if (payload == null || payload.isBlank()) return null;
        if (targetType instanceof Class<?> cls) {
            if (String.class.equals(cls)) return payload;
            try { return objectMapper.readValue(payload, cls); } catch (Exception e) { return payload; }
        }
        if (targetType instanceof java.lang.reflect.ParameterizedType pt) {
            try {
                return objectMapper.readValue(payload,
                        objectMapper.getTypeFactory().constructType(pt));
            } catch (Exception e) { return payload; }
        }
        return payload;
    }


    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try { return environment.resolvePlaceholders(value); } catch (Exception e) { return value; }
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            // Shorthand: "500ms", "5s", "1m" — check "ms" before "s" to avoid substring match
            if (value.endsWith("ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            if (value.endsWith("s")) return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            if (value.endsWith("m")) return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            return Duration.parse(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public Class<T> getObjectType() {
        return clientInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    /** Runtime exception thrown when a request-reply operation fails. */
    public static class RacerRequestReplyException extends RuntimeException {
        public RacerRequestReplyException(String message) { super(message); }
    }
}
