package com.cheetah.racer.service;

import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlqReprocessorServiceTest {

    @Mock DeadLetterQueueService dlqService;
    @Mock ReactiveRedisTemplate<String, String> redisTemplate;

    ObjectMapper objectMapper;
    DlqReprocessorService service;

    RacerMessage sampleMessage;
    RuntimeException sampleError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new DlqReprocessorService(dlqService, redisTemplate, objectMapper, null);

        sampleMessage = RacerMessage.create("racer:orders", "bad-payload", "svc");
        sampleError   = new RuntimeException("processing failed");
    }

    // ── republishOne — empty queue ─────────────────────────────────────────────

    @Test
    void republishOne_emptyQueue_returnsZero() {
        when(dlqService.dequeue()).thenReturn(Mono.empty());

        StepVerifier.create(service.republishOne())
                .expectNext(0L)
                .verifyComplete();
    }

    // ── republishOne — happy path ──────────────────────────────────────────────

    @Test
    void republishOne_publishesMessageToOriginalChannel() {
        sampleMessage.setRetryCount(0);
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage, sampleError);
        when(dlqService.dequeue()).thenReturn(Mono.just(dlm));
        when(redisTemplate.convertAndSend(eq("racer:orders"), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.republishOne())
                .expectNext(1L)
                .verifyComplete();

        assertThat(service.getRepublishedCount()).isEqualTo(1L);
    }

    // ── republishOne — exceeded max retries ────────────────────────────────────

    @Test
    void republishOne_exceededMaxRetries_permanentlyDiscards() {
        sampleMessage.setRetryCount(100); // well above MAX_RETRY_ATTEMPTS
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage, sampleError);
        when(dlqService.dequeue()).thenReturn(Mono.just(dlm));

        StepVerifier.create(service.republishOne())
                .expectNext(0L)
                .verifyComplete();

        assertThat(service.getPermanentlyFailedCount()).isEqualTo(1L);
    }

    // ── republishAll — empty queue ─────────────────────────────────────────────

    @Test
    void republishAll_emptyQueue_returnsZero() {
        when(dlqService.size()).thenReturn(Mono.just(0L));

        StepVerifier.create(service.republishAll())
                .expectNext(0L)
                .verifyComplete();
    }

    // ── republishAll — with messages ───────────────────────────────────────────

    @Test
    void republishAll_withThreeMessages_publishesAllAndReturnsCount() {
        RacerMessage msg1 = RacerMessage.create("racer:orders", "payload1", "svc");
        RacerMessage msg2 = RacerMessage.create("racer:orders", "payload2", "svc");
        RacerMessage msg3 = RacerMessage.create("racer:orders", "payload3", "svc");
        DeadLetterMessage dlm1 = DeadLetterMessage.from(msg1, sampleError);
        DeadLetterMessage dlm2 = DeadLetterMessage.from(msg2, sampleError);
        DeadLetterMessage dlm3 = DeadLetterMessage.from(msg3, sampleError);

        when(dlqService.size()).thenReturn(Mono.just(3L));
        when(dlqService.dequeue())
                .thenReturn(Mono.just(dlm1), Mono.just(dlm2), Mono.just(dlm3), Mono.empty());
        when(redisTemplate.convertAndSend(eq("racer:orders"), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.republishAll())
                .expectNext(3L)
                .verifyComplete();

        assertThat(service.getRepublishedCount()).isEqualTo(3L);
    }

    @Test
    void republishAll_withSingleMessage_returnsOneAndIncrementsCounter() {
        RacerMessage msg = RacerMessage.create("racer:events", "data", "svc");
        DeadLetterMessage dlm = DeadLetterMessage.from(msg, sampleError);

        when(dlqService.size()).thenReturn(Mono.just(1L));
        when(dlqService.dequeue()).thenReturn(Mono.just(dlm), Mono.empty());
        when(redisTemplate.convertAndSend(eq("racer:events"), anyString()))
                .thenReturn(Mono.just(2L));

        StepVerifier.create(service.republishAll())
                .expectNext(1L)
                .verifyComplete();

        assertThat(service.getRepublishedCount()).isEqualTo(1L);
    }

    // ── republishAll — large batch (> BATCH_CHUNK=100), triggers recursive batch ──

    @Test
    void republishAll_withMoreThanBatchChunk_recursiveBatchProcessed() {
        when(dlqService.size()).thenReturn(Mono.just(101L));
        when(dlqService.dequeue()).thenAnswer(inv -> {
            RacerMessage msg = RacerMessage.create("racer:orders", "p", "svc");
            return Mono.just(DeadLetterMessage.from(msg, sampleError));
        });
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.republishAll())
                .assertNext(count -> assertThat(count).isGreaterThanOrEqualTo(100L))
                .verifyComplete();
    }

    // ── republishOne — JSON serialization failure ──────────────────────────────

    @Test
    void republishOne_serializationFails_propagatesError() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mockMapper =
                org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class);
        org.mockito.Mockito.when(mockMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("ser-fail") {});

        DlqReprocessorService failingService =
                new DlqReprocessorService(dlqService, redisTemplate, mockMapper, null);

        sampleMessage.setRetryCount(0);
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage, sampleError);
        when(dlqService.dequeue()).thenReturn(Mono.just(dlm));

        StepVerifier.create(failingService.republishOne())
                .expectError(com.fasterxml.jackson.core.JsonProcessingException.class)
                .verify();
    }
}
