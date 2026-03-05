package com.cheetah.racer.common.listener;

import com.cheetah.racer.common.model.RacerMessage;
import reactor.core.publisher.Mono;

/**
 * SPI for sending failed messages to a Dead Letter Queue.
 *
 * <p>Implement this interface and expose it as a Spring bean to integrate the
 * {@link com.cheetah.racer.common.listener.RacerListenerRegistrar} with your DLQ
 * infrastructure. The racer-client module provides a default Redis-backed implementation
 * ({@code DeadLetterQueueService}).
 *
 * <p>If no bean implementing this interface is present in the application context,
 * failed messages are <strong>only</strong> logged — they are not enqueued.
 */
public interface RacerDeadLetterHandler {

    /**
     * Enqueue a message that failed processing into the dead letter store.
     *
     * @param message the original message that failed
     * @param error   the exception thrown during processing
     * @return a {@code Mono} that completes when the enqueue operation finishes
     */
    Mono<?> enqueue(RacerMessage message, Throwable error);
}
