package com.cheetah.racer.router.dsl;

/**
 * An immutable (predicate, handler) pair used inside a {@link RacerFunctionalRouter}.
 *
 * <p>Produced by {@link RacerFunctionalRouter.Builder#route} and evaluated at
 * message-dispatch time with zero additional allocation.
 *
 * @param predicate condition that must be satisfied for this rule to fire
 * @param handler   action to apply when the predicate matches
 */
public record FunctionalRouteEntry(RoutePredicate predicate, RouteHandler handler) {}
