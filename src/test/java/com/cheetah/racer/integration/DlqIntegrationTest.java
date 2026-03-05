package com.cheetah.racer.integration;

import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.cheetah.racer.service.DeadLetterQueueService}.
 *
 * <p>Covers: enqueue, dequeue, peekAll, and DLQ isolation between tests.
 */
class DlqIntegrationTest extends RacerIntegrationTestBase {

    @BeforeEach
    void cleanDlq() {
        clearDlq();
    }

    @Test
    void enqueue_persists_message_to_redis() {
        RacerMessage msg = message("racer:it:dlq", "dlq-payload-1");
        RuntimeException error = new RuntimeException("simulated failure");

        Long size = dlqService.enqueue(msg, error).block();

        assertThat(size).isEqualTo(1L);
    }

    @Test
    void dequeue_returns_enqueued_message() {
        RacerMessage msg = message("racer:it:dlq", "dequeue-test");
        RuntimeException error = new IllegalStateException("boom");

        dlqService.enqueue(msg, error).block();

        DeadLetterMessage dlm = dlqService.dequeue().block();

        assertThat(dlm).isNotNull();
        assertThat(dlm.getOriginalMessage().getPayload()).isEqualTo("dequeue-test");
        assertThat(dlm.getExceptionClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(dlm.getErrorMessage()).isEqualTo("boom");
        assertThat(dlm.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void dequeue_returns_empty_when_queue_is_empty() {
        DeadLetterMessage result = dlqService.dequeue().block();
        assertThat(result).isNull();
    }

    @Test
    void peekAll_returns_all_queued_messages_without_removing_them() {
        RacerMessage msg1 = message("racer:it:dlq", "peeked-1");
        RacerMessage msg2 = message("racer:it:dlq", "peeked-2");
        RuntimeException e = new RuntimeException("err");

        dlqService.enqueue(msg1, e).block();
        dlqService.enqueue(msg2, e).block();

        StepVerifier.create(dlqService.peekAll())
                .expectNextCount(2)
                .verifyComplete();

        // Messages must still be there after peek
        StepVerifier.create(dlqService.peekAll())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void enqueue_multiple_dequeue_fifo() {
        RacerMessage first  = message("racer:it:dlq", "first");
        RacerMessage second = message("racer:it:dlq", "second");
        RuntimeException e  = new RuntimeException("err");

        dlqService.enqueue(first, e).block();
        dlqService.enqueue(second, e).block();

        DeadLetterMessage dq1 = dlqService.dequeue().block();
        DeadLetterMessage dq2 = dlqService.dequeue().block();

        assertThat(dq1).isNotNull();
        assertThat(dq2).isNotNull();
        // LPUSH + RPOP means first-enqueued is first-dequeued
        assertThat(dq1.getOriginalMessage().getPayload()).isEqualTo("first");
        assertThat(dq2.getOriginalMessage().getPayload()).isEqualTo("second");
    }
}
