package com.cheetah.racer.requestreply;

import com.cheetah.racer.annotation.RacerResponder;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerResponderRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerResponderRegistrarTest {

    // ── Test beans ────────────────────────────────────────────────────────────

    static class SampleResponderBean {
        @RacerResponder(channel = "racer:requests")
        public String handleRequest(RacerRequest request) {
            return "Processed: " + request.getPayload();
        }
    }

    static class StreamResponderBean {
        @RacerResponder(stream = "racer:stream:requests", group = "test-group")
        public String handleStreamRequest(RacerRequest request) {
            return "Stream: " + request.getPayload();
        }
    }

    static class NoArgResponderBean {
        final AtomicInteger calls = new AtomicInteger();
        @RacerResponder(channel = "racer:noarg")
        public String handle() { calls.incrementAndGet(); return "ok"; }
    }

    static class PlainBean {
        public String noAnnotation() { return "plain"; }
    }

    static class NoChannelBean {
        @RacerResponder
        public String handle(RacerRequest request) { return "none"; }
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock ReactiveRedisMessageListenerContainer listenerContainer;
    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    @Mock ReactiveStreamOperations streamOps;
    @Mock Environment environment;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerResponderRegistrar registrar;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new RacerProperties();
        properties.setChannels(new LinkedHashMap<>());

        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(Flux.never());
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.createGroup(anyString(), any(), anyString())).thenReturn(Mono.just("OK"));

        registrar = new RacerResponderRegistrar(
                listenerContainer, redisTemplate, objectMapper, properties);
        registrar.setEnvironment(environment);
    }

    // ── Tests: bean discovery ─────────────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods_pubsub() throws Exception {
        SampleResponderBean bean = new SampleResponderBean();
        Object result = registrar.postProcessAfterInitialization(bean, "sampleResponderBean");

        assertThat(result).isSameAs(bean);
        Thread.sleep(200);
    }

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods_stream() throws Exception {
        StreamResponderBean bean = new StreamResponderBean();
        Object result = registrar.postProcessAfterInitialization(bean, "streamResponderBean");

        assertThat(result).isSameAs(bean);
        Thread.sleep(200);
    }

    @Test
    void postProcessAfterInitialization_noAnnotation_returnsBean() {
        PlainBean bean = new PlainBean();
        Object result = registrar.postProcessAfterInitialization(bean, "plainBean");

        assertThat(result).isSameAs(bean);
    }

    @Test
    void postProcessAfterInitialization_noChannelOrStream_isSkipped() throws Exception {
        NoChannelBean bean = new NoChannelBean();
        registrar.postProcessAfterInitialization(bean, "noChannelBean");
        Thread.sleep(100);
        // No subscription created — listenerContainer.receive() never called for this bean
        verify(listenerContainer, never()).receive(any(ChannelTopic.class));
    }

    // ── Tests: lifecycle ──────────────────────────────────────────────────────

    @Test
    void stop_disposesSubscriptions() throws Exception {
        SampleResponderBean bean = new SampleResponderBean();
        registrar.postProcessAfterInitialization(bean, "sampleResponderBean");
        Thread.sleep(200);

        // Should not throw
        registrar.stop();
    }

    @Test
    void stop_withNoSubscriptions_doesNotThrow() {
        registrar.stop();
    }

    // ── Tests: null listenerContainer — pubsub skipped ───────────────────────

    @Test
    void pubsubResponder_nullListenerContainer_isSkipped() throws Exception {
        RacerResponderRegistrar noContainerRegistrar = new RacerResponderRegistrar(
                null, redisTemplate, objectMapper, properties);
        noContainerRegistrar.setEnvironment(environment);

        SampleResponderBean bean = new SampleResponderBean();
        noContainerRegistrar.postProcessAfterInitialization(bean, "bean");
        Thread.sleep(100);
        // No exception; subscription silently skipped
    }

    // ── Tests: pubsub message delivery — fire-and-reply ──────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void pubsubResponder_validRequest_publishesReply() throws Exception {
        Sinks.Many<ReactiveSubscription.Message<String, String>> sink =
                Sinks.many().unicast().onBackpressureBuffer();

        when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(sink.asFlux());
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

        SampleResponderBean bean = new SampleResponderBean();
        registrar.postProcessAfterInitialization(bean, "sampleBean");
        Thread.sleep(100);

        // Build a valid RacerRequest JSON with replyTo and correlationId
        RacerRequest req = new RacerRequest();
        req.setCorrelationId(UUID.randomUUID().toString());
        req.setReplyTo("racer:reply:test");
        req.setPayload("test-payload");
        String reqJson = objectMapper.writeValueAsString(req);

        ReactiveSubscription.Message<String, String> msg =
                new ReactiveSubscription.Message<String, String>() {
                    @Override public String getChannel() { return "racer:requests"; }
                    @Override public String getMessage() { return reqJson; }
                };
        sink.tryEmitNext(msg);
        Thread.sleep(300);

        verify(redisTemplate, atLeastOnce()).convertAndSend(eq("racer:reply:test"), anyString());
    }

    @Test
    void pubsubResponder_invalidJson_isIgnored() throws Exception {
        Sinks.Many<ReactiveSubscription.Message<String, String>> sink =
                Sinks.many().unicast().onBackpressureBuffer();

        when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(sink.asFlux());

        SampleResponderBean bean = new SampleResponderBean();
        registrar.postProcessAfterInitialization(bean, "sampleBean");
        Thread.sleep(100);

        ReactiveSubscription.Message<String, String> msg =
                new ReactiveSubscription.Message<String, String>() {
                    @Override public String getChannel() { return "racer:requests"; }
                    @Override public String getMessage() { return "NOT-VALID-JSON"; }
                };
        sink.tryEmitNext(msg);
        Thread.sleep(200);

        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void pubsubResponder_noReplyTo_isIgnored() throws Exception {
        Sinks.Many<ReactiveSubscription.Message<String, String>> sink =
                Sinks.many().unicast().onBackpressureBuffer();

        when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(sink.asFlux());

        SampleResponderBean bean = new SampleResponderBean();
        registrar.postProcessAfterInitialization(bean, "sampleBean");
        Thread.sleep(100);

        // Valid JSON but no replyTo / correlationId
        RacerRequest req = new RacerRequest();
        req.setPayload("fire-and-forget");
        String reqJson = objectMapper.writeValueAsString(req);

        ReactiveSubscription.Message<String, String> msg =
                new ReactiveSubscription.Message<String, String>() {
                    @Override public String getChannel() { return "racer:requests"; }
                    @Override public String getMessage() { return reqJson; }
                };
        sink.tryEmitNext(msg);
        Thread.sleep(200);

        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    // ── Tests: stream dispatch — missing fields → ACK and skip ───────────────

    @SuppressWarnings("unchecked")
    @Test
    void streamResponder_missingCorrelationId_acksAndSkips() throws Exception {
        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("racer:stream:requests")
                .withId(RecordId.of("1-0"))
                .ofMap(Map.of("replyTo", "something")); // missing correlationId

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));

        StreamResponderBean bean = new StreamResponderBean();
        registrar.postProcessAfterInitialization(bean, "streamBean");
        Thread.sleep(400);

        verify(streamOps, atLeastOnce()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    // ── Tests: stream dispatch — invokes method and writes reply ─────────────

    @SuppressWarnings("unchecked")
    @Test
    void streamResponder_validRecord_writesReply() throws Exception {
        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("racer:stream:requests")
                .withId(RecordId.of("2-0"))
                .ofMap(Map.of(
                        "correlationId", "corr-123",
                        "replyTo", "racer:stream:replies",
                        "payload", "test-payload"));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class))).thenReturn(Flux.just(record), Flux.never());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(Mono.just(1L));
        when(streamOps.add(any(MapRecord.class))).thenReturn(Mono.just(RecordId.of("99-0")));

        StreamResponderBean bean = new StreamResponderBean();
        registrar.postProcessAfterInitialization(bean, "streamBean");
        Thread.sleep(400);

        verify(streamOps, atLeastOnce()).add(any(MapRecord.class));
        verify(streamOps, atLeastOnce()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }
}
