package com.cheetah.racer.exception;

/**
 * Thrown when a Dead Letter Queue operation fails (enqueue, dequeue, or reprocessing).
 */
public class RacerDlqException extends RacerException {

    public RacerDlqException(String message) {
        super(message);
    }

    public RacerDlqException(String message, Throwable cause) {
        super(message, cause);
    }
}
