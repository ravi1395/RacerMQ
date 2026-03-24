package com.cheetah.racer.ratelimit;

import com.cheetah.racer.exception.RacerRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerRateLimiter}.
 */
@ExtendWith(MockitoExtension.class)
class RacerRateLimiterTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;

    RacerRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RacerRateLimiter(redisTemplate, 10L, 10L, "racer:rl:",
                Map.of());
    }

    // ── allowed path ─────────────────────────────────────────────────────────

    @Test
    void checkLimit_completesEmpty_whenScriptReturnsOne() {
        when(redisTemplate.execute(any(), any(List.class), any(List.class)))
                .thenReturn(Flux.just(1L));

        StepVerifier.create(limiter.checkLimit("orders"))
                .verifyComplete();
    }

    // ── rejected path ────────────────────────────────────────────────────────

    @Test
    void checkLimit_errorsWithRateLimitException_whenScriptReturnsZero() {
        when(redisTemplate.execute(any(), any(List.class), any(List.class)))
                .thenReturn(Flux.just(0L));

        StepVerifier.create(limiter.checkLimit("orders"))
                .expectErrorSatisfies(ex -> {
                    assertIsRateLimitException(ex, "orders");
                })
                .verify();
    }

    @Test
    void checkLimit_rateLimitException_carriesChannelAlias() {
        when(redisTemplate.execute(any(), any(List.class), any(List.class)))
                .thenReturn(Flux.just(0L));

        StepVerifier.create(limiter.checkLimit("payments"))
                .expectErrorMatches(ex ->
                        ex instanceof RacerRateLimitException rle
                                && "payments".equals(rle.getChannel()))
                .verify();
    }

    // ── fail-open on Redis error ──────────────────────────────────────────────

    @Test
    void checkLimit_completesEmpty_whenRedisErrorOccurs() {
        when(redisTemplate.execute(any(), any(List.class), any(List.class)))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));

        // Fail-open: should complete without error
        StepVerifier.create(limiter.checkLimit("orders"))
                .verifyComplete();
    }

    // ── empty Flux → fail-open ────────────────────────────────────────────────

    @Test
    void checkLimit_completesEmpty_whenScriptReturnsEmpty() {
        when(redisTemplate.execute(any(), any(List.class), any(List.class)))
                .thenReturn(Flux.empty());

        // defaultIfEmpty(1L) → allowed
        StepVerifier.create(limiter.checkLimit("orders"))
                .verifyComplete();
    }

    // ── per-channel override ──────────────────────────────────────────────────

    @Test
    void checkLimit_usesPerChannelOverride() {
        com.cheetah.racer.config.RacerProperties.RateLimitProperties.ChannelRateLimitProperties overrides =
                new com.cheetah.racer.config.RacerProperties.RateLimitProperties.ChannelRateLimitProperties();
        overrides.setCapacity(5);
        overrides.setRefillRate(5);

        RacerRateLimiter limiterWithOverride = new RacerRateLimiter(
                redisTemplate, 100L, 100L, "racer:rl:",
                Map.of("special", overrides));

        when(redisTemplate.execute(any(), any(List.class), any(List.class)))
                .thenReturn(Flux.just(1L));

        // Should complete without error even with a custom override
        StepVerifier.create(limiterWithOverride.checkLimit("special"))
                .verifyComplete();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertIsRateLimitException(Throwable ex, String expectedChannel) {
        if (!(ex instanceof RacerRateLimitException rle)) {
            throw new AssertionError("Expected RacerRateLimitException but got " + ex.getClass());
        }
        if (!expectedChannel.equals(rle.getChannel())) {
            throw new AssertionError("Expected channel '" + expectedChannel + "' but got '" + rle.getChannel() + "'");
        }
    }
}
