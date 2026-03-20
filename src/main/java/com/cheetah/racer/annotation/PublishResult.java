package com.cheetah.racer.annotation;

import java.lang.annotation.*;

/**
 * Publishes the return value of an annotated method to a Redis channel.
 *
 * <p>The return value is serialized to JSON and sent to the configured channel.
 * Both reactive ({@code Mono}/{@code Flux}) and regular return types are supported:
 * <ul>
 *   <li>Reactive: result is tapped inside the reactive pipeline (non-blocking)</li>
 *   <li>Regular: result is published after the method returns</li>
 * </ul>
 *
 * <h3>Usage — direct channel name</h3>
 * <pre>
 * {@literal @}PublishResult(channel = "racer:orders", async = true)
 * public Order createOrder(OrderRequest req) { ... }
 * </pre>
 *
 * <h3>Usage — channel alias from properties</h3>
 * <pre>
 * // application.properties:
 * racer.channels.orders.name=racer:orders
 *
 * {@literal @}PublishResult(channelRef = "orders")
 * public Mono&lt;Order&gt; createOrder(OrderRequest req) { ... }
 * </pre>
 *
 * <h3>Usage — concurrent fan-out for Flux return types</h3>
 * <pre>
 * // Publish up to 8 elements to Redis simultaneously instead of sequentially.
 * {@literal @}PublishResult(channel = "racer:events", mode = ConcurrencyMode.CONCURRENT, concurrency = 8)
 * public Flux&lt;Event&gt; broadcastAll() { ... }
 * </pre>
 *
 * <p>{@code channel} takes priority over {@code channelRef}.
 * If neither is set, the default channel is used.
 *
 * <p>Requires {@link EnableRacer} to be active and the bean to be a Spring proxy.
 *
 * <h3>Self-invocation caveat</h3>
 * <p>{@code @PublishResult} relies on Spring AOP proxying.  When a method annotated
 * with {@code @PublishResult} is called from <em>within the same bean</em>
 * ({@code this.method(...)}), the proxy is bypassed and the annotation <strong>never
 * fires</strong>.</p>
 * <pre>
 * // ❌ Self-invocation — annotation is silently ignored:
 * public void caller() {
 *     this.createOrder(req);         // proxy bypassed, result NOT published
 * }
 *
 * // ✅ External invocation — works correctly:
 * {@literal @}Autowired OrderService orderService;
 * public void caller() {
 *     orderService.createOrder(req); // goes through proxy, result published
 * }
 * </pre>
 * <p>If you need to invoke the annotated method from within the same class, inject
 * the bean via {@code @Autowired} (self-injection) or use
 * {@code applicationContext.getBean(MyService.class).method(...)}.
 *
 * <p><b>Concurrency note:</b> {@link #mode()} and {@link #concurrency()} only affect
 * {@code Flux&lt;T&gt;} return types. For {@code Mono&lt;T&gt;} and plain objects a single
 * publish operation is always used.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(PublishResults.class)
public @interface PublishResult {

    /**
     * Direct Redis channel name, e.g. {@code "racer:orders"}.
     * Takes priority over {@link #channelRef()}.
     */
    String channel() default "";

    /**
     * Channel alias defined under {@code racer.channels.<alias>}.
     * Resolved at runtime via {@link com.cheetah.racer.publisher.RacerPublisherRegistry}.
     */
    String channelRef() default "";

    /**
     * Sender identifier attached to the published message.
     *
     * <p>When empty (the default), the sender is inherited from the channel configuration
     * ({@code racer.channels.<alias>.sender}) when {@link #channelRef()} is set, or falls
     * back to {@code "racer-publisher"} if no channel config is found.
     * Set an explicit value here only to override the channel-level default.
     */
    String sender() default "";

    /**
     * {@code true} = fire-and-forget (subscribe and return immediately).
     * {@code false} = blocks until Redis confirms the publish.
     *
     * <p>When {@link #mode()} is {@link ConcurrencyMode#CONCURRENT} this flag is effectively
     * {@code true} regardless of its value — the concurrent pipeline is always reactive.
     */
    boolean async() default true;

    /**
     * When {@code true} the return value is published to a Redis Stream
     * (durable, consumer-group delivery) instead of Pub/Sub.
     * Requires {@code racer-client} with {@code racer.durable.stream-keys} configured.
     */
    boolean durable() default false;

    /**
     * The Redis Stream key to publish to when {@link #durable()} is {@code true}.
     * Defaults to {@code <resolvedChannelName>:stream} when blank.
     */
    String streamKey() default "";

    /**
     * Dispatch strategy for {@code Flux&lt;T&gt;} return types.
     *
     * <ul>
     *   <li>{@link ConcurrencyMode#SEQUENTIAL} (default) — each element is published
     *       via a fire-and-forget side-effect ({@code doOnNext}); the reactive chain
     *       is not held up waiting for Redis confirmation.</li>
     *   <li>{@link ConcurrencyMode#CONCURRENT} — up to {@link #concurrency()} elements
     *       are published in parallel using {@code flatMap}. The downstream subscriber
     *       receives each element only after its corresponding publish has completed
     *       (or errored), providing natural backpressure.</li>
     * </ul>
     *
     * <p>Only meaningful for {@code Flux&lt;T&gt;} return types; ignored otherwise.
     */
    ConcurrencyMode mode() default ConcurrencyMode.SEQUENTIAL;

    /**
     * Maximum number of concurrent in-flight publish operations when
     * {@link #mode()} is {@link ConcurrencyMode#CONCURRENT}.
     *
     * <p>Ignored when {@code mode = SEQUENTIAL}.
     * Must be &gt; 0.
     */
    int concurrency() default 4;

    /**
     * Per-annotation priority level override (e.g. {@code "HIGH"}, {@code "NORMAL"}, {@code "LOW"}).
     *
     * <p>When non-empty, the return value is published to the priority sub-channel
     * {@code <channel>:priority:<LEVEL>} instead of the base channel — regardless of
     * whether a method-level {@link RacerPriority} annotation is present.
     *
     * <p>When empty (the default), falls back to the method-level {@link RacerPriority}
     * annotation if present, or publishes to the standard (non-priority) channel.
     *
     * <p>This attribute is primarily useful when multiple {@code @PublishResult}
     * annotations are present on the same method and only <em>some</em> channels
     * require priority routing.
     */
    String priority() default "";
}
