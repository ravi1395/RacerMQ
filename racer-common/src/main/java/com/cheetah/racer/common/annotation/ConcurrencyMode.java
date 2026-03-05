package com.cheetah.racer.common.annotation;

/**
 * Controls how a {@link RacerListener}-annotated method handles concurrent messages
 * arriving on its subscribed channel.
 *
 * <ul>
 *   <li>{@link #SEQUENTIAL} — messages are processed strictly one at a time.
 *       Each message must complete (success or failure/DLQ) before the next is dispatched.
 *       Equivalent to a concurrency of 1. Ideal for ordered processing, single-threaded
 *       state mutation, or when the downstream system cannot handle parallel calls.</li>
 *   <li>{@link #CONCURRENT} — up to {@link RacerListener#concurrency()} messages are
 *       processed simultaneously on {@code Schedulers.boundedElastic()}.
 *       Use this for I/O-bound handlers, external API calls, or any workload where
 *       throughput matters more than strict ordering.</li>
 * </ul>
 *
 * @see RacerListener#mode()
 * @see RacerListener#concurrency()
 */
public enum ConcurrencyMode {

    /**
     * Process messages one at a time (concurrency = 1).
     * Order is preserved; the next message is dispatched only after the current one finishes.
     */
    SEQUENTIAL,

    /**
     * Process messages in parallel up to the configured {@link RacerListener#concurrency()} limit.
     * Order is <em>not</em> guaranteed. Multiple worker threads handle messages simultaneously.
     */
    CONCURRENT
}
