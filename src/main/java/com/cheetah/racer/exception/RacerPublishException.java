package com.cheetah.racer.exception;

/**
 * Thrown when a Racer publish operation fails (Pub/Sub or stream).
 */
public class RacerPublishException extends RacerException {

    public RacerPublishException(String message) {
        super(message);
    }

    public RacerPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
