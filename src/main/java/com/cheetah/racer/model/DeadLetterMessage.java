package com.cheetah.racer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Wraps a failed message with error details for the Dead Letter Queue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterMessage implements Serializable {

    private String id;
    private RacerMessage originalMessage;
    private String errorMessage;
    private String exceptionClass;
    private Instant failedAt;
    private int attemptCount;

    public static DeadLetterMessage from(RacerMessage message, Throwable error) {
        return DeadLetterMessage.builder()
                .id(message.getId())
                .originalMessage(message)
                .errorMessage(error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName())
                .exceptionClass(error.getClass().getName())
                .failedAt(Instant.now())
                .attemptCount(message.getRetryCount() + 1)
                .build();
    }
}
