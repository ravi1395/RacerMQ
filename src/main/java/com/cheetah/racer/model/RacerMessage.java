package com.cheetah.racer.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a message exchanged via Redis Pub/Sub.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RacerMessage implements Serializable {

    private String id;
    private String channel;
    private String payload;
    private String sender;
    private Instant timestamp;
    private int retryCount;

    /**
     * Optional priority level for priority-channel routing (R-10).
     * Valid values: {@code HIGH}, {@code NORMAL}, {@code LOW} (or any custom level).
     * Defaults to {@code NORMAL} when absent.
     */
    @Builder.Default
    private String priority = "NORMAL";

    /**
     * Set to {@code true} by the router when a message has been forwarded via a routing rule.
     * Messages with {@code routed=true} are skipped by the router to prevent infinite cycles.
     */
    @Builder.Default
    private boolean routed = false;

    /**
     * W3C {@code traceparent} header value for distributed tracing (Phase 4.2).
     * Format: {@code 00-<16-byte-trace-id>-<8-byte-parent-id>-<flags>}.
     * Populated automatically when {@code racer.tracing.enabled=true}.
     * {@code null} when tracing is disabled.
     */
    private String traceparent;

    /**
     * Factory method to create a new message with auto-generated id and timestamp.
     */
    public static RacerMessage create(String channel, String payload, String sender) {
        return create(channel, payload, sender, null);
    }

    /**
     * Factory method with explicit priority.
     */
    public static RacerMessage create(String channel, String payload, String sender, String priority) {
        return RacerMessage.builder()
                .id(UUID.randomUUID().toString())
                .channel(channel)
                .payload(payload)
                .sender(sender)
                .timestamp(Instant.now())
                .retryCount(0)
                .priority(priority != null ? priority : "NORMAL")
                .build();
    }
}
