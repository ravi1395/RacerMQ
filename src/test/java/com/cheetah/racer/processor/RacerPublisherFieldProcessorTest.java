package com.cheetah.racer.processor;

import com.cheetah.racer.annotation.RacerPublisher;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerPublisherFieldProcessorTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private RacerPublisherRegistry publisherRegistry;
    @Mock private RacerChannelPublisher channelPublisher;

    private RacerPublisherFieldProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RacerPublisherFieldProcessor();
        processor.setApplicationContext(applicationContext);

        when(applicationContext.getBean(RacerPublisherRegistry.class)).thenReturn(publisherRegistry);
        when(publisherRegistry.getPublisher("orders")).thenReturn(channelPublisher);
        when(publisherRegistry.getPublisher("")).thenReturn(channelPublisher);
        when(channelPublisher.getChannelName()).thenReturn("racer:orders");
    }

    // ── Bean with a properly annotated field ──────────────────────────────────

    static class ValidBean {
        @RacerPublisher("orders")
        private RacerChannelPublisher ordersPublisher;

        RacerChannelPublisher getOrdersPublisher() { return ordersPublisher; }
    }

    @Test
    void injectsPublisher_intoAnnotatedField() {
        ValidBean bean = new ValidBean();
        Object result = processor.postProcessBeforeInitialization(bean, "validBean");

        assertThat(result).isSameAs(bean);
        assertThat(bean.getOrdersPublisher()).isSameAs(channelPublisher);
        verify(publisherRegistry).getPublisher("orders");
    }

    // ── Bean with wrong field type (not RacerChannelPublisher) ────────────────

    static class WrongTypeBean {
        @RacerPublisher("orders")
        private String notAPublisher;
    }

    @Test
    void skipsField_whenTypeIsNotRacerChannelPublisher() {
        WrongTypeBean bean = new WrongTypeBean();
        processor.postProcessBeforeInitialization(bean, "wrongTypeBean");
        // Should not attempt to get publisher — wrong type path
        verify(publisherRegistry, never()).getPublisher(anyString());
    }

    // ── Bean with default alias ──────────────────────────────────────────────

    static class DefaultAliasBean {
        @RacerPublisher
        private RacerChannelPublisher defaultPub;

        RacerChannelPublisher getDefaultPub() { return defaultPub; }
    }

    @Test
    void injectsPublisher_withDefaultAlias() {
        DefaultAliasBean bean = new DefaultAliasBean();
        processor.postProcessBeforeInitialization(bean, "defaultAliasBean");

        assertThat(bean.getDefaultPub()).isSameAs(channelPublisher);
        verify(publisherRegistry).getPublisher("");
    }

    // ── Bean with no annotated field → no injection ──────────────────────────

    static class PlainBean {
        private String name;
    }

    @Test
    void noOp_forBeanWithoutAnnotation() {
        PlainBean bean = new PlainBean();
        Object result = processor.postProcessBeforeInitialization(bean, "plainBean");
        assertThat(result).isSameAs(bean);
        verifyNoInteractions(publisherRegistry);
    }
}
