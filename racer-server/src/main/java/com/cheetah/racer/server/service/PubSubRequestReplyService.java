package com.cheetah.racer.server.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.model.RacerReply;
import com.cheetah.racer.common.model.RacerRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Server-side Pub/Sub request-reply.
 *
 * Flow:
 *  1. Server creates a {@link RacerRequest} with a unique correlationId.
 *  2. Sets {@code replyTo} to an ephemeral channel: racer:reply:{correlationId}.
 *  3. Subscribes to that reply channel BEFORE publishing the request.
 *  4. Publishes the request on the main MESSAGE_CHANNEL.
 *  5. Waits (with timeout) for the client's reply on the reply channel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PubSubRequestReplyService {

    private final ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate;
    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    @Nullable
    @Autowired(required = false)
    private RacerMetrics racerMetrics;

    /**
     * Send a request and wait for a reply (non-blocking reactive).
     *
     * @param payload  the request body
     * @param sender   who is sending
     * @param timeout  how long to wait for a reply
     * @return Mono of the reply, or Mono.error on timeout / deserialization failure
     */
    public Mono<RacerReply> requestReply(String payload, String sender, Duration timeout) {
        RacerRequest request = RacerRequest.create(payload, sender);
        String replyChannel = RedisChannels.REPLY_CHANNEL_PREFIX + request.getCorrelationId();
        request.setReplyTo(replyChannel);
        request.setChannel(RedisChannels.MESSAGE_CHANNEL);

        io.micrometer.core.instrument.Timer.Sample sample =
                (racerMetrics != null) ? racerMetrics.startRequestReplyTimer() : null;

        log.info("[REQ-REPLY] Sending request correlationId={} replyTo={}", request.getCorrelationId(), replyChannel);

        // Subscribe to the reply channel FIRST to guarantee no reply is missed,
        // then publish the request inside doOnSubscribe so the channel is already
        // SUBSCRIBED before the client ever sees the request.
        return listenerContainer
                .receive(ChannelTopic.of(replyChannel))
                .next()                                       // take the first message
                .map(msg -> {
                    try {
                        return objectMapper.readValue(msg.getMessage(), RacerReply.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to deserialize reply", e);
                    }
                })
                .timeout(timeout)
                .doOnSubscribe(subscription -> {
                    // Publish after the subscription to Redis is active
                    publishRequest(request).subscribe(
                            n  -> log.debug("[REQ-REPLY] Published request correlationId={}", request.getCorrelationId()),
                            ex -> log.error("[REQ-REPLY] Failed to publish request correlationId={}: {}",
                                    request.getCorrelationId(), ex.getMessage()));
                })
                .doOnNext(reply -> {
                    log.info("[REQ-REPLY] Received reply correlationId={} success={}",
                            reply.getCorrelationId(), reply.isSuccess());
                    if (sample != null && racerMetrics != null) {
                        racerMetrics.stopRequestReplyTimer(sample, "pubsub");
                    }
                })
                .doOnError(e -> log.warn("[REQ-REPLY] Timeout or error waiting for reply correlationId={}",
                        request.getCorrelationId()));
    }

    /**
     * Convenience overload with default timeout.
     */
    public Mono<RacerReply> requestReply(String payload, String sender) {
        return requestReply(payload, sender,
                Duration.ofSeconds(RedisChannels.REPLY_TIMEOUT_SECONDS));
    }

    private Mono<Long> publishRequest(RacerRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            return reactiveStringRedisTemplate.convertAndSend(RedisChannels.MESSAGE_CHANNEL, json)
                    .doOnSuccess(n -> log.debug("[REQ-REPLY] Published request correlationId={}", request.getCorrelationId()));
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("Failed to serialize request", e));
        }
    }
}
