package com.cheetah.racer.ratelimit;

import com.cheetah.racer.exception.RacerRateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Phase 4.3 — Per-channel token-bucket rate limiter backed by Redis.
 *
 * <h3>Algorithm — token bucket</h3>
 * Each channel gets its own Redis key that stores two values:
 * <ul>
 *   <li>{@code tokens} — the current token count in the bucket</li>
 *   <li>{@code last_refill} — Unix timestamp (seconds) of the last refill</li>
 * </ul>
 *
 * On every call the Lua script atomically:
 * <ol>
 *   <li>Refills the bucket based on elapsed time × refill-rate.</li>
 *   <li>Caps the bucket at {@code capacity} (burst limit).</li>
 *   <li>If at least 1 token is available, decrements the counter and returns {@code 1}
 *       (allowed).</li>
 *   <li>Otherwise returns {@code 0} (rejected).</li>
 * </ol>
 *
 * <h3>Fail-open</h3>
 * If the Redis call fails (network error, timeout, etc.) the request is
 * <em>allowed</em> rather than rejected, and a {@code WARN} log is emitted.
 * This prevents Redis downtime from cascading into publishing failures.
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer.rate-limit.enabled=true
 * racer.rate-limit.default-capacity=100
 * racer.rate-limit.default-refill-rate=100
 * racer.rate-limit.key-prefix=racer:ratelimit:
 * racer.rate-limit.channels.orders.capacity=50
 * racer.rate-limit.channels.orders.refill-rate=20
 * </pre>
 */
@Slf4j
public class RacerRateLimiter {

    /**
     * Redis Lua token-bucket script.
     *
     * <p>KEYS: {@code [1]} = bucket key (e.g. {@code racer:ratelimit:orders})
     * <p>ARGV: {@code [1]} = capacity (max tokens),
     *          {@code [2]} = refill rate (tokens/sec),
     *          {@code [3]} = current Unix timestamp (seconds),
     *          {@code [4]} = TTL for the key (seconds)
     * <p>Returns: {@code 1} if the request is allowed, {@code 0} if rejected.
     */
    private static final String LUA_SCRIPT = """
            local key         = KEYS[1]
            local capacity    = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now         = tonumber(ARGV[3])
            local ttl         = tonumber(ARGV[4])
            
            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens      = tonumber(bucket[1]) or capacity
            local last_refill = tonumber(bucket[2]) or now
            
            -- Refill based on elapsed time
            local elapsed = math.max(0, now - last_refill)
            tokens = math.min(capacity, tokens + elapsed * refill_rate)
            
            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
                redis.call('EXPIRE', key, ttl)
                return 1
            else
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
                redis.call('EXPIRE', key, ttl)
                return 0
            end
            """;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final long defaultCapacity;
    private final long defaultRefillRate;
    private final String keyPrefix;

    /**
     * Per-channel override map.  Key is channel alias (or channel name), value
     * holds capacity and refill-rate overrides.
     */
    private final java.util.Map<String, long[]> channelOverrides;

    public RacerRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate,
                             long defaultCapacity, long defaultRefillRate, String keyPrefix,
                             java.util.Map<String, com.cheetah.racer.config.RacerProperties.RateLimitProperties.ChannelRateLimitProperties> channels) {
        this.redisTemplate    = redisTemplate;
        this.defaultCapacity  = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
        this.keyPrefix        = keyPrefix;

        // Pre-resolve overrides into a simple long[] {capacity, refillRate} map
        this.channelOverrides = new java.util.HashMap<>();
        if (channels != null) {
            channels.forEach((alias, props) -> {
                long cap  = props.getCapacity()  > 0 ? props.getCapacity()  : defaultCapacity;
                long rate = props.getRefillRate() > 0 ? props.getRefillRate() : defaultRefillRate;
                channelOverrides.put(alias, new long[]{cap, rate});
            });
        }

        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    /**
     * Check whether a publish to {@code channelAlias} is permitted.
     *
     * @param channelAlias the channel alias (or channel name) to check
     * @return {@code Mono<Void>} that completes empty when allowed, or terminates
     *         with {@link RacerRateLimitException} when the bucket is exhausted
     */
    public Mono<Void> checkLimit(String channelAlias) {
        long[] limits = channelOverrides.getOrDefault(channelAlias,
                new long[]{defaultCapacity, defaultRefillRate});
        long capacity   = limits[0];
        long refillRate = limits[1];

        String key    = keyPrefix + channelAlias;
        long   now    = Instant.now().getEpochSecond();
        // TTL = capacity / refill-rate + 10 s buffer, minimum 30 s
        long   ttl    = Math.max(30L, capacity / Math.max(1, refillRate) + 10L);

        return redisTemplate.execute(script,
                        List.of(key),
                        List.of(String.valueOf(capacity),
                                String.valueOf(refillRate),
                                String.valueOf(now),
                                String.valueOf(ttl)))
                .next()
                .defaultIfEmpty(1L)   // fail-open if script returns nothing
                .onErrorResume(ex -> {
                    log.warn("[racer-ratelimit] Redis error in rate limiter for channel='{}', failing open: {}",
                            channelAlias, ex.getMessage());
                    return Mono.just(1L);
                })
                .flatMap(result -> {
                    if (result == 1L) {
                        return Mono.empty();
                    }
                    log.debug("[racer-ratelimit] Rate limit exceeded for channel='{}'", channelAlias);
                    return Mono.error(new RacerRateLimitException(channelAlias));
                });
    }
}
