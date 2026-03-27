package com.cheetah.racer.publisher;

import com.cheetah.racer.model.PriorityLevel;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerPriorityPublisher}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerPriorityPublisherTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private RacerSchemaRegistry schemaRegistry;

    private ObjectMapper objectMapper;
    private RacerPriorityPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        publisher = new RacerPriorityPublisher(redisTemplate, objectMapper, schemaRegistry);
    }

    @Test
    void publish_highPriority_sendsToCorrectSubChannel() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(publisher.publish("racer:orders", "payload", "svc", "HIGH"))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("racer:orders:priority:HIGH"), anyString());
    }

    @Test
    void publish_lowPriority_sendsToCorrectSubChannel() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(publisher.publish("racer:orders", "payload", "svc", "LOW"))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("racer:orders:priority:LOW"), anyString());
    }

    @Test
    void publish_nullPriority_defaultsToNormal() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(publisher.publish("racer:orders", "payload", "svc", null))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("racer:orders:priority:NORMAL"), anyString());
    }

    @Test
    void publish_blankPriority_defaultsToNormal() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(publisher.publish("racer:orders", "payload", "svc", "  "))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("racer:orders:priority:NORMAL"), anyString());
    }

    @Test
    void publish_lowercasePriority_isUppercased() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(publisher.publish("racer:orders", "payload", "svc", "high"))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("racer:orders:priority:HIGH"), anyString());
    }

    @Test
    void priorityChannelName_derivesCorrectly() {
        assertThat(RacerPriorityPublisher.priorityChannelName("racer:orders", "HIGH"))
                .isEqualTo("racer:orders:priority:HIGH");
        assertThat(RacerPriorityPublisher.priorityChannelName("racer:events", "low"))
                .isEqualTo("racer:events:priority:LOW");
    }

    @Test
    void publish_withoutSchemaRegistry_works() {
        RacerPriorityPublisher pubNoSchema = new RacerPriorityPublisher(redisTemplate, objectMapper);

        StepVerifier.create(pubNoSchema.publish("ch", "data", "svc", "NORMAL"))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void publish_error_propagates() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis down")));

        StepVerifier.create(publisher.publish("ch", "data", "svc", "HIGH"))
                .expectErrorMessage("Redis down")
                .verify();
    }
}
