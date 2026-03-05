package com.cheetah.racer.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterMessageTest {

    @Test
    void from_copiesIdAndMessageFromOriginal() {
        RacerMessage original = RacerMessage.create("racer:orders", "bad-payload", "svc");
        original.setRetryCount(1);

        RuntimeException cause = new RuntimeException("processing failed");
        Instant before = Instant.now();
        DeadLetterMessage dlm = DeadLetterMessage.from(original, cause);
        Instant after = Instant.now();

        assertThat(dlm.getId()).isEqualTo(original.getId());
        assertThat(dlm.getOriginalMessage()).isSameAs(original);
        assertThat(dlm.getErrorMessage()).isEqualTo("processing failed");
        assertThat(dlm.getExceptionClass()).isEqualTo(RuntimeException.class.getName());
        assertThat(dlm.getFailedAt()).isBetween(before, after);
    }

    @Test
    void from_attemptCountIsRetryCountPlusOne() {
        RacerMessage msg = RacerMessage.create("ch", "p", "s");
        msg.setRetryCount(2);

        DeadLetterMessage dlm = DeadLetterMessage.from(msg, new RuntimeException("err"));

        assertThat(dlm.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void from_withZeroRetryCount_attemptCountIsOne() {
        RacerMessage msg = RacerMessage.create("ch", "p", "s");

        DeadLetterMessage dlm = DeadLetterMessage.from(msg, new IllegalStateException("boom"));

        assertThat(dlm.getAttemptCount()).isEqualTo(1);
        assertThat(dlm.getExceptionClass()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void builder_canSetAllFields() {
        Instant now = Instant.now();
        RacerMessage msg = RacerMessage.create("ch", "p", "s");

        DeadLetterMessage dlm = DeadLetterMessage.builder()
                .id("dlq-id")
                .originalMessage(msg)
                .errorMessage("error")
                .exceptionClass("java.lang.Exception")
                .failedAt(now)
                .attemptCount(3)
                .build();

        assertThat(dlm.getId()).isEqualTo("dlq-id");
        assertThat(dlm.getAttemptCount()).isEqualTo(3);
        assertThat(dlm.getFailedAt()).isEqualTo(now);
    }
}
