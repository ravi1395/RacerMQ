package com.cheetah.racer.common.service;

import com.cheetah.racer.common.model.DeadLetterMessage;
import com.cheetah.racer.common.model.RacerMessage;
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
}
