package com.cheetah.racer.aspect;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.annotation.PublishResults;
import com.cheetah.racer.annotation.RacerPriority;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
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
        when(annotation.priority()).thenReturn("");

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

    // ── @PublishResults (repeatable multi-channel fan-out) ────────────────────

    @Test
    void multiChannel_mono_publishesToBothChannels() throws Throwable {
        // Second channel + publisher
        String channel2 = "racer:audit";
        RacerChannelPublisher publisher2 = mock(RacerChannelPublisher.class);
        when(publisher2.getChannelName()).thenReturn(channel2);
        when(publisher2.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(registry.getAll()).thenReturn(Map.of(CHANNEL, publisher, channel2, publisher2));

        // Two @PublishResult annotations wrapped in @PublishResults
        PublishResult ann1 = annotation; // already stubs CHANNEL
        PublishResult ann2 = mock(PublishResult.class);
        when(ann2.channel()).thenReturn(channel2);
        when(ann2.channelRef()).thenReturn("");
        when(ann2.sender()).thenReturn("audit-sender");
        when(ann2.async()).thenReturn(true);
        when(ann2.durable()).thenReturn(false);
        when(ann2.streamKey()).thenReturn("");
        when(ann2.mode()).thenReturn(ConcurrencyMode.SEQUENTIAL);
        when(ann2.concurrency()).thenReturn(4);
        when(ann2.priority()).thenReturn("");

        PublishResults container = mock(PublishResults.class);
        when(container.value()).thenReturn(new PublishResult[]{ann1, ann2});

        // Build a PJP with a MethodSignature (no priority annotation)
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("dummyMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn(Mono.just("hello-multi"));

        Object result = aspect.interceptMulti(pjp, container);
        ((Mono<?>) result).block();

        verify(publisher, times(1)).publishAsync(eq("hello-multi"), eq("test-sender"));
        verify(publisher2, times(1)).publishAsync(eq("hello-multi"), eq("audit-sender"));
    }

    @Test
    void multiChannel_flux_publishesToBothChannelsForEachElement() throws Throwable {
        String channel2 = "racer:events";
        RacerChannelPublisher publisher2 = mock(RacerChannelPublisher.class);
        when(publisher2.getChannelName()).thenReturn(channel2);
        when(publisher2.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(registry.getAll()).thenReturn(Map.of(CHANNEL, publisher, channel2, publisher2));

        PublishResult ann2 = mock(PublishResult.class);
        when(ann2.channel()).thenReturn(channel2);
        when(ann2.channelRef()).thenReturn("");
        when(ann2.sender()).thenReturn("event-sender");
        when(ann2.async()).thenReturn(true);
        when(ann2.durable()).thenReturn(false);
        when(ann2.streamKey()).thenReturn("");
        when(ann2.mode()).thenReturn(ConcurrencyMode.SEQUENTIAL);
        when(ann2.concurrency()).thenReturn(4);
        when(ann2.priority()).thenReturn("");

        PublishResults container = mock(PublishResults.class);
        when(container.value()).thenReturn(new PublishResult[]{annotation, ann2});

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("dummyMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn(Flux.just("a", "b"));

        Object result = aspect.interceptMulti(pjp, container);
        ((Flux<?>) result).blockLast();

        verify(publisher, times(2)).publishAsync(any(), eq("test-sender"));
        verify(publisher2, times(2)).publishAsync(any(), eq("event-sender"));
    }

    @Test
    void multiChannel_pojo_publishesToBothChannels() throws Throwable {
        String channel2 = "racer:log";
        RacerChannelPublisher publisher2 = mock(RacerChannelPublisher.class);
        when(publisher2.getChannelName()).thenReturn(channel2);
        when(publisher2.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(registry.getAll()).thenReturn(Map.of(CHANNEL, publisher, channel2, publisher2));

        PublishResult ann2 = mock(PublishResult.class);
        when(ann2.channel()).thenReturn(channel2);
        when(ann2.channelRef()).thenReturn("");
        when(ann2.sender()).thenReturn("log-sender");
        when(ann2.async()).thenReturn(true);
        when(ann2.durable()).thenReturn(false);
        when(ann2.streamKey()).thenReturn("");
        when(ann2.mode()).thenReturn(ConcurrencyMode.SEQUENTIAL);
        when(ann2.concurrency()).thenReturn(4);
        when(ann2.priority()).thenReturn("");

        PublishResults container = mock(PublishResults.class);
        when(container.value()).thenReturn(new PublishResult[]{annotation, ann2});

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("dummyMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn("plain-pojo");

        Object result = aspect.interceptMulti(pjp, container);
        assertThat(result).isEqualTo("plain-pojo");

        verify(publisher, times(1)).publishAsync(eq("plain-pojo"), eq("test-sender"));
        verify(publisher2, times(1)).publishAsync(eq("plain-pojo"), eq("log-sender"));
    }

    // ── @RacerPriority routing ───────────────────────────────────────────────

    @Test
    void priority_mono_routesViaPriorityPublisher() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        // Rebuild aspect WITH priority publisher
        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        // PJP with @RacerPriority-annotated method
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn(Mono.just("urgent-payload"));

        Object result = aspect.intercept(pjp, annotation);
        ((Mono<?>) result).block();

        // Should route via priority publisher, NOT the regular one
        verify(priorityPub, times(1)).publish(eq(CHANNEL), eq("urgent-payload"), eq("test-sender"), eq("HIGH"));
        verify(publisher, never()).publishAsync(any(), anyString());
    }

    @Test
    void priority_pojo_routesViaPriorityPublisher() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn("plain-priority");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("plain-priority");

        verify(priorityPub, times(1)).publish(eq(CHANNEL), eq("plain-priority"), eq("test-sender"), eq("HIGH"));
        verify(publisher, never()).publishAsync(any(), anyString());
    }

    @Test
    void noPriority_mono_usesRegularPublisher() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        // Method WITHOUT @RacerPriority
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("dummyMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.proceed()).thenReturn(Mono.just("no-priority"));

        Object result = aspect.intercept(pjp, annotation);
        ((Mono<?>) result).block();

        // Should use regular publisher
        verify(publisher, times(1)).publishAsync(eq("no-priority"), eq("test-sender"));
        verify(priorityPub, never()).publish(any(), any(), any(), any());
    }

    // ── Per-annotation selective priority ─────────────────────────────────────

    @Test
    void multiChannel_selectivePriority_onlyAnnotatedChannelGetsPriority() throws Throwable {
        // Channel 1: notification → priority = "HIGH"
        when(annotation.priority()).thenReturn("HIGH");

        // Channel 2: audit → no priority (standard pub/sub)
        String auditChannel = "racer:audit";
        RacerChannelPublisher auditPublisher = mock(RacerChannelPublisher.class);
        when(auditPublisher.getChannelName()).thenReturn(auditChannel);
        when(auditPublisher.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(registry.getAll()).thenReturn(Map.of(CHANNEL, publisher, auditChannel, auditPublisher));

        PublishResult ann2 = mock(PublishResult.class);
        when(ann2.channel()).thenReturn(auditChannel);
        when(ann2.channelRef()).thenReturn("");
        when(ann2.sender()).thenReturn("audit-sender");
        when(ann2.async()).thenReturn(true);
        when(ann2.durable()).thenReturn(false);
        when(ann2.streamKey()).thenReturn("");
        when(ann2.mode()).thenReturn(ConcurrencyMode.SEQUENTIAL);
        when(ann2.concurrency()).thenReturn(4);
        when(ann2.priority()).thenReturn(""); // no priority

        PublishResults container = mock(PublishResults.class);
        when(container.value()).thenReturn(new PublishResult[]{annotation, ann2});

        // Build PJP with a method that has NO @RacerPriority (priority lives on annotation)
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("dummyMethod"));
        when(pjp.getSignature()).thenReturn(sig);

        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));
        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        when(pjp.proceed()).thenReturn(Mono.just("selective-payload"));

        Object result = aspect.interceptMulti(pjp, container);
        ((Mono<?>) result).block();

        // Channel 1 → routed via priority publisher to HIGH sub-channel
        verify(priorityPub, times(1))
                .publish(eq(CHANNEL), eq("selective-payload"), eq("test-sender"), eq("HIGH"));
        // Channel 1 should NOT go through regular publisher
        verify(publisher, never()).publishAsync(any(), anyString());

        // Channel 2 → standard pub/sub on audit channel (no priority routing)
        verify(auditPublisher, times(1))
                .publishAsync(eq("selective-payload"), eq("audit-sender"));
        // Priority publisher should NOT be invoked for audit
        verify(priorityPub, never())
                .publish(eq(auditChannel), any(), anyString(), anyString());
    }

    // ── Helper methods used by test reflection ───────────────────────────────

    /** Dummy method without @RacerPriority — used for MethodSignature mocking. */
    @SuppressWarnings("unused")
    private void dummyMethod() {}

    /** Method annotated with @RacerPriority — used for MethodSignature mocking. */
    @RacerPriority(defaultLevel = "HIGH")
    @SuppressWarnings("unused")
    private void priorityAnnotatedMethod() {}

    // ── Reactive upstream error handlers ─────────────────────────────────────

    @Test
    void mono_upstreamError_propagatesAndLogsDebug() throws Throwable {
        when(pjp.proceed()).thenReturn(Mono.error(new RuntimeException("upstream-failure")));

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isInstanceOf(Mono.class);
        StepVerifier.create((Mono<?>) result)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void sequential_flux_upstreamError_propagatesAndLogsDebug() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.SEQUENTIAL);
        when(pjp.proceed()).thenReturn(Flux.error(new RuntimeException("seq-flux-failure")));

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isInstanceOf(Flux.class);
        StepVerifier.create((Flux<?>) result)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void concurrent_flux_upstreamError_propagatesAndLogsDebug() throws Throwable {
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(pjp.proceed()).thenReturn(Flux.error(new RuntimeException("concurrent-flux-failure")));

        Object result = aspect.intercept(pjp, annotation);

        assertThat(result).isInstanceOf(Flux.class);
        StepVerifier.create((Flux<?>) result)
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── Durable publish error handlers ────────────────────────────────────────

    @Test
    void pojo_asyncDurable_publishFails_errorHandlerInvoked() throws Throwable {
        when(annotation.durable()).thenReturn(true);
        when(annotation.async()).thenReturn(true);
        when(annotation.streamKey()).thenReturn("");
        doReturn(Mono.error(new RuntimeException("stream-async-error")))
                .when(streamPublisher).publishToStream(anyString(), any(), anyString());

        when(pjp.proceed()).thenReturn("durable-fail-value");

        // Should not throw — error callback handles it
        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("durable-fail-value");
        verify(streamPublisher, times(1)).publishToStream(anyString(), any(), anyString());
    }

    @Test
    void pojo_syncDurable_publishFails_exceptionCaught() throws Throwable {
        when(annotation.durable()).thenReturn(true);
        when(annotation.async()).thenReturn(false);
        when(annotation.streamKey()).thenReturn("");
        doReturn(Mono.error(new RuntimeException("stream-sync-error")))
                .when(streamPublisher).publishToStream(anyString(), any(), anyString());

        when(pjp.proceed()).thenReturn("sync-durable-fail");

        // catch block should handle the exception
        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("sync-durable-fail");
        verify(streamPublisher, times(1)).publishToStream(anyString(), any(), anyString());
    }

    // ── Standard publish error handlers ──────────────────────────────────────

    @Test
    void pojo_async_publishFails_errorHandlerInvoked() throws Throwable {
        when(publisher.publishAsync(any(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("async-pub-error")));
        when(pjp.proceed()).thenReturn("failing-value");

        // Fire-and-forget subscribe error callback should handle without throwing
        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("failing-value");
    }

    @Test
    void pojo_sync_publishFails_exceptionCaught() throws Throwable {
        when(annotation.async()).thenReturn(false);
        when(publisher.publishAsync(any(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("sync-pub-error")));
        when(pjp.proceed()).thenReturn("sync-failing-value");

        // catch block should handle the block() exception
        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("sync-failing-value");
    }

    // ── Priority publish error handlers ──────────────────────────────────────

    @Test
    void priority_pojo_async_publishFails_errorHandlerInvoked() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("async-priority-error")));
        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(annotation.async()).thenReturn(true);
        when(pjp.proceed()).thenReturn("priority-fail-async");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("priority-fail-async");
    }

    @Test
    void priority_pojo_sync_publishFails_exceptionCaught() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("sync-priority-error")));
        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(annotation.async()).thenReturn(false);
        when(pjp.proceed()).thenReturn("priority-fail-sync");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("priority-fail-sync");
    }

    @Test
    void threeArgConstructor_withProperties_sendsChannelRefConfiguredSender() throws Throwable {
        RacerProperties props = new RacerProperties();
        RacerProperties.ChannelProperties ch = new RacerProperties.ChannelProperties();
        ch.setSender("orders-service");
        ch.setAsync(true);
        props.getChannels().put("orders", ch);

        PublishResultAspect aspectWithProps = new PublishResultAspect(registry, streamPublisher, props);

        when(annotation.sender()).thenReturn("");            // blank → fall through to props
        when(annotation.channelRef()).thenReturn("orders");
        when(annotation.channel()).thenReturn(CHANNEL);     // direct channel name still there
        when(pjp.proceed()).thenReturn("payload");

        Object result = aspectWithProps.intercept(pjp, annotation);
        assertThat(result).isEqualTo("payload");

        verify(publisher, times(1)).publishAsync(eq("payload"), eq("orders-service"));
    }

    @Test
    void threeArgConstructor_channelRefNotInProps_fallsBackToDefaultSender() throws Throwable {
        RacerProperties props = new RacerProperties(); // empty channels map

        PublishResultAspect aspectWithProps = new PublishResultAspect(registry, streamPublisher, props);

        when(annotation.sender()).thenReturn("");            // blank → fall through
        when(annotation.channelRef()).thenReturn("unknown-ref");
        when(annotation.channel()).thenReturn(CHANNEL);
        when(pjp.proceed()).thenReturn("payload");

        Object result = aspectWithProps.intercept(pjp, annotation);
        assertThat(result).isEqualTo("payload");

        // Should fall back to "racer-publisher"
        verify(publisher, times(1)).publishAsync(eq("payload"), eq("racer-publisher"));
    }

    @Test
    void resolveAsync_withChannelRefInProps_usesPropsAsyncSetting() throws Throwable {
        RacerProperties props = new RacerProperties();
        RacerProperties.ChannelProperties ch = new RacerProperties.ChannelProperties();
        ch.setAsync(false); // props say sync
        props.getChannels().put("orders", ch);

        PublishResultAspect aspectWithProps = new PublishResultAspect(registry, streamPublisher, props);

        when(annotation.sender()).thenReturn("svc");
        when(annotation.channelRef()).thenReturn("orders");
        when(annotation.channel()).thenReturn(CHANNEL);
        when(annotation.async()).thenReturn(true); // annotation says async=true, but props override
        when(pjp.proceed()).thenReturn("sync-payload");

        aspectWithProps.intercept(pjp, annotation);

        // Sync path: still calls publishAsync (it's the underlying API) but via sync branch
        verify(publisher, times(1)).publishAsync(eq("sync-payload"), eq("svc"));
    }

    // ── resolveChannel with channelRef ────────────────────────────────────────

    @Test
    void resolveChannel_withChannelRef_getsPublisherByChannelRef() throws Throwable {
        RacerChannelPublisher channelRefPublisher = mock(RacerChannelPublisher.class);
        when(channelRefPublisher.getChannelName()).thenReturn("racer:orders");
        when(channelRefPublisher.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(registry.getPublisher("orders")).thenReturn(channelRefPublisher);
        when(registry.getAll()).thenReturn(Map.of("racer:orders", channelRefPublisher));

        // no direct channel, only channelRef
        when(annotation.channel()).thenReturn("");
        when(annotation.channelRef()).thenReturn("orders");
        when(pjp.proceed()).thenReturn("channelref-payload");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("channelref-payload");

        verify(channelRefPublisher, times(1)).publishAsync(eq("channelref-payload"), anyString());
    }

    @Test
    void resolveChannel_withNoChannelAndNoChannelRef_usesNullPublisher() throws Throwable {
        RacerChannelPublisher defaultPublisher = mock(RacerChannelPublisher.class);
        when(defaultPublisher.getChannelName()).thenReturn("racer:default");
        when(defaultPublisher.publishAsync(any(), anyString())).thenReturn(Mono.just(1L));
        when(registry.getPublisher(null)).thenReturn(defaultPublisher);
        when(registry.getAll()).thenReturn(Map.of("racer:default", defaultPublisher));

        when(annotation.channel()).thenReturn("");
        when(annotation.channelRef()).thenReturn("");
        when(pjp.proceed()).thenReturn("default-payload");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("default-payload");

        verify(defaultPublisher, times(1)).publishAsync(eq("default-payload"), anyString());
    }

    // ── resolveStreamKey with explicit streamKey ──────────────────────────────

    @Test
    void resolveStreamKey_withExplicitStreamKey_usesItForStream() throws Throwable {
        when(annotation.durable()).thenReturn(true);
        when(annotation.async()).thenReturn(true);
        when(annotation.streamKey()).thenReturn("custom:orders:stream");
        doReturn(Mono.just(org.springframework.data.redis.connection.stream.RecordId.of("2-0")))
                .when(streamPublisher).publishToStream(eq("custom:orders:stream"), any(), anyString());

        when(pjp.proceed()).thenReturn("streamed-value");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("streamed-value");

        verify(streamPublisher, times(1)).publishToStream(eq("custom:orders:stream"), any(), anyString());
    }

    // ── Durable sequential paths ──────────────────────────────────────────────

    @Test
    void durable_sequential_async_subscribesToStream() throws Throwable {
        when(annotation.durable()).thenReturn(true);
        when(annotation.async()).thenReturn(true);
        when(annotation.streamKey()).thenReturn("");
        doReturn(Mono.just(org.springframework.data.redis.connection.stream.RecordId.of("1-0")))
                .when(streamPublisher).publishToStream(anyString(), any(), anyString());

        when(pjp.proceed()).thenReturn("durable-async");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("durable-async");

        verify(streamPublisher, times(1)).publishToStream(contains(CHANNEL), eq("durable-async"), anyString());
        verify(publisher, never()).publishAsync(any(), anyString());
    }

    @Test
    void durable_sequential_sync_blocksOnStream() throws Throwable {
        when(annotation.durable()).thenReturn(true);
        when(annotation.async()).thenReturn(false);
        when(annotation.streamKey()).thenReturn("");
        doReturn(Mono.just(org.springframework.data.redis.connection.stream.RecordId.of("3-0")))
                .when(streamPublisher).publishToStream(anyString(), any(), anyString());

        when(pjp.proceed()).thenReturn("durable-sync");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("durable-sync");

        verify(streamPublisher, times(1)).publishToStream(contains(CHANNEL), eq("durable-sync"), anyString());
        verify(publisher, never()).publishAsync(any(), anyString());
    }

    // ── Priority sequential paths ─────────────────────────────────────────────

    @Test
    void priority_pojo_async_usesSubscribePath() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(annotation.async()).thenReturn(true);
        when(pjp.proceed()).thenReturn("async-priority");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("async-priority");

        verify(priorityPub, times(1)).publish(eq(CHANNEL), eq("async-priority"), eq("test-sender"), eq("HIGH"));
    }

    @Test
    void priority_pojo_sync_blocksOnPublish() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(annotation.async()).thenReturn(false);
        when(pjp.proceed()).thenReturn("sync-priority");

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isEqualTo("sync-priority");

        verify(priorityPub, times(1)).publish(eq(CHANNEL), eq("sync-priority"), eq("test-sender"), eq("HIGH"));
    }

    // ── Priority concurrent paths ─────────────────────────────────────────────

    @Test
    void priority_concurrent_flux_routesViaPriorityPublisher() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);
        when(annotation.mode()).thenReturn(ConcurrencyMode.CONCURRENT);
        when(annotation.concurrency()).thenReturn(2);
        when(pjp.proceed()).thenReturn(Flux.just("a", "b", "c"));

        Object result = aspect.intercept(pjp, annotation);
        ((Flux<?>) result).blockLast();

        verify(priorityPub, times(3)).publish(eq(CHANNEL), any(), eq("test-sender"), eq("HIGH"));
        verify(publisher, never()).publishAsync(any(), anyString());
    }

    // ── resolvePriorityLevel with RacerMessage ────────────────────────────────

    @Test
    void resolvePriorityLevel_racerMessageWithPriority_usesMessagePriority() throws Throwable {
        RacerPriorityPublisher priorityPub = mock(RacerPriorityPublisher.class);
        when(priorityPub.publish(anyString(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        aspect = new PublishResultAspect(registry, streamPublisher, null, priorityPub);

        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(getClass().getDeclaredMethod("priorityAnnotatedMethod"));
        when(pjp.getSignature()).thenReturn(sig);

        // RacerMessage with priority="LOW" should override method-level "HIGH"
        RacerMessage msgWithPriority = RacerMessage.create(CHANNEL, "content", "svc", "low");
        when(pjp.proceed()).thenReturn(msgWithPriority);

        Object result = aspect.intercept(pjp, annotation);
        assertThat(result).isSameAs(msgWithPriority);

        // message priority "low" -> toUpperCase() -> "LOW"
        verify(priorityPub, times(1)).publish(eq(CHANNEL), eq(msgWithPriority), eq("test-sender"), eq("LOW"));
    }
}
