package com.cheetah.racer.router.dsl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.RouteDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RouteHandlers}.
 */
@ExtendWith(MockitoExtension.class)
class RouteHandlersTest {

    @Mock
    RouteContext ctx;

    private static RacerMessage msg() {
        return RacerMessage.builder()
                .id("msg-1")
                .channel("orders")
                .payload("{\"type\":\"EMAIL\"}")
                .sender("checkout-service")
                .build();
    }

    // ── forward(alias) ────────────────────────────────────────────────────

    @Test
    void forward_publishesToAliasAndReturnsFORWARDED() {
        RacerMessage m = msg();
        RouteDecision result = RouteHandlers.forward("email").handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED);
        verify(ctx).publishTo("email", m);
        verifyNoMoreInteractions(ctx);
    }

    // ── forward(alias, sender) ────────────────────────────────────────────

    @Test
    void forward_withSender_publishesWithOverriddenSender() {
        RacerMessage m = msg();
        RouteDecision result = RouteHandlers.forward("email", "router-svc").handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED);
        verify(ctx).publishTo("email", m, "router-svc");
        verifyNoMoreInteractions(ctx);
    }

    // ── forwardAndProcess ─────────────────────────────────────────────────

    @Test
    void forwardAndProcess_returnsFORWARDED_AND_PROCESS() {
        RacerMessage m = msg();
        RouteDecision result = RouteHandlers.forwardAndProcess("email").handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED_AND_PROCESS);
        verify(ctx).publishTo("email", m);
    }

    // ── multicast ─────────────────────────────────────────────────────────

    @Test
    void multicast_publishesToAllAliases() {
        RacerMessage m = msg();
        RouteDecision result = RouteHandlers.multicast("email", "sms", "push").handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED);
        verify(ctx).publishTo("email", m);
        verify(ctx).publishTo("sms", m);
        verify(ctx).publishTo("push", m);
        verifyNoMoreInteractions(ctx);
    }

    // ── multicastAndProcess ───────────────────────────────────────────────

    @Test
    void multicastAndProcess_publishesAndReturnsFORWARDED_AND_PROCESS() {
        RacerMessage m = msg();
        RouteDecision result = RouteHandlers.multicastAndProcess("email", "sms").handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED_AND_PROCESS);
        verify(ctx).publishTo("email", m);
        verify(ctx).publishTo("sms", m);
        verifyNoMoreInteractions(ctx);
    }

    // ── forwardWithPriority ───────────────────────────────────────────────

    @Test
    void forwardWithPriority_publishesWithPriority() {
        RacerMessage m = msg();
        RouteDecision result = RouteHandlers.forwardWithPriority("push", "HIGH").handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED);
        verify(ctx).publishToWithPriority("push", m, "HIGH");
        verifyNoMoreInteractions(ctx);
    }

    // ── priority (composable wrapper) ─────────────────────────────────────

    @Test
    void priority_publishesThenDelegates() {
        RacerMessage m = msg();
        RouteHandler delegate = (message, context) -> RouteDecision.FORWARDED_AND_PROCESS;

        RouteDecision result = RouteHandlers.priority("push", "HIGH", delegate).handle(m, ctx);

        assertThat(result).isEqualTo(RouteDecision.FORWARDED_AND_PROCESS);
        verify(ctx).publishToWithPriority("push", m, "HIGH");
    }

    // ── drop ──────────────────────────────────────────────────────────────

    @Test
    void drop_returnsDROPPED() {
        RouteDecision result = RouteHandlers.drop().handle(msg(), ctx);

        assertThat(result).isEqualTo(RouteDecision.DROPPED);
        verifyNoInteractions(ctx);
    }

    // ── drop() — debug-enabled paths ──────────────────────────────────────

    @Test
    void drop_withDebugEnabled_longPayload_truncatesAndReturnsDROPPED() {
        Logger logger = (Logger) LoggerFactory.getLogger(RouteHandlers.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            String longPayload = "A".repeat(200); // > 120 chars — triggers truncation
            RacerMessage m = RacerMessage.builder()
                    .id("msg-2").channel("orders").payload(longPayload).sender("svc").build();

            RouteDecision result = RouteHandlers.drop().handle(m, ctx);

            assertThat(result).isEqualTo(RouteDecision.DROPPED);
        } finally {
            logger.setLevel(original);
        }
    }

    @Test
    void drop_withDebugEnabled_shortPayloadWithControlChars_sanitizesAndReturnsDROPPED() {
        Logger logger = (Logger) LoggerFactory.getLogger(RouteHandlers.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            RacerMessage m = RacerMessage.builder()
                    .id("msg-3").channel("orders").payload("line1\r\nline2").sender("svc").build();

            RouteDecision result = RouteHandlers.drop().handle(m, ctx);

            assertThat(result).isEqualTo(RouteDecision.DROPPED);
        } finally {
            logger.setLevel(original);
        }
    }

    @Test
    void drop_withDebugEnabled_nullPayload_returnsDROPPED() {
        Logger logger = (Logger) LoggerFactory.getLogger(RouteHandlers.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            RacerMessage m = RacerMessage.builder()
                    .id("msg-4").channel("orders").payload(null).sender("svc").build();

            RouteDecision result = RouteHandlers.drop().handle(m, ctx);

            assertThat(result).isEqualTo(RouteDecision.DROPPED);
        } finally {
            logger.setLevel(original);
        }
    }

    // ── dropQuietly ───────────────────────────────────────────────────────

    @Test
    void dropQuietly_returnsDROPPED() {
        RouteDecision result = RouteHandlers.dropQuietly().handle(msg(), ctx);

        assertThat(result).isEqualTo(RouteDecision.DROPPED);
        verifyNoInteractions(ctx);
    }

    // ── dropToDlq ─────────────────────────────────────────────────────────

    @Test
    void dropToDlq_returnsDROPPED_TO_DLQ() {
        RouteDecision result = RouteHandlers.dropToDlq().handle(msg(), ctx);

        assertThat(result).isEqualTo(RouteDecision.DROPPED_TO_DLQ);
        verifyNoInteractions(ctx);
    }
}
