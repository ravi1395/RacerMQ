package com.cheetah.racer.common.requestreply;

import com.cheetah.racer.common.annotation.RacerRequestReply;
import com.cheetah.racer.common.config.RacerProperties;
import com.cheetah.racer.common.model.RacerReply;
import com.cheetah.racer.common.model.RacerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;

/**
 * {@link FactoryBean} that creates a reactive proxy for a {@link com.cheetah.racer.common.annotation.RacerClient}
 * interface. Each method annotated with {@link RacerRequestReply} is wired as a request-reply
 * caller over Redis Pub/Sub or Streams.
 */
@Slf4j
public class RacerClientFactoryBean<T> implements FactoryBean<T>, EnvironmentAware {

    private final Class<T> clientInterface;

    @Autowired private ReactiveRedisTemplate<String, String> redisTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RacerProperties racerProperties;
    @Nullable @Autowired(required = false) private ReactiveRedisMessageListenerContainer listenerContainer;

    private Environment environment;

    public RacerClientFactoryBean(Class<T> clientInterface) {
        this.clientInterface = clientInterface;
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
            String streamKey = resolveStreamKey(resolve(ann.stream()), resolve(ann.streamRef()));
            request.setReplyTo("racer:stream:response:" + request.getCorrelationId());
            replyMono = sendStreamRequest(request, streamKey, timeout);
        } else {
            String channel = resolveChannel(resolve(ann.channel()), resolve(ann.channelRef()));
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

        String replyChannel = request.getReplyTo();
        String correlationId = request.getCorrelationId();

        // Subscribe BEFORE publishing to avoid missing the reply
        Mono<RacerReply> replyMono = listenerContainer
                .receive(ChannelTopic.of(replyChannel))
                .next()
                .flatMap(msg -> {
                    try {
                        return Mono.just(objectMapper.readValue(msg.getMessage(), RacerReply.class));
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .filter(r -> correlationId.equals(r.getCorrelationId()))
                .timeout(timeout);

        Mono<Void> publishMono;
        try {
            String json = objectMapper.writeValueAsString(request);
            publishMono = redisTemplate.convertAndSend(channel, json).then();
        } catch (Exception e) {
            return Mono.error(e);
        }

        return replyMono.doOnSubscribe(s -> publishMono.subscribe());
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

    private String resolveChannel(String channel, String channelRef) {
        if (!channel.isEmpty()) return channel;
        if (!channelRef.isEmpty()) {
            RacerProperties.ChannelProperties cp = racerProperties.getChannels().get(channelRef);
            if (cp != null && cp.getName() != null && !cp.getName().isEmpty()) return cp.getName();
        }
        return racerProperties.getDefaultChannel();
    }

    private String resolveStreamKey(String stream, String streamRef) {
        if (!stream.isEmpty()) return stream;
        if (!streamRef.isEmpty()) {
            RacerProperties.ChannelProperties cp = racerProperties.getChannels().get(streamRef);
            if (cp != null && cp.getName() != null && !cp.getName().isEmpty()) return cp.getName();
        }
        return racerProperties.getDefaultChannel();
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try { return environment.resolvePlaceholders(value); } catch (Exception e) { return value; }
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            // Shorthand: "5s", "30s", "1m"
            if (value.endsWith("s")) return Duration.ofSeconds(Long.parseLong(value.replace("s", "")));
            if (value.endsWith("m")) return Duration.ofMinutes(Long.parseLong(value.replace("m", "")));
            if (value.endsWith("ms")) return Duration.ofMillis(Long.parseLong(value.replace("ms", "")));
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
