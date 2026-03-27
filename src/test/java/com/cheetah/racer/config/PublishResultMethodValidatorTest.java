package com.cheetah.racer.config;

import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.exception.RacerConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublishResultMethodValidatorTest {

    @Mock private ApplicationContext applicationContext;

    // ── Test beans ────────────────────────────────────────────────────────────

    static class VoidReturnBean {
        @PublishResult(channel = "ch")
        public void badMethod() { }
    }

    static class ValidReturnBean {
        @PublishResult(channel = "ch")
        public String goodMethod() { return "ok"; }
    }

    static class MonoReturnBean {
        @PublishResult(channel = "ch")
        public Mono<String> reactiveMethod() { return Mono.just("ok"); }
    }

    static class NoAnnotationBean {
        public void plainMethod() { }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void throwsOnVoidReturn_withPublishResult() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"voidBean"});
        when(applicationContext.getType("voidBean")).thenReturn((Class) VoidReturnBean.class);

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("void method");
    }

    @Test
    void passesValidation_forNonVoidReturn() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"validBean"});
        when(applicationContext.getType("validBean")).thenReturn((Class) ValidReturnBean.class);

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void passesValidation_forMonoReturn() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"monoBean"});
        when(applicationContext.getType("monoBean")).thenReturn((Class) MonoReturnBean.class);

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void passesValidation_noBeans() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{});

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void skipsBean_whenGetTypeThrows() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"broken"});
        when(applicationContext.getType("broken")).thenThrow(new RuntimeException("oops"));

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void skipsBean_whenGetTypeReturnsNull() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"nullType"});
        when(applicationContext.getType("nullType")).thenReturn(null);

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void passesValidation_forBeanWithNoAnnotatedMethods() {
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[]{"plain"});
        when(applicationContext.getType("plain")).thenReturn((Class) NoAnnotationBean.class);

        PublishResultMethodValidator validator = new PublishResultMethodValidator(applicationContext);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}
