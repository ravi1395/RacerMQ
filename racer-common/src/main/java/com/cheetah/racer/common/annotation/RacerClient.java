package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks an interface as a Racer request-reply client.
 *
 * <p>Racer generates a JDK dynamic proxy for this interface and registers it as a Spring bean.
 * Methods annotated with {@link RacerRequestReply} on this interface become reactive
 * request-reply callers over Redis Pub/Sub or Streams.
 *
 * <p>The interface must be in a package (or sub-package) scanned by
 * {@link EnableRacerClients}.
 *
 * <h3>Example</h3>
 * <pre>
 * &#64;RacerClient
 * public interface PaymentClient {
 *
 *     &#64;RacerRequestReply(channelRef = "payments", timeout = "10s")
 *     Mono&lt;String&gt; processPayment(PaymentDto payment);
 * }
 * </pre>
 *
 * @see RacerRequestReply
 * @see EnableRacerClients
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerClient {
}
