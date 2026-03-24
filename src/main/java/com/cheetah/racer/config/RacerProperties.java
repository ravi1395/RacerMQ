package com.cheetah.racer.config;

import com.cheetah.racer.RedisChannels;
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

        /**
         * When {@code true}, the publisher writes to a Redis Stream (XADD) instead of
         * Pub/Sub (PUBLISH), and {@link com.cheetah.racer.listener.RacerListenerRegistrar}
         * consumes via XREADGROUP — guaranteeing at-least-once delivery even when the
         * consumer was temporarily offline. Default {@code false}.
         */
        private boolean durable = false;

        /**
         * Consumer group name used when {@link #durable} is {@code true}.
         * Defaults to {@code "<alias>-group"} when blank.
         */
        private String durableGroup = "";

        /**
         * Redis Stream key used when {@link #durable} is {@code true}.
         * Defaults to {@code "<channelName>:stream"} when blank.
         */
        private String streamKey = "";
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

    // ── DLQ capacity ─────────────────────────────────────────────────────────

    /**
     * Dead Letter Queue capacity settings.
     * Mapped under {@code racer.dlq.*}.
     */
    @Data
    public static class DlqProperties {

        /**
         * Maximum number of entries allowed in the DLQ Redis list.
         * Once exceeded, the oldest entries are trimmed after each enqueue.
         * Defaults to {@code 10 000}.
         */
        private long maxSize = 10_000;
    }

    /** DLQ capacity configuration. */
    private DlqProperties dlq = new DlqProperties();

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

        // ── Phase 4.1 – Cluster-Aware Publishing ─────────────────────────────

        /**
         * When {@code true} (Phase 4.1), a consistent-hash ring is used to map
         * shard keys to shard indices instead of the simple {@code CRC-16 mod N}
         * strategy.  This allows shards to be added/removed with minimal
         * redistribution.
         */
        private boolean consistentHashEnabled = false;

        /**
         * Number of virtual nodes placed on the hash ring per physical shard.
         * More virtual nodes improve distribution uniformity at the cost of
         * slightly more memory.  Defaults to {@code 150}.
         */
        private int virtualNodesPerShard = 150;

        /**
         * When {@code true} (Phase 4.1), if publishing to the primary shard fails
         * the publisher automatically retries on the next shard in the hash ring.
         * Defaults to {@code true} when consistent-hash is active.
         */
        private boolean failoverEnabled = true;
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

        /**
         * Spring Security integration for Racer web endpoints.
         * Mapped under {@code racer.web.security.*}.
         *
         * <p>When {@code racer.web.security.enabled=true} <em>and</em>
         * {@code spring-boot-starter-security} is on the classpath, Racer registers
         * a {@link org.springframework.security.web.server.SecurityWebFilterChain}
         * that enforces the configured roles on every {@code /api/**} endpoint.
         *
         * <p>The chain is registered with {@link org.springframework.core.annotation.Order}
         * value {@code 99}, leaving order 0-98 free for application-level chains.
         *
         * <p>If you already have your own {@code SecurityWebFilterChain} bean for
         * {@code /api/racer/**} paths you can leave this disabled and secure the
         * routes yourself.
         */
        @Data
        public static class SecurityProperties {

            /**
             * When {@code true}, Racer registers its own
             * {@code SecurityWebFilterChain} protecting {@code /api/**}.
             * Defaults to {@code false} so existing apps are not broken.
             */
            private boolean enabled = false;

            /**
             * Comma-separated Spring Security role names (without the {@code ROLE_}
             * prefix) that are required to call <em>read-only</em> endpoints:
             * {@code GET /api/dlq/**}, {@code GET /api/schema/**},
             * {@code GET /api/router/**}, {@code GET /api/channels/**},
             * {@code GET /api/retention/config}.
             * Defaults to {@code OPS}.
             */
            private String readRole = "OPS";

            /**
             * Comma-separated Spring Security role names (without the {@code ROLE_}
             * prefix) that are required to call <em>mutating</em> endpoints:
             * {@code POST /api/dlq/republish/**}, {@code DELETE /api/dlq/clear},
             * {@code POST /api/retention/trim}.
             * Defaults to {@code ADMIN}.
             */
            private String writeRole = "ADMIN";
        }

        /** Security configuration for Racer web endpoints. */
        private SecurityProperties security = new SecurityProperties();

        // ── Phase 4.4 – Admin UI ─────────────────────────────────────────────

        /**
         * When {@code true} (Phase 4.4), the Racer Admin UI endpoints at
         * {@code /api/admin/**} and the embedded web console at
         * {@code /racer-admin/} are registered.
         * Defaults to {@code false}.
         */
        private boolean adminEnabled = false;
    }

    /** Web controller opt-in configuration. */
    private WebProperties web = new WebProperties();

    // ── Retention scheduling ─────────────────────────────────────────────────

    /**
     * When {@code true} (and {@code @EnableScheduling} is active), the
     * {@link com.cheetah.racer.service.RacerRetentionService} scheduled job runs
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
         * Used when {@link com.cheetah.racer.annotation.RacerRequestReply#timeout()}
         * is not explicitly specified. Defaults to {@code "5s"}.
         */
        private String defaultTimeout = "5s";
    }

    /** Request-reply configuration. */
    private RequestReplyProperties requestReply = new RequestReplyProperties();

    // ── Dedicated listener thread pool ───────────────────────────────────────

    /**
     * Configuration for the dedicated thread pool that executes all
     * {@code @RacerListener} and {@code @RacerStreamListener} handler invocations.
     * Mapped under {@code racer.thread-pool.*}.
     *
     * <p>When not customised, defaults mirror the limits of Reactor's built-in
     * {@code boundedElastic()} scheduler: core={@code 2×CPU}, max={@code 10×CPU},
     * keep-alive=60 s. The dedicated pool prevents listener workloads from competing
     * with other {@code boundedElastic()} consumers in the application.
     *
     * <pre>
     * # application.properties example
     * racer.thread-pool.core-size=8
     * racer.thread-pool.max-size=32
     * racer.thread-pool.queue-capacity=2000
     * racer.thread-pool.keep-alive-seconds=30
     * racer.thread-pool.thread-name-prefix=racer-worker-
     * </pre>
     */
    @Data
    public static class ThreadPoolProperties {

        private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

        /**
         * Number of threads always kept alive, even when idle.
         * Defaults to {@code 2 × availableProcessors()}.
         */
        private int coreSize = 2 * CPU_COUNT;

        /**
         * Maximum number of threads the pool will ever create.
         * This is also the upper ceiling for {@link com.cheetah.racer.annotation.ConcurrencyMode#AUTO}
         * tuning. Defaults to {@code 10 × availableProcessors()}.
         */
        private int maxSize = 10 * CPU_COUNT;

        /**
         * Seconds an idle thread above {@link #coreSize} is kept alive before being
         * terminated. Defaults to {@code 60}.
         */
        private int keepAliveSeconds = 60;

        /**
         * Capacity of the work queue. Requests that arrive when all threads are busy
         * and the queue is full are rejected with a {@code RejectedExecutionException}.
         * Defaults to {@code 1000}.
         */
        private int queueCapacity = 1000;

        /**
         * Prefix for thread names, e.g. {@code "racer-worker-"} produces threads named
         * {@code racer-worker-1}, {@code racer-worker-2}, …
         */
        private String threadNamePrefix = "racer-worker-";
    }

    /** Dedicated listener thread-pool configuration. */
    private ThreadPoolProperties threadPool = new ThreadPoolProperties();

    // ── 3.1 Message Deduplication ────────────────────────────────────────────

    /**
     * Idempotency settings for message deduplication.
     * Mapped under {@code racer.dedup.*}.
     */
    @Data
    public static class DedupProperties {

        /**
         * When {@code false} (default) the dedup service is not registered.
         * Set to {@code true} to enable opt-in deduplication via
         * {@code @RacerListener(dedup = true)}.
         */
        private boolean enabled = false;

        /**
         * How long (in seconds) a processed message ID is remembered.
         * Messages with the same ID that arrive within this window are silently dropped.
         * Defaults to {@code 300} (5 minutes).
         */
        private int ttlSeconds = 300;

        /**
         * Redis key prefix for dedup entries.
         * The full key is {@code <keyPrefix><messageId>}.
         * Defaults to {@code "racer:dedup:"}.
         */
        private String keyPrefix = "racer:dedup:";
    }

    /** Deduplication configuration (3.1). */
    private DedupProperties dedup = new DedupProperties();

    // ── 3.2 Circuit Breaker ──────────────────────────────────────────────────

    /**
     * Built-in circuit breaker settings.
     * Mapped under {@code racer.circuit-breaker.*}.
     */
    @Data
    public static class CircuitBreakerProperties {

        /**
         * When {@code false} (default) no circuit breakers are created.
         * Set to {@code true} to wrap each listener dispatch with a circuit breaker.
         */
        private boolean enabled = false;

        /**
         * Percentage of failures in the sliding window that triggers the OPEN state.
         * Range: {@code 1–100}. Defaults to {@code 50} (50%).
         */
        private float failureRateThreshold = 50.0f;

        /**
         * Number of calls tracked in the count-based sliding window.
         * Defaults to {@code 10}.
         */
        private int slidingWindowSize = 10;

        /**
         * How long (in seconds) the circuit stays OPEN before transitioning to HALF_OPEN.
         * Defaults to {@code 30}.
         */
        private int waitDurationInOpenStateSeconds = 30;

        /**
         * Number of probe calls allowed in the HALF_OPEN state.
         * If all succeed the circuit closes; any failure re-opens it.
         * Defaults to {@code 3}.
         */
        private int permittedCallsInHalfOpenState = 3;
    }

    /** Circuit breaker configuration (3.2). */
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    // ── 3.3 Back-pressure ────────────────────────────────────────────────────

    /**
     * Back-pressure signalling settings.
     * Mapped under {@code racer.backpressure.*}.
     */
    @Data
    public static class BackPressureProperties {

        /**
         * When {@code false} (default) back-pressure monitoring is disabled.
         */
        private boolean enabled = false;

        /**
         * Queue-fill ratio ({@code 0.0–1.0}) above which back-pressure is activated.
         * Defaults to {@code 0.80} (80% full).
         */
        private double queueThreshold = 0.80;

        /**
         * How often (milliseconds) the back-pressure monitor checks the queue fill ratio.
         * Defaults to {@code 1000} ms.
         */
        private long checkIntervalMs = 1_000;

        /**
         * Poll interval (milliseconds) applied to all {@code @RacerStreamListener} loops
         * while back-pressure is active.
         * Defaults to {@code 2000} ms.
         */
        private long streamPollBackoffMs = 2_000;
    }

    /** Back-pressure monitoring configuration (3.3). */
    private BackPressureProperties backpressure = new BackPressureProperties();

    // ── 3.4 Consumer Group Lag ───────────────────────────────────────────────

    /**
     * Consumer-group lag dashboard settings.
     * Mapped under {@code racer.consumer-lag.*}.
     */
    @Data
    public static class ConsumerLagProperties {

        /**
         * When {@code false} (default) lag metrics are not scraped.
         * Requires {@code racer.consumer-lag.enabled=true} AND Micrometer on the classpath.
         */
        private boolean enabled = false;

        /**
         * How often (seconds) XPENDING is issued per (stream, group) pair.
         * Defaults to {@code 15}.
         */
        private int scrapeIntervalSeconds = 15;

        /**
         * Lag value above which a WARN log is emitted on each scrape cycle.
         * Defaults to {@code 1000}.
         */
        private long lagWarnThreshold = 1_000;

        /**
         * Lag value above which the health indicator flips to {@code OUT_OF_SERVICE}.
         * Defaults to {@code 10_000}.  Set to {@code 0} to disable the health-status flip.
         */
        private long lagDownThreshold = 10_000;
    }

    /** Consumer-group lag configuration (3.4). */
    private ConsumerLagProperties consumerLag = new ConsumerLagProperties();

    // ── 4.1 Cluster-Aware Publishing ─────────────────────────────────────────
    //    Extend ShardingProperties below with consistent-hash options.

    // ── 4.2 Distributed Tracing ───────────────────────────────────────────────

    /**
     * W3C-traceparent propagation settings.
     * Mapped under {@code racer.tracing.*}.
     */
    @Data
    public static class TracingProperties {

        /**
         * When {@code false} (default) tracing is not enabled.
         * Set to {@code true} to propagate W3C {@code traceparent} headers across
         * all published and consumed messages.
         */
        private boolean enabled = false;

        /**
         * When {@code true} (default) the {@code traceparent} value is written to
         * MDC under the key {@code "traceparent"} for each consumed message so that
         * log lines are automatically correlated.
         */
        private boolean propagateToMdc = true;

        /**
         * When {@code true} (default) the {@code traceparent} value is embedded in
         * the message envelope JSON, enabling end-to-end trace propagation.
         */
        private boolean injectIntoEnvelope = true;
    }

    /** Distributed-tracing configuration (4.2). */
    private TracingProperties tracing = new TracingProperties();

    // ── 4.3 Rate Limiting ─────────────────────────────────────────────────────

    /**
     * Per-channel token-bucket rate limiting backed by Redis.
     * Mapped under {@code racer.rate-limit.*}.
     */
    @Data
    public static class RateLimitProperties {

        /**
         * When {@code false} (default) rate limiting is disabled globally.
         */
        private boolean enabled = false;

        /**
         * Maximum number of tokens (burst size) per channel bucket.
         * Defaults to {@code 100}.
         */
        private long defaultCapacity = 100;

        /**
         * Number of tokens refilled per second in each channel bucket.
         * Defaults to {@code 100}.
         */
        private long defaultRefillRate = 100;

        /**
         * Redis key prefix for rate-limit buckets.
         * Full key: {@code <keyPrefix><channelAlias>}.
         * Defaults to {@code "racer:ratelimit:"}.
         */
        private String keyPrefix = "racer:ratelimit:";

        /**
         * Per-channel overrides.  Key is the channel alias (or channel name
         * when no alias is configured).
         */
        private java.util.Map<String, ChannelRateLimitProperties> channels = new java.util.LinkedHashMap<>();

        /**
         * Per-channel capacity / refill-rate overrides.
         */
        @Data
        public static class ChannelRateLimitProperties {
            /** Override capacity (-1 = use {@link RateLimitProperties#defaultCapacity}). */
            private long capacity = -1;
            /** Override refill rate (-1 = use {@link RateLimitProperties#defaultRefillRate}). */
            private long refillRate = -1;
        }
    }

    /** Rate-limiting configuration (4.3). */
    private RateLimitProperties rateLimit = new RateLimitProperties();

    // ── 4.4 Admin UI — web properties extended separately in WebProperties ────

    // ── Graceful shutdown ─────────────────────────────────────────────────────

    @Data
    public static class ShutdownProperties {

        /**
         * Maximum time (in seconds) to wait for in-flight messages to finish processing
         * before forcing shutdown of {@code @RacerListener} and {@code @RacerStreamListener}
         * pipelines. Defaults to {@code 30}.
         */
        private int timeoutSeconds = 30;
    }

    /** Graceful-shutdown configuration. */
    private ShutdownProperties shutdown = new ShutdownProperties();
}
