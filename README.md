# Racer — Reactive Redis Messaging

A Spring Boot library for annotation-driven reactive Redis messaging. Define publishers, subscribers, request-reply responders, and durable stream consumers with simple annotations — no boilerplate infrastructure code required.

- **Annotation-Driven Publishing** — `@EnableRacer`, `@RacerPublisher`, `@PublishResult`, `@RacerPriority` for declarative, property-driven publishing
- **Declarative Pub/Sub Consumers** — `@RacerListener` turns any Spring method into a Redis Pub/Sub subscriber with `SEQUENTIAL` or `CONCURRENT` processing, schema validation, router integration, and automatic DLQ on failure
- **Durable Stream Consumers** — `@RacerStreamListener` registers a Redis Streams consumer group reader directly on any Spring bean method, with configurable concurrency and batch size
- **Annotation-Driven Request/Reply** — `@RacerResponder` marks any method as a request handler; `@RacerClient` interfaces generate proxy callers that send requests and await typed replies
- **Dead Letter Queue (DLQ)** — automatic enqueue on failure; opt-in REST API (`racer.web.dlq-enabled=true`) for inspection and republishing
- **Multiple Channels** — declare unlimited named channels in `application.properties`
- **Durable Publishing** — `@PublishResult(durable = true)` writes to Redis Streams for at-least-once delivery
- **Content-Based Router** — annotation style (`@RacerRoute` / `@RacerRouteRule`) and functional DSL (`RacerFunctionalRouter` builder with `RoutePredicates` / `RouteHandlers`); regex-pattern matching on payload fields, sender, or message ID; native multi-alias fan-out via `multicast`; composable predicates (`.and()`, `.or()`, `.negate()`); `RouteAction` controls FORWARD / FORWARD\_AND\_PROCESS / DROP / DROP\_TO\_DLQ; method-level `@RacerRoute` on `@RacerListener` handlers; `@Routed` boolean parameter injection; `RacerMessageInterceptor` SPI; opt-in REST API (`racer.web.router-enabled=true`)
- **Atomic Batch Publish** — `RacerTransaction.execute()` for ordered multi-channel publish
- **Pipelined Batch Publish** — `RacerPipelinedPublisher` issues all commands in parallel for maximum throughput
- **Consumer Scaling** — configurable concurrency per stream via `@RacerStreamListener(concurrency=N)` and key-based sharding via `RacerShardedStreamPublisher`
- **Cluster-Aware Publishing** — consistent-hash shard routing via `RacerConsistentHashRing`; automatic failover across shards (v1.3)
- **Message Priority** — `RacerPriorityPublisher` routes messages to `HIGH`/`NORMAL`/`LOW` sub-channels
- **Distributed Tracing** — W3C `traceparent` propagation via `RacerTraceContext` and `RacerTracingInterceptor`; MDC integration for correlated logging (v1.3)
- **Per-Channel Rate Limiting** — Redis token-bucket via `RacerRateLimiter`; fail-open on Redis errors; per-channel overrides (v1.3)
- **Admin UI** — live dashboard at `/racer-admin/` backed by `RacerAdminController`; overview, channel stats, circuit breaker state, and rate limit info (v1.3)
- **Micrometer Metrics** — Prometheus/Actuator instrumentation for published/consumed/failed/DLQ/latency counters
- **Retention Service** — scheduled `XTRIM` + DLQ age-based eviction; opt-in REST API (`racer.web.retention-enabled=true`)
- **High Availability** — Sentinel and Cluster Docker Compose topologies included

> **Building a new service?** Follow the **[New App from Scratch →](TUTORIAL-NEW-APP.md)** guide for a complete end-to-end walkthrough.
> **Want feature-level tutorials?** Browse the **[Tutorials →](TUTORIALS.md)** for step-by-step walkthroughs of every feature.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Project Structure](#project-structure)
3. [Prerequisites & Setup](#prerequisites--setup)
4. [Running the Application](#running-the-application)
5. [Configuration Reference](#configuration-reference)
6. [Racer Annotations](#racer-annotations)
   - [@EnableRacer](#enableracer)
   - [@RacerPublisher — field injection](#racerpublisher--field-injection)
   - [@PublishResult — method-level auto-publish](#publishresult--method-level-auto-publish)
   - [@RacerRoute — content-based routing](#racerroute--content-based-routing)
   - [@RacerPriority — message priority routing](#racerpriority--message-priority-routing)
   - [@RacerPoll — scheduled publishing](#racerpoll--scheduled-publishing)
   - [@RacerListener — declarative Pub/Sub consumers](#racerlistener--declarative-channel-consumers)
   - [@RacerStreamListener — durable stream consumers](#racerstreamlistener--durable-stream-consumers)
   - [@RacerResponder — request-reply responder](#racerresponder--request-reply-responder)
   - [@RacerClient / @RacerRequestReply — request-reply caller](#racerclient--racerrequestreply--request-reply-caller)
   - [@EnableRacerClients](#enableracerclients)
   - [Multi-channel configuration](#multi-channel-configuration)
7. [Redis Keys & Channels Reference](#redis-keys--channels-reference)
8. [Message Schemas](#message-schemas)
9. [API Reference (port 8080, opt-in)](#api-reference-port-8080-opt-in)
   - [DLQ APIs](#dlq-apis)
   - [Retention APIs](#retention-apis)
   - [Router APIs](#router-apis)
   - [Channel Registry APIs](#channel-registry-apis)
   - [Schema APIs](#schema-apis)
   - [Admin UI APIs](#admin-ui-apis)
10. [Observability & Metrics](#observability--metrics)
11. [High Availability](#high-availability)
12. [Consumer Scaling & Sharding](#consumer-scaling--sharding)
13. [Pipelined Publishing](#pipelined-publishing)
14. [Message Priority](#message-priority)
15. [Cluster-Aware Publishing](#cluster-aware-publishing)
16. [Distributed Tracing](#distributed-tracing)
17. [Per-Channel Rate Limiting](#per-channel-rate-limiting)
18. [End-to-End Flows](#end-to-end-flows)
19. [Extending the Application](#extending-the-application)
20. [Error Handling & DLQ Behaviour](#error-handling--dlq-behaviour)
21. [Comparison with Other Brokers](#comparison-with-other-brokers)
    - [Architecture at a Glance](#architecture-at-a-glance)
    - [Advantages of Racer](#advantages-of-racer)
    - [Disadvantages & Mitigations](#disadvantages--mitigations)
    - [When to Use What](#when-to-use-what)
22. [Roadmap & Implementation Status](#roadmap--implementation-status)
23. [Tutorials](TUTORIALS.md) *(separate file)*

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                   REDIS                                       │
│                                                                               │
│  Pub/Sub channels          Streams (durable)          Lists                  │
│  ─────────────────         ─────────────────────      ─────────────────      │
│  racer:messages            racer:stream:requests       racer:dlq             │
│  racer:notifications       racer:stream:response:*                           │
│  racer:reply:*             racer:<name>:stream  ←── @PublishResult(durable)  │
│  racer:<channel>  ←─ @RacerRoute routes here                                 │
└──────────┬─────────────────────────┬──────────────────────────┬─────────────┘
           │ pub/sub                 │ streams                   │ list ops
           │                         │                           │
 ┌─────────┴─────────────────────────┴───────────────────────────┴─────────────┐
 │                            racer-demo :8080                                  │
 │                                                                               │
 │  @RacerPublisher / @PublishResult  → fire-and-forget Pub/Sub or XADD        │
 │  @RacerPoll                        → scheduled publishing                    │
 │  @RacerListener                    → Pub/Sub subscriber (SEQUENTIAL/CONCURRENT)
 │  @RacerStreamListener              → XREADGROUP consumer group               │
 │  @RacerResponder                   → request-reply handler (Pub/Sub or Stream)
 │  @RacerClient proxy                → sends requests, awaits typed replies     │
 │  RacerRouterService                → content-based routing                   │
 │  DeadLetterQueueService            → DLQ enqueue on failure                  │
 │  RacerRetentionService             → scheduled XTRIM + DLQ age pruning       │
 │                                                                               │
 │  Opt-in REST APIs (racer.web.*-enabled=true):                                │
 │    /api/dlq/**          racer.web.dlq-enabled=true                           │
 │    /api/retention/**    racer.web.retention-enabled=true                     │
 │    /api/router/**       racer.web.router-enabled=true                        │
 │    GET /api/channels    racer.web.channels-enabled=true                      │
 │    /api/schema/**       racer.web.schema-enabled=true                        │
 │    /api/admin/**        racer.web.admin-enabled=true  (v1.3)                 │
 └───────────────────────────────────────────────────────────────────────────────┘

Metrics: RacerMetrics (Micrometer) wired into all publish/consume/DLQ paths
         → exposed via /actuator/metrics and /actuator/prometheus on port 8080
```

| Module | Role | Port |
|--------|------|----- |
| `racer` | Library: annotations, models, auto-configuration, web controllers | — |
| `racer-demo` | Standalone demo app combining publisher + consumer + responder + client | 8080 |

---

## Project Structure

```
racer/                                   # Library (single-module Maven project)
├── pom.xml                              # Library POM (groupId: com.cheetah, artifactId: racer)
├── compose.yaml                         # Docker Compose (single Redis)
├── compose.sentinel.yaml                # High-availability: Sentinel mode
├── compose.cluster.yaml                 # High-availability: Cluster mode
└── src/
    ├── main/
    │   ├── java/com/cheetah/racer/
    │   │   ├── RedisChannels.java       # Channel/key constants
    │   │   ├── annotation/
    │   │   │   ├── EnableRacer.java             # Activates the annotation framework
    │   │   │   ├── EnableRacerClients.java      # Enables @RacerClient scanning
    │   │   │   ├── RacerPublisher.java          # Field injection annotation
    │   │   │   ├── PublishResult.java           # Method auto-publish (+ durable mode)
    │   │   │   ├── RacerRoute.java              # Content-based routing: @Target(TYPE, METHOD)
    │   │   │   ├── RacerRouteRule.java          # Per-rule: field, matches, to, sender, source, action
    │   │   │   ├── RouteAction.java             # FORWARD / FORWARD_AND_PROCESS / DROP / DROP_TO_DLQ
    │   │   │   ├── RouteMatchSource.java        # PAYLOAD (default) / SENDER / ID
    │   │   │   ├── Routed.java                  # @Parameter: injects wasForwarded boolean into handler
    │   │   │   ├── ConcurrencyMode.java         # SEQUENTIAL / CONCURRENT / AUTO dispatch enum
    │   │   │   ├── RacerListener.java           # Declarative Pub/Sub subscriber
    │   │   │   ├── RacerStreamListener.java     # Durable Redis Streams consumer
    │   │   │   ├── RacerResponder.java          # Request-reply handler annotation
    │   │   │   ├── RacerClient.java             # Interface marker for proxy generation
    │   │   │   └── RacerRequestReply.java       # Interface method: declare request-reply call
    │   │   ├── aspect/
    │   │   │   └── PublishResultAspect.java     # AOP: pub/sub OR durable stream
    │   │   ├── config/
    │   │   │   ├── RedisConfig.java                  # ReactiveRedisTemplate beans
    │   │   │   ├── RacerAutoConfiguration.java        # Wires all beans
    │   │   │   ├── RacerWebAutoConfiguration.java     # Wires opt-in web controllers
    │   │   │   └── RacerProperties.java               # racer.* property binding
    │   │   ├── listener/
    │   │   │   ├── AbstractRacerRegistrar.java        # Base BeanPostProcessor + SmartLifecycle for listener registrars
    │   │   │   ├── RacerDeadLetterHandler.java        # SPI: forward failed msgs to DLQ
    │   │   │   ├── RacerListenerRegistrar.java        # BeanPostProcessor for @RacerListener
    │   │   │   ├── RacerMessageInterceptor.java       # @FunctionalInterface SPI: intercept messages before handler dispatch
    │   │   │   └── InterceptorContext.java            # record(listenerId, channel, method) — passed to each interceptor
    │   │   ├── metrics/
    │   │   │   ├── RacerMetrics.java                  # Micrometer counters/timers/gauges (implements RacerMetricsPort)
    │   │   │   ├── RacerMetricsPort.java              # SPI: metrics abstraction — implement to provide custom instrumentation
    │   │   │   └── NoOpRacerMetrics.java              # No-op implementation used when RacerMetrics bean is absent
    │   │   ├── model/
    │   │   │   ├── RacerMessage.java     # Fire-and-forget message
    │   │   │   ├── RacerRequest.java     # Request-reply request
    │   │   │   ├── RacerReply.java       # Request-reply response
    │   │   │   └── DeadLetterMessage.java
    │   │   ├── processor/
    │   │   │   └── RacerPublisherFieldProcessor.java  # BeanPostProcessor for @RacerPublisher
    │   │   ├── publisher/
    │   │   │   ├── MessageEnvelopeBuilder.java        # Static utility: builds serialised JSON message envelopes
    │   │   │   ├── RacerChannelPublisher.java         # Publisher interface
    │   │   │   ├── RacerChannelPublisherImpl.java     # Pub/Sub implementation (+ metrics)
    │   │   │   ├── RacerPublisherRegistry.java        # Multi-channel registry
    │   │   │   ├── RacerShardedStreamPublisher.java   # Key-based shard publisher (CRC-16)
    │   │   │   ├── RacerConsistentHashRing.java       # Consistent-hash ring for cluster-aware publishing (v1.3)
    │   │   │   └── RacerStreamPublisher.java          # Durable stream publisher (XADD)
    │   │   ├── requestreply/
    │   │   │   ├── RacerResponderRegistrar.java       # BeanPostProcessor for @RacerResponder
    │   │   │   ├── RacerClientRegistrar.java          # ImportBeanDefinitionRegistrar for @RacerClient
    │   │   │   └── RacerClientFactoryBean.java        # JDK dynamic proxy FactoryBean
    │   │   ├── router/
    │   │   │   ├── CompiledRouteRule.java             # Compiled, regex-ready rule (record)
    │   │   │   ├── RouteDecision.java                 # PASS / FORWARDED / FORWARDED_AND_PROCESS / DROPPED / DROPPED_TO_DLQ
    │   │   │   ├── RacerRouterService.java            # compile() / evaluate() / route(); annotation + DSL routers
    │   │   │   └── dsl/
    │   │   │       ├── RoutePredicate.java            # @FunctionalInterface with .and()/.or()/.negate()
    │   │   │       ├── RouteHandler.java              # @FunctionalInterface returning RouteDecision
    │   │   │       ├── RouteContext.java              # Bridge: publishTo(alias, msg)
    │   │   │       ├── FunctionalRouteEntry.java      # Record pairing predicate + handler
    │   │   │       ├── RoutePredicates.java           # Static predicate factories
    │   │   │       ├── RouteHandlers.java             # Static handler factories (forward/multicast/drop)
    │   │   │       └── RacerFunctionalRouter.java     # Builder-style router bean; evaluated by RacerRouterService
    │   │   ├── service/
    │   │   │   ├── DeadLetterQueueService.java        # DLQ enqueue + republish
    │   │   │   ├── DlqReprocessorService.java         # Republish-only DLQ reprocessor
    │   │   │   └── RacerRetentionService.java         # Scheduled XTRIM + DLQ age pruning
    │   │   ├── stream/
    │   │   │   ├── RacerStreamListenerRegistrar.java  # BeanPostProcessor for @RacerStreamListener
    │   │   │   └── RacerStreamUtils.java              # Static utility: XGROUP CREATE (ensureGroup) + XACK (ackRecord)
    │   │   ├── tracing/
    │   │   │   ├── RacerTraceContext.java             # W3C traceparent propagation context (v1.3)
    │   │   │   └── RacerTracingInterceptor.java       # @Order(1) interceptor: MDC propagation for correlated logging (v1.3)
    │   │   ├── ratelimit/
    │   │   │   ├── RacerRateLimiter.java              # Redis token-bucket rate limiter (v1.3)
    │   │   │   └── RacerRateLimitException.java       # Thrown when rate limit is exceeded (v1.3)
    │   │   ├── tx/
    │   │   │   └── RacerTransaction.java              # Atomic ordered multi-channel publish
    │   │   ├── util/
    │   │   │   └── RacerChannelResolver.java          # Static utility: resolves channel/stream key from annotation + RacerProperties
    │   │   └── web/
    │   │       ├── DlqController.java                 # Conditional on racer.web.dlq-enabled
    │   │       ├── RetentionController.java           # Conditional on racer.web.retention-enabled
    │   │       ├── RouterController.java              # Conditional on racer.web.router-enabled
    │   │       ├── ChannelRegistryController.java     # Conditional on racer.web.channels-enabled
    │   │       ├── SchemaController.java              # Conditional on racer.web.schema-enabled
    │   │       └── RacerAdminController.java          # Conditional on racer.web.admin-enabled (v1.3)
    │   └── resources/
    │       ├── META-INF/spring/
    │       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       └── static/
    │           └── racer-admin/
    │               └── index.html                         # Bootstrap 5 Admin UI dashboard (v1.3)
    └── test/java/com/cheetah/racer/
        └── (unit tests)

../racer-demo/                           # Standalone demo application (separate project)
└── src/main/java/com/cheetah/racer/demo/
    ├── RacerDemoApplication.java   # @EnableRacer @EnableRacerClients
    ├── client/
    │   └── DemoClient.java          # @RacerClient interface with @RacerRequestReply
    ├── config/
    │   └── RedisListenerConfig.java # ReactiveRedisMessageListenerContainer
    ├── listener/
    │   └── DemoMessageListener.java # @RacerListener, @RacerStreamListener examples
    ├── poller/
    │   └── DemoPoller.java          # @RacerPoll example
    ├── publisher/
    │   └── DemoPublisher.java       # @PublishResult, @RacerPublisher examples
    ├── responder/
    │   └── DemoResponder.java       # @RacerResponder example
    └── router/
        └── DemoRouter.java          # @RacerRoute example
```

---

## Prerequisites & Setup

| Requirement | Version |
|-------------|---------|
| Java | 21 (JDK 25 is installed but Lombok is incompatible) |
| Maven | 3.9+ |
| Redis | 7+ |
| Docker | Optional (for Redis via Compose) |

### Start Redis

**Via Docker Compose (recommended):**
```bash
docker compose -f compose.yaml up -d
```

**Via Homebrew:**
```bash
brew install redis
brew services start redis
```

**Verify Redis is up:**
```bash
redis-cli ping
# Expected: PONG
```

---

## Running the Application

Always set `JAVA_HOME` to JDK 21 before running.

### Step 1 — Build and install the library

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean install -DskipTests
```

Expected output:
```
[INFO] racer .............................................. SUCCESS
[INFO] BUILD SUCCESS
```

### Step 2 — Start the demo application

From the `../racer-demo/` directory:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Or via jar:
```bash
java -jar target/racer-demo-0.0.1-SNAPSHOT.jar
```

The application starts on **port 8080**. Startup log includes:
```
Started RacerDemoApplication in X.XXX seconds
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
[racer] Channel 'audit'         registered → 'racer:audit'
```

---

## Configuration Reference

### racer-demo (`racer-demo/src/main/resources/application.properties`)

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP port |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `racer.default-channel` | `racer:messages` | Fallback channel used when no alias is given |
| `racer.channels.<alias>.name` | — | Redis channel name for this alias |
| `racer.channels.<alias>.async` | `true` | Default async flag for this channel |
| `racer.channels.<alias>.sender` | `racer` | Default sender label for this channel |
| `racer.durable.stream-keys` | — | Comma-separated stream keys to consume with consumer groups |
| `racer.retention.stream-max-len` | `10000` | Max entries to keep in durable streams (XTRIM) |
| `racer.retention.dlq-max-age-hours` | `72` | DLQ entries older than this are pruned |
| `racer.retention.schedule-cron` | `0 0 * * * *` | Cron for automatic retention runs (hourly by default) |
| `racer.retention-enabled` | `false` | Enable the scheduled retention service |
| `racer.pipeline.enabled` | `false` | Enable pipelined batch publishing (R-9) |
| `racer.pipeline.max-batch-size` | `100` | Maximum messages per pipelined batch (R-9) |
| `racer.priority.enabled` | `false` | Enable priority sub-channel publishing/consuming (R-10) |
| `racer.priority.levels` | `HIGH,NORMAL,LOW` | Comma-separated priority level names, highest first (R-10) |
| `racer.priority.strategy` | `strict` | Drain strategy: `strict` or `weighted` (R-10) |
| `racer.priority.channels` | — | Comma-separated channel aliases eligible for priority routing (R-10) |
| `racer.sharding.enabled` | `false` | Enable key-based stream sharding (R-8) |
| `racer.sharding.shard-count` | `4` | Number of shard suffixes: `stream:0` … `stream:N-1` (R-8) |
| `racer.sharding.streams` | — | Comma-separated base stream keys to shard (R-8) |
| `racer.sharding.consistent-hash-enabled` | `false` | Use `RacerConsistentHashRing` for consistent-hash shard routing (v1.3) |
| `racer.sharding.virtual-nodes-per-shard` | `150` | Virtual nodes per shard in the hash ring — higher = more even distribution (v1.3) |
| `racer.sharding.failover-enabled` | `true` | Automatically route to the next shard when the primary shard is unavailable (v1.3) |
| `racer.tracing.enabled` | `false` | Enable W3C `traceparent` propagation via `RacerTracingInterceptor` (v1.3) |
| `racer.tracing.propagate-to-mdc` | `true` | Copy `traceparent` to SLF4J MDC for correlated log lines (v1.3) |
| `racer.tracing.inject-into-envelope` | `true` | Stamp `traceparent` on outbound `RacerMessage` envelopes (v1.3) |
| `racer.rate-limit.enabled` | `false` | Enable Redis token-bucket per-channel rate limiting (v1.3) |
| `racer.rate-limit.default-permits-per-second` | `100` | Default token refill rate (requests/second) for all channels (v1.3) |
| `racer.rate-limit.default-burst-size` | `200` | Default burst capacity (maximum token bucket size) (v1.3) |
| `racer.rate-limit.channels.<alias>.permits-per-second` | — | Per-channel override for refill rate (v1.3) |
| `racer.rate-limit.channels.<alias>.burst-size` | — | Per-channel override for burst capacity (v1.3) |
| `racer.pubsub.concurrency` | `256` | Max in-flight Pub/Sub messages processed concurrently (R-11) |
| `racer.poll.enabled` | `true` | Enable/disable all `@RacerPoll` pollers (R-11) |
| `racer.request-reply.default-timeout` | `30s` | Default timeout for `@RacerRequestReply` calls |
| `racer.thread-pool.core-size` | `2×CPU` | Core threads in the dedicated Racer listener thread pool |
| `racer.thread-pool.max-size` | `10×CPU` | Maximum threads; also caps `ConcurrencyMode.AUTO` ceiling |
| `racer.thread-pool.queue-capacity` | `1000` | Bounded task queue depth for the Racer thread pool |
| `racer.thread-pool.keep-alive-seconds` | `60` | Idle thread timeout (seconds) above `core-size` |
| `racer.thread-pool.thread-name-prefix` | `racer-worker-` | Thread name prefix — visible in thread dumps and profilers |
| `racer.web.dlq-enabled` | `false` | Expose `/api/dlq/**` REST endpoints |
| `racer.web.retention-enabled` | `false` | Expose `/api/retention/**` REST endpoints |
| `racer.web.router-enabled` | `false` | Expose `/api/router/**` REST endpoints |
| `racer.web.channels-enabled` | `false` | Expose `GET /api/channels` REST endpoint |
| `racer.web.schema-enabled` | `false` | Expose `/api/schema/**` REST endpoints |
| `racer.web.admin-enabled` | `false` | Expose `/api/admin/**` and serve `/racer-admin/` Admin UI dashboard (v1.3) |
| `management.endpoints.web.exposure.include` | `health,info` | Actuator endpoints to expose (add `metrics,prometheus`) |
| `management.metrics.tags.application` | — | Tag all metrics with app name |
| `logging.level.com.cheetah.racer` | `DEBUG` | Log level |

### High-Availability Redis (`application.properties` overrides)

**Sentinel mode:**
```properties
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=localhost:26379,localhost:26380,localhost:26381
# Remove the standalone host/port lines
```

**Cluster mode:**
```properties
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
# Remove the standalone host/port lines
```

---

## Racer Annotations

The annotation module adds a declarative, property-driven publishing layer on top of the reactive Redis infrastructure. Enable it once with `@EnableRacer` and then use field injection or method-level publishing anywhere in your Spring beans.

### `@EnableRacer`

Place on any `@SpringBootApplication` or `@Configuration` class. This single annotation imports `RacerAutoConfiguration` which registers:

| Bean | Purpose |
|------|---------|
| `RacerPublisherRegistry` | Holds one `RacerChannelPublisher` per configured channel alias |
| `PublishResultAspect` | AOP advice that intercepts `@PublishResult` methods |
| `RacerPublisherFieldProcessor` | `BeanPostProcessor` that injects `@RacerPublisher` fields |

```java
@SpringBootApplication
@EnableRacer
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

---

### `@RacerPublisher` — field injection

Annotate any `RacerChannelPublisher` field with the channel alias. The `RacerPublisherFieldProcessor` (a `BeanPostProcessor`) injects the correct publisher before the bean is initialised — **no `@Autowired` needed**.

```java
@Service
public class OrderService {

    @RacerPublisher("orders")         // → racer.channels.orders.name
    private RacerChannelPublisher ordersPublisher;

    @RacerPublisher("notifications")  // → racer.channels.notifications.name
    private RacerChannelPublisher notificationsPublisher;

    @RacerPublisher                   // no alias → racer.default-channel
    private RacerChannelPublisher defaultPublisher;

    public Mono<Void> placeOrder(Order order) {
        return ordersPublisher.publishAsync(order)
                .then(notificationsPublisher.publishAsync("Order placed: " + order.getId()))
                .then();
    }
}
```

**`RacerChannelPublisher` interface**

| Method | Returns | Description |
|--------|---------|-------------|
| `publishAsync(payload)` | `Mono<Long>` | Fire-and-forget; Long = subscriber count |
| `publishAsync(payload, sender)` | `Mono<Long>` | Same, custom sender label |
| `publishSync(payload)` | `Long` | Blocking until Redis confirms |
| `getChannelName()` | `String` | Redis channel name |
| `getChannelAlias()` | `String` | Alias as declared in properties |

---

### `@PublishResult` — method-level auto-publish

Annotate **any Spring-managed method**. The return value is automatically serialised and published to the configured channel as a side-effect. The HTTP caller / calling code receives the original return value unchanged.

```java
// Using a channel alias — sender and async are inherited from racer.channels.orders.*
@PublishResult(channelRef = "orders")
public Mono<Order> createOrder(OrderRequest req) {
    return orderRepository.save(req.toOrder());
}

// Override sender or async per-annotation when you need different values from the channel config
@PublishResult(channelRef = "orders", sender = "checkout-service", async = false)
public Mono<Order> createPriorityOrder(OrderRequest req) {
    return orderRepository.save(req.toOrder());
}

// Using a direct Redis channel name (no alias fallback available)
@PublishResult(channel = "racer:audit", async = false)  // blocking for audit
public AuditRecord recordAudit(AuditEvent event) {
    return auditRepository.save(event.toRecord());
}

// Durable publishing — writes to a Redis Stream instead of Pub/Sub
// The client's RacerStreamConsumerService reads from this stream via consumer groups
@PublishResult(durable = true, streamKey = "racer:orders:stream", sender = "order-service")
public Mono<Order> createDurableOrder(OrderRequest req) {
    return orderRepository.save(req.toOrder());
}

// Works with Flux too — every emitted element is published
@PublishResult(channelRef = "notifications")
public Flux<Notification> broadcastAll() {
    return notificationService.getAll();
}

// Concurrent fan-out — publish up to 8 elements to Redis simultaneously
@PublishResult(channel = "racer:events", mode = ConcurrencyMode.CONCURRENT, concurrency = 8)
public Flux<Event> generateEvents() {
    return eventService.stream();   // each Event is published via flatMap(concurrency=8)
}
```

**Attribute reference**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `channel` | `String` | `""` | Direct Redis channel name (Pub/Sub). Takes priority over `channelRef`. |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>`. |
| `sender` | `String` | `""` | Sender label embedded in the message envelope. When empty (default), falls back to `racer.channels.<alias>.sender` (when `channelRef` is set), then to `"racer-publisher"`. Explicit values always take priority. |
| `async` | `boolean` | `true` | `true` = fire-and-forget; `false` = blocks until Redis confirms. When `channelRef` maps to a configured channel, `racer.channels.<alias>.async` takes precedence over this attribute, allowing the publish mode to be controlled entirely from properties. |
| `durable` | `boolean` | `false` | When `true`, publishes to a **Redis Stream** (XADD) instead of Pub/Sub. |
| `streamKey` | `String` | `""` | The Redis Stream key to write to when `durable=true` (e.g. `racer:orders:stream`). |
| `mode` | `ConcurrencyMode` | `SEQUENTIAL` | Dispatch strategy for `Flux<T>` returns. `SEQUENTIAL` = fire-and-forget `doOnNext`; `CONCURRENT` = `flatMap` with up to `concurrency` in-flight publishes. Ignored for `Mono` and POJO returns. |
| `priority` | `String` | `""` | Priority level for this specific publish (`"HIGH"`, `"NORMAL"`, `"LOW"`). When set, overrides the `@RacerPriority(defaultLevel)` on the same method for this particular channel. Requires `racer.priority.enabled=true`. |
| `concurrency` | `int` | `4` | Maximum concurrent in-flight publish operations when `mode = CONCURRENT`. |

**Resolution order:** `channel` (direct name) → `channelRef` (alias lookup) → default channel (`racer.default-channel`).

**Repeatable:** `@PublishResult` is `@Repeatable` — stack multiple annotations on a single method to fan out to several channels at once:

```java
@PublishResult(channelRef = "orders",  priority = "HIGH")
@PublishResult(channelRef = "audit",   async = false)
public Order createOrder(OrderRequest req) {
    return orderService.create(req);
    // Result published to racer:orders (HIGH priority) AND racer:audit (blocking)
}
```

**Startup safety:** if `@PublishResult` is placed on a `void` method, Racer throws a `RacerConfigurationException` at startup — a `void` return type means there is nothing to publish.

**`sender` resolution chain (when `channelRef` is set):**
1. Annotation `sender` value if non-empty
2. `racer.channels.<alias>.sender` from properties
3. Hardcoded fallback `"racer-publisher"`

**`async` resolution (when `channelRef` is set):**
- If the alias maps to a configured channel, `racer.channels.<alias>.async` overrides the annotation attribute — allowing publish mode to be managed entirely from properties without touching code.
- If the alias is not configured (or `channel` is used directly), the annotation attribute value applies.

**Supported return types:**

| Return type | Behaviour |
|-------------|-----------|
| `Mono<T>` | Taps into the reactive pipeline via `doOnNext` — no blocking |
| `Flux<T>` — `SEQUENTIAL` (default) | Taps every element via `doOnNext` — fire-and-forget, no backpressure |
| `Flux<T>` — `CONCURRENT` | Uses `flatMap(publish, concurrency)` — up to N Redis publishes in flight simultaneously; downstream waits for publish before receiving each element |
| Any POJO / `void` | Published synchronously or asynchronously after return |

> **Important — self-invocation:** The annotated method must be invoked through a **Spring proxy** (i.e. called from a _different_ bean). Calling `this.method()` from within the same class bypasses the AOP proxy and `@PublishResult` **will not fire**.
>
> ```java
> // ❌ Self-invocation — @PublishResult is silently ignored:
> public void caller() { this.createOrder(req); }
>
> // ✅ External invocation — works correctly:
> @Autowired OrderService orderService;
> public void caller() { orderService.createOrder(req); }
> ```
>
> If you must call the annotated method from within the same class, inject the bean via `@Autowired` (self-injection) or use `applicationContext.getBean(MyService.class).createOrder(req)`.

---

### `@RacerRoute` — content-based routing

Apply `@RacerRoute` to a **`@Component`** (or any Spring bean) **or directly to a `@RacerListener` handler method**. At startup `RacerRouterService` scans all beans, compiles the rules, and checks every inbound message against them before dispatching to a processor.

**Type-level router (dedicated router bean):**

```java
@Component
@RacerRoute({
    @RacerRouteRule(field = "type",   matches = "^ORDER.*",        to = "racer:orders"),
    @RacerRouteRule(field = "type",   matches = "^NOTIFICATION.*", to = "racer:notifications"),
    @RacerRouteRule(source = RouteMatchSource.SENDER, field = "",
                    matches = "payment-service",                   to = "racer:payments",
                    action = RouteAction.FORWARD_AND_PROCESS,       sender = "router")
})
public class OrderRouter {
    // no methods required — the annotation does all the work
}
```

**Method-level router (per-listener routing rule):**

```java
@Component
public class OrderListener {

    @RacerListener(channel = "racer:orders")
    @RacerRoute({
        @RacerRouteRule(field = "priority", matches = "HIGH", to = "racer:orders:high",
                        action = RouteAction.FORWARD),
        @RacerRouteRule(field = "priority", matches = ".*",   to = "",
                        action = RouteAction.DROP)
    })
    public void onOrder(RacerMessage msg, @Routed boolean wasForwarded) {
        // only invoked for FORWARD_AND_PROCESS or PASS decisions
        log.info("Processing order. wasForwarded={}", wasForwarded);
    }
}
```

**`@RacerRouteRule` attributes**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `field` | `String` | `""` | JSON field in the payload to evaluate (ignored when `source` is `SENDER` or `ID`). |
| `matches` | `String` | `""` | Java regex applied to the field value (or envelope field when `source ≠ PAYLOAD`). |
| `to` | `String` | `""` | Target Redis channel/alias to re-publish the message to when the rule fires. |
| `sender` | `String` | `"racer-router"` | Sender label stamped on the re-published message. |
| `source` | `RouteMatchSource` | `PAYLOAD` | Which part of the message is matched (see table below). |
| `action` | `RouteAction` | `FORWARD` | What to do when the rule matches (see table below). |

**`RouteMatchSource` values**

| Value | Matches against |
|-------|----------------|
| `PAYLOAD` | A top-level JSON field in `RacerMessage.payload` (identified by `field`). |
| `SENDER` | `RacerMessage.getSender()` — the envelope sender label. |
| `ID` | `RacerMessage.getId()` — the unique message ID. |

**`RouteAction` values**

| Value | Behaviour |
|-------|----------|
| `FORWARD` | Re-publish to `to` channel and **skip** the local handler (default). |
| `FORWARD_AND_PROCESS` | Re-publish to `to` channel **and** invoke the local handler (fan-out). |
| `DROP` | Silently discard — no re-publish, no local handler invocation. |
| `DROP_TO_DLQ` | Route the message to the Dead Letter Queue and skip the local handler. |

**`@Routed` parameter injection:**  
Add a `boolean` parameter annotated `@Routed` to any `@RacerListener` handler. Racer injects `true` if the message was forwarded (`FORWARD_AND_PROCESS`), and `false` otherwise.

**`RacerMessageInterceptor` SPI:**  
Declare one or more `RacerMessageInterceptor` beans to intercept every message before handler dispatch. Use `@Order` to control the chain order. Return a different `Mono<RacerMessage>` to mutate the message, or return `Mono.error(...)` to abort processing.

```java
@Component
@Order(1)
public class LoggingInterceptor implements RacerMessageInterceptor {
    @Override
    public Mono<RacerMessage> intercept(RacerMessage msg, InterceptorContext ctx) {
        log.info("[{}] received on {}", ctx.listenerId(), ctx.channel());
        return Mono.just(msg); // pass through unchanged
    }
}
```

**Functional Router DSL:**

As an alternative to the annotation-based `@RacerRoute`, declare a `@Bean` of type
`RacerFunctionalRouter` using the fluent builder. Functional routers are discovered
automatically at startup and evaluated (in bean-registration order) after any
annotation-based rules.

```java
import static com.cheetah.racer.router.dsl.RouteHandlers.*;
import static com.cheetah.racer.router.dsl.RoutePredicates.*;

@Configuration
public class OrderRouterConfig {

    @Bean
    public RacerFunctionalRouter orderRouter() {
        return RacerFunctionalRouter.builder()
                .name("order-router")
                // Single-alias routes
                .route(fieldEquals("type", "EMAIL"), forward("email"))
                .route(fieldEquals("type", "SMS"),   forward("sms"))
                // True fan-out: one rule → multiple aliases
                .route(fieldEquals("type", "BROADCAST"),
                       multicastAndProcess("email", "sms", "push"))
                // Composable predicates
                .route(fieldEquals("type", "AUDIT")
                               .and(senderEquals("checkout-service")), forward("audit"))
                .defaultRoute(drop())
                .build();
    }
}
```

**`RoutePredicates` factory methods**

| Method | Description |
|--------|-------------|
| `fieldEquals(field, value)` | Exact match against a top-level JSON payload field |
| `fieldMatches(field, regex)` | Regex match against a top-level JSON payload field |
| `senderEquals(name)` | Exact match against `RacerMessage.getSender()` |
| `senderMatches(regex)` | Regex match against `RacerMessage.getSender()` |
| `idEquals(id)` | Exact match against `RacerMessage.getId()` |
| `idMatches(regex)` | Regex match against `RacerMessage.getId()` |
| `any()` | Always-true catch-all |
| `p.and(q)` / `p.or(q)` / `p.negate()` | Boolean predicate composition |

**`RouteHandlers` factory methods**

| Method | Effect | `RouteDecision` returned |
|--------|--------|--------------------------|
| `forward(alias)` | Publish to one alias; skip local handler | `FORWARDED` |
| `forward(alias, sender)` | Publish with overridden sender; skip local handler | `FORWARDED` |
| `forwardAndProcess(alias)` | Publish to one alias AND invoke local handler | `FORWARDED_AND_PROCESS` |
| `multicast(a, b, ...)` | Publish to ALL listed aliases; skip local handler | `FORWARDED` |
| `multicastAndProcess(a, b, ...)` | Publish to ALL listed aliases AND invoke local handler | `FORWARDED_AND_PROCESS` |
| `forwardWithPriority(alias, level)` | Publish to `alias`'s priority sub-channel `{channel}:priority:{LEVEL}`; skip local handler. Falls back to `forward(alias)` when priority is not configured. | `FORWARDED` |
| `drop()` | Discard and log at DEBUG (`id`, `channel`, truncated payload) | `DROPPED` |
| `dropQuietly()` | Silently discard with no logging (for health-check pings, etc.) | `DROPPED` |
| `dropToDlq()` | Route to the Dead Letter Queue (**recommended default route**) | `DROPPED_TO_DLQ` |

**Runtime API:**
- `GET /api/router/rules` — list all compiled rules with their index, source, field, pattern, target and action.
- `POST /api/router/test` — dry-run: pass a message body and see which rule (if any) matches.

---

### `@RacerPriority` — message priority routing

Annotate a method alongside `@PublishResult` to tag the published message with a priority level. `RacerPriorityPublisher` (active when `racer.priority.enabled=true`) routes the message to the correct priority sub-channel.

**Sub-channel naming:**
```
racer:orders:priority:HIGH
racer:orders:priority:NORMAL
racer:orders:priority:LOW
```

**Usage:**
```java
@PublishResult(channelRef = "orders", sender = "checkout")
@RacerPriority(defaultLevel = "HIGH")
public RacerMessage placeUrgentOrder(OrderRequest req) {
    // If the returned RacerMessage has priority = null/blank,
    // defaultLevel ("HIGH") is used.
    return RacerMessage.create("racer:orders", req.toString(), "checkout", "HIGH");
}
```

**`@RacerPriority` attributes**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `defaultLevel` | `String` | `"NORMAL"` | Priority level used when the message's own `priority` field is blank. Must match one of the names in `racer.priority.levels`. |

**Built-in levels** (`PriorityLevel` enum):

| Level | Weight | Description |
|-------|--------|-------------|
| `HIGH` | 0 | Processed first |
| `NORMAL` | 1 | Default |
| `LOW` | 2 | Processed last |

**Publishing with priority:**
```java
@RacerPriority(defaultLevel = PriorityLevel.HIGH)
@PublishResult(channelRef = "orders")
public Order createUrgentOrder(OrderRequest req) {
    return orderService.create(req);
}
```

Or programmatically:
```java
@Autowired RacerPriorityPublisher priorityPublisher;

priorityPublisher.publish("racer:orders", "urgent-order", "checkout", PriorityLevel.HIGH).subscribe();
```

**Consumer side (`racer-demo`):**

Enable `racer.priority.enabled=true` and configure `racer.priority.channels`. The `RacerPriorityConsumerService` subscribes to all priority sub-channels, buffers messages in a `PriorityBlockingQueue` ordered by level weight, and drains them in strict priority order.

```properties
# racer-demo/application.properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=racer:orders,racer:notifications
```

---

### `@RacerPoll` — scheduled publishing

Annotate a no-arg method in any Spring bean to publish its return value to a Racer channel on a fixed schedule or cron expression. The method handles all data fetching or computation — `@RacerPoll` only deals with the scheduling and the publish destination.

**Fixed-rate example:**
```java
@Component
public class InventoryPoller {

    @RacerPoll(
        fixedRate = 30_000,              // every 30 seconds
        channel   = "racer:inventory",
        sender    = "inventory-poller"
    )
    public String fetchInventory() {
        // Your code fetches the data however you like
        return restClient.get("https://api.example.com/inventory");
    }
}
```

**Cron-based example with reactive return type:**
```java
@RacerPoll(
    cron       = "0 0/5 * * * *",       // every 5 minutes
    channelRef = "pricing",
    sender     = "price-poller"
)
public Mono<String> fetchPrices() {
    return webClient.get()
            .uri("https://api.example.com/prices")
            .retrieve()
            .bodyToMono(String.class);
}
```

**`@RacerPoll` attributes**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `fixedRate` | `long` | `10000` | Polling interval in milliseconds (ignored when `cron` is set) |
| `initialDelay` | `long` | `0` | Delay before first poll (ms) |
| `cron` | `String` | `""` | Spring cron expression (overrides `fixedRate` when non-empty) |
| `channel` | `String` | `""` | Direct Redis channel name to publish to |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>` |
| `sender` | `String` | `"racer-poller"` | Sender label on published messages |
| `async` | `boolean` | `true` | Whether to publish asynchronously |

**Supported return types:** `String` (as-is), any serializable object (JSON-encoded), `Mono<?>` (subscribed to), `void`/`null` (nothing published).

---

### `@RacerListener` — declarative channel consumers

Annotate **any Spring-managed method** to subscribe it to a Redis Pub/Sub channel. `RacerListenerRegistrar` (a `BeanPostProcessor`) discovers every `@RacerListener` method at startup, subscribes to the channel, and dispatches incoming messages reactively on the bounded-elastic scheduler.

**Sequential listener (default):**
```java
@Component
public class OrderHandler {

    @RacerListener(channel = "racer:orders")
    public void onOrder(RacerMessage message) {
        // receives the full message envelope — process in-place
        System.out.println("Order arrived: " + message.getPayload());
    }
}
```

**Concurrent listener with POJO deserialization:**
```java
@Component
public class ShipmentHandler {

    @RacerListener(
        channel     = "racer:shipments",
        mode        = ConcurrencyMode.CONCURRENT,
        concurrency = 8,
        id          = "shipment-listener"
    )
    public Mono<Void> onShipment(Shipment shipment) {
        // payload is automatically deserialised to Shipment via ObjectMapper
        return shipmentService.process(shipment);
    }
}
```

**Using a channel alias from `application.properties`:**
```java
@RacerListener(channelRef = "orders", mode = ConcurrencyMode.SEQUENTIAL)
public void handleOrder(String rawPayload) {
    // rawPayload is the plain String content of the message's payload field
}
```

**Attribute reference**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `channel` | `String` | `""` | Direct Redis Pub/Sub channel name. Takes priority over `channelRef`. |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>`. Resolved at startup. |
| `mode` | `ConcurrencyMode` | `SEQUENTIAL` | Dispatch strategy — see table below. |
| `concurrency` | `int` | `4` | Maximum parallel in-flight messages when `mode = CONCURRENT`. |
| `id` | `String` | `""` | Optional listener ID used in metrics tags and log messages. Defaults to `<BeanName>#<methodName>`. |

**`ConcurrencyMode` values**

| Value | Max in-flight | Ordering |
|-------|---------------|---------|
| `SEQUENTIAL` | 1 | Strictly ordered — one message fully processed before next starts |
| `CONCURRENT` | `concurrency` | Up to N messages processed in parallel on the dedicated Racer thread pool |
| `AUTO` | adaptive | AIMD self-tuning — starts at `2×CPU`, adjusts every 10 seconds up to `racer.thread-pool.max-size`. The `concurrency` attribute is ignored. |

**Supported parameter types**

| Parameter type | What is passed |
|----------------|----------------|
| `RacerMessage` | Full message envelope (channel, sender, payload, id, …) |
| `String` | The raw string value of `RacerMessage#payload` |
| Any other type `T` | `objectMapper.readValue(payload, T.class)` — automatic JSON deserialization |

**Supported return types:** `void`, any type (result discarded), `Mono<?>` (subscribed to before the next dispatch).

**Integration with schema validation, routing, and DLQ:**
- If a `RacerSchemaValidator` bean is present, the payload is validated before dispatch; schema failures are forwarded to the DLQ without invoking the method.
- If a `RacerRouterService` bean is present, it evaluates routing rules for the message. The `RouteDecision` outcome controls dispatch: `PASS` → handler invoked normally; `FORWARDED` → message re-published to target channel, local handler **skipped**; `FORWARDED_AND_PROCESS` → message re-published **and** local handler invoked; `DROPPED` → message silently discarded; `DROPPED_TO_DLQ` → message sent to the Dead Letter Queue. A `@Routed boolean` parameter in the handler receives `true` when the decision was `FORWARDED_AND_PROCESS`.
- Any exception thrown by the method (or emitted by a returned `Mono`) increments the listener's `failedCount` and forwards the message to `RacerDeadLetterHandler` (implemented by `DeadLetterQueueService` in `racer`).

**Metrics:** each listener exposes `getProcessedCount(id)` and `getFailedCount(id)` via `RacerListenerRegistrar`, and records to Micrometer under `racer.listener.processed` / `racer.listener.failed` tags.

**Lifecycle:** subscriptions are started in `postProcessAfterInitialization` and disposed via `SmartLifecycle.stop()`, which gracefully drains in-flight messages before shutting down (configurable via `racer.shutdown.timeout-seconds`). No manual cleanup required.

---

### `@RacerStreamListener` — durable stream consumers

Annotate **any Spring-managed method** to register it as a Redis Streams consumer via `XREADGROUP`. `RacerStreamListenerRegistrar` (a `BeanPostProcessor`) creates the consumer group (if needed), spawns up to `concurrency` named consumer loops, and dispatches each entry reactively. Failed messages are forwarded to `RacerDeadLetterHandler`.

**Sequential stream consumer (default):**
```java
@Component
public class OrderStreamHandler {

    @RacerStreamListener(streamKey = "racer:orders:stream", group = "orders-group")
    public Mono<Void> onOrderEntry(RacerMessage message) {
        return orderService.process(message.getPayload());
    }
}
```

**Concurrent stream consumer with POJO deserialization and batch reads:**
```java
@RacerStreamListener(
    streamKey    = "racer:shipments:stream",
    group        = "shipments-group",
    concurrency  = 4,
    batchSize    = 10,
    pollIntervalMs = 100,
    id           = "shipments-worker"
)
public Mono<Void> onShipment(Shipment shipment) {
    return shipmentService.process(shipment);
}
```

**Using a stream key alias from `application.properties`:**
```java
@RacerStreamListener(streamKeyRef = "orders-stream", group = "orders-group")
public void handleEntry(String rawPayload) { ... }
```

**`@RacerStreamListener` attribute reference**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `streamKey` | `String` | `""` | Direct Redis Stream key (e.g. `racer:orders:stream`). Takes priority over `streamKeyRef`. |
| `streamKeyRef` | `String` | `""` | Alias resolved from `racer.channels.<alias>.name` at startup. |
| `group` | `String` | `"racer-group"` | Consumer group name. Created automatically if it does not exist. |
| `mode` | `ConcurrencyMode` | `SEQUENTIAL` | `SEQUENTIAL` = 1 consumer loop; `CONCURRENT` = up to `concurrency` loops. |
| `concurrency` | `int` | `1` | Number of independent named consumer loops in the group. |
| `batchSize` | `int` | `1` | XREADGROUP COUNT — entries per poll cycle. |
| `pollIntervalMs` | `long` | `200` | Milliseconds to wait between polls when the stream is empty. |
| `id` | `String` | `""` | Optional consumer ID used in metrics tags and log output. |

**Supported parameter types:** same as `@RacerListener` — `RacerMessage`, `String`, any POJO `T` (auto-deserialized).

---

### `@RacerResponder` — request-reply responder

Annotate **any Spring-managed method** to register it as a request-reply handler. `RacerResponderRegistrar` (a `BeanPostProcessor`) subscribes to the configured channel or stream, detects incoming `RacerRequest` envelopes (payloads with a `replyTo` field), invokes the method, and publishes a `RacerReply` back to `replyTo`.

**Pub/Sub responder:**
```java
@Component
public class DemoResponder {

    @RacerResponder(channel = "racer:messages")
    public String handleRequest(String requestPayload) {
        return "Processed: " + requestPayload;
    }
}
```

**Stream-based responder:**
```java
@RacerResponder(
    stream  = "racer:stream:requests",
    group   = "responder-group",
    id      = "demo-stream-responder"
)
public Mono<String> handleStreamRequest(RacerMessage request) {
    return myService.handle(request.getPayload());
}
```

**`@RacerResponder` attribute reference**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `channel` | `String` | `""` | Pub/Sub channel to listen on for requests. Takes priority over `channelRef`. |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>`. |
| `stream` | `String` | `""` | Redis Stream key for stream-based request-reply. Takes priority over `streamRef`. |
| `streamRef` | `String` | `""` | Stream alias resolved at startup. |
| `group` | `String` | `"racer-responder-group"` | Consumer group name (stream mode only). |
| `mode` | `ConcurrencyMode` | `SEQUENTIAL` | Dispatch strategy for concurrent request handling. |
| `concurrency` | `int` | `1` | Max parallel request handlers when `mode = CONCURRENT`. |
| `id` | `String` | `""` | Responder ID for metrics and log output. |

---

### `@RacerClient` / `@RacerRequestReply` — request-reply caller

`@RacerClient` marks an **interface** as a Racer proxy. Place it on any interface and add `@RacerRequestReply` on methods that should send a request and await a typed reply. The framework generates a JDK dynamic proxy bean automatically — no implementation class needed.

```java
@RacerClient
public interface OrderClient {

    // Send to racer:messages (pub/sub), wait up to 10 s for a reply
    @RacerRequestReply(channel = "racer:messages", timeout = "10s")
    Mono<String> processOrder(String orderPayload);

    // Send to a stream, wait with default timeout
    @RacerRequestReply(stream = "racer:stream:requests")
    Mono<String> processStream(String payload);
}
```

Inject the proxy via constructor injection or field injection in any Spring bean:

```java
@Service
public class CheckoutService {

    private final OrderClient orderClient;

    public CheckoutService(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    public Mono<String> checkout(Order order) {
        return orderClient.processOrder(order.toJson());
    }
}
```

**`@RacerRequestReply` attribute reference**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `channel` | `String` | `""` | Pub/Sub channel to send the request to. |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>`. |
| `stream` | `String` | `""` | Redis Stream key for stream-based request-reply. |
| `streamRef` | `String` | `""` | Stream alias resolved at startup. |
| `timeout` | `String` | `""` | Override the default timeout (e.g. `"10s"`, `"500ms"`). Falls back to `racer.request-reply.default-timeout`. |

---

### `@EnableRacerClients`

Place on any `@SpringBootApplication` or `@Configuration` class to activate scanning of all `@RacerClient` interfaces in the given base packages.

```java
@SpringBootApplication
@EnableRacer
@EnableRacerClients(basePackages = "com.example.myapp.client")
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `basePackages` | `String[]` | `{}` | Package paths to scan for `@RacerClient` interfaces. |
| `basePackageClasses` | `Class[]` | `{}` | Type-safe alternative to `basePackages` (uses the package of each class). |

When neither attribute is provided, scanning starts from the package of the annotated class.

---

### Multi-channel configuration

Declare as many channel aliases as needed in `application.properties`. Each alias becomes a dedicated `RacerChannelPublisher` registered at startup.

```properties
# Default channel (used when no alias is specified)
racer.default-channel=racer:messages

# Orders channel — async, labelled with service name
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

# Notifications channel — async
racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=true
racer.channels.notifications.sender=notification-service

# Audit channel — blocking to guarantee delivery
racer.channels.audit.name=racer:audit
racer.channels.audit.async=false
racer.channels.audit.sender=audit-service
```

A log line is printed for each registered channel at startup:
```
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
[racer] Channel 'audit'         registered → 'racer:audit'
```

All registered channels (and their Redis names) are also queryable at runtime via `GET /api/channels`.

**Published message envelope**

Every `RacerChannelPublisher` wraps the payload in a lightweight JSON envelope before publishing:

```json
{
  "channel": "racer:orders",
  "sender":  "order-service",
  "payload": { ...your object... }
}
```

---

## Redis Keys & Channels Reference

| Redis Key / Channel | Type | Description |
|---------------------|------|-------------|
| `racer:messages` | Pub/Sub channel | Primary fire-and-forget + request-reply channel |
| `racer:notifications` | Pub/Sub channel | Broadcast-only notification channel |
| `racer:reply:<correlationId>` | Pub/Sub channel | Ephemeral per-request reply channel (auto-cleaned) |
| `racer:<alias>` | Pub/Sub channel | Dynamic channels created via `@RacerRoute` targets |
| `racer:<channel>:priority:<LEVEL>` | Pub/Sub channel | Priority sub-channels (R-10) — e.g. `racer:orders:priority:HIGH` |
| `racer:dlq` | List | Dead Letter Queue (LIFO push, FIFO pop) |
| `racer:stream:requests` | Stream | Request stream for streams-based request-reply |
| `racer:stream:response:<correlationId>` | Stream | Per-request response stream (auto-deleted after read) |
| `racer:<name>:stream` | Stream | **Durable stream** written by `@PublishResult(durable=true)` |
| `racer:<name>:stream:<n>` | Stream | **Sharded durable stream** shard `n` (R-8) — e.g. `racer:orders:stream:0` |

Consumer group on `racer:stream:requests` (when using `@RacerResponder(transport=STREAM)`): configurable via `group` attribute  
Consumer group on durable streams: set via `@RacerStreamListener(group="...")` attribute  
Consumer names within group: **`<group>-<index>`** e.g. `orders-group-0`, `orders-group-1` (concurrency set via `@RacerStreamListener(concurrency=N)`)

---

## Message Schemas

### RacerMessage (fire-and-forget)

```json
{
  "id":           "uuid-auto-generated",
  "channel":      "racer:messages",
  "payload":      "your message content",
  "sender":       "racer-demo",
  "timestamp":    "2026-03-01T10:00:00Z",
  "retryCount":   0,
  "priority":     "NORMAL",
  "traceparent":  "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
}
```

> **`priority` field (R-10):** Optional. Accepted values: `HIGH`, `NORMAL`, `LOW` (or any custom level declared in `racer.priority.levels`). Defaults to `NORMAL` when absent. Used by `RacerPriorityPublisher` to route to the correct sub-channel.

### RacerRequest (request-reply)

```json
{
  "correlationId": "uuid-auto-generated",
  "channel":       "racer:messages",
  "payload":       "your request content",
  "sender":        "racer-demo",
  "timestamp":     "2026-03-01T10:00:00Z",
  "replyTo":       "racer:reply:<correlationId>"
}
```

> For streams-based request-reply, `replyTo` is `racer:stream:response:<correlationId>`.

### RacerReply

```json
{
  "correlationId": "same-as-request",
  "payload":       "Processed: your request [echoed by racer-demo]",
  "responder":     "racer-demo",
  "success":       true,
  "errorMessage":  null,
  "timestamp":     "2026-03-01T10:00:01Z"
}
```

### DeadLetterMessage

```json
{
  "id":              "same-as-original-message-id",
  "originalMessage": { ...RacerMessage... },
  "errorMessage":    "Simulated processing failure",
  "exceptionClass":  "java.lang.RuntimeException",
  "failedAt":        "2026-03-01T10:00:02Z",
  "attemptCount":    1
}
```

---

## API Reference (port 8080, opt-in)

All REST endpoints are **opt-in** and only exposed when the corresponding property is set to `true` in `application.properties`. Each controller is registered conditionally via `@ConditionalOnProperty`.

| Endpoint group | Enable property | Base path |
|----------------|-----------------|-----------|
| DLQ | `racer.web.dlq-enabled=true` | `/api/dlq` |
| Retention | `racer.web.retention-enabled=true` | `/api/retention` |
| Router | `racer.web.router-enabled=true` | `/api/router` |
| Channels | `racer.web.channels-enabled=true` | `/api/channels` |
| Schema | `racer.web.schema-enabled=true` | `/api/schema` |
| Admin UI | `racer.web.admin-enabled=true` | `/api/admin` + `/racer-admin/` |

---

### DLQ APIs

Base path: `/api/dlq` (requires `racer.web.dlq-enabled=true`)

Messages that cause an unhandled exception in a `@RacerListener`, `@RacerStreamListener`, or `@RacerResponder` method are automatically moved to the Dead Letter Queue (a Redis List, key: `racer:dlq`). The DLQ supports inspection, republishing, and clearing.

---

#### `GET /api/dlq/messages`

List all messages currently in the DLQ without removing them.

**Response `200 OK`** — JSON array of `DeadLetterMessage`

```json
[
  {
    "id": "550e8400-...",
    "originalMessage": {
      "id":         "550e8400-...",
      "channel":    "racer:messages",
      "payload":    "this will cause error",
      "sender":     "me",
      "timestamp":  "2026-03-01T10:00:00Z",
      "retryCount": 1
    },
    "errorMessage":  "Simulated processing failure for message: 550e8400-...",
    "exceptionClass": "java.lang.RuntimeException",
    "failedAt":       "2026-03-01T10:00:01Z",
    "attemptCount":   1
  }
]
```

**curl example:**
```bash
curl http://localhost:8080/api/dlq/messages
```

---

#### `GET /api/dlq/size`

Returns the number of messages currently in the DLQ.

**Response `200 OK`**

```json
{
  "dlqSize": 5
}
```

**curl example:**
```bash
curl http://localhost:8080/api/dlq/size
```

---

#### `GET /api/dlq/stats`

Returns combined DLQ size and republishing statistics.

**Response `200 OK`**

```json
{
  "queueSize":        3,
  "totalReprocessed": 7,
  "permanentlyFailed": 1
}
```

**curl example:**
```bash
curl http://localhost:8080/api/dlq/stats
```

---

#### `POST /api/dlq/republish/one`

**Republish** a single DLQ message back to its original Pub/Sub channel. The message flows through the normal pipeline (`@RacerListener` / `@RacerStreamListener`) again.

- Increments `retryCount` on the original message.
- If `retryCount > MAX_RETRY_ATTEMPTS (3)`: message is permanently discarded.

**Response `200 OK`**

```json
{
  "republished": true,
  "subscribers": 1
}
```

Returns `republished: false` when the queue was empty.

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/dlq/republish/one
```

---

#### `DELETE /api/dlq/clear`

Remove **all messages** from the DLQ permanently. Use with caution.

**Response `200 OK`**

```json
{
  "cleared": true
}
```

**curl example:**
```bash
curl -s -X DELETE http://localhost:8080/api/dlq/clear
```

---

### Retention APIs

Base path: `/api/retention` (requires `racer.web.retention-enabled=true`)

---

#### `POST /api/retention/trim`

Trigger an **immediate on-demand retention run**: trims all configured durable streams to `racer.retention.stream-max-len` entries, and prunes DLQ entries older than `racer.retention.dlq-max-age-hours`.

**Response `200 OK`**

```json
{
  "status": "trimmed",
  "timestamp": "2026-03-01T10:00:00Z"
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/retention/trim
```

---

#### `GET /api/retention/config`

Returns the current retention configuration being applied by `RacerRetentionService`.

**Response `200 OK`**

```json
{
  "streamMaxLen":     10000,
  "dlqMaxAgeHours":   72,
  "scheduleCron":     "0 0 * * * *"
}
```

**curl example:**
```bash
curl http://localhost:8080/api/retention/config
```

---

### Router APIs

Base path: `/api/router` (requires `racer.web.router-enabled=true`)

Content-based routing is configured via `@RacerRoute` annotations (see [`@RacerRoute — content-based routing`](#racerroute--content-based-routing)). These endpoints let you inspect and test the compiled rules at runtime.

---

#### `GET /api/router/rules`

Returns all compiled routing rules registered from `@RacerRoute` beans.

**Response `200 OK`**

```json
[
  { "index": 0, "field": "type",   "pattern": "^ORDER.*",        "to": "racer:orders" },
  { "index": 1, "field": "type",   "pattern": "^NOTIFICATION.*", "to": "racer:notifications" },
  { "index": 2, "field": "sender", "pattern": "payment-service", "to": "racer:payments" }
]
```

**curl example:**
```bash
curl http://localhost:8080/api/router/rules
```

---

#### `POST /api/router/test`

Dry-run a message through the router without actually publishing it. Returns which rule (if any) would match.

**Request Body** — any JSON object (simulates the message payload)

```json
{ "type": "ORDER_CREATED", "id": "123", "amount": 99.99 }
```

**Response `200 OK`** (match found):

```json
{
  "matched":   true,
  "ruleIndex": 0,
  "field":     "type",
  "pattern":   "^ORDER.*",
  "to":        "racer:orders"
}
```

**Response `200 OK`** (no match):

```json
{ "matched": false }
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/router/test \
  -H "Content-Type: application/json" \
  -d '{"type":"ORDER_CREATED","id":"42"}'
```

---

### Channel Registry APIs

Base path: `/api/channels` (requires `racer.web.channels-enabled=true`)

---

#### `GET /api/channels`

Lists every channel alias registered in the `RacerPublisherRegistry`.

**Response `200 OK`**

```json
{
  "__default__":   { "channel": "racer:messages" },
  "orders":        { "channel": "racer:orders" },
  "notifications": { "channel": "racer:notifications" },
  "audit":         { "channel": "racer:audit" }
}
```

**curl example:**
```bash
curl http://localhost:8080/api/channels
```

---

### Schema APIs

Base path: `/api/schema` (requires `racer.web.schema-enabled=true`)

`RacerSchemaRegistry` validates every message against a JSON Schema Draft-07 file at publish and consume time (opt-in via `racer.schema.enabled=true`). These endpoints expose the schema registry at runtime.

See the full schema API documentation and `RacerSchemaRegistry` javadoc for endpoint details.

---

### Admin UI APIs

Base path: `/api/admin` (requires `racer.web.admin-enabled=true`)

`RacerAdminController` exposes a live operational dashboard for the Racer instance. The static web UI is served at `http://localhost:8080/racer-admin/` (Bootstrap 5 single-page app, auto-configured alongside the REST endpoints).

Enable in `application.properties`:
```properties
racer.web.admin-enabled=true
```

---

#### `GET /api/admin/overview`

Health-level summary of the Racer instance.

**Response `200 OK`**

```json
{
  "status":          "UP",
  "channelCount":    4,
  "dlqDepth":        0,
  "circuitBreakers": 3,
  "rateLimiters":    2,
  "timestamp":       "2026-03-01T10:00:00Z"
}
```

**curl example:**
```bash
curl http://localhost:8080/api/admin/overview
```

---

#### `GET /api/admin/channels`

Lists all registered channel aliases with their publish configuration.

**Response `200 OK`**

```json
[
  { "alias": "__default__", "channel": "racer:messages",      "async": true },
  { "alias": "orders",      "channel": "racer:orders",        "async": true },
  { "alias": "notifications","channel": "racer:notifications", "async": true },
  { "alias": "audit",       "channel": "racer:audit",         "async": false }
]
```

**curl example:**
```bash
curl http://localhost:8080/api/admin/channels
```

---

#### `GET /api/admin/circuitbreakers`

Returns the current state of every registered `RacerCircuitBreaker`.

**Response `200 OK`**

```json
[
  { "listenerId": "sms-worker",   "state": "CLOSED",    "failureRate": 0.0  },
  { "listenerId": "email-worker", "state": "CLOSED",    "failureRate": 0.0  },
  { "listenerId": "push-worker",  "state": "HALF_OPEN", "failureRate": 55.0 }
]
```

States: `CLOSED` (healthy), `OPEN` (rejecting calls), `HALF_OPEN` (probe phase).

**curl example:**
```bash
curl http://localhost:8080/api/admin/circuitbreakers
```

---

#### `GET /api/admin/ratelimits`

Returns current rate limiter configuration and token bucket status for each channel.

**Response `200 OK`**

```json
[
  { "channel": "racer:orders",        "permitsPerSecond": 100, "burstSize": 200, "availableTokens": 197 },
  { "channel": "racer:notifications", "permitsPerSecond": 500, "burstSize": 1000, "availableTokens": 998 }
]
```

**curl example:**
```bash
curl http://localhost:8080/api/admin/ratelimits
```

---

## Observability & Metrics

Racer integrates with **Micrometer** via `RacerMetrics` (auto-configured when `micrometer-core` is on the classpath). When `RacerMetrics` is absent from the context, a `NoOpRacerMetrics` implementation is used automatically — no null checks required in any component. To provide a custom metrics backend, implement the `RacerMetricsPort` interface and register the bean. The `racer-demo` module includes `spring-boot-starter-actuator` and `micrometer-registry-prometheus`, all served on port **8080**.

### Actuator endpoints

| Endpoint | Port | Description |
|----------|------|-------------|
| `GET /actuator/health` | 8080 | Liveness check |
| `GET /actuator/info` | 8080 | Build info |
| `GET /actuator/metrics` | 8080 | All registered metric names |
| `GET /actuator/metrics/{name}` | 8080 | Detail for one metric |
| `GET /actuator/prometheus` | 8080 | Prometheus-format scrape endpoint |

Enable all relevant endpoints in `application.properties`:
```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.metrics.tags.application=${spring.application.name}
```

### Racer metrics

| Metric name | Type | Description |
|-------------|------|-------------|
| `racer.published` | Counter | Messages successfully published to Pub/Sub |
| `racer.published.stream` | Counter | Messages published to durable streams (XADD) |
| `racer.consumed` | Counter | Messages successfully processed by a consumer |
| `racer.failed` | Counter | Messages that threw an exception during processing |
| `racer.dlq.reprocessed` | Counter | DLQ messages successfully reprocessed |
| `racer.dlq.size` | Gauge | Current number of entries in `racer:dlq` |
| `racer.requestreply.latency` | Timer | Round-trip latency for request-reply operations |
| `racer.stream.consumer.lag` | Gauge | Pending message count per `(stream, group)` — requires `racer.consumer-lag.enabled=true` |
| `racer.circuit.breaker.state` | Gauge | Circuit breaker state per listener: `0` = CLOSED, `1` = OPEN, `2` = HALF_OPEN |
| `racer.backpressure.active` | Gauge | `1` while back-pressure is in effect, `0` otherwise |
| `racer.backpressure.events` | Counter | Transitions into/out of the back-pressure throttle state |
| `racer.dedup.duplicates` | Counter | Duplicate messages suppressed per listener |

All metrics include a `transport` tag (`pubsub` or `stream`) and an `application` tag set by `management.metrics.tags.application`.

### Health indicator — consumer lag

When `racer.consumer-lag.enabled=true`, the `/actuator/health` response includes a `consumer-lag` detail map showing the pending message count for every tracked `(streamKey|group)` pair:

```json
{
  "status": "UP",
  "components": {
    "racer": {
      "status": "UP",
      "details": {
        "redis.ping": "PONG",
        "dlq.depth": 0,
        "consumer-lag": {
          "racer:orders-stream|order-processors": 12,
          "racer:audit-stream|audit-consumers": 0
        }
      }
    }
  }
}
```

When any lag value exceeds `racer.consumer-lag.lag-down-threshold` (default `10000`) the status flips to `OUT_OF_SERVICE`:

```json
{
  "status": "OUT_OF_SERVICE",
  "details": {
    "consumer-lag": { "racer:orders-stream|order-processors": 15000 },
    "consumer-lag.threshold-breached": true
  }
}
```

Set `racer.consumer-lag.lag-down-threshold=0` to disable the health-status flip while still keeping the detail map visible.

### Checking metrics with curl

```bash
# List all metric names
curl http://localhost:8080/actuator/metrics | jq '.names[]' | grep racer

# Get detail for one metric
curl "http://localhost:8080/actuator/metrics/racer.published"

# Prometheus scrape (for Grafana / Prometheus integration)
curl http://localhost:8080/actuator/prometheus | grep racer
```

### Grafana quick-start

1. Add `http://localhost:8080/actuator/prometheus` as a Prometheus scrape target.
2. Import a generic Spring Boot Micrometer dashboard (e.g. Grafana dashboard ID **4701**).
3. Filter panels by `application="racer-demo"`.

---

## High Availability

Racer ships two Docker Compose files for production-grade Redis deployments.

### Sentinel mode (recommended for most teams)

Provides automatic failover with one primary, one replica and three Sentinel nodes.

```bash
docker compose -f compose.sentinel.yaml up -d
```

Configure both applications to use Sentinel instead of a single host:
```properties
# Remove spring.data.redis.host / port lines and add:
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=localhost:26379,localhost:26380,localhost:26381
```

**Testing failover:**
```bash
# Stop the primary — Sentinel elects the replica as new primary within ~5 s
docker stop racer-redis-primary
# Watch Sentinel logs
docker logs -f racer-sentinel-1
# Restart survived services
docker start racer-redis-primary
```

### Cluster mode (horizontal scale-out)

A 6-node Redis Cluster (3 primaries + 3 replicas) with an auto-init container.

```bash
docker compose -f compose.cluster.yaml up -d
```

Configure cluster mode:
```properties
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
```

### Choosing a mode

| | Standalone (`compose.yaml`) | Sentinel | Cluster |
|---|---|---|---|
| **Failover** | ❌ | ✅ auto | ✅ auto |
| **Horizontal scale** | ❌ | ❌ | ✅ |
| **Complexity** | Low | Medium | High |
| **Pub/Sub** | ✅ | ✅ | ✅ (primary only) |
| **Streams** | ✅ | ✅ | ✅ |
| **Recommended for** | Dev / testing | Production (most teams) | Very large data sets |

> See [Tutorial 15](TUTORIALS.md#tutorial-15--high-availability-sentinel--cluster) for a full walkthrough.

---

## Consumer Scaling & Sharding

> **R-8 — Consumer Scaling & Horizontal Sharding**

By default a single consumer (`consumer-0`) reads from each stream. For high-throughput workloads you can:

1. **Increase concurrency** — spawn N named consumers inside one process, each issuing an independent `XREADGROUP COUNT <batchSize>` loop.
2. **Enable key-based sharding** — publish to `racer:<stream>:stream:<n>` shards using CRC-16/CCITT routing (`RacerShardedStreamPublisher`).

### Concurrency configuration

Concurrency is configured **per-listener** directly on the `@RacerStreamListener` annotation:

```java
@RacerStreamListener(
    streamKey       = "racer:orders:stream",
    group           = "orders-group",
    concurrency     = 4,    // spawn consumer-0 … consumer-3
    batchSize       = 10,   // read 10 entries per XREADGROUP call
    pollIntervalMs  = 100   // poll every 100 ms when idle
)
public Mono<Void> handleOrder(RacerMessage msg) {
    return processOrder(msg);
}
```

Each `@RacerStreamListener` method independently controls its own thread pool, so different streams can have different concurrency levels without any global properties.

### Sharding configuration

```properties
# racer-demo/application.properties — publisher side
racer.sharding.enabled=true
racer.sharding.shard-count=4
racer.sharding.streams=racer:orders:stream,racer:events:stream
```

Publishing with a shard key:
```java
@Autowired RacerShardedStreamPublisher shardedPublisher;

shardedPublisher.publishToShard("racer:orders:stream", payload, sender, orderId)
    .subscribe();
// Routes to racer:orders:stream:0 … :3 based on CRC-16(orderId) % 4
```

**Consumer side** — add the concrete shard keys to `racer.durable.stream-keys`:
```properties
racer.durable.stream-keys=racer:orders:stream:0,racer:orders:stream:1,racer:orders:stream:2,racer:orders:stream:3
```

> See [Tutorial 16](TUTORIALS.md#tutorial-16--consumer-scaling--stream-sharding) for a full walkthrough.

---

## Pipelined Publishing

> **R-9 — Throughput Optimisation / Pipelining**

`RacerPipelinedPublisher` issues all `PUBLISH` commands in a batch concurrently via `Flux.flatMap(concurrency = N)`. Lettuce (the reactive Redis driver) automatically pipelines these commands over a single connection, collapsing N round-trips into approximately 1, which significantly increases throughput for bulk workloads.

### Comparison

| Method | Execution model | Use when |
|--------|-----------------|----------|
| `RacerPublisher.publish(...)` (loop) | Sequential | Order matters, low volume |
| `RacerPipelinedPublisher.publishBatch(...)` | Parallel `Flux.flatMap` (pipelined) | High throughput, single channel |
| `RacerTransaction` (concat) | Sequential, multi-channel | Ordered cross-channel fanout |
| `RacerPipelinedPublisher.publishBatchMultiChannel(...)` | Parallel, multi-channel | Cross-channel, max throughput |

### Usage from Java

```java
@Autowired RacerPipelinedPublisher pipelinedPublisher;

List<String> payloads = IntStream.range(0, 1000)
    .mapToObj(i -> "event-" + i)
    .toList();

pipelinedPublisher.publishBatch("racer:orders", payloads, "producer")
    .doOnNext(counts -> log.info("Sent {} messages", counts.size()))
    .subscribe();
```

Cross-channel pipelined batch:
```java
var items = List.of(
    new RacerPipelinedPublisher.PipelineItem("racer:orders",  "order-1",   "checkout"),
    new RacerPipelinedPublisher.PipelineItem("racer:audit",   "audit-1",   "checkout"),
    new RacerPipelinedPublisher.PipelineItem("racer:metrics", "metric-1",  "checkout")
);
pipelinedPublisher.publishItems(items).subscribe();
```

### Using pipelined mode in `RacerTransaction`

```java
transaction.execute(tx -> {
    tx.publish("orders",  "order-1");
    tx.publish("audit",   "audit-1");
}, /* pipelined = */ true);  // all PUBLISH calls go through RacerPipelinedPublisher
```

> See [Tutorial 17](TUTORIALS.md#tutorial-17--pipelined-batch-publishing) for a full walkthrough.

---

## Message Priority

> **R-10 — Message Priority Queuing**

RacerMQ implements priority via separate Pub/Sub **sub-channels**, one per priority level. Publishers route messages to `{channel}:priority:{LEVEL}` and consumers drain messages from highest-priority channels first.

### Priority levels (`PriorityLevel` enum)

| Level | Weight | Sub-channel suffix |
|-------|--------|-------------------|
| `HIGH` | 0 | `:priority:HIGH` |
| `NORMAL` | 1 | `:priority:NORMAL` |
| `LOW` | 2 | `:priority:LOW` |

Lower weight = higher priority. Custom levels can be declared in `racer.priority.levels`.

### Publishing with priority

```properties
# racer-demo/application.properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.channels=orders,notifications
```

Publish programmatically via `RacerPriorityPublisher`:

```java
@Autowired RacerPriorityPublisher priorityPublisher;

priorityPublisher.publish("racer:orders", "urgent order", "checkout", PriorityLevel.HIGH)
    .subscribe();
```

Or use `@RacerPriority` alongside `@PublishResult`:

```java
@RacerPriority(defaultLevel = PriorityLevel.HIGH)
@PublishResult(channelRef = "orders")
public Order createUrgentOrder(OrderRequest req) {
    return orderService.create(req);
}
```

This publishes to `racer:orders:priority:HIGH`.

### Client-side consumption

```properties
# racer-demo/application.properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=racer:orders,racer:notifications
```

`RacerPriorityConsumerService` (auto-configured when `racer.priority.enabled=true`) subscribes to all priority sub-channels, buffers incoming messages in a `PriorityBlockingQueue`, and drains them in strict weight order:
1. All `HIGH` messages are processed before any `NORMAL` messages.
2. All `NORMAL` messages are processed before any `LOW` messages.

### `@RacerPriority` annotation

```java
@PublishResult(channelRef = "orders")
@RacerPriority(defaultLevel = "HIGH")
public RacerMessage submitOrder(OrderRequest req) {
    return RacerMessage.create("racer:orders", toJson(req), "checkout", "HIGH");
}
```

If the returned `RacerMessage.priority` is blank/null, `defaultLevel` from `@RacerPriority` is used as the fallback.

> See [Tutorial 18](TUTORIALS.md#tutorial-18--message-priority-channels) for a full walkthrough.

---

## Cluster-Aware Publishing

> **v1.3 — opt-in:** `racer.sharding.consistent-hash-enabled=true`

`RacerConsistentHashRing` implements a virtual-node consistent hash ring over the configured shard list (`racer.sharding.shards`). When enabled, `RacerShardedStreamPublisher` maps each message to a deterministic shard by hashing the message ID — spreading load evenly and keeping the same key on the same shard across topology changes.

**How it works:**

1. At startup the ring is built from `racer.sharding.shards` × `racer.sharding.virtual-nodes-per-shard` virtual nodes.
2. `publish(alias, payload)` hashes `message.getId()` to select the target shard.
3. If the selected shard is unavailable and `racer.sharding.failover-enabled=true`, the ring automatically routes to the next live shard.

**Key properties:**

| Property | Default | Description |
|---|---|---|
| `racer.sharding.consistent-hash-enabled` | `false` | Replace round-robin sharding with consistent hash ring |
| `racer.sharding.virtual-nodes-per-shard` | `150` | Virtual node count (higher = more even distribution) |
| `racer.sharding.failover-enabled` | `true` | Route to next shard when preferred shard is unavailable |

**Configuration example:**

```properties
racer.sharding.enabled=true
racer.sharding.shards=redis-shard-1:6379,redis-shard-2:6379,redis-shard-3:6379
racer.sharding.consistent-hash-enabled=true
racer.sharding.virtual-nodes-per-shard=150
racer.sharding.failover-enabled=true
```

> See [Tutorial 28](TUTORIALS.md#tutorial-28--cluster-aware-publishing-with-consistent-hashing) for a full walkthrough.

---

## Distributed Tracing

> **v1.3 — opt-in:** `racer.tracing.enabled=true`

`RacerTracingInterceptor` (`@Order(1)`) intercepts every outbound `RacerMessage` and stamps a W3C `traceparent` header generated by `RacerTraceContext`. Inbound listeners extract the traceparent and (optionally) propagate it to the SLF4J MDC so all log lines within a message handler are correlated to the originating trace.

**How it works:**

1. On publish — `RacerTracingInterceptor` calls `RacerTraceContext.generate()` → sets `message.traceparent`.
2. On consume — `traceparent` is read from the incoming `RacerMessage` envelope.
3. If `racer.tracing.propagate-to-mdc=true` — sets `MDC.put("traceparent", ...)` for the duration of the listener call.

**`RacerTraceContext` API:**

```java
// Generate a new W3C traceparent
String tp = RacerTraceContext.generate();
// "00-<16-byte trace-id>-<8-byte span-id>-01"

// Extract from a received message
String tp = RacerTraceContext.extract(message);

// Propagate to MDC
RacerTraceContext.propagateToMdc(tp);   // sets MDC key "traceparent"
RacerTraceContext.clearMdc();            // clears after handler returns
```

**Key properties:**

| Property | Default | Description |
|---|---|---|
| `racer.tracing.enabled` | `false` | Enable W3C `traceparent` propagation |
| `racer.tracing.propagate-to-mdc` | `true` | Copy `traceparent` to SLF4J MDC |
| `racer.tracing.inject-into-envelope` | `true` | Stamp `traceparent` on outbound envelopes |

**Configuration example:**

```properties
racer.tracing.enabled=true
racer.tracing.propagate-to-mdc=true
racer.tracing.inject-into-envelope=true
```

```yaml
logging.pattern.console: "%d{HH:mm:ss} [%X{traceparent}] %-5level %logger{36} - %msg%n"
```

> See [Tutorial 26](TUTORIALS.md#tutorial-26--distributed-tracing) for a full walkthrough.

---

## Per-Channel Rate Limiting

> **v1.3 — opt-in:** `racer.rate-limit.enabled=true`

`RacerRateLimiter` wraps a Redis-backed token-bucket algorithm. Each channel gets its own bucket (sized by `burstSize`) that refills at `permitsPerSecond`. When a channel's bucket is exhausted, `RacerRateLimiter.tryAcquire()` returns `false`, a `RacerRateLimitException` is thrown, and the publish call fails open (the caller is responsible for back-off or fallback without crashing the instance).

**How it works:**

1. `RacerRateLimiter.tryAcquire(channel)` atomically decrements the token bucket in Redis.
2. On `false` → `RacerRateLimiter` throws `RacerRateLimitException`.
3. Catch `RacerRateLimitException` to log, drop, or queue the rejected message.

**Fail-open semantics:** if Redis is unavailable the rate limiter defaults to **allow** so a Redis outage does not halt publishing entirely.

**`RacerRateLimitException` usage:**

```java
@Autowired RacerRateLimiter rateLimiter;

try {
    rateLimiter.acquire("orders");   // throws if exhausted
    publisher.publish("orders", payload);
} catch (RacerRateLimitException ex) {
    log.warn("Rate limit exceeded for channel 'orders': {}", ex.getMessage());
    // optionally queue for retry
}
```

**Key properties:**

| Property | Default | Description |
|---|---|---|
| `racer.rate-limit.enabled` | `false` | Enable Redis token-bucket rate limiting |
| `racer.rate-limit.default-permits-per-second` | `100` | Default refill rate if no channel override |
| `racer.rate-limit.default-burst-size` | `200` | Default burst bucket capacity |
| `racer.rate-limit.channels.<alias>.permits-per-second` | — | Per-channel refill rate override |
| `racer.rate-limit.channels.<alias>.burst-size` | — | Per-channel burst capacity override |

**Configuration example:**

```properties
racer.rate-limit.enabled=true
racer.rate-limit.default-permits-per-second=100
racer.rate-limit.default-burst-size=200
racer.rate-limit.channels.orders.permits-per-second=500
racer.rate-limit.channels.orders.burst-size=1000
```

> See [Tutorial 27](TUTORIALS.md#tutorial-27--per-channel-rate-limiting) for a full walkthrough.

---

## End-to-End Flows

### Flow 1 — Fire-and-Forget (Pub/Sub)

```
Producer    @PublishResult(channelRef="orders")
            → PublishResultAspect intercepts return value
            → RacerChannelPublisher.publishAsync("racer:orders", payload)
            → PUBLISH racer:orders <JSON>

Consumer    @RacerListener(channel="racer:orders", mode=CONCURRENT, concurrency=4)
            → RacerListenerRegistrar subscribes via ReactiveRedisMessageListenerContainer
            → Message dispatched on the dedicated Racer thread pool (racer-worker-*) via flatMap(concurrency=4)
            → If throws: RacerDeadLetterHandler.enqueue(message, error)
            → DLQ written to racer:dlq (Redis List, leftPush)
```

### Flow 2 — Request-Reply via Pub/Sub

```
Client      @RacerClient(timeout=10s) interface with @RacerRequestReply(channel="racer:messages")
            → RacerClientFactoryBean creates a dynamic JDK proxy
            → Caller invokes proxy method → correlationId generated
            → Subscribes to racer:reply:<correlationId>
            → Publishes RacerRequest to racer:messages

Responder   @RacerResponder(requestChannel="racer:messages", transport=PUBSUB)
            → RacerResponderRegistrar subscribes at startup
            → Receives request, invokes annotated method (business logic)
            → Publishes RacerReply to replyTo channel

Caller      → Receives reply on racer:reply:<correlationId>
            → Proxy returns reply payload to caller
            → Timeout (default 30 s) → TimeoutException propagated
```

### Flow 3 — Durable Stream Listener

```
Producer    @PublishResult(channelRef="orders", durable=true, streamKey="racer:orders:stream")
            → PublishResultAspect routes to RacerStreamPublisher
            → XADD racer:orders:stream * payload=<JSON>

Consumer    @RacerStreamListener(streamKey="racer:orders:stream", group="orders-group",
                concurrency=4, batchSize=10, pollIntervalMs=100)
            → RacerStreamListenerRegistrar creates consumer group (XGROUP CREATE)
            → Spawns N threads (consumer-0 … consumer-3)
            → Each thread: XREADGROUP GROUP orders-group consumer-N COUNT 10
            → Dispatches to annotated method
            → On success: XACK racer:orders:stream orders-group <id>
            → On failure: message forwarded to DLQ
```

### Flow 4 — DLQ and Republish

```
Message fails → RacerDeadLetterHandler.enqueue(message, error)
             → JSON written to racer:dlq (Redis List, leftPush)
             → retryCount incremented in message

Later (opt-in REST, racer.web.dlq-enabled=true):
POST /api/dlq/republish/one
             → DeadLetterQueueService pops entry from racer:dlq (rightPop, FIFO)
             → Re-publishes to original channel via RacerChannelPublisher
             → Consumer receives it again and retries

POST /api/dlq/republish/all
             → Drains entire DLQ, republishing each message
```

---

## Extending the Application

### Add a consumer for a new channel

Use `@RacerListener` on any Spring bean method:

```java
@Component
public class InventoryConsumer {

    @RacerListener(channel = "racer:inventory", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
    public Mono<Void> onInventoryUpdate(RacerMessage message) {
        return inventoryService.apply(message.getPayload());
    }
}
```

The `RacerListenerRegistrar` automatically subscribes at startup — no wiring in XML or configuration classes.

**Supported parameter types:**

| Parameter type | What gets passed |
|---------------|-----------------|
| `RacerMessage` | Full envelope (id, payload, sender, timestamp, …) |
| `String` | Raw payload string |
| `MyDto` (any type) | `objectMapper.readValue(payload, MyDto.class)` |

### Add a durable stream consumer

```java
@Component
public class OrderStreamConsumer {

    @RacerStreamListener(
        streamKey      = "racer:orders:stream",
        group          = "orders-group",
        concurrency    = 2,
        batchSize      = 20,
        pollIntervalMs = 50
    )
    public Mono<Void> handleOrderEvent(RacerMessage message) {
        return orderService.process(message.getPayload());
    }
}
```

Declare the stream-key in `application.properties`:
```properties
racer.durable.stream-keys=racer:orders:stream
```

### Add a request-reply responder

```java
@Component
public class PricingResponder {

    @RacerResponder(requestChannel = "racer:pricing:requests", transport = Transport.PUBSUB)
    public Mono<String> getPrice(RacerMessage request) {
        return pricingService.getPrice(request.getPayload());
    }
}
```

### Add a declarative request-reply client

```java
@RacerClient(timeout = 5)
public interface PricingClient {

    @RacerRequestReply(channel = "racer:pricing:requests")
    Mono<String> getPrice(String payload);
}

@EnableRacerClients(basePackages = "com.example")
@SpringBootApplication
public class MyApp { ... }
```

### Add a custom channel

1. Add the alias to `application.properties`:
```properties
racer.channels.inventory.name=racer:inventory
racer.channels.inventory.async=true
racer.channels.inventory.sender=inventory-service
```

2. Inject and use in any Spring bean:
```java
@RacerPublisher("inventory")
private RacerChannelPublisher inventoryPublisher;

// Publish imperatively
inventoryPublisher.publishAsync(stockEvent).subscribe();

// Or annotate the producing method
@PublishResult(channelRef = "inventory")
public Mono<StockEvent> reserveStock(StockRequest req) { ... }
```

---

## Error Handling & DLQ Behaviour

| Scenario | Behaviour |
|----------|-----------|
| `@RacerListener` method throws | Message forwarded to `RacerDeadLetterHandler` → enqueued to `racer:dlq` |
| `@RacerStreamListener` method throws | NACK / no ACK → message stays pending; after configurable pending threshold forwarded to DLQ |
| Deserialization fails | Error is logged, message skipped (not DLQ'd) |
| DLQ republish fails | Message stays in DLQ; error logged |
| `retryCount > 3` | Message permanently discarded, logged as error |
| Request-reply timeout | `TimeoutException` propagated to caller via `Mono.error(...)` |
| Redis unavailable | Spring Boot reactive pipeline propagates error; check logs |

The maximum retry limit is controlled by `RedisChannels.MAX_RETRY_ATTEMPTS` (default: **3**).

To trigger DLQ intentionally for testing, publish a message and have your `@RacerListener` throw an exception:

```bash
# Enable DLQ REST API first
# racer.web.dlq-enabled=true in application.properties

# Check DLQ size
curl http://localhost:8080/api/dlq/size

# Republish one DLQ entry back to its original channel
curl -s -X POST http://localhost:8080/api/dlq/republish/one
```

---

## Comparison with Other Brokers

Racer is an **application-level messaging library** built on top of Redis.
This section explains how it compares architecturally to dedicated message brokers.

### Architecture at a Glance

| Dimension | **Racer** | **RabbitMQ** | **ActiveMQ** | **Apache Kafka** |
|-----------|-----------|-------------|-------------|------------------|
| **Core** | Library on Redis | Dedicated broker (Erlang) | Dedicated broker (Java/JMS) | Distributed commit log |
| **Protocol** | Redis Pub/Sub + Streams | AMQP 0-9-1, MQTT, STOMP | JMS, AMQP 1.0, STOMP | Custom binary protocol |
| **Deployment** | Redis (already in most stacks) | Separate cluster | Separate cluster | Multi-node cluster + ZooKeeper/KRaft |
| **Persistence** | Redis Streams + Lists (DLQ) with configurable XTRIM retention | Per-queue on disk | KahaDB / JDBC | Disk-backed partitioned log |
| **Routing** | `@RacerRoute` content-based routing + multi-channel fan-out | Exchanges → bindings → queues | Destinations, virtual topics | Topics → partitions |
| **Consumer groups** | Redis `XREADGROUP` + configurable concurrency + key-based sharding | Competing consumers on a queue | JMS shared subscriptions | Native consumer groups + partition rebalancing |
| **Message ordering** | Per-stream (single partition); strict priority drain within a channel | Per-queue | Per-queue | Per-partition |
| **Message priority** | ✅ `HIGH` / `NORMAL` / `LOW` sub-channels (`@RacerPriority`) | ✅ Native queue priority (0–255) | ✅ JMS message priority | ❌ No native priority; workaround: multiple topics |
| **Schema validation** | ✅ JSON Schema Draft-07 via `RacerSchemaRegistry` (opt-in) | ⚠️ Plugin or custom validator | ❌ No built-in | ✅ Confluent Schema Registry (Avro/JSON/Protobuf) |
| **Backpressure** | Project Reactor operators + configurable poll-batch-size | Channel-level QoS prefetch | JMS prefetch | Consumer fetch size |
| **Batch / pipeline publish** | ✅ `RacerPipelinedPublisher` (parallel) + `RacerTransaction` (ordered) | ⚠️ Publisher confirms, no true pipelining | ❌ Per-message send | ✅ Producer batching + linger.ms |
| **Reactive first-class** | ✅ Project Reactor end-to-end | ⚠️ Reactor RabbitMQ wrapper | ❌ Blocking JMS | ⚠️ Reactor Kafka wrapper |
| **High availability** | ✅ Redis Sentinel + Cluster (Docker Compose provided) | ✅ Mirrored queues / quorum queues | ✅ KahaDB replication | ✅ Native partition replication |
| **Deployment complexity** | Low (Redis + Spring Boot) | Medium (broker + management plugin) | Medium (broker + plugins) | High (brokers + ZooKeeper/KRaft) |

---

### Advantages of Racer

| Advantage | Detail |
|-----------|--------|
| **Zero infra overhead** | If you already run Redis, nothing extra to deploy — no Erlang runtime, no JVM broker, no ZooKeeper. |
| **Sub-millisecond latency** | Redis Pub/Sub delivers in-memory at ~0.1 ms. Dedicated brokers add network hops + disk I/O. |
| **Fully reactive** | Built on Project Reactor + Spring WebFlux end-to-end. RabbitMQ/ActiveMQ clients block threads by default. |
| **Annotation-driven DX** | `@RacerPublisher`, `@PublishResult`, `@EnableRacer` — zero boilerplate. No `ConnectionFactory → Channel → basicPublish` wiring. |
| **Embeddable as a library** | Ships as a Spring Boot starter JAR — import and go, no sidecar or agent. |
| **Request-reply built in** | First-class two-way communication over both Pub/Sub (ephemeral) and Streams (durable). |
| **Dual transport** | Same framework for fire-and-forget (Pub/Sub) and durable (Streams). No second system needed. |
| **Content-based routing** | `@RacerRoute` + `@RacerRouteRule` — declarative regex-pattern fan-out to named channels with zero routing code in business logic. |
| **Message priority** | `@RacerPriority` + `RacerPriorityConsumerService` — `HIGH`/`NORMAL`/`LOW` sub-channels with strict-order drain; no separate queue infrastructure needed. |
| **Pipelined batch publish** | `RacerPipelinedPublisher` issues all commands concurrently over a single Lettuce connection, collapsing N round-trips into ~1 for maximum throughput. |
| **Consumer sharding** | `RacerShardedStreamPublisher` distributes messages across N streams by CRC-16 key hash; `@RacerStreamListener(concurrency=N)` scales readers per stream. |
| **Schema validation** | `RacerSchemaRegistry` validates every message against a JSON Schema Draft-07 file at publish and consume time — opt-in via `racer.schema.enabled=true`. |
| **Retention lifecycle** | `RacerRetentionService` automatically trims streams (`XTRIM MAXLEN`) and prunes stale DLQ entries on a configurable cron schedule. |
| **Config-driven channels** | Add `racer.channels.payments.name=racer:payments` → channel exists at startup. No broker admin, no exchange bindings. |
| **Tiny footprint** | `racer` is 35 KB. Easy to audit, fork, and extend. |

---

### Disadvantages & Mitigations

| Disadvantage | Impact | Mitigation | Status |
|-------------|--------|------------|--------|
| **No exchange/routing layer** | Flat channel names only; no wildcards, header routing, or fan-out exchanges | Route manually by publishing to multiple channels | ✅ **Implemented** — `@RacerRoute` + `RacerRouterService` (R-1) |
| **Pub/Sub drops messages when no subscriber** | Messages lost if consumer is offline | Use Redis Streams for durable delivery | ✅ **Implemented** — `@PublishResult(durable=true)` + `RacerStreamConsumerService` (R-2) |
| **No built-in monitoring** | No management UI | Redis `INFO`/`XINFO` via `redis-cli` | ✅ **Implemented** — `RacerMetrics` + Actuator + Prometheus/Grafana (R-3) |
| **No message TTL / expiry** | Streams and DLQ grow indefinitely | `DELETE /api/dlq/clear` for manual cleanup | ✅ **Implemented** — `RacerRetentionService` — `@Scheduled` XTRIM + DLQ age pruning (R-4) |
| **No cross-channel transactions** | Can't atomically publish to multiple channels | Sequential publish (at-most-once) | ✅ **Implemented** — `RacerTransaction` (R-5) |
| **Single Redis = single point of failure** | No built-in clustering at the broker level | Spring Data Redis supports Sentinel/Cluster natively | ✅ **Implemented** — `compose.sentinel.yaml` + `compose.cluster.yaml` (R-6) |
| **No schema registry** | Raw JSON; no schema evolution guards | `@JsonTypeInfo` versioned DTOs | ✅ **Implemented** — `RacerSchemaRegistry` JSON Schema Draft-07 validation on publish & consume paths; opt-in via `racer.schema.enabled=true`; REST API at `/api/schema` (R-7) |
| **Limited consumer scaling** | One stream = one partition; no auto-rebalancing | Multiple consumer group members share 1 stream | ✅ **Implemented** — `@RacerStreamListener(concurrency=N)` + `RacerShardedStreamPublisher` (R-8) |
| **Throughput ceiling** | Redis single-threaded per shard; dedicated brokers win at millions of msg/sec | 100K+ msg/sec easily handled for most apps | ✅ **Implemented** — `RacerPipelinedPublisher` (R-9) |
| **No message priority** | FIFO only | Use `async=false` for critical channels | ✅ **Implemented** — `RacerPriorityPublisher` + `RacerPriorityConsumerService` (R-10) |
| **No replay / offset seek** | Cannot re-read historical messages from an offset | Use `XRANGE` / `XREVRANGE` directly via `redis-cli` | ❌ Not planned — use Kafka when full replay is required |
| **No exactly-once semantics** | At-least-once delivery; duplicate messages possible on consumer restart | Idempotent consumers (deduplicate on `RacerMessage.id`) | ❌ Not planned — Redis MULTI/EXEC does not span network partitions |

---

### When to Use What

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Use Racer when...                                                           │
│  ✓ Redis is already in your stack                                            │
│  ✓ You want reactive, non-blocking messaging without a separate broker       │
│  ✓ You need sub-millisecond pub/sub + optional durability via Streams        │
│  ✓ You want content-based routing, message priority, and schema validation   │
│    without standing up a separate routing or schema-registry service         │
│  ✓ You need pipelined batch publishing or key-based consumer sharding        │
│  ✓ You want a library, not another infrastructure component to operate       │
│  ✓ Team is small and operational simplicity is a priority                   │
├──────────────────────────────────────────────────────────────────────────────┤
│  Use RabbitMQ when...                                                        │
│  ✓ You need per-message TTL, dead-letter exchanges, and quorum queues        │
│  ✓ You need multi-protocol support (MQTT for IoT, STOMP for web clients)    │
│  ✓ You want a management UI and alerting out of the box                     │
│  ✓ You need sophisticated exchange bindings between many heterogeneous       │
│    producers and consumers (topic, headers, fanout exchanges)                │
├──────────────────────────────────────────────────────────────────────────────┤
│  Use Apache Kafka when...                                                    │
│  ✓ You need millions of messages/sec with horizontal partition scaling       │
│  ✓ You need full log replay (re-read historical messages by offset)          │
│  ✓ You need exactly-once semantics and distributed transactions              │
│  ✓ You're building event-sourcing / CQRS / stream-processing architecture   │
│  ✓ You need a schema registry for Avro / Protobuf contract enforcement       │
├──────────────────────────────────────────────────────────────────────────────┤
│  Use ActiveMQ when...                                                        │
│  ✓ You need JMS compliance for enterprise Java integration                   │
│  ✓ You're integrating with legacy systems that speak JMS/STOMP               │
│  ✓ You need XA transactions (two-phase commit with a database)              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Roadmap & Implementation Status

All roadmap items through Phase 4 have been **fully implemented**. See [CHANGELOG.md](CHANGELOG.md) for the full release notes.

### ✅ Phase 4 — Done (v1.3.0)

| # | Feature | Description | Key Artifacts |
|---|---------|-------------|---------------|
| 4.1 | **Cluster-Aware Publishing** | Consistent-hash routing across shards with automatic failover | `RacerConsistentHashRing`, `racer.sharding.consistent-hash-enabled` |
| 4.2 | **Distributed Tracing** | W3C `traceparent` propagation through `RacerMessage` hops; MDC integration | `RacerTraceContext`, `RacerTracingInterceptor`, `racer.tracing.enabled` |
| 4.3 | **Per-Channel Rate Limiting** | Redis token-bucket rate limiter; fail-open on Redis outage | `RacerRateLimiter`, `RacerRateLimitException`, `racer.rate-limit.enabled` |
| 4.4 | **Admin UI** | Four REST endpoints + Bootstrap 5 live dashboard at `/racer-admin/` | `RacerAdminController`, `racer.web.admin-enabled` |

---

### ✅ R-1 — Content-Based Routing (`@RacerRoute` / `RacerRouterService`)

**Closes gap:** No exchange/routing layer

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `@RacerRoute` container annotation + `@RacerRouteRule` per-rule annotation (field, matches regex, to channel, sender)
- `RacerRouterService` — scans all beans with `@RacerRoute` at startup via `@PostConstruct`, compiles regex patterns, exposes `route(msg)` and `dryRun()` methods
- `RacerListenerRegistrar` (BeanPostProcessor) — scans all beans for `@RacerListener` methods; routes to `RacerDeadLetterHandler` on failure
- `RouterController` — `GET /api/router/rules` (view compiled rules) + `POST /api/router/test` (dry-run)

**Key files:** `RacerRoute.java`, `RacerRouteRule.java`, `RacerRouterService.java`, `RacerFunctionalRouter.java`, `RoutePredicates.java`, `RouteHandlers.java`, `RouterController.java`

---

### ✅ R-2 — Durable Publishing (`@PublishResult(durable = true)`)

**Closes gap:** Pub/Sub drops messages when no subscriber is active

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- Added `boolean durable()` and `String streamKey()` attributes to `@PublishResult`
- `RacerStreamPublisher` — writes to a Redis Stream via `XADD` instead of Pub/Sub
- `PublishResultAspect` updated to branch: `durable=true` → `RacerStreamPublisher`, else existing Pub/Sub path
- `RacerStreamConsumerService` — consumer group reader using `XREADGROUP`, dispatches to existing processors, DLQ on failure

**Configuration:**
```properties
racer.durable.stream-keys=racer:orders:stream,racer:audit:stream
```

**Key files:** `PublishResult.java`, `RacerStreamPublisher.java`, `RacerStreamConsumerService.java`

---

### ✅ R-3 — Micrometer Metrics

**Closes gap:** No built-in monitoring

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `RacerMetrics` — wired into `RacerChannelPublisherImpl`, `RacerListenerRegistrar`, `DlqReprocessorService`, `RacerClientFactoryBean`
- `ConsumerSubscriber` replaced by `@RacerListener` / `@RacerStreamListener` annotations
- `spring-boot-starter-actuator` + `micrometer-registry-prometheus` added to `racer-demo` POM
- Exposed at `/actuator/metrics` and `/actuator/prometheus`

**Metrics:** `racer.published`, `racer.published.stream`, `racer.consumed`, `racer.failed`, `racer.dlq.reprocessed`, `racer.dlq.size` (gauge), `racer.requestreply.latency` (timer)

**Key files:** `RacerMetrics.java`, `racer-demo/pom.xml`, `application.properties`

---

### ✅ R-4 — Retention Service (`RacerRetentionService`)

**Closes gap:** No message TTL / expiry

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `RetentionProperties` inner class added to `RacerProperties` (streamMaxLen, dlqMaxAgeHours, scheduleCron)
- `RacerRetentionService` — `@Scheduled` service that runs `XTRIM MAXLEN ~<n>` on all durable streams and removes DLQ entries older than the configured age
- `DlqController` extended with `POST /api/dlq/trim` (on-demand run, requires `racer.web.dlq-enabled=true`) and `GET /api/retention/config`

**Configuration:**
```properties
racer.retention.stream-max-len=10000
racer.retention.dlq-max-age-hours=72
racer.retention.schedule-cron=0 0 * * * *
```

**Key files:** `RacerRetentionService.java`, `RacerProperties.java`, `DlqController.java`

---

### ✅ R-5 — Atomic Batch Publishing (`RacerTransaction`)

**Closes gap:** No cross-channel atomicity

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `RacerTransaction` — collects `(alias, payload, sender)` tuples in a list, executes all via `Flux.concat` for strict ordering
- Registered as a Spring bean in `RacerAutoConfiguration`
- `PublisherController` removed; publishing is annotation-driven via `@PublishResult` / `@RacerPublisher`

**Key files:** `RacerTransaction.java`, `PublisherController.java`

---

### ✅ R-6 — High Availability (Sentinel & Cluster)

**Closes gap:** Single Redis = single point of failure

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `compose.sentinel.yaml` — 1 primary + 1 replica + 3 Sentinel nodes, ready for `docker compose up`
- `compose.cluster.yaml` — 6-node Redis Cluster (3 primaries + 3 replicas) with auto-init container
- HA configuration snippets added (commented block) in both `application.properties`
- See [High Availability](#high-availability) section and [Tutorial 15](TUTORIALS.md#tutorial-15--high-availability-sentinel--cluster)

**Key files:** `compose.sentinel.yaml`, `compose.cluster.yaml`

---

### Implementation summary

| # | Feature | Status | Key Artifact |
|---|---------|--------|--------------|
| R-1 | Content-Based Routing | ✅ Done | `@RacerRoute`, `RacerRouterService`, `RouterController` |
| R-2 | Durable Publish | ✅ Done | `@PublishResult(durable=true)`, `RacerStreamPublisher`, `RacerStreamConsumerService` |
| R-3 | Micrometer Metrics | ✅ Done | `RacerMetrics`, Actuator, Prometheus |
| R-4 | Retention & Pruning | ✅ Done | `RacerRetentionService`, `/api/retention/trim` |
| R-5 | Atomic Batch Publish | ✅ Done | `RacerTransaction` |
| R-6 | HA — Sentinel + Cluster | ✅ Done | `compose.sentinel.yaml`, `compose.cluster.yaml` |
| R-7 | Schema Registry | ✅ Implemented | `RacerSchemaRegistry` — JSON Schema Draft-07 validation on publish & consume paths; opt-in via `racer.schema.enabled=true`; REST API at `/api/schema` |
| R-8 | Consumer Scaling + Sharding | ✅ Done | `@RacerStreamListener(concurrency=N)`, `RacerShardedStreamPublisher` |
| R-9 | Throughput — Pipelining | ✅ Done | `RacerPipelinedPublisher` |
| R-10 | Message Priority | ✅ Done | `@RacerPriority`, `RacerPriorityPublisher`, `RacerPriorityConsumerService` |
| R-11 | Scheduled Publishing | ✅ Done | `@RacerPoll`, `RacerPollRegistrar` |
| R-12 | Declarative Consumers | ✅ Done | `@RacerListener`, `@RacerStreamListener`, `RacerListenerRegistrar`, `RacerStreamListenerRegistrar` |
| R-13 | Publisher Concurrency Control | ✅ Done | `@PublishResult(mode=CONCURRENT)`, `PublishResultAspect` |

---

### ✅ R-8 — Consumer Scaling + Key-Based Sharding

**Closes gap:** Limited consumer scaling — single hardcoded consumer per stream

**Status:** **DONE**

**What was implemented:**
- `RacerStreamListenerRegistrar` (BeanPostProcessor) — scans all beans for `@RacerStreamListener` methods and spawns N consumers per stream (e.g. `consumer-0 … consumer-3`) within the same consumer group
- `RacerShardedStreamPublisher` — shard-aware stream publisher; computes shard index via CRC-16/CCITT modulo `racer.sharding.shard-count`; activated by `@ConditionalOnProperty(racer.sharding.enabled=true)`
- `ShardingProperties` — `racer.sharding.enabled`, `shard-count`, `streams`

**Configuration:**
```properties
# Per-listener configuration (on the annotation):
# @RacerStreamListener(streamKey="racer:orders:stream", group="orders-group",
#     concurrency=3, batchSize=10, pollIntervalMs=200)

racer.sharding.enabled=true
racer.sharding.shard-count=4
racer.sharding.streams=racer:orders:stream,racer:audit:stream
```

**Key files:** `RacerStreamListenerRegistrar.java`, `RacerShardedStreamPublisher.java`, `RacerProperties.java`

---

### ✅ R-9 — Throughput Optimisation (Pipelining)

**Closes gap:** Every publish is a separate Redis round-trip; `RacerTransaction` is sequential

**Status:** **DONE**

**What was implemented:**
- `RacerPipelinedPublisher` — uses `Flux.mergeDelayError` to issue all PUBLISH commands concurrently
- `publishBatch(channel, payloads, sender)` — publishes a list of payloads to the same channel in parallel
- `publishItems(List<PipelineItem>)` — multi-channel pipeline batch (same behaviour as `RacerTransaction` but parallel)
- `RacerTransaction` upgraded — accepts an optional `RacerPipelinedPublisher`; auto-promotes to pipeline when available
- `PipelineProperties` — `racer.pipeline.enabled`, `max-batch-size`

**Configuration:**
```properties
racer.pipeline.enabled=true
racer.pipeline.max-batch-size=100
```

**Key files:** `RacerPipelinedPublisher.java`, `RacerTransaction.java`, `PublisherController.java`

---

### ✅ R-10 — Message Priority

**Closes gap:** All channels are FIFO; no way to express message urgency

**Status:** **DONE**

**What was implemented:**
- `PriorityLevel` enum — `HIGH(0)`, `NORMAL(1)`, `LOW(2)` with numeric weight; `PriorityLevel.of(name)` resolves by name with `NORMAL` fallback
- `@RacerPriority` annotation — `defaultLevel` attribute for use alongside `@PublishResult`
- `RacerMessage.priority` field — `String`, defaults to `"NORMAL"`; backward-compatible (missing field → `NORMAL`)
- `RacerPriorityPublisher` — routes messages to sub-channels keyed `{baseChannel}:priority:{LEVEL}` (e.g. `racer:orders:priority:HIGH`)
- `RacerPriorityConsumerService` (in `racer`) — subscribes to all configured priority sub-channels; buffers arriving messages in a `PriorityBlockingQueue<PrioritizedMessage>` ordered by weight; a drain loop running on `Schedulers.boundedElastic()` processes messages in strict priority order; active only when `racer.priority.enabled=true`
- `@RacerPriority` annotation — `defaultLevel` attribute for use alongside `@PublishResult`; priority routing handled via `RacerPriorityPublisher`
- `PriorityProperties` — `racer.priority.enabled`, `levels`, `strategy`, `channels`

**Configuration:**
```properties
# racer-priority config (racer-demo/application.properties)
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=racer:orders,racer:notifications
```

**Key files:** `PriorityLevel.java`, `@RacerPriority.java`, `RacerPriorityPublisher.java`, `RacerPriorityConsumerService.java`

---

### ✅ R-11 — Scheduled Publishing & Pub/Sub Concurrency Control

**Closes gap:** No declarative way to trigger periodic data ingestion into Racer; Pub/Sub concurrency was hardcoded

**Status:** **DONE**

**What was implemented:**

#### `@RacerPoll` — Scheduled Publishing
- `@RacerPoll` annotation — marks a no-arg method as a scheduled publisher. The method handles all data fetching/computation; the annotation declares only the schedule (`fixedRate` / `cron`) and the destination (`channel` / `channelRef` / `sender`)
- `RacerPollRegistrar` (BeanPostProcessor) — scans all Spring beans for `@RacerPoll` methods at startup; spins up a reactive `Flux.interval` (fixed-rate) or cron-matched ticker per method; invokes the annotated method, unwraps `Mono<?>` return types, and publishes the result to the configured Racer channel
- Supports Spring property placeholders (`${…}`) in all string attributes
- Return types: `String` (as-is), any serializable object (JSON), `Mono<?>` (unwrapped), `void`/`null` (skipped)
- Metrics: `totalPolls` / `totalErrors` counters; optionally records via `RacerMetrics`
- `PollProperties` — `racer.poll.enabled`

#### Pub/Sub Concurrency Control
- `RacerListenerRegistrar` — `flatMap` concurrency now configurable per-listener via `@RacerListener(concurrency=N)` (default 256)
- `PubSubProperties` — `racer.pubsub.concurrency` (global default)

**Key files:** `@RacerPoll.java`, `RacerPollRegistrar.java`, `RacerProperties.java`, `RacerListenerRegistrar.java`

---

### ✅ R-12 — Declarative Channel Consumers (`@RacerListener`)

**Closes gap:** No annotation-driven way for application beans to subscribe to a Pub/Sub channel; all consumers were hardcoded in `ConsumerSubscriber`

**Status:** **DONE**

**What was implemented:**

- `@RacerListener` annotation — marks a method as a reactive channel subscriber. Attributes: `channel`, `channelRef`, `mode` (`SEQUENTIAL` / `CONCURRENT`), `concurrency`, `id`
- `ConcurrencyMode` enum — `SEQUENTIAL` (concurrency = 1, ordered) and `CONCURRENT` (up to N parallel workers)
- `RacerDeadLetterHandler` interface (`com.cheetah.racer.listener`) — SPI in `racer` so the registrar can forward failed messages to the DLQ without a direct dependency on `racer-client`
- `RacerListenerRegistrar` (BeanPostProcessor, extends `AbstractRacerRegistrar`) — scans all Spring beans for `@RacerListener` methods at startup; resolves channel names (direct or via alias); subscribes to `ReactiveRedisMessageListenerContainer`; dispatches on the dedicated Racer thread pool (`racer-worker-*`) using `flatMap(handler, effectiveConcurrency)`; runs schema validation and router checks; records `processedCount`/`failedCount` per listener; forwards exceptions to `RacerDeadLetterHandler`; disposes all subscriptions gracefully via `SmartLifecycle.stop()`
- Flexible parameter dispatch: `RacerMessage` → full envelope; `String` → raw payload; any type `T` → `objectMapper.readValue(payload, T.class)`
- `DeadLetterQueueService` updated to `implements RacerDeadLetterHandler`
- `RacerAutoConfiguration` — registers `racerListenerRegistrar` bean under `@ConditionalOnBean(ReactiveRedisMessageListenerContainer.class)` with all collaborators (`ObjectMapper`, `RacerPublisherRegistry`, `RacerRouterService`, `RacerSchemaValidator`, `RacerDeadLetterHandler`, `MeterRegistry`) as `Optional<>` parameters

**Configuration:** no new properties required — channel names and concurrency are set directly on the annotation or via existing `racer.channels.*` aliases.

**Key files:** `ConcurrencyMode.java`, `@RacerListener.java`, `RacerDeadLetterHandler.java`, `RacerListenerRegistrar.java`, `DeadLetterQueueService.java` (updated), `RacerAutoConfiguration.java` (updated)

---

### ✅ R-13 — Publisher Concurrency Control (`@PublishResult` CONCURRENT mode)

**Closes gap:** `@PublishResult` on `Flux<T>` methods always published elements sequentially via fire-and-forget `doOnNext`; no way to control how many Redis `PUBLISH` calls ran in parallel

**Status:** **DONE**

**What was implemented:**

- `@PublishResult` — two new attributes:
  - `mode() ConcurrencyMode` (default `SEQUENTIAL`) — controls dispatch strategy for `Flux<T>` returns
  - `concurrency() int` (default `4`) — maximum in-flight Redis `PUBLISH` operations when `mode = CONCURRENT`
- `PublishResultAspect` — updated `Flux` branch:
  - `SEQUENTIAL` (default): existing `doOnNext` fire-and-forget side-effect behavior unchanged
  - `CONCURRENT`: uses `flatMap(value -> publishValueReactive(value, ...).thenReturn(value), effectiveConcurrency)` — up to N Redis publish commands in-flight simultaneously; subscriber receives each element after its publish completes (backpressure-aware)
- `publishValueReactive(...)` helper — new `Mono<Void>` variant of the publish path used in concurrent mode (always reactive / non-blocking)
- 12 new unit tests in `PublishResultAspectTest` covering: sequential fire-and-forget, concurrent fan-out, concurrency bound enforcement, durable stream path, Mono pass-through, POJO sync/async publish

**Behavior matrix:**

| Return type | Mode | Behavior |
|-------------|------|----------|
| `Mono<T>` | any | `doOnNext` side-effect — mode is ignored |
| `Flux<T>` | `SEQUENTIAL` | `doOnNext` fire-and-forget per element — no backpressure |
| `Flux<T>` | `CONCURRENT` | `flatMap(publish, concurrency)` — N publishes in parallel with backpressure |
| POJO / `void` | any | single publish, sync or async based on `async` flag — mode is ignored |

**Key files:** `@PublishResult.java` (updated), `PublishResultAspect.java` (updated), `PublishResultAspectTest.java` (new)
