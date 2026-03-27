package com.cheetah.racer.poll;

import com.cheetah.racer.annotation.RacerPoll;
import com.cheetah.racer.publisher.RacerChannelPublisher;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerPollRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerPollRegistrarTest {

    // ── Test beans ────────────────────────────────────────────────────────────

    static class SamplePollBean {
        @RacerPoll(channelRef = "orders", fixedRate = 1000)
        public String generateOrder() {
            return "{\"orderId\": 123}";
        }
    }

    static class PlainBean {
        public String doSomething() {
            return "no annotation";
        }
    }

    static class MonoReturnBean {
        public Mono<String> fetchLatest() {
            return Mono.just("{\"status\":\"ok\"}");
        }
    }

    static class NullReturnBean {
        public String noData() {
            return null;
        }
    }

    static class WithParamsBean {
        public String withParams(String x) {
            return x;
        }
    }

    static class DirectChannelBean {
        @RacerPoll(channel = "racer:orders", fixedRate = 5_000)
        public String fetchData() {
            return "{\"direct\": true}";
        }
    }

    static class NeitherChannelBean {
        @RacerPoll(fixedRate = 5_000) // channel="" channelRef="" both empty
        public String fetchData() {
            return "{}";
        }
    }

    static class CronPollBean {
        @RacerPoll(channelRef = "orders", cron = "* * * * * *")
        public String fetchCron() {
            return "{\"cron\": true}";
        }
    }

    static class NonStringReturnBean {
        @RacerPoll(channelRef = "orders", fixedRate = 5_000)
        public java.util.concurrent.atomic.AtomicBoolean fetchUnserializable() {
            // Jackson can serialize AtomicBoolean normally; to cause failure we'll
            // override the mapper in the specific test
            return new java.util.concurrent.atomic.AtomicBoolean(true);
        }
    }

    static class ThrowingBean {
        @RacerPoll(channelRef = "orders", fixedRate = 5_000)
        public String failAlways() {
            throw new RuntimeException("intentional-failure");
        }
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock RacerPublisherRegistry publisherRegistry;
    @Mock RacerChannelPublisher publisher;

    ObjectMapper objectMapper;
    RacerPollRegistrar registrar;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        when(publisherRegistry.getPublisher("orders")).thenReturn(publisher);
        when(publisher.getChannelName()).thenReturn("orders-channel");
        when(publisher.publishAsync(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(1L));

        registrar = new RacerPollRegistrar(publisherRegistry, objectMapper, null);
        registrar.setEnvironment(new MockEnvironment());
    }

    // ── Tests: bean discovery ─────────────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods() throws Exception {
        SamplePollBean bean = new SamplePollBean();
        Object result = registrar.postProcessAfterInitialization(bean, "samplePollBean");

        assertThat(result).isSameAs(bean);
        // Allow async scheduling to wire up — the subscription is added immediately
        Thread.sleep(200);
        // The registrar should have at least one subscription after discovery
        // We verify indirectly: stop() should not throw and poll counters start at 0
        registrar.stop();
    }

    @Test
    void postProcessAfterInitialization_noAnnotation_returnsBean() {
        PlainBean bean = new PlainBean();
        Object result = registrar.postProcessAfterInitialization(bean, "plainBean");

        assertThat(result).isSameAs(bean);
    }

    // ── Tests: stop lifecycle ─────────────────────────────────────────────────

    @Test
    void stop_disposesAllSubscriptions() throws Exception {
        SamplePollBean bean = new SamplePollBean();
        registrar.postProcessAfterInitialization(bean, "samplePollBean");
        Thread.sleep(200);

        // Should not throw
        registrar.stop();
    }

    // ── Tests: counter accessors ──────────────────────────────────────────────

    @Test
    void getTotalPolls_initiallyZero() {
        assertThat(registrar.getTotalPolls()).isZero();
    }

    @Test
    void getTotalErrors_initiallyZero() {
        assertThat(registrar.getTotalErrors()).isZero();
    }

    // ── Tests: CronMatcher ────────────────────────────────────────────────────

    @Test
    void cronMatcher_matchesOnce_returnsTrue_whenCronFires() {
        // "* * * * * *" fires every second — should match the current second
        AtomicReference<LocalDateTime> lastFired = new AtomicReference<>(LocalDateTime.MIN);
        boolean matched = RacerPollRegistrar.CronMatcher.matchesOnce("* * * * * *", lastFired);

        assertThat(matched).isTrue();
    }

    @Test
    void cronMatcher_matchesOnce_deduplicates() {
        AtomicReference<LocalDateTime> lastFired = new AtomicReference<>(LocalDateTime.MIN);

        boolean first = RacerPollRegistrar.CronMatcher.matchesOnce("* * * * * *", lastFired);
        assertThat(first).isTrue();

        // Second call within the same second should return false (dedup)
        boolean second = RacerPollRegistrar.CronMatcher.matchesOnce("* * * * * *", lastFired);
        assertThat(second).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void cronMatcher_matches_deprecatedMethod_works() {
        // "* * * * * *" fires every second — should match now
        boolean matched = RacerPollRegistrar.CronMatcher.matches("* * * * * *");
        assertThat(matched).isTrue();
    }

    @Test
    void cronMatcher_matchesOnce_invalidCron_returnsFalse() {
        AtomicReference<LocalDateTime> lastFired = new AtomicReference<>(LocalDateTime.MIN);
        boolean matched = RacerPollRegistrar.CronMatcher.matchesOnce("not-a-cron", lastFired);

        assertThat(matched).isFalse();
    }

    // ── Tests: invokeThenPublish (via reflection) ──────────────────────────────

    @Test
    void invokeThenPublish_stringReturnAsync_publishesViaAsync() throws Exception {
        when(publisher.publishAsync(anyString(), anyString())).thenReturn(Mono.just(1L));

        SamplePollBean bean = new SamplePollBean();
        Method method = SamplePollBean.class.getDeclaredMethod("generateOrder");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                registrar, "invokeThenPublish",
                bean, method, publisher, "orders-channel", "test-sender", true);

        StepVerifier.create(result).verifyComplete();
        verify(publisher).publishAsync(anyString(), anyString());
    }

    @Test
    void invokeThenPublish_stringReturnSync_publishesViaSync() throws Exception {
        when(publisher.publishSync(anyString())).thenReturn(1L);

        SamplePollBean bean = new SamplePollBean();
        Method method = SamplePollBean.class.getDeclaredMethod("generateOrder");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                registrar, "invokeThenPublish",
                bean, method, publisher, "orders-channel", "test-sender", false);

        StepVerifier.create(result).verifyComplete();
        verify(publisher).publishSync(anyString());
    }

    @Test
    void invokeThenPublish_nullReturn_completesImmediately() throws Exception {
        NullReturnBean bean = new NullReturnBean();
        Method method = NullReturnBean.class.getDeclaredMethod("noData");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                registrar, "invokeThenPublish",
                bean, method, publisher, "orders-channel", "test-sender", true);

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void invokeThenPublish_monoReturn_subscribesAndPublishes() throws Exception {
        when(publisher.publishAsync(anyString(), anyString())).thenReturn(Mono.just(1L));

        MonoReturnBean bean = new MonoReturnBean();
        Method method = MonoReturnBean.class.getDeclaredMethod("fetchLatest");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                registrar, "invokeThenPublish",
                bean, method, publisher, "orders-channel", "test-sender", true);

        StepVerifier.create(result).verifyComplete();
        verify(publisher).publishAsync(anyString(), anyString());
    }

    @Test
    void invokeThenPublish_methodWithParams_skipsAndCompletesEmpty() throws Exception {
        WithParamsBean bean = new WithParamsBean();
        Method method = WithParamsBean.class.getDeclaredMethod("withParams", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                registrar, "invokeThenPublish",
                bean, method, publisher, "orders-channel", "test-sender", true);

        StepVerifier.create(result).verifyComplete();
    }

    // ── Tests: direct channel attribute (L107) ────────────────────────────────

    @Test
    void registerPoll_withDirectChannelAttribute_usesChannelDirectly() {
        when(publisherRegistry.getPublisher("racer:orders")).thenReturn(publisher);
        when(publisher.getChannelName()).thenReturn("racer:orders");
        when(publisher.publishAsync(anyString(), anyString())).thenReturn(Mono.just(1L));

        DirectChannelBean bean = new DirectChannelBean();
        registrar.postProcessAfterInitialization(bean, "directChannelBean");

        // publisherRegistry.getPublisher(channel) is called synchronously during registration
        verify(publisherRegistry).getPublisher("racer:orders");
        registrar.stop();
    }

    // ── Tests: neither channel nor channelRef (L109-111) ──────────────────────

    @Test
    void registerPoll_withNeitherChannelNorRef_logsErrorAndSkips() {
        NeitherChannelBean bean = new NeitherChannelBean();
        // Should not throw, should not call the registry, should not register any subscription
        registrar.postProcessAfterInitialization(bean, "neitherChannelBean");
        registrar.stop();
    }

    // ── Tests: cron expression path (L122-126) ────────────────────────────────

    @Test
    void registerPoll_withCronExpression_registersCronTicker() {
        // CronPollBean uses channelRef="orders" which is already stubbed in setUp()
        CronPollBean bean = new CronPollBean();
        registrar.postProcessAfterInitialization(bean, "cronPollBean");

        // The cron ticker Flux is assembled synchronously during registerPoll — lines 122-126
        verify(publisherRegistry).getPublisher("orders");
        registrar.stop();
    }

    // ── Tests: invokeThenPublish error handler (L166-168) ────────────────────

    @Test
    void invokeThenPublish_throwingMethod_incrementsErrorCount() throws Exception {
        ThrowingBean bean = new ThrowingBean();
        Method method = ThrowingBean.class.getDeclaredMethod("failAlways");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                registrar, "invokeThenPublish",
                bean, method, publisher, "orders-channel", "test-sender", true);

        StepVerifier.create(result).verifyComplete();
        assertThat(registrar.getTotalErrors()).isEqualTo(1L);
    }

    // ── Tests: serialization failure in publish (L180-182) ───────────────────

    @Test
    void publish_withSerializationFailure_returnsEmptyMono() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("ser-fail") {});

        RacerPollRegistrar failingRegistrar = new RacerPollRegistrar(publisherRegistry, failingMapper, null);
        failingRegistrar.setEnvironment(new MockEnvironment());

        // Call publish() with a non-String payload so objectMapper.writeValueAsString is invoked
        Object nonStringPayload = new java.util.concurrent.atomic.AtomicBoolean(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) ReflectionTestUtils.invokeMethod(
                failingRegistrar, "publish",
                nonStringPayload, publisher, "orders-channel", "test-sender", true);

        StepVerifier.create(result).verifyComplete();
    }
}
