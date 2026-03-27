package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.RacerRouteRule;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.router.dsl.RacerFunctionalRouter;
import com.cheetah.racer.router.dsl.RouteHandlers;
import com.cheetah.racer.router.dsl.RoutePredicates;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.util.Map;

import static com.cheetah.racer.router.RouteDecision.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerRouterService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerRouterServiceTest {

    // -----------------------------------------------------------------------
    // Sample @RacerRoute bean: routes ORDER → orders, NOTIFY* → notifications
    // -----------------------------------------------------------------------

    @RacerRoute({
        @RacerRouteRule(field = "type", matches = "ORDER",   to = "orders"),
        @RacerRouteRule(field = "type", matches = "NOTIFY.*", to = "notifications")
    })
    static class SampleRouter {}

    @Mock ApplicationContext applicationContext;
    @Mock RacerPublisherRegistry registry;
    @Mock RacerChannelPublisher ordersPublisher;
    @Mock RacerChannelPublisher notificationsPublisher;

    RacerRouterService routerService;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // Wire beans with annotation
        when(applicationContext.getBeansWithAnnotation(RacerRoute.class))
                .thenReturn(Map.of("sampleRouter", new SampleRouter()));

        // No functional routers by default
        when(applicationContext.getBeansOfType(RacerFunctionalRouter.class))
                .thenReturn(Map.of());

        // Registry stubs
        when(registry.getPublisher("orders")).thenReturn(ordersPublisher);
        when(registry.getPublisher("notifications")).thenReturn(notificationsPublisher);

        // Publisher stubs (fire-and-forget subscribe in route())
        when(ordersPublisher.publishAsync(anyString(), anyString()))
                .thenReturn(Mono.just(1L));
        when(notificationsPublisher.publishAsync(anyString(), anyString()))
                .thenReturn(Mono.just(1L));
        when(ordersPublisher.publishRoutedAsync(anyString(), anyString()))
                .thenReturn(Mono.just(1L));
        when(notificationsPublisher.publishRoutedAsync(anyString(), anyString()))
                .thenReturn(Mono.just(1L));

        routerService = new RacerRouterService(applicationContext, registry, objectMapper);
        routerService.init();
    }

    // ------------------------------------------------------------------
    // init() — rule loading
    // ------------------------------------------------------------------

    @Test
    void hasRules_afterInit_isTrue() {
        assertThat(routerService.hasRules()).isTrue();
    }

    @Test
    void getRuleDescriptions_returnsDescriptionsForAllRules() {
        assertThat(routerService.getRuleDescriptions()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // dryRun — exact match
    // ------------------------------------------------------------------

    @Test
    void dryRun_exactMatch_returnsAlias() {
        String alias = routerService.dryRun("{\"type\":\"ORDER\"}");
        assertThat(alias).isEqualTo("orders");
    }

    // ------------------------------------------------------------------
    // dryRun — regex match
    // ------------------------------------------------------------------

    @Test
    void dryRun_regexMatch_returnsAlias() {
        // "NOTIFY.*" matches "NOTIFY_EVENT" (starts with NOTIFY, then any chars)
        String alias = routerService.dryRun("{\"type\":\"NOTIFY_EVENT\"}");
        assertThat(alias).isEqualTo("notifications");
    }

    @Test
    void dryRun_regexMatch_anotherValue() {
        // "NOTIFY_USER" also matches "NOTIFY.*"
        String alias = routerService.dryRun("{\"type\":\"NOTIFY_USER\"}");
        assertThat(alias).isEqualTo("notifications");
    }

    // ------------------------------------------------------------------
    // dryRun — no match
    // ------------------------------------------------------------------

    @Test
    void dryRun_noMatch_returnsNull() {
        String alias = routerService.dryRun("{\"type\":\"UNKNOWN\"}");
        assertThat(alias).isNull();
    }

    @Test
    void dryRun_missingField_returnsNull() {
        String alias = routerService.dryRun("{\"amount\":100}");
        assertThat(alias).isNull();
    }

    @Test
    void dryRun_nonJson_returnsNull() {
        // Non-JSON input must not throw; just returns null
        String alias = routerService.dryRun("plain text");
        assertThat(alias).isNull();
    }

    // ------------------------------------------------------------------
    // route() — returns true on match
    // ------------------------------------------------------------------

    @Test
    void route_matchingMessage_returnsTrue() {
        RacerMessage msg = RacerMessage.create(
                "racer:messages", "{\"type\":\"ORDER\"}", "svc");

        assertThat(routerService.route(msg)).isEqualTo(FORWARDED);
    }

    @Test
    void route_noMatchingRule_returnsFalse() {
        RacerMessage msg = RacerMessage.create(
                "racer:messages", "{\"type\":\"AUDIT\"}", "svc");

        assertThat(routerService.route(msg)).isEqualTo(PASS);
    }

    @Test
    void route_malformedJson_returnsFalse() {
        RacerMessage msg = RacerMessage.create(
                "racer:messages", "not json at all", "svc");

        assertThat(routerService.route(msg)).isEqualTo(PASS);
    }

    // ------------------------------------------------------------------
    // No rules — route always returns false
    // ------------------------------------------------------------------

    @Test
    void routerWithNoRules_routeReturnsFalse() {
        when(applicationContext.getBeansWithAnnotation(RacerRoute.class))
                .thenReturn(Map.of());
        when(applicationContext.getBeansOfType(RacerFunctionalRouter.class))
                .thenReturn(Map.of());

        RacerRouterService emptyRouter =
                new RacerRouterService(applicationContext, registry, objectMapper);
        emptyRouter.init();

        assertThat(emptyRouter.hasRules()).isFalse();
        assertThat(emptyRouter.route(
                RacerMessage.create("ch", "{\"type\":\"ORDER\"}", "s"))).isEqualTo(PASS);
    }

    // ------------------------------------------------------------------
    // routeReactive() — fully reactive path (M-4 fix)
    // ------------------------------------------------------------------

    @Test
    void routeReactive_matchingMessage_emitsForwarded() {
        RacerMessage msg = RacerMessage.create("racer:messages", "{\"type\":\"ORDER\"}", "svc");

        RouteDecision decision = routerService.routeReactive(msg).block();

        assertThat(decision).isEqualTo(FORWARDED);
    }

    @Test
    void routeReactive_noMatchingRule_emitsPass() {
        RacerMessage msg = RacerMessage.create("racer:messages", "{\"type\":\"AUDIT\"}", "svc");

        RouteDecision decision = routerService.routeReactive(msg).block();

        assertThat(decision).isEqualTo(PASS);
    }

    @Test
    void routeReactive_redisFailure_propagatesError() {
        // Simulate Redis being unreachable — publish call fails
        when(ordersPublisher.publishRoutedAsync(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        RacerMessage msg = RacerMessage.create("racer:messages", "{\"type\":\"ORDER\"}", "svc");

        // Error must propagate (not be swallowed), so the caller's DLQ handling kicks in
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> routerService.routeReactive(msg).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis connection refused");
    }

    @Test
    void routeReactive_alreadyRouted_emitsPass() {
        RacerMessage msg = RacerMessage.create("racer:messages", "{\"type\":\"ORDER\"}", "svc");
        msg.setRouted(true); // simulate a message already forwarded once

        RouteDecision decision = routerService.routeReactive(msg).block();

        assertThat(decision).isEqualTo(PASS);
    }

    // ------------------------------------------------------------------
    // Functional router (DefaultRouteContext) tests
    // ------------------------------------------------------------------

    private RacerRouterService buildWithFunctionalRouter(RacerFunctionalRouter router) {
        when(applicationContext.getBeansWithAnnotation(RacerRoute.class)).thenReturn(Map.of());
        when(applicationContext.getBeansOfType(RacerFunctionalRouter.class))
                .thenReturn(Map.of("testRouter", router));
        RacerRouterService svc = new RacerRouterService(applicationContext, registry, objectMapper);
        svc.init();
        return svc;
    }

    @Test
    void functionalRouter_matchingMessage_forwardsViaPublishTo() {
        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("test-router")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forward("orders"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);

        RacerMessage msg = RacerMessage.create("ch", "{\"type\":\"ORDER\"}", "svc");
        RouteDecision decision = svc.routeReactive(msg).block();

        assertThat(decision).isEqualTo(FORWARDED);
        verify(ordersPublisher, atLeastOnce()).publishRoutedAsync(anyString(), anyString());
    }

    @Test
    void functionalRouter_noMatch_returnsPass() {
        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("test-router")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forward("orders"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);

        RacerMessage msg = RacerMessage.create("ch", "{\"type\":\"UNKNOWN\"}", "svc");
        RouteDecision decision = svc.routeReactive(msg).block();

        assertThat(decision).isEqualTo(PASS);
        verify(ordersPublisher, never()).publishRoutedAsync(anyString(), anyString());
    }

    @Test
    void functionalRouter_forwardAndProcess_returnsForwardedAndProcess() {
        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("fwd-and-process")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forwardAndProcess("orders"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);

        RacerMessage msg = RacerMessage.create("ch", "{\"type\":\"ORDER\"}", "svc");
        RouteDecision decision = svc.routeReactive(msg).block();

        assertThat(decision).isEqualTo(FORWARDED_AND_PROCESS);
        verify(ordersPublisher, atLeastOnce()).publishRoutedAsync(anyString(), anyString());
    }

    @Test
    void functionalRouter_multicast_publishesToMultipleAliases() {
        when(registry.getPublisher("notifications")).thenReturn(notificationsPublisher);

        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("multicast-router")
                .route(RoutePredicates.fieldEquals("type", "BROADCAST"),
                        RouteHandlers.multicast("orders", "notifications"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);

        RacerMessage msg = RacerMessage.create("ch", "{\"type\":\"BROADCAST\"}", "svc");
        RouteDecision decision = svc.routeReactive(msg).block();

        assertThat(decision).isEqualTo(FORWARDED);
        verify(ordersPublisher, atLeastOnce()).publishRoutedAsync(anyString(), anyString());
        verify(notificationsPublisher, atLeastOnce()).publishRoutedAsync(anyString(), anyString());
    }

    @Test
    void functionalRouter_defaultRoute_catchesUnmatched() {
        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("default-router")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forward("orders"))
                .defaultRoute(RouteHandlers.forward("notifications"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);

        RacerMessage msg = RacerMessage.create("ch", "{\"type\":\"ANYTHING_ELSE\"}", "svc");
        RouteDecision decision = svc.routeReactive(msg).block();

        assertThat(decision).isEqualTo(FORWARDED);
        verify(notificationsPublisher, atLeastOnce()).publishRoutedAsync(anyString(), anyString());
    }

    @Test
    void functionalRouter_publishToError_propagatesError() {
        when(ordersPublisher.publishRoutedAsync(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("DSL Redis error")));

        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("error-router")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forward("orders"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);

        RacerMessage msg = RacerMessage.create("ch", "{\"type\":\"ORDER\"}", "svc");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.routeReactive(msg).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DSL Redis error");
    }

    @Test
    void functionalRouter_hasRules_isTrue() {
        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("any-router")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forward("orders"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);
        assertThat(svc.hasRules()).isTrue();
    }

    @Test
    void functionalRouter_getRuleDescriptions_containsFunctionalEntry() {
        RacerFunctionalRouter router = RacerFunctionalRouter.builder()
                .name("desc-router")
                .route(RoutePredicates.fieldEquals("type", "ORDER"), RouteHandlers.forward("orders"))
                .build();

        RacerRouterService svc = buildWithFunctionalRouter(router);
        assertThat(svc.getRuleDescriptions()).anyMatch(d -> d.contains("desc-router"));
    }
}
