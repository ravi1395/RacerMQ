package com.cheetah.racer.exception;

/**
 * Base exception for all Racer framework errors.
 *
 * <p>Callers can catch {@code RacerException} to handle any framework-level
 * failure generically, or catch a specific subtype for finer-grained handling.
 */
public class RacerException extends RuntimeException {

    public RacerException(String message) {
        super(message);
    }

    public RacerException(String message, Throwable cause) {
        super(message, cause);
    }
}
