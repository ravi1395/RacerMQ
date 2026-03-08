package com.cheetah.racer.router.dsl;

import com.cheetah.racer.router.RouteDecision;

/**
 * Factory methods for the standard {@link RouteHandler} implementations.
 *
 * <p>Import statically alongside {@link RoutePredicates} for a concise DSL:
 * <pre>{@code
 * import static com.cheetah.racer.router.dsl.RoutePredicates.*;
 * import static com.cheetah.racer.router.dsl.RouteHandlers.*;
 *
 * RacerFunctionalRouter.builder()
 *     .route(fieldEquals("type", "EMAIL"),     forward("email"))
 *     .route(fieldEquals("type", "SMS"),        forward("sms"))
 *     // True multi-target fan-out — impossible with a single @RacerRouteRule
 *     .route(fieldEquals("type", "BROADCAST"), multicastAndProcess("email", "sms", "push"))
 *     .defaultRoute(drop())
 *     .build();
 * }</pre>
 */
public final class RouteHandlers {

    private RouteHandlers() {}

    // ── Forward ───────────────────────────────────────────────────────────

    /**
     * Re-publishes the message to {@code alias} and skips the local handler.
     * Equivalent to annotation {@code action = RouteAction.FORWARD}.
     */
    public static RouteHandler forward(String alias) {
        return (msg, ctx) -> {
            ctx.publishTo(alias, msg);
            return RouteDecision.FORWARDED;
        };
    }

    /**
     * Re-publishes the message to {@code alias} with an overridden sender and skips the local handler.
     */
    public static RouteHandler forward(String alias, String sender) {
        return (msg, ctx) -> {
            ctx.publishTo(alias, msg, sender);
            return RouteDecision.FORWARDED;
        };
    }

    /**
     * Re-publishes the message to {@code alias} and also invokes the local handler (fan-out).
     * Equivalent to annotation {@code action = RouteAction.FORWARD_AND_PROCESS}.
     */
    public static RouteHandler forwardAndProcess(String alias) {
        return (msg, ctx) -> {
            ctx.publishTo(alias, msg);
            return RouteDecision.FORWARDED_AND_PROCESS;
        };
    }

    // ── Multi-target fan-out ───────────────────────────────────────────────

    /**
     * Re-publishes the message to <em>all</em> given aliases simultaneously and skips
     * the local handler.
     *
     * <p>This is the primary advantage over annotation-based routing: a single rule can
     * target multiple channels, which is impossible with {@code @RacerRouteRule}.
     */
    public static RouteHandler multicast(String... aliases) {
        return (msg, ctx) -> {
            for (String alias : aliases) {
                ctx.publishTo(alias, msg);
            }
            return RouteDecision.FORWARDED;
        };
    }

    /**
     * Re-publishes the message to <em>all</em> given aliases and also invokes the local handler.
     *
     * <p>Use for broadcast patterns where the local service must also process the event.
     */
    public static RouteHandler multicastAndProcess(String... aliases) {
        return (msg, ctx) -> {
            for (String alias : aliases) {
                ctx.publishTo(alias, msg);
            }
            return RouteDecision.FORWARDED_AND_PROCESS;
        };
    }

    // ── Drop ──────────────────────────────────────────────────────────────

    /**
     * Silently discards the message — no forwarding, no local handler, no DLQ entry.
     * Equivalent to annotation {@code action = RouteAction.DROP}.
     */
    public static RouteHandler drop() {
        return (msg, ctx) -> RouteDecision.DROPPED;
    }

    /**
     * Routes the message to the Dead Letter Queue and skips the local handler.
     * Equivalent to annotation {@code action = RouteAction.DROP_TO_DLQ}.
     */
    public static RouteHandler dropToDlq() {
        return (msg, ctx) -> RouteDecision.DROPPED_TO_DLQ;
    }
}
