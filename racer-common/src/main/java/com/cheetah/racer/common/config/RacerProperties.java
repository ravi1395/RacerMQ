package com.cheetah.racer.common.config;

import com.cheetah.racer.common.RedisChannels;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Racer framework properties.
 *
 * <pre>
 * # Default channel used when no alias is specified
 * racer.default-channel=racer:messages
 *
 * # Named channel definitions
 * racer.channels.orders.name=racer:orders
 * racer.channels.orders.async=true
 * racer.channels.orders.sender=order-service
 *
 * racer.channels.notifications.name=racer:notifications
 * racer.channels.notifications.async=false
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "racer")
public class RacerProperties {

    /**
     * Default Redis channel used when {@code @RacerPublisher} has no alias
     * and {@code @PublishResult} has neither {@code channel} nor {@code channelRef}.
     */
    private String defaultChannel = RedisChannels.MESSAGE_CHANNEL;

    /**
     * Named channel definitions keyed by alias.
     */
    private Map<String, ChannelProperties> channels = new LinkedHashMap<>();

    @Data
    public static class ChannelProperties {

        /** Redis channel name (key), e.g. {@code racer:orders}. */
        private String name;

        /** Default async flag for publish operations on this channel. */
        private boolean async = true;

        /** Default sender identifier for messages on this channel. */
        private String sender = "racer";
    }

    /**
     * Retention settings for durable streams and DLQ pruning.
     * Mapped under {@code racer.retention.*}.
     */
    @Data
    public static class RetentionProperties {

        /**
         * Maximum number of entries to keep per stream (XTRIM MAXLEN ~).
         * Defaults to 10 000.
         */
        private long streamMaxLen = 10_000;

        /**
         * Age in hours after which DLQ entries are pruned.
         * Defaults to 72 h (3 days).
         */
        private long dlqMaxAgeHours = 72;

        /**
         * Cron expression controlling when the pruning job runs.
         * Defaults to the top of every hour.
         */
        private String scheduleCron = "0 0 * * * *";
    }

    /** Retention / pruning configuration. */
    private RetentionProperties retention = new RetentionProperties();

    // ── R-8: Consumer Scaling ────────────────────────────────────────────────

    /**
     * Consumer scaling settings for durable stream consumers.
     * Mapped under {@code racer.consumer.*}.
     */
    @Data
    public static class ConsumerProperties {

        /**
         * Number of concurrent consumer instances to spawn per stream.
         * Each gets a unique name: {@code <namePrefix>-<index>}.
         * Defaults to 1 (original behaviour).
         */
        private int concurrency = 1;

        /** Prefix for generated consumer names within the consumer group. */
        private String namePrefix = "consumer";

        /**
         * Maximum number of stream entries to read per poll (COUNT argument to XREADGROUP).
         * Defaults to 1.
         */
        private int pollBatchSize = 1;

        /** Interval in milliseconds between polls when the stream is empty. */
        private long pollIntervalMs = 200;
    }

    /** Consumer scaling configuration. */
    private ConsumerProperties consumer = new ConsumerProperties();

    /**
     * Key-based stream sharding settings.
     * Mapped under {@code racer.sharding.*}.
     */
    @Data
    public static class ShardingProperties {

        /** When {@code true}, sharded publishing and consuming is active. */
        private boolean enabled = false;

        /**
         * Number of shards (shard suffix 0 … N-1).
         * Stream key becomes {@code <base>:<shardIndex>}.
         */
        private int shardCount = 4;

        /**
         * Comma-separated base stream keys to shard.
         * E.g. {@code racer:orders:stream,racer:audit:stream}.
         */
        private String streams = "";
    }

    /** Sharding configuration (R-8). */
    private ShardingProperties sharding = new ShardingProperties();

    // ── R-9: Pipelining ──────────────────────────────────────────────────────

    /**
     * Pipeline/batch publish settings.
     * Mapped under {@code racer.pipeline.*}.
     */
    @Data
    public static class PipelineProperties {

        /**
         * When {@code true}, batch-publish operations use parallel reactive merging
         * (Lettuce auto-pipelines concurrent commands) rather than sequential concat.
         */
        private boolean enabled = false;

        /**
         * Maximum number of messages to include in one pipelined batch.
         * Batches larger than this are split.
         */
        private int maxBatchSize = 100;
    }

    /** Pipeline configuration (R-9). */
    private PipelineProperties pipeline = new PipelineProperties();

    // ── R-10: Priority Channels ───────────────────────────────────────────────

    /**
     * Priority channel settings.
     * Mapped under {@code racer.priority.*}.
     */
    @Data
    public static class PriorityProperties {

        /** When {@code true}, priority sub-channels and consumer are active. */
        private boolean enabled = false;

        /**
         * Comma-separated priority level names, ordered highest to lowest.
         * Defaults to {@code HIGH,NORMAL,LOW}.
         */
        private String levels = "HIGH,NORMAL,LOW";

        /**
         * Drain strategy: {@code strict} (drain highest level completely before
         * moving to the next) or {@code weighted} (proportional throughput per level).
         */
        private String strategy = "strict";

        /**
         * Comma-separated channel aliases on which priority sub-channels are activated.
         * E.g. {@code orders,notifications}.
         */
        private String channels = "";
    }

    /** Priority configuration (R-10). */
    private PriorityProperties priority = new PriorityProperties();

    // ── R-7: Schema Registry ─────────────────────────────────────────────────

    /**
     * Schema validation settings.
     * Mapped under {@code racer.schema.*}.
     */
    @Data
    public static class SchemaDefinition {

        /**
         * Classpath or file resource path to a JSON Schema file.
         * E.g. {@code classpath:schemas/orders-v1.json}.
         */
        private String location;

        /**
         * Inline JSON Schema string. Takes precedence over {@link #location}
         * when both are set.
         * E.g. {@code {"type":"object","required":["orderId"]}}.
         */
        private String inline;

        /**
         * Human-readable schema version label, e.g. {@code 1.0}, {@code 2.1-beta}.
         * Not used for validation logic — surfaced via the schema REST API.
         */
        private String version = "1.0";

        /**
         * Optional description surfaced by the schema REST API.
         */
        private String description = "";
    }

    /**
     * Controls when schema validation is applied.
     */
    public enum SchemaValidationMode {
        /** Validate payloads only when publishing. */
        PUBLISH,
        /** Validate payloads only when consuming. */
        CONSUME,
        /** Validate on both publish and consume (default). */
        BOTH
    }

    @Data
    public static class SchemaProperties {

        /**
         * When {@code false} (default) the registry is not started and all publish/consume
         * paths skip validation entirely. Set to {@code true} to activate R-7.
         */
        private boolean enabled = false;

        /**
         * Controls which direction is validated.
         * Defaults to {@link SchemaValidationMode#BOTH}.
         */
        private SchemaValidationMode validationMode = SchemaValidationMode.BOTH;

        /**
         * When {@code true} (default) a schema violation throws
         * {@code SchemaValidationException}, blocking the publish or consume.
         * When {@code false} violations are logged as warnings and processing continues.
         */
        private boolean failOnViolation = true;

        /**
         * Schema definitions keyed by channel alias (matching {@code racer.channels.<alias>})
         * or by literal Redis channel name.
         *
         * <pre>
         * racer.schema.schemas.orders.location=classpath:schemas/orders-v1.json
         * racer.schema.schemas.orders.version=1.0
         * </pre>
         */
        private java.util.Map<String, SchemaDefinition> schemas = new java.util.LinkedHashMap<>();
    }

    /** Schema registry configuration (R-7). */
    private SchemaProperties schema = new SchemaProperties();

    // ── R-11: Polling ──────────────────────────────────────────────────────

    /**
     * Polling properties.
     * Mapped under {@code racer.poll.*}.
     */
    @Data
    public static class PollProperties {

        /**
         * When {@code false}, all {@code @RacerPoll} pollers are silently skipped at startup.
         * Defaults to {@code true} — pollers are active whenever the annotation is present.
         */
        private boolean enabled = true;
    }

    /** Polling configuration (R-11). */
    private PollProperties poll = new PollProperties();

    // ── Pub/Sub concurrency ──────────────────────────────────────────────

    /**
     * Pub/Sub consumer concurrency settings.
     * Mapped under {@code racer.pubsub.*}.
     */
    @Data
    public static class PubSubProperties {

        /**
         * Maximum number of messages processed concurrently by the Pub/Sub
         * consumer ({@code ConsumerSubscriber}). Controls the {@code flatMap}
         * concurrency argument. Set to {@code 256} (Reactor default) for
         * maximum throughput or lower (e.g. {@code 1}) for strictly serial
         * processing. Defaults to {@code 256}.
         */
        private int concurrency = 256;
    }

    /** Pub/Sub consumer concurrency configuration. */
    private PubSubProperties pubsub = new PubSubProperties();

    // ── Web API toggles ──────────────────────────────────────────────────────

    /**
     * Optional web controller toggles.
     * Mapped under {@code racer.web.*}.
     */
    @Data
    public static class WebProperties {

        /** Expose {@code /api/dlq/**} endpoints when true. Default: {@code false}. */
        private boolean dlqEnabled = false;

        /** Expose {@code /api/schema/**} endpoints when true. Default: {@code false}. */
        private boolean schemaEnabled = false;

        /** Expose {@code GET /api/router/rules} and {@code POST /api/router/test} when true. Default: {@code false}. */
        private boolean routerEnabled = false;

        /** Expose {@code GET /api/channels} when true. Default: {@code false}. */
        private boolean channelsEnabled = false;

        /** Expose {@code GET /api/retention/config} when true. Default: {@code false}. */
        private boolean retentionEnabled = false;
    }

    /** Web controller opt-in configuration. */
    private WebProperties web = new WebProperties();

    // ── Retention scheduling ─────────────────────────────────────────────────

    /**
     * When {@code true} (and {@code @EnableScheduling} is active), the
     * {@link com.cheetah.racer.common.service.RacerRetentionService} scheduled job runs
     * on the configured cron expression. Default: {@code false}.
     */
    private boolean retentionEnabled = false;

    // ── Request-reply defaults ───────────────────────────────────────────────

    /**
     * Request-reply configuration.
     * Mapped under {@code racer.request-reply.*}.
     */
    @Data
    public static class RequestReplyProperties {

        /**
         * Default timeout for request-reply operations.
         * Used when {@link com.cheetah.racer.common.annotation.RacerRequestReply#timeout()}
         * is not explicitly specified. Defaults to {@code "5s"}.
         */
        private String defaultTimeout = "5s";
    }

    /** Request-reply configuration. */
    private RequestReplyProperties requestReply = new RequestReplyProperties();
}
