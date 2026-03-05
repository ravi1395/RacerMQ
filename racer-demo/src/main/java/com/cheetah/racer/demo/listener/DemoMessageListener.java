package com.cheetah.racer.demo.listener;

import com.cheetah.racer.common.annotation.ConcurrencyMode;
import com.cheetah.racer.common.annotation.RacerListener;
import com.cheetah.racer.common.annotation.RacerStreamListener;
import com.cheetah.racer.common.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Demonstrates {@code @RacerListener} (Pub/Sub) and {@code @RacerStreamListener}
 * (durable Redis Streams) in a single Spring bean.
 */
@Component
@Slf4j
public class DemoMessageListener {

    /**
     * Listens on the default {@code racer:messages} channel via Pub/Sub.
     * Sequential mode — one message at a time.
     */
    @RacerListener(channel = "racer:messages")
    public void onMessage(RacerMessage message) {
        log.info("[LISTENER] Received Pub/Sub message: id={} payload={}",
                message.getId(), message.getPayload());
    }

    /**
     * Listens on the {@code orders} channel alias (mapped to {@code racer:orders})
     * with concurrent processing (up to 4 parallel workers).
     */
    @RacerListener(channelRef = "orders", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
    public Mono<Void> onOrder(RacerMessage order) {
        log.info("[LISTENER] Processing order: id={}", order.getId());
        return Mono.empty();
    }

    /**
     * Consumes durable messages from the {@code racer:demo:stream} Redis Stream
     * via a consumer group. Acknowledgement is handled automatically on success;
     * failures are routed to the DLQ.
     */
    @RacerStreamListener(
            streamKey  = "racer:demo:stream",
            group      = "demo-consumers",
            mode       = ConcurrencyMode.CONCURRENT,
            concurrency = 2,
            batchSize  = 5
    )
    public Mono<Void> onStreamMessage(RacerMessage message) {
        log.info("[STREAM-LISTENER] Durable stream message: id={} channel={} payload={}",
                message.getId(), message.getChannel(), message.getPayload());
        return Mono.empty();
    }
}
