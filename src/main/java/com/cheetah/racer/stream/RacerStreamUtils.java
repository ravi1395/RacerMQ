package com.cheetah.racer.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Static helpers shared by {@link RacerStreamListenerRegistrar} and
 * {@link com.cheetah.racer.requestreply.RacerResponderRegistrar} for common
 * Redis Streams operations.
 *
 * <p>Eliminates the near-identical {@code ensureGroup} and {@code ackRecord} private
 * methods that were duplicated in both registrar classes.
 */
@Slf4j
public final class RacerStreamUtils {

    private RacerStreamUtils() {}

    /**
     * Creates a consumer group on {@code streamKey} starting from offset {@code 0},
     * silently ignoring the {@code BUSYGROUP} error when the group already exists.
     */
    public static Mono<Void> ensureGroup(ReactiveRedisTemplate<String, String> redisTemplate,
                                          String streamKey, String group) {
        return redisTemplate.opsForStream()
                .createGroup(streamKey, ReadOffset.from("0"), group)
                .onErrorResume(ex -> {
                    if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                        log.debug("[RACER-STREAM] Group '{}' already exists on '{}'", group, streamKey);
                        return Mono.empty();
                    }
                    return Mono.error(ex);
                })
                .then();
    }

    /**
     * Acknowledges a stream entry, converting the result to {@code Mono<Void>}.
     */
    public static Mono<Void> ackRecord(ReactiveRedisTemplate<String, String> redisTemplate,
                                        String streamKey, String group, RecordId recordId) {
        return redisTemplate.opsForStream()
                .acknowledge(streamKey, group, recordId)
                .then();
    }
}
