package com.cheetah.racer.router.dsl;

import com.cheetah.racer.model.RacerMessage;

/**
 * Passed to a {@link RouteHandler} during message evaluation, providing the ability to
 * forward messages to channel aliases without holding a direct reference to the
 * {@link com.cheetah.racer.publisher.RacerPublisherRegistry}.
 *
 * <p>Implementations are created by {@link com.cheetah.racer.router.RacerRouterService}
 * and are not intended to be implemented by application code.
 */
public interface RouteContext {

    /**
     * Publishes {@code message} to the given channel alias.
     * The alias must be declared under {@code racer.channels.*}.
     * The original message sender is preserved.
     */
    void publishTo(String alias, RacerMessage message);

    /**
     * Publishes {@code message} to the given channel alias with an overridden sender label.
     */
    void publishTo(String alias, RacerMessage message, String sender);

    /**
     * Publishes {@code message} to the priority sub-channel
     * {@code <channel>:priority:<level>} for the given alias.
     *
     * <p>Requires {@code racer.priority.enabled=true} and a
     * {@link com.cheetah.racer.publisher.RacerPriorityPublisher} bean to be present.
     * If priority publishing is not configured, the message is published to the
     * standard channel as a fallback.
     *
     * @param alias channel alias declared under {@code racer.channels.*}
     * @param message the message to publish
     * @param level priority level (e.g. {@code "HIGH"}, {@code "NORMAL"}, {@code "LOW"})
     */
    void publishToWithPriority(String alias, RacerMessage message, String level);
}
