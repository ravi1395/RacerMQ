package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a Racer channel receiver.
 *
 * <p>The annotated method is auto-subscribed to the specified Redis Pub/Sub channel at
 * application startup. Incoming messages are deserialized and dispatched to the method
 * according to the configured {@link #mode() concurrency mode}.
 *
 * <h3>Supported parameter types</h3>
 * The method must declare exactly <strong>one</strong> parameter of any of these types:
 * <ul>
 *   <li>{@code RacerMessage} — receives the full message envelope (id, channel, payload,
 *       sender, timestamp, priority).</li>
 *   <li>{@code String} — receives the raw payload string.</li>
 *   <li>Any other type {@code T} — the payload JSON is automatically deserialized into
 *       an instance of {@code T} via Jackson.</li>
 * </ul>
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code void} / {@code Void} — fire-and-forget; method runs to completion.</li>
 *   <li>{@code Mono<Void>} / {@code Mono<?>} — the returned {@code Mono} is subscribed
 *       to; downstream errors are caught and forwarded to the DLQ.</li>
 * </ul>
 *
 * <h3>Sequential processing (default)</h3>
 * <pre>
 * &#64;RacerListener(channel = "racer:orders")
 * public void onOrder(RacerMessage message) {
 *     orderService.handle(message.getPayload());
 * }
 * </pre>
 *
 * <h3>Concurrent processing with 8 workers — channel alias from properties</h3>
 * <pre>
 * // application.yaml:
 * racer:
 *   channels:
 *     payments:
 *       name: racer:payments:incoming
 *
 * &#64;RacerListener(channelRef = "payments", mode = ConcurrencyMode.CONCURRENT, concurrency = 8)
 * public Mono&lt;Void&gt; onPayment(PaymentDto dto) {
 *     return paymentService.process(dto);
 * }
 * </pre>
 *
 * <h3>Raw-payload listener with CONCURRENT workers</h3>
 * <pre>
 * &#64;RacerListener(channel = "racer:raw-events", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
 * public void onEvent(String rawJson) {
 *     eventBus.dispatch(rawJson);
 * }
 * </pre>
 *
 * <p>Failed messages are automatically forwarded to the Dead Letter Queue when
 * {@code DeadLetterQueueService} is available. Schema validation and content-based
 * routing (via {@code @RacerRoute}) are applied automatically when those features are active.
 *
 * <p>Requires {@link EnableRacer} to be active.
 *
 * @see ConcurrencyMode
 * @see EnableRacer
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerListener {

    /**
     * Raw Redis channel name to subscribe to, e.g. {@code "racer:orders"}.
     * Supports Spring property placeholder syntax: {@code "${racer.my-channel}"}.
     * Takes precedence over {@link #channelRef()} when both are specified.
     */
    String channel() default "";

    /**
     * Channel alias defined under {@code racer.channels.<alias>.name} in application
     * properties. Resolved at runtime via {@code RacerProperties}.
     * Used when {@link #channel()} is empty.
     */
    String channelRef() default "";

    /**
     * Concurrency strategy for dispatching incoming messages.
     * <ul>
     *   <li>{@link ConcurrencyMode#SEQUENTIAL} (default) — one message at a time.</li>
     *   <li>{@link ConcurrencyMode#CONCURRENT} — up to {@link #concurrency()} parallel workers.</li>
     * </ul>
     */
    ConcurrencyMode mode() default ConcurrencyMode.SEQUENTIAL;

    /**
     * Number of parallel workers when {@link #mode()} is {@link ConcurrencyMode#CONCURRENT}.
     * Ignored in {@code SEQUENTIAL} mode (effective concurrency is always 1).
     * Must be &ge; 1. Defaults to 4.
     */
    int concurrency() default 4;

    /**
     * Optional listener identifier used in log messages and metrics tags.
     * If empty, defaults to {@code "<beanName>.<methodName>"}.
     */
    String id() default "";
}
