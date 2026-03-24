package com.cheetah.racer.exception;

/**
 * Phase 4.3 — Thrown when a publish attempt is rejected by the Racer rate limiter.
 *
 * <p>This exception is emitted as a reactive error signal (i.e. returned by
 * {@code Mono.error(...)}) and is <em>not</em> retried automatically by the
 * publisher.  Callers should handle it explicitly, for example:
 *
 * <pre>
 * publisher.publishAsync(payload)
 *          .onErrorResume(RacerRateLimitException.class,
 *                         ex -> Mono.error(new ServiceException("Too many requests")));
 * </pre>
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer.rate-limit.enabled=true
 * racer.rate-limit.default-capacity=100
 * racer.rate-limit.default-refill-rate=100
 * </pre>
 */
public class RacerRateLimitException extends RacerException {

    /** Channel or alias for which the rate limit was exceeded. */
    private final String channel;

    public RacerRateLimitException(String channel) {
        super("Rate limit exceeded for channel: " + channel);
        this.channel = channel;
    }

    public RacerRateLimitException(String channel, String detail) {
        super("Rate limit exceeded for channel: " + channel + " — " + detail);
        this.channel = channel;
    }

    /** Returns the channel or alias for which the rate limit was exceeded. */
    public String getChannel() {
        return channel;
    }
}
