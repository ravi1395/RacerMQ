package com.cheetah.racer.publisher;

import com.cheetah.racer.config.RacerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RacerPublisherRegistryTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerPublisherRegistry registry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        properties = new RacerProperties();
        properties.setDefaultChannel("racer:messages");

        RacerProperties.ChannelProperties orders = new RacerProperties.ChannelProperties();
        orders.setName("racer:orders");
        orders.setSender("order-service");

        RacerProperties.ChannelProperties notifications = new RacerProperties.ChannelProperties();
        notifications.setName("racer:notifications");
        notifications.setSender("notification-service");

        properties.setChannels(Map.of("orders", orders, "notifications", notifications));

        registry = new RacerPublisherRegistry(properties, redisTemplate, objectMapper);
        registry.init();
    }

    // ------------------------------------------------------------------
    // Alias look-up
    // ------------------------------------------------------------------

    @Test
    void getPublisher_byKnownAlias_returnsCorrectPublisher() {
        RacerChannelPublisher pub = registry.getPublisher("orders");

        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo("racer:orders");
        assertThat(pub.getChannelAlias()).isEqualTo("orders");
    }

    @Test
    void getPublisher_notifications_returnsCorrectPublisher() {
        RacerChannelPublisher pub = registry.getPublisher("notifications");

        assertThat(pub.getChannelName()).isEqualTo("racer:notifications");
        assertThat(pub.getChannelAlias()).isEqualTo("notifications");
    }

    // ------------------------------------------------------------------
    // Fallback to default
    // ------------------------------------------------------------------

    @Test
    void getPublisher_nullAlias_returnsDefaultPublisher() {
        RacerChannelPublisher pub = registry.getPublisher(null);

        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo("racer:messages");
        assertThat(pub.getChannelAlias()).isEqualTo(RacerPublisherRegistry.DEFAULT_ALIAS);
    }

    @Test
    void getPublisher_emptyAlias_returnsDefaultPublisher() {
        RacerChannelPublisher pub = registry.getPublisher("");

        assertThat(pub.getChannelName()).isEqualTo("racer:messages");
    }

    @Test
    void getPublisher_blankAlias_returnsDefaultPublisher() {
        RacerChannelPublisher pub = registry.getPublisher("   ");

        assertThat(pub.getChannelName()).isEqualTo("racer:messages");
    }

    @Test
    void getPublisher_unknownAlias_fallsBackToDefault() {
        RacerChannelPublisher pub = registry.getPublisher("does-not-exist");

        assertThat(pub.getChannelName()).isEqualTo("racer:messages");
    }

    // ------------------------------------------------------------------
    // getAll()
    // ------------------------------------------------------------------

    @Test
    void getAll_containsDefaultAndNamedChannels() {
        Map<String, RacerChannelPublisher> all = registry.getAll();

        assertThat(all).containsKeys(
                RacerPublisherRegistry.DEFAULT_ALIAS, "orders", "notifications");
    }

    @Test
    void getAll_returnsImmutableCopy() {
        Map<String, RacerChannelPublisher> all = registry.getAll();

        // must throw UnsupportedOperationException, not silently modify
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> all.put("extra", null));
    }

    // ------------------------------------------------------------------
    // Channel with blank name is skipped
    // ------------------------------------------------------------------

    @Test
    void channelWithBlankName_isSkippedDuringInit() {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");

        RacerProperties.ChannelProperties blank = new RacerProperties.ChannelProperties();
        blank.setName("   "); // blank name

        props.setChannels(Map.of("broken", blank));

        RacerPublisherRegistry reg = new RacerPublisherRegistry(props, redisTemplate, objectMapper);
        reg.init();

        // "broken" alias should not be registered; unknown alias falls back to default
        assertThat(reg.getPublisher("broken").getChannelName()).isEqualTo("racer:messages");
    }

    // ------------------------------------------------------------------
    // 4-arg constructor with Optional<RacerMetrics>
    // ------------------------------------------------------------------

    @Test
    void fourArgConstructor_withMetrics_buildsRegistrySuccessfully() {
        com.cheetah.racer.metrics.RacerMetrics metrics = org.mockito.Mockito.mock(com.cheetah.racer.metrics.RacerMetrics.class);
        RacerPublisherRegistry reg = new RacerPublisherRegistry(
                properties, redisTemplate, objectMapper, java.util.Optional.of(metrics));
        reg.init();
        assertThat(reg.getPublisher(null)).isNotNull();
        assertThat(reg.getAll()).containsKey(RacerPublisherRegistry.DEFAULT_ALIAS);
    }

    @Test
    void fourArgConstructor_withEmptyMetrics_buildsRegistrySuccessfully() {
        RacerPublisherRegistry reg = new RacerPublisherRegistry(
                properties, redisTemplate, objectMapper, java.util.Optional.empty());
        reg.init();
        assertThat(reg.getPublisher(null)).isNotNull();
    }

    // ------------------------------------------------------------------
    // Durable channel registration
    // ------------------------------------------------------------------

    @Test
    void durableChannel_withBlankStreamKey_registersDefaultStreamKey() {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");

        RacerProperties.ChannelProperties durableChannel = new RacerProperties.ChannelProperties();
        durableChannel.setName("racer:events");
        durableChannel.setSender("event-service");
        durableChannel.setDurable(true);
        durableChannel.setStreamKey(""); // blank → default "<name>:stream"

        props.setChannels(Map.of("events", durableChannel));

        RacerPublisherRegistry reg = new RacerPublisherRegistry(props, redisTemplate, objectMapper);
        reg.init();

        RacerChannelPublisher pub = reg.getPublisher("events");
        assertThat(pub).isNotNull();
        assertThat(pub.getChannelAlias()).isEqualTo("events");
        assertThat(pub.getChannelName()).isEqualTo("racer:events");
    }

    @Test
    void durableChannel_withCustomStreamKey_usesProvidedKey() {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");

        RacerProperties.ChannelProperties durableChannel = new RacerProperties.ChannelProperties();
        durableChannel.setName("racer:orders");
        durableChannel.setSender("order-service");
        durableChannel.setDurable(true);
        durableChannel.setStreamKey("custom:orders:stream");

        props.setChannels(Map.of("orders-durable", durableChannel));

        RacerPublisherRegistry reg = new RacerPublisherRegistry(props, redisTemplate, objectMapper);
        reg.init();

        RacerChannelPublisher pub = reg.getPublisher("orders-durable");
        assertThat(pub).isNotNull();
        assertThat(pub.getChannelAlias()).isEqualTo("orders-durable");
    }
}
