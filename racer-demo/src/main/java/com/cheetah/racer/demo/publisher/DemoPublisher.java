package com.cheetah.racer.demo.publisher;

import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates both annotation-driven publishing mechanisms:
 * <ul>
 *   <li>{@code @PublishResult}  — AOP intercepts the method return value and publishes it</li>
 *   <li>{@code @RacerPublisher} — field injection of a named channel publisher</li>
 * </ul>
 */
@Service
@Slf4j
public class DemoPublisher {

    /** Injected publisher bound to the {@code orders} channel alias. */
    @RacerPublisher("orders")
    private RacerChannelPublisher ordersPublisher;

    /** Injected publisher bound to the default channel. */
    @RacerPublisher
    private RacerChannelPublisher defaultPublisher;

    // ── @PublishResult demo ───────────────────────────────────────────────────

    /**
     * Creates a demo order and automatically publishes the result to the
     * {@code orders} channel via the {@code @PublishResult} AOP aspect.
     */
    @PublishResult(channelRef = "orders", sender = "demo-publisher")
    public Map<String, Object> createOrder(String item, int quantity) {
        Map<String, Object> order = Map.of(
                "orderId",   UUID.randomUUID().toString(),
                "item",      item,
                "quantity",  quantity,
                "timestamp", Instant.now().toString()
        );
        log.info("[PUBLISHER] Creating order: {}", order);
        return order;
    }

    /**
     * Reactively creates an order event; the {@code @PublishResult} aspect taps
     * into the Mono pipeline without blocking.
     */
    @PublishResult(channel = "racer:messages", async = true)
    public Mono<Map<String, Object>> publishEvent(String eventType, Object data) {
        return Mono.fromSupplier(() -> Map.of(
                "eventId",   UUID.randomUUID().toString(),
                "eventType", eventType,
                "data",      data,
                "timestamp", Instant.now().toString()
        )).doOnNext(e -> log.info("[PUBLISHER] Publishing event: {}", e));
    }

    // ── @RacerPublisher injected field demo ──────────────────────────────────

    /**
     * Demonstrates programmatic publishing via the injected {@link RacerChannelPublisher}.
     */
    public Mono<Long> directPublish(Object payload) {
        log.info("[PUBLISHER] Direct publish: {}", payload);
        return ordersPublisher.publishAsync(payload);
    }
}
