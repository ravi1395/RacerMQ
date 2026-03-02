package com.cheetah.racer.server.service;

import com.cheetah.racer.common.annotation.PublishResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service responsible for building order events and publishing them to the
 * configured Racer channel via the {@link PublishResult} AOP aspect.
 *
 * <p>This is intentionally a <em>separate Spring bean</em> from
 * {@link com.cheetah.racer.server.controller.ChannelRegistryController}.
 * Spring AOP uses JDK/CGLIB proxies, so {@code @PublishResult} is only
 * intercepted when the annotated method is called <em>through the proxy</em> —
 * i.e. via an injected bean reference, not via {@code this.method()}.
 * Moving the annotated method here ensures every call passes through the proxy.
 *
 * <p>Fixes BUG-2: {@code @PublishResult} self-invocation bypasses AOP.
 */
@Service
public class OrderEventService {

    /**
     * Builds an {@code ORDER_CREATED} event map from the given request body and
     * publishes it to the {@code orders} channel via {@link PublishResult}.
     *
     * <p>The HTTP caller still receives the same map — publishing is a side-effect
     * handled transparently by the aspect.
     *
     * @param request the raw request body from the HTTP caller
     * @return Mono emitting the enriched order event (also forwarded to Redis)
     */
    @PublishResult(channelRef = "orders", sender = "channel-controller", async = true)
    public Mono<Map<String, Object>> buildOrderEvent(Map<String, Object> request) {
        Map<String, Object> event = new LinkedHashMap<>(request);
        event.put("eventType", "ORDER_CREATED");
        event.put("processedAt", Instant.now().toString());
        event.put("source", "racer-server");
        return Mono.just(event);
    }
}
