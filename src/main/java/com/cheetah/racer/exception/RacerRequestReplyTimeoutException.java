package com.cheetah.racer.exception;

/**
 * Thrown when a request-reply operation does not receive a response within the
 * configured timeout window.
 */
public class RacerRequestReplyTimeoutException extends RacerException {

    public RacerRequestReplyTimeoutException(String message) {
        super(message);
    }

    public RacerRequestReplyTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
