package com.cheetah.racer.demo.poller;

import com.cheetah.racer.common.annotation.RacerPoll;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates {@code @RacerPoll} — a method invoked on a schedule whose return
 * value is automatically published to the configured Redis channel.
 *
 * <p>The method can return any serializable object; Racer wraps it in a
 * {@code RacerMessage} envelope and publishes to the configured channel.
 */
@Component
@Slf4j
public class DemoPoller {

    /**
     * Fires every 30 seconds and publishes a heartbeat event to the default channel.
     */
    @RacerPoll(
            fixedRate   = 30_000,
            channel     = "racer:messages",
            sender      = "demo-poller",
            async       = true
    )
    public Map<String, Object> heartbeat() {
        Map<String, Object> event = Map.of(
                "eventId",   UUID.randomUUID().toString(),
                "eventType", "HEARTBEAT",
                "timestamp", Instant.now().toString()
        );
        log.debug("[POLLER] Publishing heartbeat: {}", event);
        return event;
    }

    /**
     * Fires on a cron schedule (every minute) and publishes an order-type event
     * to the {@code orders} channel alias, demonstrating the router rules in
     * {@link com.cheetah.racer.demo.router.DemoRouter}.
     */
    @RacerPoll(
            cron        = "0 * * * * *",
            channelRef  = "orders",
            sender      = "demo-poller"
    )
    public Map<String, Object> periodicOrderCheck() {
        Map<String, Object> event = Map.of(
                "id",        UUID.randomUUID().toString(),
                "type",      "ORDER_CHECK",
                "timestamp", Instant.now().toString()
        );
        log.debug("[POLLER] Publishing periodic order check: {}", event);
        return event;
    }
}
