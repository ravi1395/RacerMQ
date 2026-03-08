package com.cheetah.racer.router.dsl;

import com.cheetah.racer.model.RacerMessage;

/**
 * Functional predicate used to match an inbound {@link RacerMessage} against a route rule.
 *
 * <p>Instances are created via {@link RoutePredicates} factory methods or written as lambdas.
 * Predicates can be composed with {@link #and}, {@link #or}, and {@link #negate}.
 *
 * @see RoutePredicates
 * @see RacerFunctionalRouter.Builder#route(RoutePredicate, RouteHandler)
 */
@FunctionalInterface
public interface RoutePredicate {

    /** Returns {@code true} if this predicate matches {@code message}. */
    boolean test(RacerMessage message);

    /** Returns a predicate that matches when {@code this} AND {@code other} both match. */
    default RoutePredicate and(RoutePredicate other) {
        return msg -> this.test(msg) && other.test(msg);
    }

    /** Returns a predicate that matches when {@code this} OR {@code other} matches. */
    default RoutePredicate or(RoutePredicate other) {
        return msg -> this.test(msg) || other.test(msg);
    }

    /** Returns a predicate that matches when {@code this} does not match. */
    default RoutePredicate negate() {
        return msg -> !this.test(msg);
    }
}
