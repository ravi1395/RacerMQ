# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
