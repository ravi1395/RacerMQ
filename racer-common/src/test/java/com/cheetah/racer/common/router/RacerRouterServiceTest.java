package com.cheetah.racer.common.router;

import com.cheetah.racer.common.annotation.RacerRoute;
import com.cheetah.racer.common.annotation.RacerRouteRule;
import com.cheetah.racer.common.model.RacerMessage;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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

        // Registry stubs
        when(registry.getPublisher("orders")).thenReturn(ordersPublisher);
        when(registry.getPublisher("notifications")).thenReturn(notificationsPublisher);

        // Publisher stubs (fire-and-forget subscribe in route())
        when(ordersPublisher.publishAsync(anyString(), anyString()))
                .thenReturn(Mono.just(1L));
        when(notificationsPublisher.publishAsync(anyString(), anyString()))
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

        boolean routed = routerService.route(msg);

        assertThat(routed).isTrue();
    }

    @Test
    void route_noMatchingRule_returnsFalse() {
        RacerMessage msg = RacerMessage.create(
                "racer:messages", "{\"type\":\"AUDIT\"}", "svc");

        boolean routed = routerService.route(msg);

        assertThat(routed).isFalse();
    }

    @Test
    void route_malformedJson_returnsFalse() {
        RacerMessage msg = RacerMessage.create(
                "racer:messages", "not json at all", "svc");

        boolean routed = routerService.route(msg);

        assertThat(routed).isFalse();
    }

    // ------------------------------------------------------------------
    // No rules — route always returns false
    // ------------------------------------------------------------------

    @Test
    void routerWithNoRules_routeReturnsFalse() {
        when(applicationContext.getBeansWithAnnotation(RacerRoute.class))
                .thenReturn(Map.of());

        RacerRouterService emptyRouter =
                new RacerRouterService(applicationContext, registry, objectMapper);
        emptyRouter.init();

        assertThat(emptyRouter.hasRules()).isFalse();
        assertThat(emptyRouter.route(
                RacerMessage.create("ch", "{\"type\":\"ORDER\"}", "s"))).isFalse();
    }
}
