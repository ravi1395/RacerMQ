package com.cheetah.racer.exception;

/**
 * Thrown when a security operation (AES-256-GCM encryption/decryption, HMAC-SHA256
 * signing/verification, or sender filtering) fails during Racer's publish or consume pipeline.
 */
public class RacerSecurityException extends RacerException {

    public RacerSecurityException(String message) {
        super(message);
    }

    public RacerSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
