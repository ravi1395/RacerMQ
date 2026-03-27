package com.cheetah.racer.requestreply;

import com.cheetah.racer.annotation.RacerRequestReply;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerReply;
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
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerClientFactoryBeanTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private RacerProperties racerProperties;
    @Mock private ReactiveRedisMessageListenerContainer listenerContainer;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    // ── Basic factory bean contract ──────────────────────────────────────────

    interface SampleClient {
        String echo(String message);
    }

    interface AnnotatedClient {
        @RacerRequestReply(channel = "racer:test", timeout = "1s")
        Mono<String> call(String payload);
    }

    @Test
    void getObjectType_returnsClientInterface() {
        RacerClientFactoryBean<SampleClient> factoryBean = new RacerClientFactoryBean<>(SampleClient.class);
        assertThat(factoryBean.getObjectType()).isEqualTo(SampleClient.class);
    }

    @Test
    void isSingleton_returnsTrue() {
        RacerClientFactoryBean<SampleClient> factoryBean = new RacerClientFactoryBean<>(SampleClient.class);
        assertThat(factoryBean.isSingleton()).isTrue();
    }

    // ── parseDuration via reflection ─────────────────────────────────────────

    @Test
    void parseDuration_seconds() throws Exception {
        var method = RacerClientFactoryBean.class.getDeclaredMethod("parseDuration", String.class, java.time.Duration.class);
        method.setAccessible(true);

        java.time.Duration result = (java.time.Duration) method.invoke(null, "5s", java.time.Duration.ofSeconds(10));
        assertThat(result).isEqualTo(java.time.Duration.ofSeconds(5));
    }

    @Test
    void parseDuration_milliseconds() throws Exception {
        var method = RacerClientFactoryBean.class.getDeclaredMethod("parseDuration", String.class, java.time.Duration.class);
        method.setAccessible(true);

        java.time.Duration result = (java.time.Duration) method.invoke(null, "500ms", java.time.Duration.ofSeconds(10));
        assertThat(result).isEqualTo(java.time.Duration.ofMillis(500));
    }

    @Test
    void parseDuration_minutes() throws Exception {
        var method = RacerClientFactoryBean.class.getDeclaredMethod("parseDuration", String.class, java.time.Duration.class);
        method.setAccessible(true);

        java.time.Duration result = (java.time.Duration) method.invoke(null, "2m", java.time.Duration.ofSeconds(10));
        assertThat(result).isEqualTo(java.time.Duration.ofMinutes(2));
    }

    @Test
    void parseDuration_blank_returnsFallback() throws Exception {
        var method = RacerClientFactoryBean.class.getDeclaredMethod("parseDuration", String.class, java.time.Duration.class);
        method.setAccessible(true);

        java.time.Duration fallback = java.time.Duration.ofSeconds(10);
        java.time.Duration result = (java.time.Duration) method.invoke(null, "", fallback);
        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void parseDuration_null_returnsFallback() throws Exception {
        var method = RacerClientFactoryBean.class.getDeclaredMethod("parseDuration", String.class, java.time.Duration.class);
        method.setAccessible(true);

        java.time.Duration fallback = java.time.Duration.ofSeconds(10);
        java.time.Duration result = (java.time.Duration) method.invoke(null, null, fallback);
        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void parseDuration_invalid_returnsFallback() throws Exception {
        var method = RacerClientFactoryBean.class.getDeclaredMethod("parseDuration", String.class, java.time.Duration.class);
        method.setAccessible(true);

        java.time.Duration fallback = java.time.Duration.ofSeconds(10);
        java.time.Duration result = (java.time.Duration) method.invoke(null, "not-a-duration", fallback);
        assertThat(result).isEqualTo(fallback);
    }

    // ── serializeArgs via reflection ─────────────────────────────────────────

    @Test
    void serializeArgs_nullArgs_returnsEmptyObject() throws Exception {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        var method = RacerClientFactoryBean.class.getDeclaredMethod("serializeArgs", Object[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(fb, (Object) null);
        assertThat(result).isEqualTo("{}");
    }

    @Test
    void serializeArgs_emptyArray_returnsEmptyObject() throws Exception {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        var method = RacerClientFactoryBean.class.getDeclaredMethod("serializeArgs", Object[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(fb, (Object) new Object[]{});
        assertThat(result).isEqualTo("{}");
    }

    @Test
    void serializeArgs_singleString_returnsAsIs() throws Exception {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        var method = RacerClientFactoryBean.class.getDeclaredMethod("serializeArgs", Object[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(fb, (Object) new Object[]{"hello"});
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void serializeArgs_singleObject_sendsJson() throws Exception {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        var method = RacerClientFactoryBean.class.getDeclaredMethod("serializeArgs", Object[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(fb, (Object) new Object[]{42});
        assertThat(result).isEqualTo("42");
    }

    // ── destroy without subscription ─────────────────────────────────────────

    @Test
    void destroy_withoutSubscription_doesNotThrow() {
        RacerClientFactoryBean<SampleClient> fb = new RacerClientFactoryBean<>(SampleClient.class);
        assertThatCode(fb::destroy).doesNotThrowAnyException();
    }

    // ── RacerRequestReplyException inner class ───────────────────────────────

    @Test
    void requestReplyException_carriesMessage() {
        var ex = new RacerClientFactoryBean.RacerRequestReplyException("timeout");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("timeout");
    }

    // ── routePubSubReply via reflection ───────────────────────────────────────

    @Test
    void routePubSubReply_malformedJson_doesNotThrow() throws Exception {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        var method = RacerClientFactoryBean.class.getDeclaredMethod("routePubSubReply", String.class);
        method.setAccessible(true);

        assertThatCode(() -> {
            try { method.invoke(fb, "not json"); } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void routePubSubReply_validJsonWithCorrelationId_emitsSink() throws Exception {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        var pendingField = RacerClientFactoryBean.class.getDeclaredField("pendingReplies");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var pending = (java.util.concurrent.ConcurrentHashMap<String, reactor.core.publisher.Sinks.One<RacerReply>>) pendingField.get(fb);

        reactor.core.publisher.Sinks.One<RacerReply> sink = reactor.core.publisher.Sinks.one();
        pending.put("abc-123", sink);

        RacerReply reply = new RacerReply();
        reply.setCorrelationId("abc-123");
        reply.setPayload("result");
        reply.setSuccess(true);

        String json = objectMapper.writeValueAsString(reply);
        var method = RacerClientFactoryBean.class.getDeclaredMethod("routePubSubReply", String.class);
        method.setAccessible(true);
        method.invoke(fb, json);

        reactor.test.StepVerifier.create(sink.asMono())
                .assertNext(r -> {
                    assertThat(r.getCorrelationId()).isEqualTo("abc-123");
                    assertThat(r.getPayload()).isEqualTo("result");
                })
                .verifyComplete();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private RacerClientFactoryBean<SampleClient> createFactoryBean() {
        RacerClientFactoryBean<SampleClient> fb = new RacerClientFactoryBean<>(SampleClient.class);
        ReflectionTestUtils.setField(fb, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(fb, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(fb, "racerProperties", racerProperties);
        return fb;
    }

    private RacerClientFactoryBean<AnnotatedClient> createAnnotatedFactoryBean() {
        RacerClientFactoryBean<AnnotatedClient> fb = new RacerClientFactoryBean<>(AnnotatedClient.class);
        ReflectionTestUtils.setField(fb, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(fb, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(fb, "racerProperties", racerProperties);
        fb.setEnvironment(new MockEnvironment());
        return fb;
    }

    // ── afterPropertiesSet / setEnvironment ──────────────────────────────────

    @Test
    void afterPropertiesSet_withoutListenerContainer_doesNotThrow() {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        fb.setEnvironment(new MockEnvironment());
        assertThatCode(fb::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void afterPropertiesSet_withListenerContainer_subscribesToReplyPattern() {
        when(listenerContainer.receive(any(PatternTopic.class))).thenReturn(Flux.never());

        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        ReflectionTestUtils.setField(fb, "listenerContainer", listenerContainer);
        fb.setEnvironment(new MockEnvironment());

        assertThatCode(fb::afterPropertiesSet).doesNotThrowAnyException();

        // There should be an active subscription set
        Object sub = ReflectionTestUtils.getField(fb, "pubSubReplySubscription");
        assertThat(sub).isNotNull();
    }

    @Test
    void destroy_withActiveSubscription_disposesIt() {
        when(listenerContainer.receive(any(PatternTopic.class))).thenReturn(Flux.never());

        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        ReflectionTestUtils.setField(fb, "listenerContainer", listenerContainer);
        fb.setEnvironment(new MockEnvironment());
        fb.afterPropertiesSet();

        assertThatCode(fb::destroy).doesNotThrowAnyException();
    }

    @Test
    void setEnvironment_storesEnvironment() {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        MockEnvironment env = new MockEnvironment();
        fb.setEnvironment(env);
        // afterPropertiesSet should work fine when environment is set
        assertThatCode(fb::afterPropertiesSet).doesNotThrowAnyException();
    }

    // ── getObject proxy ───────────────────────────────────────────────────────

    @Test
    void getObject_returnsNonNullProxy() {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        fb.setEnvironment(new MockEnvironment());
        fb.afterPropertiesSet();

        SampleClient proxy = fb.getObject();
        assertThat(proxy).isNotNull();
    }

    @Test
    void proxy_objectMethods_doNotThrow() {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        fb.setEnvironment(new MockEnvironment());

        SampleClient proxy = fb.getObject();
        assertThatCode(() -> proxy.toString()).doesNotThrowAnyException();
        assertThatCode(() -> proxy.hashCode()).doesNotThrowAnyException();
    }

    @Test
    void proxy_unannotatedMethod_throwsUnsupportedOperation() {
        RacerClientFactoryBean<SampleClient> fb = createFactoryBean();
        fb.setEnvironment(new MockEnvironment());

        SampleClient proxy = fb.getObject();
        // SampleClient.echo() has no @RacerRequestReply annotation
        assertThatThrownBy(() -> proxy.echo("hello"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── sendPubSubRequest with no listenerContainer ───────────────────────────

    @Test
    void proxy_pubSubCall_withoutListenerContainer_returnsErrorMono() {
        RacerClientFactoryBean<AnnotatedClient> fb = createAnnotatedFactoryBean();
        // No listenerContainer injected

        AnnotatedClient proxy = fb.getObject();
        Mono<String> result = proxy.call("test-payload");

        StepVerifier.create(result)
                .expectErrorMatches(err -> err instanceof IllegalStateException
                        && err.getMessage().contains("ReactiveRedisMessageListenerContainer"))
                .verify();
    }
}
