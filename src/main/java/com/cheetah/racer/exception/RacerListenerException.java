package com.cheetah.racer.exception;

/**
 * Thrown when a {@code @RacerListener} or {@code @RacerStreamListener} handler
 * fails during message processing.
 */
public class RacerListenerException extends RacerException {

    public RacerListenerException(String message) {
        super(message);
    }

    public RacerListenerException(String message, Throwable cause) {
        super(message, cause);
    }
}
