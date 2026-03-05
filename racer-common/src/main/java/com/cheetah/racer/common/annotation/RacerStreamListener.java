package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a durable Redis Streams consumer (consumer-group reader).
 *
 * <p>The annotated method is auto-registered at startup as a consumer-group reader for
 * the specified stream key. Incoming stream entries are deserialized and dispatched to
 * the method. The entry is ACKed on success, and forwarded to the DLQ + ACKed on failure
 * (preventing infinite redelivery of poison-pill messages).
 *
 * <h3>Supported parameter types</h3>
 * Same as {@link RacerListener}:
 * <ul>
 *   <li>{@code RacerMessage} — the full deserialized message envelope.</li>
 *   <li>{@code String} — raw payload string.</li>
 *   <li>Any other type {@code T} — payload JSON is deserialized into {@code T}.</li>
 * </ul>
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code void} / {@code Void}</li>
 *   <li>{@code Mono<Void>} / {@code Mono<?>}</li>
 * </ul>
 *
 * <h3>Example — sequential durable consumer</h3>
 * <pre>
 * // application.yaml:
 * racer:
 *   channels:
 *     orders:
 *       name: racer:orders:stream
 *
 * &#64;RacerStreamListener(streamKeyRef = "orders")
 * public Mono&lt;Void&gt; onDurableOrder(OrderDto order) {
 *     return orderService.persist(order);
 * }
 * </pre>
 *
 * <h3>Example — concurrent consumer with explicit stream key</h3>
 * <pre>
 * &#64;RacerStreamListener(
 *     streamKey    = "racer:audit:stream",
 *     group        = "audit-consumers",
 *     mode         = ConcurrencyMode.CONCURRENT,
 *     concurrency  = 4,
 *     batchSize    = 20,
 *     pollIntervalMs = 100
 * )
 * public void onAuditEvent(AuditEvent event) { ... }
 * </pre>
 *
 * @see RacerListener
 * @see ConcurrencyMode
 * @see EnableRacer
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerStreamListener {

    /**
     * Raw Redis stream key, e.g. {@code "racer:orders:stream"}.
     * Supports Spring property placeholder syntax: {@code "${racer.my-stream}"}.
     * Takes precedence over {@link #streamKeyRef()} when both are specified.
     */
    String streamKey() default "";

    /**
     * Alias defined under {@code racer.channels.<alias>.name} in properties.
     * The resolved channel name is used as the stream key.
     */
    String streamKeyRef() default "";

    /**
     * Redis consumer group name for this listener.
     * Defaults to {@code "racer-durable-consumers"}.
     * The group is automatically created if it does not yet exist.
     */
    String group() default "racer-durable-consumers";

    /**
     * Concurrency mode for this listener.
     * <ul>
     *   <li>{@link ConcurrencyMode#SEQUENTIAL} — one entry processed at a time (default).</li>
     *   <li>{@link ConcurrencyMode#CONCURRENT} — up to {@link #concurrency()} parallel workers.</li>
     * </ul>
     */
    ConcurrencyMode mode() default ConcurrencyMode.SEQUENTIAL;

    /**
     * Number of parallel consumer instances when {@link #mode()} is
     * {@link ConcurrencyMode#CONCURRENT}. Ignored in {@code SEQUENTIAL} mode.
     * Defaults to {@code 1}.
     */
    int concurrency() default 1;

    /**
     * Maximum number of stream entries to read per poll ({@code COUNT} parameter of
     * {@code XREADGROUP}). Defaults to {@code 10}.
     */
    int batchSize() default 10;

    /**
     * Milliseconds to wait between polls when no new entries are available.
     * Defaults to {@code 200} ms.
     */
    long pollIntervalMs() default 200;

    /**
     * Optional listener identifier for logging and metrics.
     * Defaults to {@code "<beanName>.<methodName>"}.
     */
    String id() default "";
}
