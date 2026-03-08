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
}
