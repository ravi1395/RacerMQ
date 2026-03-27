package com.cheetah.racer.service;

import com.cheetah.racer.RedisChannels;
import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadLetterQueueServiceTest {

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock ReactiveListOperations<String, String> listOps;

    ObjectMapper objectMapper;
    DeadLetterQueueService service;

    RacerMessage sampleMessage;
    RuntimeException sampleError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(redisTemplate.opsForList()).thenReturn(listOps);

        service = new DeadLetterQueueService(redisTemplate, objectMapper);

        sampleMessage = RacerMessage.create("racer:orders", "bad-payload", "svc");
        sampleError   = new RuntimeException("processing failed");
    }

    // ── enqueue ────────────────────────────────────────────────────────────────

    @Test
    void enqueue_pushesToDlqList_andReturnsQueueSize() {
        when(listOps.leftPush(eq(RedisChannels.DEAD_LETTER_QUEUE), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.enqueue(sampleMessage, sampleError))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void enqueue_queueGrows_returnsUpdatedSize() {
        when(listOps.leftPush(eq(RedisChannels.DEAD_LETTER_QUEUE), anyString()))
                .thenReturn(Mono.just(3L));

        StepVerifier.create(service.enqueue(sampleMessage, sampleError))
                .expectNext(3L)
                .verifyComplete();
    }

    // ── dequeue ───────────────────────────────────────────────────────────────

    @Test
    void dequeue_whenMessageExists_returnsDeadLetterMessage() throws Exception {
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage, sampleError);
        String json = objectMapper.writeValueAsString(dlm);

        when(listOps.rightPop(RedisChannels.DEAD_LETTER_QUEUE)).thenReturn(Mono.just(json));

        StepVerifier.create(service.dequeue())
                .assertNext(dequeued -> {
                    assertThat(dequeued.getId()).isEqualTo(sampleMessage.getId());
                    assertThat(dequeued.getErrorMessage()).isEqualTo("processing failed");
                })
                .verifyComplete();
    }

    @Test
    void dequeue_whenQueueEmpty_completesEmpty() {
        when(listOps.rightPop(RedisChannels.DEAD_LETTER_QUEUE)).thenReturn(Mono.empty());

        StepVerifier.create(service.dequeue())
                .verifyComplete();
    }

    // ── peekAll ───────────────────────────────────────────────────────────────

    @Test
    void peekAll_returnsAllMessages() throws Exception {
        DeadLetterMessage dlm1 = DeadLetterMessage.from(sampleMessage, sampleError);
        DeadLetterMessage dlm2 = DeadLetterMessage.from(
                RacerMessage.create("ch", "also bad", "svc"),
                new IllegalStateException("boom"));
        String json1 = objectMapper.writeValueAsString(dlm1);
        String json2 = objectMapper.writeValueAsString(dlm2);

        when(listOps.range(RedisChannels.DEAD_LETTER_QUEUE, 0, -1))
                .thenReturn(Flux.just(json1, json2));

        StepVerifier.create(service.peekAll())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void peekAll_emptyQueue_returnsEmptyFlux() {
        when(listOps.range(RedisChannels.DEAD_LETTER_QUEUE, 0, -1))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.peekAll())
                .verifyComplete();
    }

    // ── size ──────────────────────────────────────────────────────────────────

    @Test
    void size_returnsCurrentQueueLength() {
        when(listOps.size(RedisChannels.DEAD_LETTER_QUEUE)).thenReturn(Mono.just(5L));

        StepVerifier.create(service.size())
                .expectNext(5L)
                .verifyComplete();
    }

    @Test
    void size_whenEmpty_returnsZero() {
        when(listOps.size(RedisChannels.DEAD_LETTER_QUEUE)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.size())
                .expectNext(0L)
                .verifyComplete();
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    void clear_deletesKey_returnsTrue() {
        when(redisTemplate.delete(RedisChannels.DEAD_LETTER_QUEUE)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.clear())
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void clear_whenKeyDidNotExist_returnsFalse() {
        when(redisTemplate.delete(RedisChannels.DEAD_LETTER_QUEUE)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.clear())
                .expectNext(false)
                .verifyComplete();
    }

    // ── enqueue — trim path ───────────────────────────────────────────────────

    @Test
    void enqueue_trimsQueue_whenSizeExceedsMaxSize() {
        // Use a custom maxSize of 5 so we can trigger trim with size=6
        DeadLetterQueueService smallService =
                new DeadLetterQueueService(redisTemplate, objectMapper, 5L);

        when(listOps.leftPush(eq(RedisChannels.DEAD_LETTER_QUEUE), anyString()))
                .thenReturn(Mono.just(6L)); // size > maxSize(5)
        when(listOps.trim(eq(RedisChannels.DEAD_LETTER_QUEUE), eq(0L), anyLong()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(smallService.enqueue(sampleMessage, sampleError))
                .expectNext(6L)
                .verifyComplete();
    }

    // ── 3-arg constructor ─────────────────────────────────────────────────────

    @Test
    void customMaxSizeConstructor_usesProvidedMaxSize() {
        DeadLetterQueueService customService =
                new DeadLetterQueueService(redisTemplate, objectMapper, 42L);

        when(listOps.leftPush(eq(RedisChannels.DEAD_LETTER_QUEUE), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(customService.enqueue(sampleMessage, sampleError))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void customMaxSizeConstructor_withZeroMaxSize_usesDefault() {
        // maxSize=0 → should fall back to DEFAULT_MAX_SIZE
        DeadLetterQueueService zeroService =
                new DeadLetterQueueService(redisTemplate, objectMapper, 0L);

        when(listOps.leftPush(eq(RedisChannels.DEAD_LETTER_QUEUE), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(zeroService.enqueue(sampleMessage, sampleError))
                .expectNext(1L)
                .verifyComplete();
    }
}
