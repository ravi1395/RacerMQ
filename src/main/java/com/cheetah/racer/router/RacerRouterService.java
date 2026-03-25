package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.RacerRouteRule;
import com.cheetah.racer.annotation.RouteAction;
import com.cheetah.racer.annotation.RouteMatchSource;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.router.dsl.RacerFunctionalRouter;
import com.cheetah.racer.router.dsl.RouteContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Content-based message router.
 *
 * <p>Scans all Spring beans annotated with {@link RacerRoute} at startup and compiles
 * a list of routing rules.  When {@link #route(RacerMessage)} is called (from
 * {@code ConsumerSubscriber}), it evaluates rules against the message payload and
 * re-publishes to the first matching channel alias.
 *
 * <h3>Thread safety</h3>
 * Rules are populated once at startup via {@link #init()} and never modified afterwards,
 * so the list is safe to read concurrently without locking.
 *
 * <h3>Routing logic</h3>
 * <ol>
 *   <li>Annotation rules ({@code @RacerRoute} / {@code @RacerRouteRule}) are evaluated first.</li>
 *   <li>If no annotation rule matched, each {@link RacerFunctionalRouter} bean is evaluated in
 *       bean-registration order; the first non-{@link RouteDecision#PASS} decision wins.</li>
 *   <li>Both systems can coexist in the same application — migrate incrementally.</li>
 *   <li>If nothing matches, {@link RouteDecision#PASS} is returned and the message is
 *       processed by the local listener unchanged.</li>
 * </ol>
 */
@Slf4j
public class RacerRouterService {

    private final ApplicationContext applicationContext;
    private final RacerPublisherRegistry registry;
    private final ObjectMapper objectMapper;
    @Nullable
    private final RacerPriorityPublisher priorityPublisher;
    private final RacerMetricsPort racerMetrics;

    private final List<CompiledRouteRule> globalRules = new ArrayList<>();
    private final List<RacerFunctionalRouter> functionalRouters = new ArrayList<>();

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper) {
        this(applicationContext, registry, objectMapper, null, null);
    }

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper,
                              @Nullable RacerPriorityPublisher priorityPublisher) {
        this(applicationContext, registry, objectMapper, priorityPublisher, null);
    }

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper,
                              @Nullable RacerPriorityPublisher priorityPublisher,
                              @Nullable RacerMetricsPort racerMetrics) {
        this.applicationContext = applicationContext;
        this.registry           = registry;
        this.objectMapper       = objectMapper;
        this.priorityPublisher  = priorityPublisher;
        this.racerMetrics       = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
    }

    // -----------------------------------------------------------------------
    // Startup: scan beans
    // -----------------------------------------------------------------------

    @PostConstruct
    public void init() {
        applicationContext.getBeansWithAnnotation(RacerRoute.class).forEach((beanName, bean) -> {
            RacerRoute annotation = resolveAnnotation(bean);
            if (annotation == null) return;

            List<CompiledRouteRule> compiled = compile(annotation);
            globalRules.addAll(compiled);
            compiled.forEach(r -> {
                if (r.source() == RouteMatchSource.PAYLOAD) {
                    log.info("[racer-router] Rule: source=PAYLOAD field='{}' matches='{}' → alias='{}' action={}",
                            r.field(), r.pattern().pattern(), r.alias(), r.action());
                } else {
                    log.info("[racer-router] Rule: source={} matches='{}' → alias='{}' action={}",
                            r.source(), r.pattern().pattern(), r.alias(), r.action());
                }
            });
        });

        if (globalRules.isEmpty()) {
            log.debug("[racer-router] No @RacerRoute beans found — annotation rules inactive.");
        } else {
            log.info("[racer-router] {} annotation routing rule(s) active.", globalRules.size());
        }

        Map<String, RacerFunctionalRouter> dsls =
                applicationContext.getBeansOfType(RacerFunctionalRouter.class);
        functionalRouters.addAll(dsls.values());
        functionalRouters.forEach(r ->
                log.info("[racer-router] Functional router registered: '{}' with {} rule(s).",
                        r.getName(), r.entries().size()));

        if (globalRules.isEmpty() && functionalRouters.isEmpty()) {
            log.debug("[racer-router] No routing rules found — router is inactive.");
        }
    }

    // -----------------------------------------------------------------------
    // Compilation: annotation → CompiledRouteRule
    // -----------------------------------------------------------------------

    /**
     * Compiles a {@link RacerRoute} annotation into an ordered list of
     * {@link CompiledRouteRule} instances ready for evaluation.
     */
    public List<CompiledRouteRule> compile(RacerRoute annotation) {
        return compile(annotation.value());
    }

    /**
     * Compiles an array of {@link RacerRouteRule} annotations.
     * Exposed as a static helper so callers without a service reference can compile rules.
     */
    public static List<CompiledRouteRule> compile(RacerRouteRule[] rules) {
        return Arrays.stream(rules)
                .map(r -> new CompiledRouteRule(
                        r.source(),
                        r.field(),
                        Pattern.compile(r.matches()),
                        r.to(),
                        r.sender(),
                        r.action()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Runtime: route or skip
    // -----------------------------------------------------------------------

    /**
     * Evaluates all routing rules against {@code message} and returns a
     * {@link RouteDecision} indicating how the caller should proceed.
     *
     * <p>Annotation-based global rules are checked first.  If none match (i.e. the
     * result is {@link RouteDecision#PASS}), each registered {@link RacerFunctionalRouter}
     * is evaluated in bean-registration order until a non-PASS decision is produced.
     *
     * @return {@link RouteDecision#PASS} if no rule matched; otherwise the decision
     *         determined by the first matching rule
     */
    public RouteDecision route(RacerMessage message) {
        // Cycle prevention: skip routing for messages already forwarded by a routing rule
        if (message.isRouted()) {
            log.debug("[racer-router] Skipping already-routed message id={}", message.getId());
            return RouteDecision.PASS;
        }

        if (!globalRules.isEmpty()) {
            RouteDecision annotationDecision = evaluate(message, globalRules);
            if (annotationDecision != RouteDecision.PASS) return annotationDecision;
        }

        if (!functionalRouters.isEmpty()) {
            RouteContext ctx = new DefaultRouteContext(message);
            for (RacerFunctionalRouter router : functionalRouters) {
                RouteDecision decision = router.evaluate(message, ctx);
                if (decision != RouteDecision.PASS) return decision;
            }
        }

        return RouteDecision.PASS;
    }

    /**
     * Evaluates {@code rules} (which may be per-listener or global) against
     * {@code message} and returns a {@link RouteDecision}.
     *
     * <p>The JSON payload is parsed lazily — only if at least one rule uses
     * {@link RouteMatchSource#PAYLOAD}.
     *
     * @param message the incoming message
     * @param rules   ordered list of compiled rules to evaluate
     * @return the first matching rule's decision, or {@link RouteDecision#PASS}
     */
    public RouteDecision evaluate(RacerMessage message, List<CompiledRouteRule> rules) {
        if (rules.isEmpty()) return RouteDecision.PASS;

        // Cycle prevention: messages already forwarded by a routing rule must not be re-routed
        if (message.isRouted()) {
            log.debug("[racer-router] Skipping already-routed message id={} in evaluate()", message.getId());
            return RouteDecision.PASS;
        }

        JsonNode payloadNode = null; // parsed lazily

        for (CompiledRouteRule rule : rules) {
            String candidate;
            try {
                if (rule.source() == RouteMatchSource.SENDER) {
                    candidate = message.getSender();
                } else if (rule.source() == RouteMatchSource.ID) {
                    candidate = message.getId();
                } else {
                    // PAYLOAD (default) — JSON field lookup
                    if (payloadNode == null) {
                        payloadNode = objectMapper.readTree(toJsonString(message.getPayload()));
                    }
                    JsonNode fieldNode = payloadNode.get(rule.field());
                    if (fieldNode == null) continue;
                    candidate = fieldNode.asText();
                }
            } catch (Exception ex) {
                log.debug("[racer-router] Cannot evaluate rule for message id={}: {}",
                        message.getId(), ex.getMessage());
                continue;
            }

            if (candidate != null && rule.pattern().matcher(candidate).matches()) {
                return applyAction(message, rule);
            }
        }

        return RouteDecision.PASS;
    }

    private RouteDecision applyAction(RacerMessage message, CompiledRouteRule rule) {
        if (rule.action() == RouteAction.DROP)         return RouteDecision.DROPPED;
        if (rule.action() == RouteAction.DROP_TO_DLQ) return RouteDecision.DROPPED_TO_DLQ;

        // FORWARD or FORWARD_AND_PROCESS — publish to target alias
        RacerChannelPublisher publisher = registry.getPublisher(rule.alias());
        String sender = rule.sender().isBlank() ? message.getSender() : rule.sender();

        publisher.publishRoutedAsync(message.getPayload(), sender)
                .subscribe(
                        count -> log.debug("[racer-router] message id={} → '{}' ({} subscriber(s))",
                                message.getId(), rule.alias(), count),
                        ex -> {
                            racerMetrics.recordFailed(rule.alias(), ex.getClass().getSimpleName());
                            log.error("[racer-router] Routing failed for message id={}: {}",
                                    message.getId(), ex.getMessage());
                        }
                );

        log.info("[racer-router] Forwarded message id={} → alias='{}' action={}",
                message.getId(), rule.alias(), rule.action());

        return rule.action() == RouteAction.FORWARD_AND_PROCESS
                ? RouteDecision.FORWARDED_AND_PROCESS
                : RouteDecision.FORWARDED;
    }

    /**
     * Dry-run: evaluate rules without publishing.
     *
     * @param payload sample payload object to test against the rules
     * @return the channel alias that would be selected, or {@code null} if no rule matches
     */
    public String dryRun(Object payload) {
        try {
            String payloadStr = toJsonString(payload);
            JsonNode payloadNode = objectMapper.readTree(payloadStr);

            for (CompiledRouteRule rule : globalRules) {
                if (rule.source() != RouteMatchSource.PAYLOAD) continue;
                JsonNode fieldNode = payloadNode.get(rule.field());
                if (fieldNode == null) continue;
                if (rule.pattern().matcher(fieldNode.asText()).matches()) {
                    return rule.alias();
                }
            }
        } catch (Exception ex) {
            log.debug("[racer-router] Dry-run error: {}", ex.getMessage());
        }
        return null;
    }

    /** Returns human-readable rule descriptions for the debug REST endpoint. */
    public List<String> getRuleDescriptions() {
        List<String> descriptions = new ArrayList<>();

        globalRules.forEach(r -> {
            if (r.source() == RouteMatchSource.PAYLOAD) {
                descriptions.add(String.format("[annotation] source=PAYLOAD field='%s' matches='%s' → alias='%s' action=%s",
                        r.field(), r.pattern().pattern(), r.alias(), r.action()));
            } else {
                descriptions.add(String.format("[annotation] source=%s matches='%s' → alias='%s' action=%s",
                        r.source(), r.pattern().pattern(), r.alias(), r.action()));
            }
        });

        functionalRouters.forEach(router ->
                descriptions.add(String.format("[functional] router='%s' rules=%d",
                        router.getName(), router.entries().size())));

        return List.copyOf(descriptions);
    }

    public boolean hasRules() {
        return !globalRules.isEmpty() || !functionalRouters.isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String toJsonString(Object value) throws Exception {
        if (value instanceof String s) return s;
        return objectMapper.writeValueAsString(value);
    }

    // -----------------------------------------------------------------------
    // DefaultRouteContext — bridges DSL handlers to the publisher registry
    // -----------------------------------------------------------------------

    /**
     * {@link RouteContext} implementation that delegates publish operations to
     * {@link RacerPublisherRegistry}, mirroring what {@link #applyAction} does for
     * annotation-based rules.
     */
    private final class DefaultRouteContext implements RouteContext {

        private final RacerMessage message;

        DefaultRouteContext(RacerMessage message) { this.message = message; }

        @Override
        public void publishTo(String alias, RacerMessage msg) {
            publishTo(alias, msg, msg.getSender());
        }

        @Override
        public void publishTo(String alias, RacerMessage msg, String sender) {
            RacerChannelPublisher publisher = registry.getPublisher(alias);
            publisher.publishRoutedAsync(msg.getPayload(), sender)
                    .subscribe(
                            count -> log.debug("[racer-router] DSL forwarded id={} → '{}' ({} subscriber(s))",
                                    message.getId(), alias, count),
                            ex -> {
                                racerMetrics.recordFailed(alias, ex.getClass().getSimpleName());
                                log.error("[racer-router] DSL routing failed for id={} → '{}': {}",
                                        message.getId(), alias, ex.getMessage());
                            }
                    );
        }

        @Override
        public void publishToWithPriority(String alias, RacerMessage msg, String level) {
            if (priorityPublisher != null) {
                String channelName = registry.getPublisher(alias).getChannelName();
                String sender = msg.getSender();
                priorityPublisher.publish(channelName, msg.getPayload(), sender, level.toUpperCase())
                        .subscribe(
                                count -> log.debug("[racer-router] DSL priority-forwarded id={} → '{}:priority:{}' ({} subscriber(s))",
                                        message.getId(), alias, level, count),
                                ex -> {
                                    racerMetrics.recordFailed(alias, ex.getClass().getSimpleName());
                                    log.error("[racer-router] DSL priority routing failed for id={} → '{}:priority:{}': {}",
                                            message.getId(), alias, level, ex.getMessage());
                                }
                        );
            } else {
                log.warn("[racer-router] publishToWithPriority called but no RacerPriorityPublisher configured — falling back to standard publish for id={}", message.getId());
                publishTo(alias, msg);
            }
        }
    }

    /** Handles CGLIB-proxied beans where {@code getClass().getAnnotation()} returns null. */
    private RacerRoute resolveAnnotation(Object bean) {
        RacerRoute ann = bean.getClass().getAnnotation(RacerRoute.class);
        if (ann == null && bean.getClass().getSuperclass() != null) {
            ann = bean.getClass().getSuperclass().getAnnotation(RacerRoute.class);
        }
        return ann;
    }
}
