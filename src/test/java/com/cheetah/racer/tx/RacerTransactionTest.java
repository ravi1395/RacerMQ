package com.cheetah.racer.tx;

import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerTransaction}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerTransactionTest {

    @Mock private RacerPublisherRegistry registry;
    @Mock private RacerChannelPublisher channelPublisher;
    @Mock private RacerPipelinedPublisher pipelinedPublisher;

    private ObjectMapper objectMapper;
    private RacerTransaction transaction;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(registry.getPublisher(anyString())).thenReturn(channelPublisher);
        when(channelPublisher.getChannelName()).thenReturn("racer:orders");
        when(channelPublisher.publishAsync(any())).thenReturn(Mono.just(1L));
        when(channelPublisher.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L)));

        transaction = new RacerTransaction(registry, objectMapper, pipelinedPublisher);
    }

    @Test
    void execute_emptyTransaction_returnsEmptyList() {
        StepVerifier.create(transaction.execute(tx -> {}))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    void execute_singlePublish_returnsOneResult() {
        StepVerifier.create(transaction.execute(tx -> tx.publish("orders", "payload")))
                .assertNext(result -> {
                    assertThat(result).hasSize(1);
                    assertThat(result.get(0)).isEqualTo(1L);
                })
                .verifyComplete();
    }

    @Test
    void execute_multiplePublishes_returnsAllResults() {
        // Use no-pipeline transaction for sequential test
        RacerTransaction seqTx = new RacerTransaction(registry, objectMapper, null);
        StepVerifier.create(seqTx.execute(tx -> {
            tx.publish("orders", "p1");
            tx.publish("notifications", "p2");
            tx.publish("audit", "p3");
        }))
                .assertNext(result -> assertThat(result).hasSize(3))
                .verifyComplete();
    }

    @Test
    void execute_withSender_passesExplicitSender() {
        // Use no-pipeline transaction for sequential test
        RacerTransaction seqTx = new RacerTransaction(registry, objectMapper, null);
        StepVerifier.create(seqTx.execute(tx -> tx.publish("orders", "data", "my-svc")))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();

        verify(channelPublisher).publishAsync(any(), eq("my-svc"));
    }

    @Test
    void execute_withPipeline_usesPipelinedPublisher() {
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L, 2L)));

        StepVerifier.create(transaction.execute(tx -> {
            tx.publish("orders", "p1");
            tx.publish("orders", "p2");
        }))
                .assertNext(result -> assertThat(result).hasSize(2))
                .verifyComplete();

        verify(pipelinedPublisher).publishItems(anyList());
    }

    @Test
    void execute_explicitPipelinedFalse_usesSequentialConcat() {
        StepVerifier.create(transaction.execute(tx -> tx.publish("orders", "p1"), false))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();

        verify(channelPublisher).publishAsync(any());
        verifyNoInteractions(pipelinedPublisher);
    }

    @Test
    void execute_explicitPipelinedTrue_usesPipelined() {
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L)));

        StepVerifier.create(transaction.execute(tx -> tx.publish("orders", "p1"), true))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();

        verify(pipelinedPublisher).publishItems(anyList());
    }

    @Test
    void constructor_registryOnly() {
        RacerTransaction tx = new RacerTransaction(registry);
        StepVerifier.create(tx.execute(t -> t.publish("orders", "data")))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();
    }

    @Test
    void constructor_withPipelineNoObjectMapper() {
        RacerTransaction tx = new RacerTransaction(registry, pipelinedPublisher);
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L)));

        StepVerifier.create(tx.execute(t -> t.publish("orders", "data")))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();
    }

    @Test
    void execute_publishError_propagates() {
        when(channelPublisher.publishAsync(any()))
                .thenReturn(Mono.error(new RuntimeException("Redis failure")));

        // Without pipeline, errors propagate
        RacerTransaction noPipeline = new RacerTransaction(registry, objectMapper, null);
        StepVerifier.create(noPipeline.execute(tx -> tx.publish("orders", "data")))
                .expectErrorMessage("Redis failure")
                .verify();
    }

    @Test
    void txPublisher_serializesObjectPayloads() {
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L)));

        // Non-string payload should be serialized to JSON
        StepVerifier.create(transaction.execute(tx -> tx.publish("orders", new TestPayload("abc", 42))))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();
    }

    @Test
    void txPublisher_nullPayload_serializedAsNullString() {
        // null payload → serializePayload returns "null" (in pipelined path)
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L)));

        StepVerifier.create(transaction.execute(tx -> tx.publish("orders", null)))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();

        verify(pipelinedPublisher).publishItems(argThat(items -> {
            @SuppressWarnings("unchecked")
            var typedItems = (java.util.List<com.cheetah.racer.publisher.RacerPipelinedPublisher.PipelineItem>) items;
            return typedItems.size() == 1 && "null".equals(typedItems.get(0).payload());
        }));
    }

    @Test
    void txPublisher_unserializablePayload_fallsBackToToString() {
        // An anonymous class is not serializable by Jackson → falls back to toString()
        when(pipelinedPublisher.publishItems(anyList())).thenReturn(Mono.just(List.of(1L)));

        Object unserializable = new Object() {
            @Override public String toString() { return "fallback-string"; }
        };

        StepVerifier.create(transaction.execute(tx -> tx.publish("orders", unserializable)))
                .assertNext(result -> assertThat(result).hasSize(1))
                .verifyComplete();

        verify(pipelinedPublisher).publishItems(argThat(items -> {
            @SuppressWarnings("unchecked")
            var typedItems = (java.util.List<com.cheetah.racer.publisher.RacerPipelinedPublisher.PipelineItem>) items;
            return typedItems.size() == 1 && "fallback-string".equals(typedItems.get(0).payload());
        }));
    }

    record TestPayload(String name, int value) {}
}
