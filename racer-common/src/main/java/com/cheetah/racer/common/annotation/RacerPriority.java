package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method or type as a priority-aware publisher (R-10).
 *
 * <p>When applied to a method alongside {@link PublishResult}, the message payload
 * is routed to a priority sub-channel based on the {@code priority} field of the
 * returned {@link com.cheetah.racer.common.model.RacerMessage} (or the
 * {@link #defaultLevel()} fallback).
 *
 * <h3>Sub-channel naming</h3>
 * Priority sub-channels follow the pattern {@code <base-redis-key>:priority:<LEVEL>}.
 * For example, if the base channel is {@code racer:orders} and the priority is {@code HIGH}:
 * <pre>racer:orders:priority:HIGH</pre>
 *
 * <h3>Usage</h3>
 * <pre>
 * &#64;PublishResult(channelRef = "orders")
 * &#64;RacerPriority(defaultLevel = "HIGH")
 * public RacerMessage placeUrgentOrder(OrderRequest req) { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerPriority {

    /**
     * Default priority level to use when the message's own {@code priority} field is blank.
     * Must match one of the names declared in {@code racer.priority.levels} (default: HIGH, NORMAL, LOW).
     */
    String defaultLevel() default "NORMAL";
}
