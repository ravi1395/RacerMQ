package com.cheetah.racer.router.dsl;

import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.RouteDecision;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Code-first, functional-DSL router for Racer.
 *
 * <h3>Overview</h3>
 * <p>Declare a {@code @Bean} of this type in any {@code @Configuration} class.
 * At startup, {@link com.cheetah.racer.router.RacerRouterService} discovers all
 * {@code RacerFunctionalRouter} beans and evaluates them (in bean-registration order)
 * after the legacy annotation-based global rules.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * import static com.cheetah.racer.router.dsl.RoutePredicates.*;
 * import static com.cheetah.racer.router.dsl.RouteHandlers.*;
 *
 * @Configuration
 * public class NotificationRouterConfig {
 *
 *     @Bean
 *     public RacerFunctionalRouter notificationRouter() {
 *         return RacerFunctionalRouter.builder()
 *             .name("notification-router")
 *             .route(fieldEquals("type", "EMAIL"),    forward("email"))
 *             .route(fieldEquals("type", "SMS"),       forward("sms"))
 *             // true fan-out: publish to email AND sms, then process locally
 *             .route(fieldEquals("type", "BROADCAST"), multicastAndProcess("email", "sms"))
 *             // composable predicates
 *             .route(fieldEquals("type", "AUDIT")
 *                        .and(senderEquals("checkout-service")), forward("audit"))
 *             .defaultRoute(drop())
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * <h3>Rule evaluation</h3>
 * <ul>
 *   <li>Rules are evaluated in declaration order; the first matching predicate wins.</li>
 *   <li>Multiple aliases can be targeted in a single rule via {@link RouteHandlers#multicast}
 *       — this is the primary advantage over the annotation-based {@code @RacerRoute}.</li>
 *   <li>If no rule matches, {@link RouteDecision#PASS} is returned and the message is
 *       processed by the local handler unchanged.</li>
 * </ul>
 *
 * <h3>Backward compatibility</h3>
 * <p>The annotation-based {@code @RacerRoute} / {@code @RacerRouteRule} system remains fully
 * supported. Both styles can coexist: annotation rules are evaluated first, then functional
 * router beans in bean-registration order.
 *
 * @see RoutePredicate
 * @see RouteHandler
 * @see RoutePredicates
 * @see RouteHandlers
 */
@Slf4j
public final class RacerFunctionalRouter {

    private final String name;
    private final List<FunctionalRouteEntry> entries;

    private RacerFunctionalRouter(String name, List<FunctionalRouteEntry> entries) {
        this.name    = name;
        this.entries = List.copyOf(entries);
    }

    /** Returns the name assigned via {@link Builder#name(String)}, used in logs and the router REST API. */
    public String getName() { return name; }

    /** Returns the ordered, immutable list of route entries. */
    public List<FunctionalRouteEntry> entries() { return entries; }

    /**
     * Evaluates this router's rules against {@code message}.
     * Forwarding side-effects are applied via {@code ctx}.
     *
     * @return the decision of the first matching rule, or {@link RouteDecision#PASS}
     */
    public RouteDecision evaluate(RacerMessage message, RouteContext ctx) {
        for (FunctionalRouteEntry entry : entries) {
            try {
                if (entry.predicate().test(message)) {
                    return entry.handler().handle(message, ctx);
                }
            } catch (Exception ex) {
                log.debug("[racer-router] DSL router '{}' error evaluating rule for id={}: {}",
                        name, message.getId(), ex.getMessage());
            }
        }
        return RouteDecision.PASS;
    }

    /** Returns a new builder with an auto-generated name. */
    public static Builder builder() {
        return new Builder("functional-router-" + System.identityHashCode(new Object()));
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private String name;
        private final List<FunctionalRouteEntry> entries = new ArrayList<>();

        Builder(String name) { this.name = name; }

        /**
         * Assigns a descriptive name to this router, visible in logs and
         * {@code GET /api/router/rules}.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Adds a route: when {@code predicate} matches, apply {@code handler}.
         * Rules are evaluated in declaration order; the first match wins.
         */
        public Builder route(RoutePredicate predicate, RouteHandler handler) {
            entries.add(new FunctionalRouteEntry(predicate, handler));
            return this;
        }

        /**
         * Adds a catch-all route that matches every message not handled by a prior rule.
         * Equivalent to {@code route(RoutePredicates.any(), handler)}.
         * Should always be the last entry.
         */
        public Builder defaultRoute(RouteHandler handler) {
            entries.add(new FunctionalRouteEntry(RoutePredicates.any(), handler));
            return this;
        }

        /** Builds an immutable {@link RacerFunctionalRouter}. */
        public RacerFunctionalRouter build() {
            return new RacerFunctionalRouter(name, entries);
        }
    }
}
