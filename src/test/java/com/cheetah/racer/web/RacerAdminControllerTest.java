package com.cheetah.racer.web;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerAdminController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerAdminControllerTest {

    @Mock
    RacerPublisherRegistry publisherRegistry;

    RacerProperties properties;

    RacerAdminController controller;

    @BeforeEach
    void setUp() {
        properties = new RacerProperties();

        // Stub a minimal getAll()
        RacerChannelPublisher pub = mock(RacerChannelPublisher.class);
        lenient().when(pub.getChannelName()).thenReturn("racer:orders");
        lenient().when(publisherRegistry.getAll()).thenReturn(Map.of("orders", pub));

        // null for optional beans (rate limiter + circuit breaker)
        controller = new RacerAdminController(publisherRegistry, properties, null, null);
    }

    // ── /overview ────────────────────────────────────────────────────────────

    @Test
    void overview_containsTimestampAndChannelCount() {
        StepVerifier.create(controller.overview())
                .assertNext(body -> {
                    assertThat(body).containsKey("timestamp");
                    assertThat(body.get("channelCount")).isEqualTo(1);
                    assertThat(body).containsKey("features");
                })
                .verifyComplete();
    }

    @Test
    void overview_featuresMapContainsExpectedKeys() {
        StepVerifier.create(controller.overview())
                .assertNext(body -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> features = (Map<String, Object>) body.get("features");
                    assertThat(features).containsKeys(
                            "tracing", "rateLimit", "circuitBreaker", "sharding",
                            "consistentHash", "backpressure", "dedup", "consumerLag");
                })
                .verifyComplete();
    }

    // ── /channels ────────────────────────────────────────────────────────────

    @Test
    void channels_returnsChannelCountAndDetails() {
        StepVerifier.create(controller.channels())
                .assertNext(body -> {
                    assertThat(body.get("count")).isEqualTo(1);
                    assertThat(body).containsKey("channels");
                })
                .verifyComplete();
    }

    // ── /circuitbreakers — disabled ───────────────────────────────────────────

    @Test
    void circuitBreakers_returnsFalseEnabled_whenRegistryIsNull() {
        StepVerifier.create(controller.circuitBreakers())
                .assertNext(body -> assertThat(body.get("enabled")).isEqualTo(false))
                .verifyComplete();
    }

    // ── /circuitbreakers — enabled ────────────────────────────────────────────

    @Test
    void circuitBreakers_returnsTrueEnabled_whenRegistryPresent() {
        RacerCircuitBreakerRegistry cbRegistry = mock(RacerCircuitBreakerRegistry.class);
        when(cbRegistry.getAll()).thenReturn(java.util.List.of());

        RacerAdminController ctrl = new RacerAdminController(publisherRegistry, properties, cbRegistry, null);

        StepVerifier.create(ctrl.circuitBreakers())
                .assertNext(body -> assertThat(body.get("enabled")).isEqualTo(true))
                .verifyComplete();
    }

    // ── /ratelimits ───────────────────────────────────────────────────────────

    @Test
    void rateLimits_containsExpectedFields() {
        StepVerifier.create(controller.rateLimits())
                .assertNext(body -> {
                    assertThat(body).containsKeys("enabled", "defaultCapacity", "defaultRefillRate",
                            "keyPrefix", "channels");
                })
                .verifyComplete();
    }

    @Test
    void rateLimits_enabledFalse_byDefault() {
        StepVerifier.create(controller.rateLimits())
                .assertNext(body -> assertThat(body.get("enabled")).isEqualTo(false))
                .verifyComplete();
    }

    @Test
    void rateLimits_showsPerChannelOverrides() {
        RacerProperties.RateLimitProperties.ChannelRateLimitProperties ch =
                new RacerProperties.RateLimitProperties.ChannelRateLimitProperties();
        ch.setCapacity(50);
        ch.setRefillRate(20);

        properties.getRateLimit().getChannels().put("orders", ch);

        StepVerifier.create(controller.rateLimits())
                .assertNext(body -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> channels = (Map<String, Object>) body.get("channels");
                    assertThat(channels).containsKey("orders");
                })
                .verifyComplete();
    }
}
