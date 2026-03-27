package com.cheetah.racer.stream;

import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerMessageInterceptor;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerStreamListenerRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerStreamListenerRegistrarTest {

    // ── Test beans ────────────────────────────────────────────────────────────

    static class SampleStreamBean {
        @RacerStreamListener(streamKey = "stream:orders", group = "test-group")
        public void handleOrder(RacerMessage msg) {}
    }

    static class NoArgBean {
        final AtomicInteger calls = new AtomicInteger();
        @RacerStreamListener(streamKey = "stream:noarg", group = "noarg-group")
        public void handle() { calls.incrementAndGet(); }
    }

    static class DedupStreamBean {
        final AtomicInteger calls = new AtomicInteger();
        @RacerStreamListener(streamKey = "stream:dedup", group = "dedup-group", dedup = true)
        public void handle(RacerMessage msg) { calls.incrementAndGet(); }
    }

    static class PlainBean {
        public void noAnnotation() {}
    }

    static class NoStreamKeyBean {
        @RacerStreamListener(streamKey = "", group = "test-group")
        public void handle(RacerMessage msg) {}
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    @Mock ReactiveStreamOperations streamOps;
    @Mock Environment environment;
    @Mock RacerDeadLetterHandler deadLetterHandler;
    @Mock RacerDedupService dedupService;
    @Mock RacerCircuitBreakerRegistry cbRegistry;
    @Mock RacerCircuitBreaker circuitBreaker;
    @Mock ObjectProvider<RacerDedupService> dedupServiceProvider;
    @Mock ObjectProvider<RacerCircuitBreakerRegistry> cbRegistryProvider;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerStreamListenerRegistrar registrar;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new RacerProperties();
        properties.setChannels(new LinkedHashMap<>());

        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.createGroup(anyString(), any(), anyString())).thenReturn(Mono.just("OK"));

        registrar = new RacerStreamListenerRegistrar(
                redisTemplate, objectMapper, properties, null, null, null);
        registrar.setEnvironment(environment);
    }

    // ── Tests: bean discovery ─────────────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();
        Object result = registrar.postProcessAfterInitialization(bean, "sampleStreamBean");

        assertThat(result).isSameAs(bean);
        // Allow async group creation to complete
        Thread.sleep(300);
    }

    @Test
    void postProcessAfterInitialization_noAnnotation_returnsBean() {
        PlainBean bean = new PlainBean();
        Object result = registrar.postProcessAfterInitialization(bean, "plainBean");

        assertThat(result).isSameAs(bean);
    }

    // ── Tests: tracked stream groups ──────────────────────────────────────────

    @Test
    void getTrackedStreamGroups_afterRegistration_containsEntry() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();
        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(200);

        assertThat(registrar.getTrackedStreamGroups())
                .containsEntry("stream:orders", "test-group");
    }

    // ── Tests: counter accessors ──────────────────────────────────────────────

    @Test
    void getProcessedCount_unknownListener_returnsZero() {
        assertThat(registrar.getProcessedCount("nonexistent")).isZero();
    }

    @Test
    void getFailedCount_unknownListener_returnsZero() {
        assertThat(registrar.getFailedCount("nonexistent")).isZero();
    }

    // ── Tests: interceptors ───────────────────────────────────────────────────

    @Test
    void setInterceptors_storesInterceptors() {
        List<RacerMessageInterceptor> interceptors = Collections.emptyList();
        registrar.setInterceptors(interceptors);
        // No exception — interceptors stored successfully
    }

    @Test
    void setInterceptors_nullSafe() {
        registrar.setInterceptors(null);
        // No exception — null handled gracefully
    }

    // ── Tests: log prefix ─────────────────────────────────────────────────────

    @Test
    void logPrefix_returnsExpected() throws Exception {
        // logPrefix() is protected — we can test it via the class since we are in the same package
        // Use reflection to access the protected method
        java.lang.reflect.Method logPrefixMethod =
                RacerStreamListenerRegistrar.class.getDeclaredMethod("logPrefix");
        logPrefixMethod.setAccessible(true);
        String prefix = (String) logPrefixMethod.invoke(registrar);

        assertThat(prefix).isEqualTo("RACER-STREAM-LISTENER");
    }

    // ── Tests: lifecycle ──────────────────────────────────────────────────────

    @Test
    void stop_disposesSubscriptions() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();
        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(300);

        // Should not throw
        registrar.stop();
    }

    // ── Tests: no streamKey — skipped ────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_noStreamKey_isSkipped() throws Exception {
        properties.setDefaultChannel(""); // empty default forces skip
        NoStreamKeyBean bean = new NoStreamKeyBean();
        registrar.postProcessAfterInitialization(bean, "noStreamKeyBean");
        Thread.sleep(100);
        // No group created for an empty streamKey
        assertThat(registrar.getTrackedStreamGroups()).isEmpty();
    }

    // ── Tests: back-pressure poll interval ───────────────────────────────────

    @Test
    void setBackPressurePollIntervalMs_storesValue() {
        registrar.setBackPressurePollIntervalMs(500L);
        // Exercising the setter without exception is sufficient
    }

    @Test
    void setBackPressurePollIntervalMs_resetToZero() {
        registrar.setBackPressurePollIntervalMs(500L);
        registrar.setBackPressurePollIntervalMs(0L); // revert to annotation-defined interval
    }

    // ── Tests: consumerLagMonitor injection ───────────────────────────────────

    @Test
    void setConsumerLagMonitor_null_doesNotThrow() {
        registrar.setConsumerLagMonitor(null);
    }

    @Test
    void setConsumerLagMonitor_withMonitor_tracksStreams() throws Exception {
        RacerConsumerLagMonitor lagMonitor = mock(RacerConsumerLagMonitor.class);
        registrar.setConsumerLagMonitor(lagMonitor);

        SampleStreamBean bean = new SampleStreamBean();
        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(200);

        verify(lagMonitor, atLeastOnce()).trackStream("stream:orders", "test-group");
    }

    // ── Tests: dedup service injection ───────────────────────────────────────

    @Test
    void setDedupService_storesDedupService() {
        registrar.setDedupService(dedupService);
        // No exception — successfully injected
    }

    @Test
    void setDedupServiceProvider_storesProvider() {
        registrar.setDedupServiceProvider(dedupServiceProvider);
        // No exception — provider stored
    }

    // ── Tests: circuit breaker injection ──────────────────────────────────────

    @Test
    void setCircuitBreakerRegistry_storesRegistry() {
        registrar.setCircuitBreakerRegistry(cbRegistry);
        // No exception — successfully injected
    }

    @Test
    void setCircuitBreakerRegistryProvider_storesProvider() {
        registrar.setCircuitBreakerRegistryProvider(cbRegistryProvider);
        // No exception — provider stored
    }

    // ── Tests: dispatch — happy path ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void dispatch_validMessage_invokesMethod() throws Exception {
        NoArgBean bean = new NoArgBean();

        // Build a valid RacerMessage envelope JSON
        RacerMessage msg = RacerMessage.create("stream:noarg", "hello", "test");
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(msg);

        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream:noarg")
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of("data", json));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        registrar.postProcessAfterInitialization(bean, "noArgBean");
        Thread.sleep(400);

        assertThat(bean.calls.get()).isGreaterThanOrEqualTo(1);
    }

    // ── Tests: dispatch — missing data field → ACK and skip ──────────────────

    @SuppressWarnings("unchecked")
    @Test
    void dispatch_missingDataField_acksAndSkips() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();

        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream:orders")
                .withId(RecordId.of("2-0"))
                .ofMap(Map.of("other", "value")); // no "data" field

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(400);

        verify(streamOps, atLeastOnce()).acknowledge(eq("stream:orders"), eq("test-group"), any(RecordId.class));
    }

    // ── Tests: dispatch — invalid JSON → fails deserialization, ACKs ──────────

    @SuppressWarnings("unchecked")
    @Test
    void dispatch_invalidJson_incrementsFailedAndAcks() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();

        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream:orders")
                .withId(RecordId.of("3-0"))
                .ofMap(Map.of("data", "NOT-VALID-JSON"));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(400);

        verify(streamOps, atLeastOnce()).acknowledge(eq("stream:orders"), eq("test-group"), any(RecordId.class));
    }

    // ── Tests: dispatch — CB open → ACKs and skips ──────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void dispatch_circuitBreakerOpen_acksAndSkips() throws Exception {
        when(cbRegistry.getOrCreate(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.isCallPermitted()).thenReturn(false);

        registrar.setCircuitBreakerRegistry(cbRegistry);

        SampleStreamBean bean = new SampleStreamBean();
        RacerMessage msg = RacerMessage.create("stream:orders", "hello", "test");
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(msg);

        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream:orders")
                .withId(RecordId.of("4-0"))
                .ofMap(Map.of("data", json));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(400);

        verify(streamOps, atLeastOnce()).acknowledge(eq("stream:orders"), eq("test-group"), any(RecordId.class));
    }

    // ── Tests: dispatch — dedup duplicate → ACKs and skips ──────────────────

    @SuppressWarnings("unchecked")
    @Test
    void dispatch_dedupDuplicate_acksAndSkips() throws Exception {
        when(dedupService.checkAndMarkProcessed(anyString(), anyString()))
                .thenReturn(Mono.just(false)); // already processed

        registrar.setDedupService(dedupService);

        DedupStreamBean bean = new DedupStreamBean();
        RacerMessage msg = RacerMessage.create("stream:dedup", "payload", "test");
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(msg);

        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream:dedup")
                .withId(RecordId.of("5-0"))
                .ofMap(Map.of("data", json));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        registrar.postProcessAfterInitialization(bean, "dedupBean");
        Thread.sleep(400);

        verify(streamOps, atLeastOnce()).acknowledge(eq("stream:dedup"), anyString(), any(RecordId.class));
        assertThat(bean.calls.get()).isZero();
    }

    // ── Tests: dispatch — DLQ handler on method failure ──────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void dispatch_methodThrows_enqueuesDlq() throws Exception {
        when(deadLetterHandler.enqueue(any(RacerMessage.class), any(Throwable.class))).thenReturn(Mono.empty());

        RacerStreamListenerRegistrar dlqRegistrar = new RacerStreamListenerRegistrar(
                redisTemplate, new ObjectMapper().registerModule(new JavaTimeModule()),
                properties, null, null, deadLetterHandler);
        dlqRegistrar.setEnvironment(environment);

        AtomicInteger calls = new AtomicInteger();
        Object bean = new Object() {
            @RacerStreamListener(streamKey = "stream:orders", group = "test-group")
            public void handleOrder(RacerMessage msg) {
                calls.incrementAndGet();
                throw new RuntimeException("simulated failure");
            }
        };

        RacerMessage msg = RacerMessage.create("stream:orders", "payload", "test");
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(msg);

        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream:orders")
                .withId(RecordId.of("6-0"))
                .ofMap(Map.of("data", json));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        dlqRegistrar.postProcessAfterInitialization(bean, "failBean");
        Thread.sleep(400);

        verify(deadLetterHandler, atLeastOnce()).enqueue(any(RacerMessage.class), any(Throwable.class));
    }
}
