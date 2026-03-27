package com.cheetah.racer.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Racer exception hierarchy.
 */
class RacerExceptionHierarchyTest {

    // ── RacerException ───────────────────────────────────────────────────────

    @Test
    void racerException_messageOnly() {
        RacerException ex = new RacerException("boom");
        assertThat(ex).hasMessage("boom");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void racerException_messageAndCause() {
        Throwable cause = new IllegalStateException("root");
        RacerException ex = new RacerException("boom", cause);
        assertThat(ex).hasMessage("boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── RacerConfigurationException ──────────────────────────────────────────

    @Test
    void configurationException_messageOnly() {
        RacerConfigurationException ex = new RacerConfigurationException("bad config");
        assertThat(ex).hasMessage("bad config");
        assertThat(ex).isInstanceOf(RacerException.class);
    }

    @Test
    void configurationException_messageAndCause() {
        Throwable cause = new RuntimeException("root");
        RacerConfigurationException ex = new RacerConfigurationException("bad config", cause);
        assertThat(ex).hasMessage("bad config");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── RacerDlqException ────────────────────────────────────────────────────

    @Test
    void dlqException_messageOnly() {
        RacerDlqException ex = new RacerDlqException("dlq full");
        assertThat(ex).hasMessage("dlq full");
        assertThat(ex).isInstanceOf(RacerException.class);
    }

    @Test
    void dlqException_messageAndCause() {
        Throwable cause = new RuntimeException("io");
        RacerDlqException ex = new RacerDlqException("dlq full", cause);
        assertThat(ex).hasMessage("dlq full");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── RacerListenerException ───────────────────────────────────────────────

    @Test
    void listenerException_messageOnly() {
        RacerListenerException ex = new RacerListenerException("handler fail");
        assertThat(ex).hasMessage("handler fail");
        assertThat(ex).isInstanceOf(RacerException.class);
    }

    @Test
    void listenerException_messageAndCause() {
        Throwable cause = new NullPointerException();
        RacerListenerException ex = new RacerListenerException("handler fail", cause);
        assertThat(ex).hasMessage("handler fail");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── RacerPublishException ────────────────────────────────────────────────

    @Test
    void publishException_messageOnly() {
        RacerPublishException ex = new RacerPublishException("send failed");
        assertThat(ex).hasMessage("send failed");
        assertThat(ex).isInstanceOf(RacerException.class);
    }

    @Test
    void publishException_messageAndCause() {
        Throwable cause = new RuntimeException("connection lost");
        RacerPublishException ex = new RacerPublishException("send failed", cause);
        assertThat(ex).hasMessage("send failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── RacerRequestReplyTimeoutException ────────────────────────────────────

    @Test
    void requestReplyTimeoutException_messageOnly() {
        RacerRequestReplyTimeoutException ex = new RacerRequestReplyTimeoutException("timeout");
        assertThat(ex).hasMessage("timeout");
        assertThat(ex).isInstanceOf(RacerException.class);
    }

    @Test
    void requestReplyTimeoutException_messageAndCause() {
        Throwable cause = new RuntimeException("no reply");
        RacerRequestReplyTimeoutException ex = new RacerRequestReplyTimeoutException("timeout", cause);
        assertThat(ex).hasMessage("timeout");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ── RacerRateLimitException ──────────────────────────────────────────────

    @Test
    void rateLimitException_singleArg_hasChannelInMessage() {
        RacerRateLimitException ex = new RacerRateLimitException("orders");
        assertThat(ex.getChannel()).isEqualTo("orders");
        assertThat(ex.getMessage()).contains("orders");
        assertThat(ex).isInstanceOf(RacerException.class);
    }

    @Test
    void rateLimitException_twoArg_hasChannelAndDetailInMessage() {
        RacerRateLimitException ex = new RacerRateLimitException("orders", "bucket empty");
        assertThat(ex.getChannel()).isEqualTo("orders");
        assertThat(ex.getMessage()).contains("orders").contains("bucket empty");
    }
}
