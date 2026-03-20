package com.cheetah.racer.annotation;

import java.lang.annotation.*;

/**
 * Container annotation that enables {@link PublishResult} to be used repeatedly on a single method.
 *
 * <p>This annotation is not intended to be used directly. Instead, declare multiple
 * {@code @PublishResult} annotations on the same method:
 *
 * <pre>
 * &#64;PublishResult(channelRef = "push")
 * &#64;PublishResult(channelRef = "audit")
 * &#64;RacerPriority(defaultLevel = "HIGH")
 * public Mono&lt;NotificationResult&gt; sendUrgentPush(NotificationCommand cmd) { ... }
 * </pre>
 *
 * <p>The framework will publish the return value to <em>all</em> declared channels,
 * applying {@code @RacerPriority} routing where applicable.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublishResults {
    PublishResult[] value();
}
