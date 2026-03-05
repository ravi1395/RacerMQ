package com.cheetah.racer.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RacerReplyTest {

    // ------------------------------------------------------------------
    // success factory
    // ------------------------------------------------------------------

    @Test
    void success_populatesRequiredFields() {
        Instant before = Instant.now();
        RacerReply reply = RacerReply.success("corr-1", "result-payload", "responder-svc");
        Instant after = Instant.now();

        assertThat(reply.getCorrelationId()).isEqualTo("corr-1");
        assertThat(reply.getPayload()).isEqualTo("result-payload");
        assertThat(reply.getResponder()).isEqualTo("responder-svc");
        assertThat(reply.isSuccess()).isTrue();
        assertThat(reply.getErrorMessage()).isNull();
        assertThat(reply.getTimestamp()).isBetween(before, after);
    }

    // ------------------------------------------------------------------
    // failure factory
    // ------------------------------------------------------------------

    @Test
    void failure_populatesRequiredFields() {
        Instant before = Instant.now();
        RacerReply reply = RacerReply.failure("corr-2", "something went wrong", "responder-svc");
        Instant after = Instant.now();

        assertThat(reply.getCorrelationId()).isEqualTo("corr-2");
        assertThat(reply.getErrorMessage()).isEqualTo("something went wrong");
        assertThat(reply.getResponder()).isEqualTo("responder-svc");
        assertThat(reply.isSuccess()).isFalse();
        assertThat(reply.getPayload()).isNull();
        assertThat(reply.getTimestamp()).isBetween(before, after);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    @Test
    void builder_canSetAllFields() {
        RacerReply reply = RacerReply.builder()
                .correlationId("c")
                .payload("p")
                .responder("r")
                .success(true)
                .build();

        assertThat(reply.getCorrelationId()).isEqualTo("c");
        assertThat(reply.getPayload()).isEqualTo("p");
        assertThat(reply.getResponder()).isEqualTo("r");
        assertThat(reply.isSuccess()).isTrue();
    }
}
