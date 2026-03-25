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
     *
     * <p>The BUSYGROUP check traverses the full exception cause chain because
     * Spring Data Redis / Lettuce may wrap the Redis error in a
     * {@code DataAccessException} or similar wrapper whose top-level message does not
     * directly contain "BUSYGROUP", while the root cause does.
     */
    public static Mono<Void> ensureGroup(ReactiveRedisTemplate<String, String> redisTemplate,
                                          String streamKey, String group) {
        return redisTemplate.opsForStream()
                .createGroup(streamKey, ReadOffset.from("0"), group)
                .onErrorResume(ex -> {
                    if (isBusyGroup(ex)) {
                        log.debug("[RACER-STREAM] Group '{}' already exists on '{}', joining existing group",
                                group, streamKey);
                        return Mono.empty();
                    }
                    return Mono.error(ex);
                })
                .then();
    }

    /**
     * Returns {@code true} if any exception in the cause chain carries the Redis
     * {@code BUSYGROUP} message, regardless of how many wrapper exceptions
     * Spring Data Redis or Lettuce add around it.
     */
    private static boolean isBusyGroup(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) return true;
            current = current.getCause();
        }
        return false;
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
