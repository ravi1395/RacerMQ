package com.cheetah.racer.router.dsl;

import com.cheetah.racer.router.RouteDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *     .defaultRoute(dropToDlq())
 *     .build();
 * }</pre>
 */
public final class RouteHandlers {

    private static final Logger log = LoggerFactory.getLogger(RouteHandlers.class);

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

    // ── Forward with priority ──────────────────────────────────────────────

    /**
     * Re-publishes the message to {@code alias} via the priority sub-channel
     * {@code <channel>:priority:<level>} and skips the local handler.
     *
     * <p>Example:
     * <pre>{@code
     * .route(fieldEquals("priority", "HIGH").and(fieldEquals("type", "PUSH")),
     *        forwardWithPriority("push", "HIGH"))
     * }</pre>
     *
     * @param alias channel alias declared under {@code racer.channels.*}
     * @param level priority level (e.g. {@code "HIGH"}, {@code "NORMAL"}, {@code "LOW"})
     */
    public static RouteHandler forwardWithPriority(String alias, String level) {
        return (msg, ctx) -> {
            ctx.publishToWithPriority(alias, msg, level);
            return RouteDecision.FORWARDED;
        };
    }

    /**
     * Composable wrapper that adds priority routing to any existing handler.
     *
     * <p>Publishes the message to the priority sub-channel for {@code alias}
     * <em>before</em> delegating to {@code delegate}. The delegate's return value
     * is preserved.
     *
     * <pre>{@code
     * .route(predicate, priority("push", "HIGH", forward("push")))
     * }</pre>
     *
     * @param alias    channel alias for the priority publish
     * @param level    priority level
     * @param delegate the handler to delegate to after priority publish
     */
    public static RouteHandler priority(String alias, String level, RouteHandler delegate) {
        return (msg, ctx) -> {
            ctx.publishToWithPriority(alias, msg, level);
            return delegate.handle(msg, ctx);
        };
    }

    // ── Drop ──────────────────────────────────────────────────────────────

    /**
     * Discards the message with a DEBUG-level log entry containing the message ID
     * and a truncated payload summary. Use {@link #dropQuietly()} when no logging
     * is desired (e.g. ignoring health-check pings).
     *
     * <p>No forwarding, no local handler invocation, no DLQ entry.
     * Equivalent to annotation {@code action = RouteAction.DROP}.
     */
    public static RouteHandler drop() {
        return (msg, ctx) -> {
            if (log.isDebugEnabled()) {
                String payload = msg.getPayload();
                // Truncate and strip control characters (CR/LF) to prevent log injection
                String summary = payload != null && payload.length() > 120
                        ? payload.substring(0, 120) + "…"
                        : payload;
                if (summary != null) {
                    summary = summary.replace('\r', ' ').replace('\n', ' ');
                }
                log.debug("[racer-router] DROP id={} channel={} payload={}",
                        msg.getId(), msg.getChannel(), summary);
            }
            return RouteDecision.DROPPED;
        };
    }

    /**
     * Silently discards the message with <em>no</em> logging — intended for messages
     * that are expected to be unmatched (e.g. health-check pings, heartbeats).
     *
     * <p>Prefer {@link #drop()} for normal routing so that unmatched messages are
     * observable during development, or {@link #dropToDlq()} when discarded messages
     * should be recoverable.
     */
    public static RouteHandler dropQuietly() {
        return (msg, ctx) -> RouteDecision.DROPPED;
    }

    /**
     * Routes the message to the Dead Letter Queue and skips the local handler.
     * Equivalent to annotation {@code action = RouteAction.DROP_TO_DLQ}.
     *
     * <p>This is the recommended default route for most routers — unmatched messages
     * remain observable and recoverable via the DLQ API.
     */
    public static RouteHandler dropToDlq() {
        return (msg, ctx) -> RouteDecision.DROPPED_TO_DLQ;
    }
}
