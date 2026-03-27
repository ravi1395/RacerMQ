package com.cheetah.racer.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerStreamPublisher}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerStreamPublisherTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    @Mock private ReactiveStreamOperations streamOps;

    private ObjectMapper objectMapper;
    private RacerStreamPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class), any(RedisStreamCommands.XAddOptions.class)))
                .thenReturn(Mono.just(RecordId.of("1234-0")));
        publisher = new RacerStreamPublisher(redisTemplate, objectMapper, 5000L);
    }

    @Test
    void publishToStream_success_returnsRecordId() {
        StepVerifier.create(publisher.publishToStream("stream:orders", "test-payload", "my-svc"))
                .assertNext(recordId -> assertThat(recordId.toString()).isEqualTo("1234-0"))
                .verifyComplete();
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishToStream_writesEnvelopeWithRequiredFields() {
        publisher.publishToStream("stream:test", "hello", "sender1").block();

        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture(), any(RedisStreamCommands.XAddOptions.class));

        MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("stream:test");
        String json = record.getValue().get("data");
        assertThat(json).contains("\"sender\":\"sender1\"");
        assertThat(json).contains("\"payload\":\"hello\"");
        assertThat(json).contains("\"id\":");
        assertThat(json).contains("\"timestamp\":");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishToStream_error_propagates() {
        when(streamOps.add(any(MapRecord.class), any(RedisStreamCommands.XAddOptions.class)))
                .thenReturn(Mono.error(new RuntimeException("Redis down")));

        StepVerifier.create(publisher.publishToStream("stream:fail", "data", "svc"))
                .expectErrorMessage("Redis down")
                .verify();
    }

    @Test
    void constructor_defaultMaxLen() {
        RacerStreamPublisher defaultPublisher = new RacerStreamPublisher(redisTemplate, objectMapper);
        StepVerifier.create(defaultPublisher.publishToStream("s", "p", "sender"))
                .assertNext(id -> assertThat(id).isNotNull())
                .verifyComplete();
    }
}
