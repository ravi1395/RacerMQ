package com.cheetah.racer.server.controller;

import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import com.cheetah.racer.server.service.OrderEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Demonstrates and exposes the Racer annotation-driven publishing infrastructure.
 *
 * <h2>@RacerPublisher field injection demo</h2>
 * <p>The {@code ordersPublisher}, {@code notificationsPublisher}, and {@code defaultPublisher}
 * fields below are injected automatically by the {@code RacerPublisherFieldProcessor}
 * based on the aliases defined in {@code application.properties}.
 *
 * <h2>@PublishResult method annotation demo</h2>
 * <p>{@code POST /api/channels/publish-annotated} calls an annotated method whose
 * return value is automatically forwarded to the configured channel.
 */
@RestController
@RequestMapping("/api/channels")
@Slf4j
@RequiredArgsConstructor
public class ChannelRegistryController {

    private final RacerPublisherRegistry registry;

    /**
     * Injected separate bean so that the {@code @PublishResult} AOP aspect fires
     * correctly (self-invocation on {@code this} bypasses the Spring proxy).
     */
    private final OrderEventService orderEventService;

    // -----------------------------------------------------------------------
    // @RacerPublisher field injection (no @Autowired needed!)
    // -----------------------------------------------------------------------

    @RacerPublisher("orders")
    private RacerChannelPublisher ordersPublisher;

    @RacerPublisher("notifications")
    private RacerChannelPublisher notificationsPublisher;

    @RacerPublisher            // no alias → uses racer.default-channel
    private RacerChannelPublisher defaultPublisher;

    // -----------------------------------------------------------------------
    // REST: list all registered channels
    // -----------------------------------------------------------------------

    /**
     * GET /api/channels
     * <p>Lists every channel registered in the Racer registry.
     *
     * <pre>
     * curl http://localhost:8080/api/channels
     * </pre>
     */
    @GetMapping
    public Mono<Map<String, Object>> listChannels() {
        Map<String, Object> result = registry.getAll().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Map.of("channel", e.getValue().getChannelName())));
        return Mono.just(result);
    }

    // -----------------------------------------------------------------------
    // REST: publish via @RacerPublisher injected field
    // -----------------------------------------------------------------------

    /**
     * POST /api/channels/publish/{alias}
     * <p>Publishes the request body to the channel registered under {@code alias}
     * using the injected {@link RacerChannelPublisher}.
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/channels/publish/orders \
     *      -H "Content-Type: application/json" \
     *      -d '{"orderId":"123","item":"Widget"}'
     * </pre>
     */
    @PostMapping("/publish/{alias}")
    public Mono<Map<String, Object>> publishToAlias(
            @PathVariable String alias,
            @RequestBody Map<String, Object> body) {

        RacerChannelPublisher publisher = registry.getPublisher(alias);
        return publisher.publishAsync(body)
                .map(count -> Map.of(
                        "published", true,
                        "alias", alias,
                        "channel", publisher.getChannelName(),
                        "subscribers", count));
    }

    // -----------------------------------------------------------------------
    // REST: @PublishResult annotation demo
    // -----------------------------------------------------------------------

    /**
     * POST /api/channels/publish-annotated
     * <p>The return value of the inner method is automatically published to
     * {@code racer:orders} (via {@code channelRef = "orders"}) by the
     * {@link com.cheetah.racer.common.aspect.PublishResultAspect}.
     * The HTTP caller still receives the same response — publishing is a side-effect.
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/channels/publish-annotated \
     *      -H "Content-Type: application/json" \
     *      -d '{"item":"Gadget","qty":5}'
     * </pre>
     */
    @PostMapping("/publish-annotated")
    public Mono<Map<String, Object>> publishAnnotated(@RequestBody Map<String, Object> request) {
        return orderEventService.buildOrderEvent(request); // @PublishResult fires on the proxy
    }

    /**
     * This method's return value is intercepted by {@link PublishResult} and published
     * to the {@code orders} channel automatically.
     *
     * <p>The HTTP caller receives the same map that is also published to Redis.
     *
     * @deprecated Moved to {@link OrderEventService#buildOrderEvent} to ensure the AOP
     *             proxy is honoured (self-invocation bypasses the aspect). Kept here
     *             for reference only — do not call directly.
     */
    @Deprecated
    @PublishResult(channelRef = "orders", sender = "channel-controller", async = true)
    public Mono<Map<String, Object>> buildOrderEvent(Map<String, Object> request) {
        return orderEventService.buildOrderEvent(request);
    }

    // -----------------------------------------------------------------------
    // Injected-publisher demo endpoints
    // -----------------------------------------------------------------------

    /**
     * POST /api/channels/demo/orders
     * Publishes body directly to the orders channel via the injected publisher.
     */
    @PostMapping("/demo/orders")
    public Mono<Map<String, Object>> demoOrders(@RequestBody Map<String, Object> body) {
        return ordersPublisher.publishAsync(body)
                .map(count -> Map.of("channel", ordersPublisher.getChannelName(), "subscribers", count));
    }

    /**
     * POST /api/channels/demo/notifications
     * Publishes body directly to the notifications channel via the injected publisher.
     */
    @PostMapping("/demo/notifications")
    public Mono<Map<String, Object>> demoNotifications(@RequestBody Map<String, Object> body) {
        return notificationsPublisher.publishAsync(body)
                .map(count -> Map.of("channel", notificationsPublisher.getChannelName(), "subscribers", count));
    }
}
