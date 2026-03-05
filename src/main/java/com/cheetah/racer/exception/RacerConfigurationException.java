package com.cheetah.racer.exception;

/**
 * Thrown when a Racer component is misconfigured at startup time.
 *
 * <p>Examples: missing required channel alias, invalid property combination, or
 * a {@code @RacerListener} method with an unsupported signature.
 */
public class RacerConfigurationException extends RacerException {

    public RacerConfigurationException(String message) {
        super(message);
    }

    public RacerConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
