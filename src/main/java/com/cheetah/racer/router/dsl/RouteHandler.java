package com.cheetah.racer.router.dsl;

import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.RouteDecision;

/**
 * Functional handler invoked when a {@link RoutePredicate} matches.
 *
 * <p>The handler receives the matched message and a {@link RouteContext} it can use to
 * forward the message to one or more target channel aliases.
 * It must return one of the {@link RouteDecision} values to tell the listener registrar
 * how to continue.
 *
 * <p>Prefer the factory methods in {@link RouteHandlers} over writing raw lambdas:
 * <pre>{@code
 * .route(fieldEquals("type", "EMAIL"), forward("email"))
 * .route(fieldEquals("type", "BROADCAST"), multicastAndProcess("email", "sms"))
 * }</pre>
 *
 * @see RouteHandlers
 * @see RouteDecision
 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * Handles a matched message.
     *
     * @param message the inbound message
     * @param ctx     context object for publishing to target channels
     * @return the routing decision that the caller should enforce
     */
    RouteDecision handle(RacerMessage message, RouteContext ctx);
}
