# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.2.0] - 2026-03-20

### Added

#### `@PublishResult` — repeatable annotation & per-annotaion priority
- `@PublishResult` is now `@Repeatable` via the new `@PublishResults` container annotation.
  Place multiple `@PublishResult` annotations on one method to fan out to several channels simultaneously:
  ```java
  @PublishResult(channelRef = "orders")
  @PublishResult(channelRef = "audit", async = false)
  public Order createOrder(OrderRequest req) { ... }
  ```
- Added **`priority`** attribute to `@PublishResult` (default `""`). When set, this level is used for that specific annotation's publish instead of any `@RacerPriority(defaultLevel)` on the same method. Enables a single method to fan out to _different_ priority levels per channel:
  ```java
  @PublishResult(channelRef = "orders",  priority = "HIGH")
  @PublishResult(channelRef = "audit",   priority = "LOW")
  @RacerPriority(defaultLevel = "NORMAL")   // fallback when priority="" above
  public Order placeOrder(OrderRequest req) { ... }
  ```
- Added **self-invocation caveat** to `@PublishResult` Javadoc: calling an annotated method via `this.method()` bypasses the Spring AOP proxy and the annotation never fires. The Javadoc now includes a ❌/✅ code example.
- `PublishResultMethodValidator` (`SmartInitializingSingleton`) scans all Spring beans at startup and throws `RacerConfigurationException` if any `@PublishResult`-annotated method has a `void` return type (which would silently publish nothing).

#### Router DSL (`RouteHandlers`) enhancements
- `drop()` now **logs at DEBUG** each discarded message: `[racer-router] DROP id=… channel=… payload=…` (payload truncated to 120 chars). Use this to confirm discard rules are matching.
- New `dropQuietly()` handler — identical to the old silent `drop()`. Use for health-check pings and other expected high-volume discards where the DEBUG noise isn't wanted.
- New `forwardWithPriority(String alias, String level)` handler — publishes the message to the priority sub-channel (`{channel}:priority:{LEVEL}`) for the given alias. Requires `racer.priority.enabled=true`; falls back to standard `forward(alias)` with a WARN log if `RacerPriorityPublisher` is not configured.
- New `priority(String alias, String level, RouteHandler delegate)` composable wrapper — applies `forwardWithPriority` then delegates to the provided inner handler.
- `DefaultRouteContext` (inside `RacerRouterService`) now implements `publishToWithPriority(alias, msg, level)` and accepts an optional `RacerPriorityPublisher`.

#### `RacerTransaction` — reliable JSON serialization in pipelined path
- `TxPublisher`'s pipelined batch path previously called `payload.toString()` on each item, which produced non-JSON output for POJOs. It now uses an injected `ObjectMapper` with a `toString()` fallback on serialisation error.
- `RacerTransaction` gains a new three-argument constructor `(RacerPublisherRegistry, ObjectMapper, RacerPipelinedPublisher)`. Existing two-argument constructors remain for backward compatibility.

#### Health indicator — consumer group lag
- `RacerHealthIndicator` now accepts an optional `RacerConsumerLagMonitor` and exposes all tracked `(streamKey|group → lag)` pairs in the `consumer-lag` health detail map.
- When any lag value exceeds the new `racer.consumer-lag.lag-down-threshold` (default `10000`) the health status flips to `OUT_OF_SERVICE` and a `consumer-lag.threshold-breached: true` detail is added.
- `RacerHealthAutoConfiguration` auto-wires the optional `RacerConsumerLagMonitor` and `RacerProperties` beans into the health indicator.
- New property `racer.consumer-lag.lag-down-threshold=10000` — sets the lag level at which health flips to `OUT_OF_SERVICE`. Set to `0` to disable the health-status flip.

### Changed
- `RacerAutoConfiguration.racerRouterService()` now injects `Optional<RacerPriorityPublisher>` so priority-based routing is available in the functional DSL without any additional configuration in the consumer application.
- `RacerAutoConfiguration.racerTransaction()` now injects `ObjectMapper` to power the reliable pipelined serialization path.
- `dropToDlq()` is now the **recommended** default route handler; its Javadoc has been updated accordingly.

---

## [1.3.0] - 2026-03-24

### Added

#### 4.1 — Cluster-Aware Publishing (Consistent Hash Ring)
- `RacerConsistentHashRing` — virtual-node consistent-hash ring built on a MD5-hashed `TreeMap`; fully immutable after construction and safe for concurrent reads
  - `getShardFor(key)` — primary shard index for a routing key (clockwise ring walk)
  - `getFailoverShardFor(key)` — next distinct shard in the ring (falls back to primary when only one shard exists)
- `RacerShardedStreamPublisher` enhanced with optional `RacerConsistentHashRing` and `failoverEnabled` flag
  - New 4-arg constructor: `(delegate, shardCount, hashRing, failoverEnabled)`
  - `publishToShard()` now transparently retries on the failover shard on error when `failoverEnabled=true`
  - Old `shardFor(key)` method marked `@Deprecated(since = "1.3.0")`; replaced by `primaryShardFor(key)`
- New `racer.sharding.*` properties: `consistent-hash-enabled` (default `false`), `virtual-nodes-per-shard` (default `150`), `failover-enabled` (default `true`)
- `RacerAutoConfiguration` wires consistent-hash ring into `racerShardedStreamPublisher` bean when `racer.sharding.consistent-hash-enabled=true`

#### 4.2 — Distributed Tracing (W3C traceparent)
- `RacerTraceContext` (utility class, no OTel SDK dependency) — W3C `traceparent` header utilities:
  - `generate()` — new root trace (fresh 16-byte trace-id + 8-byte span-id)
  - `child(parent)` — child span inheriting trace-id; generates root when parent is null/invalid
  - `extractTraceId(tp)` / `extractParentId(tp)` — parse individual header components
  - `isValid(tp)` — structural validation (lengths, hex encoding)
- `RacerTracingInterceptor` — `RacerMessageInterceptor` at `@Order(1)`:
  - Stamps `RacerMessage.traceparent` with a generated or child traceparent on every consumed message
  - Writes the value into the Reactor context under key `"racer.traceparent"`
  - Optionally populates MDC key `"traceparent"` (controlled by `racer.tracing.propagate-to-mdc`, default `true`); removes it in `doFinally` to prevent thread-pool leakage
- `RacerMessage` gains field `String traceparent` (nullable)
- `MessageEnvelopeBuilder.buildWithTrace()` — overload that includes `traceparent` in the JSON envelope when non-null
- New `racer.tracing.*` properties: `enabled` (default `false`), `propagate-to-mdc` (default `true`), `inject-into-envelope` (default `true`)
- `RacerAutoConfiguration` registers `racerTracingInterceptor` bean when `racer.tracing.enabled=true`

#### 4.3 — Per-Channel Rate Limiting (Redis token bucket)
- `RacerRateLimiter` — Redis Lua token-bucket rate limiter:
  - `checkLimit(channelAlias)` → `Mono<Void>` (empty = allowed; `RacerRateLimitException` = rejected)
  - Atomically refills bucket based on elapsed time × refill rate; caps at capacity (burst limit)
  - Per-channel overrides via `racer.rate-limit.channels.<alias>.*`
  - **Fail-open**: Redis errors log WARN and allow the request (Redis downtime never cascades to publisher failures)
- `RacerRateLimitException` — new exception extending `RacerException`; carries `channel` field
- `RacerChannelPublisherImpl` — rate-limit check prepended to all 3 `publishAsync` variants when a `RacerRateLimiter` is injected (null → no-op)
- `RacerPublisherRegistry` — accepts `Optional<RacerRateLimiter>` in its 6-arg constructor; threads it through to every `RacerChannelPublisherImpl` created in `init()`
- New `racer.rate-limit.*` properties: `enabled` (default `false`), `default-capacity` (default `100`), `default-refill-rate` (default `100`), `key-prefix` (default `"racer:ratelimit:"`), `channels.<alias>.capacity`, `channels.<alias>.refill-rate`
- `RacerAutoConfiguration` registers `racerRateLimiter` bean when `racer.rate-limit.enabled=true`; passes `Optional<RacerRateLimiter>` to `racerPublisherRegistry`

#### 4.4 — Racer Admin UI
- `RacerAdminController` — REST controller at `/api/admin/**`:
  - `GET /api/admin/overview` — timestamp, channel count, feature-flag summary
  - `GET /api/admin/channels` — all registered channel aliases with resolved Redis channel/stream keys
  - `GET /api/admin/circuitbreakers` — per-breaker state, transition count, rejected count (or `enabled: false` when registry absent)
  - `GET /api/admin/ratelimits` — rate-limit configuration snapshot with effective per-channel overrides
- `src/main/resources/static/racer-admin/index.html` — self-contained Bootstrap 5 admin dashboard; loads all 4 endpoints; auto-refresh; feature pill badges; toast error notifications
- New `racer.web.admin-enabled` property (default `false`); activates `RacerAdminController` bean in `RacerWebAutoConfiguration`

### Changed
- `RacerMessage` now has `@Builder(toBuilder = true)` — existing `@Builder` usage is unchanged; the new `toBuilder()` method enables immutable copy-mutation (used by `RacerTracingInterceptor`)
- `RacerAutoConfiguration.racerPublisherRegistry()` bean now injects `Optional<RacerRateLimiter>` parameter
- `RacerWebAutoConfiguration` imports and registers `RacerAdminController` when `racer.web.admin-enabled=true`

---

## [1.1.0] - 2026-03-06

### Added

#### 3.1 — Message Deduplication
- `RacerDedupService` — idempotency via Redis `SET NX EX`; fails-open on Redis errors
- `@RacerListener(dedup = true)` — per-listener opt-in to dedup (requires `racer.dedup.enabled=true`)
- `racer.dedup.*` properties: `enabled`, `ttl-seconds` (default 300s), `key-prefix` (default `racer:dedup:`)

#### 3.2 — Circuit Breaker
- `RacerCircuitBreaker` — count-based sliding-window circuit breaker (no Resilience4j dependency)
  - States: `CLOSED` → `OPEN` → `HALF_OPEN` → `CLOSED`
  - Transitions: opens when failure rate ≥ threshold after window fills; re-opens on probe failure
- `RacerCircuitBreakerRegistry` — per-listener lazy circuit breaker registry
- `racer.circuit-breaker.*` properties: `enabled`, `failure-rate-threshold` (default 50%), `sliding-window-size` (default 10), `wait-duration-in-open-state-seconds` (default 30), `permitted-calls-in-half-open-state` (default 3)
- Circuit breaker applied to both `@RacerListener` and `@RacerStreamListener` dispatch pipelines

#### 3.3 — Back-pressure Signaling
- `RacerBackPressureMonitor` — monitors the Racer thread-pool queue fill ratio
  - Activates when fill ratio ≥ `racer.backpressure.queue-threshold`
  - Pauses Pub/Sub dispatch (`RacerListenerRegistrar.setBackPressureActive(true)`)
  - Slows XREADGROUP poll rate to `racer.backpressure.stream-poll-backoff-ms`
  - Reverses both when the queue drains
- `racer.backpressure.*` properties: `enabled`, `queue-threshold` (default 0.80), `check-interval-ms` (default 1000), `stream-poll-backoff-ms` (default 2000)
- Stream poll interval made fully dynamic — `RacerStreamListenerRegistrar` honours runtime overrides

#### 3.4 — Consumer Group Lag Dashboard
- `RacerConsumerLagMonitor` — periodic `XPENDING` scraper; exports one `racer.stream.consumer.lag` Micrometer gauge per (stream, group) pair
- WARN log when lag exceeds `racer.consumer-lag.lag-warn-threshold`
- `racer.consumer-lag.*` properties: `enabled`, `scrape-interval-seconds` (default 15), `lag-warn-threshold` (default 1000)

#### Infrastructure
- `racerListenerExecutor` exposed as a named `ThreadPoolExecutor` bean
- Thread-pool metrics (`racer.thread-pool.*`) registered automatically when Micrometer is present
- All Phase 3 beans are **off by default** via `@ConditionalOnProperty` — zero impact on existing deployments

#### Observability Polish
- `racer.circuit.breaker.state{listener}` Micrometer gauge — numeric state per listener: `0` = CLOSED, `1` = OPEN, `2` = HALF_OPEN
- `racer.backpressure.active` Micrometer gauge — `1` while back-pressure is in effect, `0` otherwise (live value, not snapshot)
- `racer.backpressure.events{state}` Micrometer counter — increments on each activation (`state=active`) and deactivation (`state=inactive`) transition
- `racer.dedup.duplicates{listener}` Micrometer counter — counts duplicate messages suppressed per listener

#### Graceful Shutdown (`SmartLifecycle`)
- `RacerListenerRegistrar` now implements `SmartLifecycle` (`phase = Integer.MAX_VALUE`) — unsubscribes from channels only after in-flight dispatches complete
- `RacerStreamListenerRegistrar` now implements `SmartLifecycle` — stops polling loops after pending stream-record processing drains
- In-flight request count tracked with `AtomicInteger`; `stop(Runnable)` is fully async (non-blocking via `boundedElastic`) so Spring's shutdown thread is never blocked
- Stopping gate added to both `dispatch()` and `processRecord()` — new messages are rejected gracefully when shutdown is in progress, not mid-stream
- `racer.shutdown.timeout-seconds` (default `30`) controls the maximum drain wait before forcing disposal

### Changed
- `@RacerListener` gains a `dedup` boolean attribute (default `false`)
- `RacerAutoConfiguration` wires optional dedup, circuit breaker, and back-pressure beans into both listener registrars via setter injection
- `RacerDedupService` now accepts an optional `RacerMetrics` reference; `checkAndMarkProcessed` overloaded with `(messageId, listenerId)` for per-listener duplicate counters
- `RacerCircuitBreakerRegistry` now accepts an optional `RacerMetrics` reference; registers the state gauge lazily on first `getOrCreate` call

---

## [1.0.0] - 2026-03-05

### Added

#### Core Messaging
- `@RacerListener` — Pub/Sub listener annotation with `SEQUENTIAL`, `CONCURRENT`, and `AUTO` concurrency modes
- `@RacerPublisher` — field-injectable publisher bean; supports `channel`, `sender`, `async`, and `ttl` properties
- `@EnableRacer` — entry-point annotation to activate all Racer infrastructure
- `RacerMessage` — envelope model with `id`, `sender`, `payload`, and `timestamp` fields
- `RacerPublisherRegistry` — runtime registry of all declared `@RacerPublisher` fields
- `RacerPublisherFieldProcessor` — BeanPostProcessor that injects `RacerChannelPublisher` instances

#### Redis Streams
- `@RacerStreamListener` — consumer-group stream listener backed by `XREADGROUP`
- `RacerStreamListenerRegistrar` — registers and starts all `@RacerStreamListener` subscriptions
- `RacerStreamPublisher` — typed `XADD` publisher for Redis Streams
- `RacerShardedStreamPublisher` — sharded stream publishing with configurable shard count
- `RacerRetentionService` — scheduled `XTRIM MAXLEN` to cap stream length

#### Request / Reply
- `@RacerClient` — declarative request/reply interface injected via `@EnableRacerClients`
- `@RacerResponder` — annotates a method as the reply handler for a request channel
- `RacerClientFactoryBean` — dynamic proxy implementing `@RacerClient` interfaces
- `RacerClientRegistrar` — scans for `@EnableRacerClients` and registers client beans
- `RacerResponderRegistrar` — wires responder methods to their reply channels
- `RacerRequest` / `RacerReply` — request and reply envelope models
- `RacerRequestReplyException` / `RacerRequestReplyTimeoutException` — typed exceptions

#### Content-Based Routing
- `@RacerRoute` — marks a class as a content-based router
- `@RacerRouteRule` — defines a SpEL condition + target channel for routing rules
- `RacerRouterService` — evaluates rules and dispatches messages to target channels

#### Dead Letter Queue
- `@RacerListener(dlq = ...)` — automatic DLQ routing on listener exception
- `DeadLetterQueueService` — enqueues failed messages with original channel and error details
- `DlqReprocessorService` — re-publishes individual or all DLQ entries back to origin channel
- `DeadLetterMessage` — DLQ envelope model with `originalChannel`, `errorMessage`, and `retryCount`
- `DlqController` — REST endpoints: `GET /api/dlq`, `POST /api/dlq/republish/one`, `POST /api/dlq/republish/all`

#### Priority Messaging
- `@RacerPriority` — specifies priority level (`HIGH`, `MEDIUM`, `LOW`) on a publisher or listener
- `PriorityLevel` — enum defining priority sub-channel suffix strategy
- `RacerPriorityPublisher` — publishes to priority-ranked sub-channels

#### Polling
- `@RacerPoll` — triggers a method on a configurable interval to publish messages
- `RacerPollRegistrar` — registers polling tasks with a dedicated scheduler

#### AOP Side-Effect Publishing
- `@PublishResult` — publishes a method's return value to a channel as a side effect
- `PublishResultAspect` — AOP advice that intercepts `@PublishResult` methods; inherits `sender` and `async` from the declaring `@RacerPublisher` field

#### Schema Validation
- `@RacerListener(schema = ...)` — validates incoming message payload against a JSON Schema
- `RacerSchemaRegistry` — loads and caches JSON Schema definitions
- `SchemaValidationException` / `SchemaViolation` — typed schema error models

#### Transactions
- `RacerTransaction` — groups multiple publishes into a single Redis `MULTI`/`EXEC` pipeline

#### Pipelining
- `RacerPipelinedPublisher` — sends a batch of messages in a single Redis pipeline round-trip

#### Adaptive Concurrency
- `ConcurrencyMode.AUTO` — AIMD adaptive concurrency tuner per listener
- `AdaptiveConcurrencyTuner` — increases permits on success, decreases on error; configurable `min`, `max`, `increment`, `decrement` via `racer.auto.*`

#### Observability
- `RacerMetrics` — Micrometer integration: `racer.listener.processed`, `racer.listener.failed` counters; thread pool gauges (`queue-depth`, `active-threads`, `pool-size`); `racer.stream.consumer.lag` gauge; `racer.auto.concurrency` gauge per listener
- `RacerHealthIndicator` — Spring Boot Actuator `ReactiveHealthIndicator` reporting Redis connectivity, active subscription count, DLQ depth (warn threshold via `racer.health.dlq-warn-threshold`), and thread pool utilization

#### Exception Hierarchy
- `RacerException` — base `RuntimeException` for all Racer errors
- `RacerPublishException` — failure publishing to Redis
- `RacerListenerException` — failure dispatching to a listener method
- `RacerDlqException` — failure enqueuing to the Dead Letter Queue
- `RacerConfigurationException` — startup-time misconfiguration

#### Configuration & Auto-Configuration
- `RacerProperties` — externalized configuration under `racer.*`; thread pool (`racer.thread-pool.*`), auto-concurrency (`racer.auto.*`), health (`racer.health.*`), stream consumer (`racer.consumer.*`)
- `RacerAutoConfiguration` — Spring Boot 3 auto-configuration registered via `AutoConfiguration.imports`
- `RacerWebAutoConfiguration` — optional web layer auto-configuration (conditional on WebFlux)
- `RacerHealthAutoConfiguration` — health indicator auto-configuration (conditional on Actuator)
- `spring-boot-configuration-processor` — generates `META-INF/spring-configuration-metadata.json` for IDE autocomplete on `racer.*` properties

#### REST API
- `ChannelRegistryController` — `GET /api/channels` lists all registered listener channels

#### Integration Tests
- `RacerIntegrationTestBase` — base class that starts a Redis container via Docker CLI and wires a full Spring context
- `PubSubIntegrationTest` — end-to-end pub/sub tests (SEQUENTIAL, CONCURRENT, AUTO modes)
- `DlqIntegrationTest` — DLQ routing on exception and `/api/dlq/republish/one` round-trip
- `StreamListenerIntegrationTest` — XADD → XREADGROUP consumer group delivery
- `RequestReplyIntegrationTest` — `@RacerClient` + `@RacerResponder` round-trip

#### Documentation & Examples
- `README.md` — full feature overview and quick-start guide
- `RACER-PROPERTIES.md` — complete reference for all `racer.*` configuration properties
- `TUTORIALS.md` — 20 step-by-step tutorials covering every feature
- `TUTORIAL-NEW-APP.md` — guide for integrating Racer into a new Spring Boot 3 application
- Docker Compose files: `compose.yaml` (standalone), `compose.sentinel.yaml` (Redis Sentinel), `compose.cluster.yaml` (Redis Cluster)

---

## [Unreleased]

<!-- Features merged to main but not yet released go here -->
