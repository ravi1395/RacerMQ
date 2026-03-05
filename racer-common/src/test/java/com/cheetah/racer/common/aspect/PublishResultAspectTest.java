package com.cheetah.racer.common.aspect;

import com.cheetah.racer.common.annotation.ConcurrencyMode;
import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import com.cheetah.racer.common.publisher.RacerStreamPublisher;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.RecordId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PublishResultAspect}, covering both SEQUENTIAL
 * and CONCURRENT dispatch modes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublishResultAspectTest {

    @Mock RacerPublisherRegistry  registry;
    @Mock RacerChannelPublisher   publisher;
    @Mock RacerStreamPublisher    streamPublisher;
    @Mock ProceedingJoinPoint     pjp;
    @Mock PublishResult           annotation;

    PublishResultAspect aspect;

    static final String CHANNEL = "racer:test";

    @BeforeEach
    void setUp() {
        aspect = new PublishResultAspect(registry, streamPublisher);

        // Default annotation stubs — SEQUENTIAL, async, direct channel
        when(annotation.channel()).thenReturn(CHANNEL);
        when(annotation.channelRef()).thenReturn("");
        when(annotation.sender()).thenReturn("test-sender");
        when(annotation.async()).thenReturn(true);
        when(annotation.durable()).thenReturn(false);
        when(annotation.streamKey()).thenReturn("");
        when(annotation.mode()).thenReturn(ConcurrencyMode.SEQUENTIAL);
        when(annotation.concurrency()).thenReturn(4);

        // Registry returns our mock publisher for the test channel
        when(registry.getAll()).thenReturn(Map.of(CHANNEL, publisher));
        when(publisher.getChannelName()).thenReturn(CHANNEL);
        when(publisher.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
    }

    // ── Sequential mode (Flux) ────────────────────────────────────────────────

    @Test
    void sequential_flux_publishesEachElementAsFireAndForget() throws Throwable {
        when(pjp.proceed()).thenReturn(Flux.just("a", "b", "c"));

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isInstanceOf(Flux.class);
        List<String> emitted = new ArrayList<>();
        ((Flux<?>) result).subscribe(v -> emitted.add(v.toString()));

        assertThat(emitted).containsExactly("a", "b", "c");
        verify(publisher, times(3)).publishAsync(any(), eq("test-sender"));
    }

    @Test
    void sequential_flux_preservesOriginalElementsDownstream() throws Throwable {
        when(pjp.proceed()).thenReturn(Flux.just(10, 20, 30));

        Object result = aspect.intercept(pjp, annotation);

        StepVerifier.create((Flux<?>) result)
                .expectNextCount(3)
                .verifyComplete();
    }

    // ── Concurrent mode (Flux) ────────────────────────────────────────────────

    @Test
    void concurrent_flux_publishesAllElements() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(annotation.concurrency()).thenReturn(4);
        when(pjp.proceed()).thenReturn(Flux.just("x", "y", "z"));

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isInstanceOf(Flux.class);

        List<String> emitted = new ArrayList<>();
        ((Flux<?>) result).doOnNext(v -> emitted.add(v.toString())).blockLast();

        assertThat(emitted).containsExactlyInAnyOrder("x", "y", "z");
        verify(publisher, times(3)).publishAsync(any(), eq("test-sender"));
    }

    @Test
    void concurrent_flux_propagatesOriginalValuesDownstream() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(annotation.concurrency()).thenReturn(2);
        when(pjp.proceed()).thenReturn(Flux.just("p", "q", "r"));

        Object result = aspect.intercept(pjp, annotation);

        StepVerifier.create((Flux<?>) result)
                .expectNextCount(3)
                .verifyComplete();
    }

    @Test
    void concurrent_flux_respectsConcurrencyBound() throws Throwable {
        int concurrency = 2;
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(annotation.concurrency()).thenReturn(concurrency);

        AtomicInteger maxInFlight  = new AtomicInteger(0);
        AtomicInteger currentCount = new AtomicInteger(0);

        when(publisher.publishAsync(any(), anyString())).thenAnswer(inv ->
                Mono.fromCallable(() -> {
                    int current = currentCount.incrementAndGet();
                    maxInFlight.updateAndGet(prev -> Math.max(prev, current));
                    Thread.sleep(30);
                    currentCount.decrementAndGet();
                    return 1L;
                })
        );

        when(pjp.proceed()).thenReturn(Flux.range(1, 6));

        Object result = aspect.intercept(pjp, annotation);
        ((Flux<?>) result).blockLast();

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(concurrency);
        verify(publisher, times(6)).publishAsync(any(), eq("test-sender"));
    }

    @Test
    void concurrent_mode_returns_flux_not_mono() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(pjp.proceed()).thenReturn(Flux.just("only-one"));

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isInstanceOf(Flux.class);
    }

    // ── Mono path (mode is irrelevant) ────────────────────────────────────────

    @Test
    void mono_publishesSingleValue_sequentialMode() throws Throwable {
        when(pjp.proceed()).thenReturn(Mono.just("hello"));

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isInstanceOf(Mono.class);
        ((Mono<?>) result).block();

        verify(publisher, times(1)).publishAsync(eq("hello"), eq("test-sender"));
    }

    @Test
    void mono_modeAttributeHasNoEffect() throws Throwable {
        // CONCURRENT on a Mono — should still publish exactly once
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(pjp.proceed()).thenReturn(Mono.just("single"));

        Object result = aspect.intercept(pjp, annotation);
        ((Mono<?>) result).block();

        verify(publisher, times(1)).publishAsync(eq("single"), eq("test-sender"));
    }

    // ── POJO / plain return ───────────────────────────────────────────────────

    @Test
    void pojo_asyncTrue_publishesFireAndForget() throws Throwable {
        when(pjp.proceed()).thenReturn("plain-value");

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isEqualTo("plain-value");
        verify(publisher, times(1)).publishAsync(eq("plain-value"), eq("test-sender"));
    }

    @Test
    void pojo_asyncFalse_publishesSync() throws Throwable {
        when(annotation.async()).thenReturn(false);
        when(pjp.proceed()).thenReturn("sync-value");

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isEqualTo("sync-value");
        verify(publisher, times(1)).publishAsync(eq("sync-value"), eq("test-sender"));
    }

    // ── Durable path in concurrent mode ──────────────────────────────────────

    @Test
    void concurrent_durable_flux_usesStreamPublisher() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(annotation.durable()).thenReturn(true);
        when(annotation.concurrency()).thenReturn(4);
        doReturn(Mono.just(RecordId.of("1-0")))
                .when(streamPublisher).publishToStream(anyString(), any(), anyString());

        when(pjp.proceed()).thenReturn(Flux.just("e1", "e2"));

        Object result = aspect.intercept(pjp, annotation);
        ((Flux<?>) result).blockLast();

        verify(streamPublisher, times(2)).publishToStream(anyString(), any(), anyString());
        verify(publisher, never()).publishAsync(any(), anyString());
    }

    // ── processedCount: concurrent publishes exact number of times ────────────

    @Test
    void concurrent_flux_fiveElements_publishedFiveTimes() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(annotation.concurrency()).thenReturn(3);
        when(pjp.proceed()).thenReturn(Flux.range(1, 5));

        Object result = aspect.intercept(pjp, annotation);
        ((Flux<?>) result).blockLast();

        verify(publisher, times(5)).publishAsync(any(), eq("test-sender"));
    }
}
