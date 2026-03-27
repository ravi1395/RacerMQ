package com.cheetah.racer.publisher;

import com.cheetah.racer.metrics.RacerMetrics;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerPipelinedPublisher}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerPipelinedPublisherTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private RacerMetrics racerMetrics;
    @Mock private RacerSchemaRegistry schemaRegistry;

    private ObjectMapper objectMapper;
    private RacerPipelinedPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        publisher = new RacerPipelinedPublisher(redisTemplate, objectMapper, 10, racerMetrics, schemaRegistry);
    }

    @Test
    void publishBatch_emptyList_returnsEmptyList() {
        StepVerifier.create(publisher.publishBatch("channel", List.of(), "sender"))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void publishBatch_nullList_returnsEmptyList() {
        StepVerifier.create(publisher.publishBatch("channel", null, "sender"))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void publishBatch_singlePayload_publishesOne() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(publisher.publishBatch("racer:orders", List.of("payload1"), "my-svc"))
                .assertNext(result -> {
                    assertThat(result).hasSize(1);
                    assertThat(result.get(0)).isEqualTo(1L);
                })
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("racer:orders"), anyString());
    }

    @Test
    void publishBatch_multiplePayloads_publishesAll() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        List<String> payloads = List.of("p1", "p2", "p3");
        StepVerifier.create(publisher.publishBatch("racer:orders", payloads, "svc"))
                .assertNext(result -> assertThat(result).hasSize(3))
                .verifyComplete();

        verify(redisTemplate, times(3)).convertAndSend(eq("racer:orders"), anyString());
    }

    @Test
    void publishBatch_batchesExceedingMaxSize_areSplit() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        // Publisher has maxBatchSize=10, so 15 payloads → 2 batches (10 + 5)
        List<String> payloads = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) payloads.add("p" + i);

        StepVerifier.create(publisher.publishBatch("channel", payloads, "svc"))
                .assertNext(result -> assertThat(result).hasSize(15))
                .verifyComplete();

        verify(redisTemplate, times(15)).convertAndSend(eq("channel"), anyString());
    }

    @Test
    void publishItems_emptyList_returnsEmptyList() {
        StepVerifier.create(publisher.publishItems(List.of()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void publishItems_nullList_returnsEmptyList() {
        StepVerifier.create(publisher.publishItems(null))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void publishItems_mixedChannels_publishesToEach() {
        when(schemaRegistry.validateForPublishReactive(anyString(), anyString())).thenReturn(Mono.empty());

        List<RacerPipelinedPublisher.PipelineItem> items = List.of(
                new RacerPipelinedPublisher.PipelineItem("ch1", "data1", "svc"),
                new RacerPipelinedPublisher.PipelineItem("ch2", "data2", "svc")
        );

        StepVerifier.create(publisher.publishItems(items))
                .assertNext(result -> assertThat(result).hasSize(2))
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("ch1"), anyString());
        verify(redisTemplate).convertAndSend(eq("ch2"), anyString());
    }

    @Test
    void constructor_withoutSchemaRegistry_works() {
        RacerPipelinedPublisher pub = new RacerPipelinedPublisher(redisTemplate, objectMapper, 5, racerMetrics);

        StepVerifier.create(pub.publishBatch("ch", List.of("test"), "svc"))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();
    }

    @Test
    void constructor_withNullMetrics_usesNoOp() {
        RacerPipelinedPublisher pub = new RacerPipelinedPublisher(redisTemplate, objectMapper, 5, null);

        StepVerifier.create(pub.publishBatch("ch", List.of("test"), "svc"))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();
    }

    @Test
    void pipelineItem_record_accessors() {
        var item = new RacerPipelinedPublisher.PipelineItem("ch", "data", "sender");
        assertThat(item.channelName()).isEqualTo("ch");
        assertThat(item.payload()).isEqualTo("data");
        assertThat(item.sender()).isEqualTo("sender");
    }
}
