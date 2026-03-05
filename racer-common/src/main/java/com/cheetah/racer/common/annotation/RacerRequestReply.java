package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method on a {@link RacerClient}-annotated interface as a request-reply caller.
 *
 * <p>At startup, Racer generates a JDK dynamic proxy for the enclosing {@link RacerClient}
 * interface and registers it as a Spring bean. When the annotated method is called:
 * <ol>
 *   <li>The method arguments are serialized to JSON as the request payload.</li>
 *   <li>A {@link com.cheetah.racer.common.model.RacerRequest} is created with a unique
 *       correlation ID.</li>
 *   <li>The request is published to the configured channel (Pub/Sub) or stream.</li>
 *   <li>The proxy subscribes to the ephemeral reply channel / stream and waits for
 *       the correlation-matched {@link com.cheetah.racer.common.model.RacerReply}.</li>
 *   <li>The reply payload is deserialized into the method's return type.</li>
 * </ol>
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code Mono<T>} — recommended; non-blocking request-reply.</li>
 *   <li>{@code String} — blocking; returns the raw reply payload string.</li>
 *   <li>Any POJO type {@code T} — blocking; payload deserialized into {@code T}.</li>
 * </ul>
 *
 * <h3>Channel sources (mutually exclusive)</h3>
 * <ul>
 *   <li>{@link #channel()} / {@link #channelRef()} — Pub/Sub request-reply.</li>
 *   <li>{@link #stream()} / {@link #streamRef()} — Stream request-reply.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>
 * // application.yaml:
 * racer:
 *   channels:
 *     orders:
 *       name: racer:messages
 *
 * &#64;RacerClient
 * public interface OrderRequestClient {
 *
 *     &#64;RacerRequestReply(channelRef = "orders", timeout = "10s")
 *     Mono&lt;String&gt; submitOrder(String payload);
 *
 *     &#64;RacerRequestReply(stream = "racer:stream:requests", timeout = "30s")
 *     Mono&lt;OrderResult&gt; submitStreamOrder(OrderDto order);
 * }
 *
 * // Injected like any Spring bean:
 * &#64;Autowired
 * OrderRequestClient orderClient;
 *
 * orderClient.submitOrder("hello").subscribe(reply -> log.info("Reply: {}", reply));
 * </pre>
 *
 * @see RacerClient
 * @see RacerResponder
 * @see com.cheetah.racer.common.model.RacerRequest
 * @see com.cheetah.racer.common.model.RacerReply
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerRequestReply {

    // ── Pub/Sub target ───────────────────────────────────────────────────────

    /**
     * Raw Redis Pub/Sub channel name to publish the request to.
     * Supports Spring property placeholder syntax.
     * Mutually exclusive with {@link #stream()} / {@link #streamRef()}.
     */
    String channel() default "";

    /**
     * Alias defined under {@code racer.channels.<alias>.name} in properties.
     * Used as Pub/Sub channel. Mutually exclusive with stream attributes.
     */
    String channelRef() default "";

    // ── Stream target ────────────────────────────────────────────────────────

    /**
     * Raw Redis Stream key to write the request to.
     * Supports Spring property placeholder syntax.
     * Mutually exclusive with {@link #channel()} / {@link #channelRef()}.
     */
    String stream() default "";

    /**
     * Alias defined under {@code racer.channels.<alias>.name} in properties.
     * Used as Stream key. Mutually exclusive with channel attributes.
     */
    String streamRef() default "";

    // ── Timeout ──────────────────────────────────────────────────────────────

    /**
     * Maximum time to wait for a reply. Uses ISO-8601 duration format or
     * shorthand like {@code "5s"}, {@code "30s"}, {@code "1m"}.
     * Defaults to {@code "5s"}.
     */
    String timeout() default "5s";
}
