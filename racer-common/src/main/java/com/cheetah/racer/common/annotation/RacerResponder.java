package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a request-reply responder.
 *
 * <p>The annotated method is auto-subscribed at startup to either a Redis Pub/Sub channel
 * or a Redis Stream. When a {@link com.cheetah.racer.common.model.RacerRequest} message
 * arrives, the method is invoked with the request (or its payload), and the return value
 * is automatically serialized and published back to the caller on the correlation channel
 * or response stream specified in the request's {@code replyTo} field.
 *
 * <h3>Channel sources (mutually exclusive)</h3>
 * <ul>
 *   <li>{@link #channel()} / {@link #channelRef()} — subscribe to a Redis Pub/Sub channel.</li>
 *   <li>{@link #stream()} / {@link #streamRef()} — join a Redis Stream consumer group.</li>
 * </ul>
 *
 * <h3>Supported parameter types</h3>
 * <ul>
 *   <li>{@link com.cheetah.racer.common.model.RacerRequest} — the full request envelope.</li>
 *   <li>{@code String} — the raw payload string from the request.</li>
 *   <li>Any other type {@code T} — the payload JSON is deserialized into {@code T}.</li>
 * </ul>
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code String} — returned directly as the reply payload.</li>
 *   <li>Any POJO — serialized to JSON and used as the reply payload.</li>
 *   <li>{@code Mono<String>} / {@code Mono<T>} — unwrapped and used as the reply payload.</li>
 * </ul>
 *
 * <p>The registrar wraps the return value into a {@link com.cheetah.racer.common.model.RacerReply}
 * envelope automatically. Exceptions thrown by the method result in a failure reply.
 *
 * <h3>Example — Pub/Sub responder</h3>
 * <pre>
 * &#64;RacerResponder(channel = "racer:messages")
 * public String handleRequest(RacerRequest request) {
 *     return "Processed: " + request.getPayload();
 * }
 * </pre>
 *
 * <h3>Example — Stream responder with concurrent mode</h3>
 * <pre>
 * // application.yaml:
 * racer:
 *   channels:
 *     orders:
 *       name: racer:stream:requests
 *
 * &#64;RacerResponder(streamRef = "orders", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
 * public Mono&lt;String&gt; handleOrderRequest(OrderDto order) {
 *     return orderService.process(order).map(Object::toString);
 * }
 * </pre>
 *
 * @see RacerRequestReply
 * @see com.cheetah.racer.common.model.RacerRequest
 * @see com.cheetah.racer.common.model.RacerReply
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerResponder {

    // ── Pub/Sub source ───────────────────────────────────────────────────────

    /**
     * Raw Redis Pub/Sub channel name to subscribe to for incoming requests.
     * Supports Spring property placeholder syntax: {@code "${racer.my-channel}"}.
     * Takes precedence over {@link #channelRef()} when both are specified.
     * Mutually exclusive with {@link #stream()} / {@link #streamRef()}.
     */
    String channel() default "";

    /**
     * Alias defined under {@code racer.channels.<alias>.name} in properties.
     * Used for Pub/Sub source lookup. Mutually exclusive with stream attributes.
     */
    String channelRef() default "";

    // ── Stream source ────────────────────────────────────────────────────────

    /**
     * Raw Redis Stream key to read requests from.
     * Supports Spring property placeholder syntax.
     * Mutually exclusive with {@link #channel()} / {@link #channelRef()}.
     */
    String stream() default "";

    /**
     * Alias defined under {@code racer.channels.<alias>.name} in properties.
     * Used for Stream source lookup. Mutually exclusive with channel attributes.
     */
    String streamRef() default "";

    /**
     * Redis consumer group name when using stream mode.
     * Defaults to {@code "racer-responder-group"}.
     */
    String group() default "racer-responder-group";

    // ── Concurrency ──────────────────────────────────────────────────────────

    /**
     * Concurrency mode for processing incoming requests.
     * Defaults to {@link ConcurrencyMode#SEQUENTIAL}.
     */
    ConcurrencyMode mode() default ConcurrencyMode.SEQUENTIAL;

    /**
     * Number of parallel request handlers when {@link #mode()} is
     * {@link ConcurrencyMode#CONCURRENT}. Ignored in {@code SEQUENTIAL} mode.
     * Defaults to {@code 4}.
     */
    int concurrency() default 4;

    /**
     * Optional responder identifier for logging and metrics.
     * Defaults to {@code "<beanName>.<methodName>"}.
     */
    String id() default "";
}
