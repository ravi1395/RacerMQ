# Racer Properties Cheat Sheet

All properties use the prefix `racer.*` and are configured in `application.properties` (or `application.yml`).

---

## Quick Reference

| Group | Prefix | Purpose |
|-------|--------|---------|
| [Core](#core) | `racer.*` | Default channel, top-level flags |
| [Channels](#channels) | `racer.channels.<alias>.*` | Named channel definitions |
| [Thread Pool](#thread-pool) | `racer.thread-pool.*` | Dedicated listener executor |
| [Pub/Sub Concurrency](#pubsub-concurrency) | `racer.pubsub.*` | Global flatMap ceiling |
| [Request-Reply](#request-reply) | `racer.request-reply.*` | Timeout defaults |
| [Retention](#retention) | `racer.retention.*` | Stream trim & DLQ pruning |
| [DLQ](#dlq) | _(see retention & web)_ | Dead-letter queue behaviour |
| [Schema](#schema) | `racer.schema.*` | Payload validation |
| [Routing](#routing) | `racer.web.*` | REST endpoint toggles |
| [Web API](#web-api-toggles) | `racer.web.*` | Opt-in REST controllers |
| [Priority Channels](#priority-channels) | `racer.priority.*` | Multi-priority sub-channels |
| [Pipelining](#pipelining) | `racer.pipeline.*` | Batch publish optimisation |
| [Sharding](#sharding) | `racer.sharding.*` | Key-based stream sharding |
| [Consumer Scaling](#consumer-scaling) | `racer.consumer.*` | Stream consumer concurrency |
| [Polling](#polling) | `racer.poll.*` | @RacerPoll scheduler toggle |
| [Dedup](#dedup) | `racer.dedup.*` | Message deduplication |
| [Circuit Breaker](#circuit-breaker) | `racer.circuit-breaker.*` | Per-listener circuit breaker |
| [Back-pressure](#back-pressure) | `racer.backpressure.*` | Thread-pool queue back-pressure |
| [Consumer Lag](#consumer-lag) | `racer.consumer-lag.*` | XPENDING lag monitoring & health |

---

## Core

```properties
# Redis channel used when no alias / channelRef is specified
racer.default-channel=racer:messages          # default

# Enable the scheduled retention / pruning job (requires @EnableScheduling)
racer.retention-enabled=false                 # default
```

---

## Channels

Define as many aliases as you need. Replace `<alias>` with your logical name (e.g. `orders`, `stock`).

```properties
racer.channels.<alias>.name=racer:<alias>     # Redis channel key — REQUIRED
racer.channels.<alias>.async=true             # fire-and-forget publish (default: true)
racer.channels.<alias>.sender=racer           # sender label in message envelope (default: "racer")
```

**Example:**
```properties
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

racer.channels.audit.name=racer:audit
racer.channels.audit.async=false              # blocking — guaranteed delivery
racer.channels.audit.sender=audit-service
```

> **`@PublishResult` inheritance:** when `channelRef` maps to a configured alias the annotation's `sender` and `async` attributes fall back to the channel's property values — you only need `@PublishResult(channelRef = "orders")`.

---

## Thread Pool

Dedicated `ThreadPoolExecutor` for all `@RacerListener` / `@RacerStreamListener` dispatches. Isolates listener workload from Spring's shared `boundedElastic()` pool.

```properties
racer.thread-pool.core-size=<2×CPU>           # always-alive threads (default: 2 × availableProcessors)
racer.thread-pool.max-size=<10×CPU>           # pool ceiling; also caps ConcurrencyMode.AUTO (default: 10 × availableProcessors)
racer.thread-pool.queue-capacity=1000         # bounded task queue (default: 1000)
racer.thread-pool.keep-alive-seconds=60       # idle timeout above core-size (default: 60)
racer.thread-pool.thread-name-prefix=racer-worker-  # visible in thread dumps / profilers (default: "racer-worker-")
```

**Sizing hints:**

| Workload | `core-size` | `max-size` |
|----------|-------------|------------|
| I/O-bound (DB, HTTP) | `4–8 × CPU` | `20–50 × CPU` |
| CPU-bound | `1–2 × CPU` | `2–4 × CPU` |
| Mixed / unknown | `2–4 × CPU` | `10–20 × CPU` |

---

## Pub/Sub Concurrency

Controls the global `flatMap` concurrency applied by `ConsumerSubscriber` before messages reach individual listeners.

```properties
racer.pubsub.concurrency=256                  # max in-flight Pub/Sub messages (default: 256)
```

> Set to `1` for strictly serial processing across all listeners on a channel. For per-listener control, use `@RacerListener(mode = ConcurrencyMode.SEQUENTIAL)` instead.

---

## Request-Reply

```properties
racer.request-reply.default-timeout=5s        # timeout when @RacerRequestReply#timeout() is not set (default: "5s")
```

Accepts Spring `Duration` strings: `500ms`, `5s`, `1m30s`, etc.

---

## Retention

Controls XTRIM for durable streams and automatic DLQ pruning.

```properties
racer.retention.stream-max-len=10000          # max entries per stream (XTRIM MAXLEN ~, default: 10 000)
racer.retention.dlq-max-age-hours=72          # prune DLQ entries older than N hours (default: 72)
racer.retention.schedule-cron=0 0 * * * *     # cron for pruning job (default: hourly)
```

Pruning only runs when `racer.retention-enabled=true` and `@EnableScheduling` is active.

---

## Schema

JSON Schema-based payload validation (R-7). Disabled by default.

```properties
racer.schema.enabled=false                    # activate schema registry (default: false)
racer.schema.validation-mode=BOTH             # PUBLISH | CONSUME | BOTH (default: BOTH)
racer.schema.fail-on-violation=true           # throw exception on violation; false = warn only (default: true)

# Per-channel schema — alias or literal Redis channel name as key
racer.schema.schemas.<alias>.location=classpath:schemas/orders-v1.json
racer.schema.schemas.<alias>.inline={"type":"object","required":["orderId"]}  # overrides location
racer.schema.schemas.<alias>.version=1.0
racer.schema.schemas.<alias>.description=Order payload schema
```

---

## Web API Toggles

All REST controllers are opt-in (disabled by default).

```properties
racer.web.dlq-enabled=false                   # expose /api/dlq/**
racer.web.schema-enabled=false                # expose /api/schema/**
racer.web.router-enabled=false                # expose /api/router/rules and /api/router/test
racer.web.channels-enabled=false              # expose GET /api/channels
racer.web.retention-enabled=false             # expose /api/retention/**
```

---

## Priority Channels

Weighted or strict priority sub-channels (R-10).

```properties
racer.priority.enabled=false                  # activate priority routing (default: false)
racer.priority.levels=HIGH,NORMAL,LOW         # comma-separated levels, highest first (default: "HIGH,NORMAL,LOW")
racer.priority.strategy=strict                # strict | weighted (default: "strict")
racer.priority.channels=                      # comma-separated channel aliases to enable (default: all)
```

**How it works:** when enabled, each message is routed to a sub-channel `racer:<name>:priority:<LEVEL>`. The consumer drains `HIGH` completely before `NORMAL` (strict), or in weighted proportions.

---

## Pipelining

Batch publish using Lettuce auto-pipelining (R-9).

```properties
racer.pipeline.enabled=false                  # enable pipelined batch publish (default: false)
racer.pipeline.max-batch-size=100             # max messages per batch (default: 100)
```

---

## Sharding

Key-based stream sharding to distribute load across N shards (R-8).

```properties
racer.sharding.enabled=false                  # activate sharding (default: false)
racer.sharding.shard-count=4                  # number of shards: stream:0 … stream:N-1 (default: 4)
racer.sharding.streams=                       # comma-separated base stream keys to shard
```

**Example:**
```properties
racer.sharding.enabled=true
racer.sharding.shard-count=8
racer.sharding.streams=racer:orders:stream,racer:audit:stream
# → racer:orders:stream:0 … racer:orders:stream:7
```

---

## Consumer Scaling

Controls the number of concurrent consumer loops per durable stream.

```properties
racer.consumer.concurrency=1                  # consumers per stream (default: 1)
racer.consumer.name-prefix=consumer           # prefix for generated consumer names (default: "consumer")
racer.consumer.poll-batch-size=1              # XREADGROUP COUNT argument per poll (default: 1)
racer.consumer.poll-interval-ms=200           # interval (ms) when stream is empty (default: 200)
```

---

## Polling

```properties
racer.poll.enabled=true                       # false disables all @RacerPoll methods at startup (default: true)
```

---

## Dedup

Idempotent message processing via Redis `SET NX EX`. Disabled by default.

```properties
racer.dedup.enabled=false                     # activate deduplication (default: false)
racer.dedup.ttl-seconds=300                   # how long to remember a processed message ID (default: 300)
racer.dedup.key-prefix=racer:dedup:           # Redis key prefix for dedup entries (default: "racer:dedup:")
```

Per-listener opt-in: `@RacerListener(dedup = true)` — requires `racer.dedup.enabled=true`.

---

## Circuit Breaker

Count-based sliding-window circuit breaker per listener. Disabled by default.

```properties
racer.circuit-breaker.enabled=false                    # activate circuit breakers (default: false)
racer.circuit-breaker.failure-rate-threshold=50        # % failures to open circuit (default: 50)
racer.circuit-breaker.sliding-window-size=10           # number of calls in the window (default: 10)
racer.circuit-breaker.wait-duration-in-open-state-seconds=30  # pause before half-open probe (default: 30)
racer.circuit-breaker.permitted-calls-in-half-open-state=3    # probe calls before closing (default: 3)
```

---

## Back-pressure

Monitors the listener thread-pool queue and throttles consumption when the fill ratio is high. Disabled by default.

```properties
racer.backpressure.enabled=false              # activate back-pressure monitor (default: false)
racer.backpressure.queue-threshold=0.80       # fill ratio (0.0–1.0) that triggers back-pressure (default: 0.80)
racer.backpressure.check-interval-ms=1000     # how often to check the queue (default: 1000)
racer.backpressure.stream-poll-backoff-ms=2000  # stream poll interval under back-pressure (default: 2000)
```

---

## Consumer Lag

Periodic `XPENDING` scraper that exports one `racer.stream.consumer.lag` Micrometer gauge per `(stream, group)` pair. When any lag exceeds `lag-down-threshold` the `GET /actuator/health` endpoint flips to `OUT_OF_SERVICE`. Disabled by default.

```properties
racer.consumer-lag.enabled=false              # activate lag monitoring (default: false)
racer.consumer-lag.scrape-interval-seconds=15 # how often to call XPENDING (default: 15)
racer.consumer-lag.lag-warn-threshold=1000    # lag that triggers a WARN log entry (default: 1000)
racer.consumer-lag.lag-down-threshold=10000   # lag that flips /actuator/health to OUT_OF_SERVICE (default: 10000)
                                              # set to 0 to disable the health flip
```

> **Health integration:** when `racer.consumer-lag.enabled=true`, the `GET /actuator/health` response includes a
> `consumer-lag` detail map with each tracked `stream|group` key and its current lag value. If any value
> exceeds `lag-down-threshold`, the overall status becomes `OUT_OF_SERVICE`.

---

## Complete Example `application.properties`

```properties
# ── Core ──────────────────────────────────────────────────────────────────
racer.default-channel=racer:messages

# ── Channels ──────────────────────────────────────────────────────────────
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

racer.channels.audit.name=racer:audit
racer.channels.audit.async=false
racer.channels.audit.sender=audit-service

racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=true
racer.channels.notifications.sender=notification-service

# ── Thread pool (listener isolation) ─────────────────────────────────────
racer.thread-pool.core-size=8
racer.thread-pool.max-size=32
racer.thread-pool.queue-capacity=1000
racer.thread-pool.keep-alive-seconds=60
racer.thread-pool.thread-name-prefix=racer-worker-

# ── Pub/Sub concurrency ────────────────────────────────────────────────────
racer.pubsub.concurrency=256

# ── Request-reply ──────────────────────────────────────────────────────────
racer.request-reply.default-timeout=5s

# ── Retention ──────────────────────────────────────────────────────────────
racer.retention-enabled=true
racer.retention.stream-max-len=50000
racer.retention.dlq-max-age-hours=48
racer.retention.schedule-cron=0 0 * * * *

# ── Schema validation ──────────────────────────────────────────────────────
racer.schema.enabled=true
racer.schema.validation-mode=BOTH
racer.schema.fail-on-violation=true
racer.schema.schemas.orders.location=classpath:schemas/orders-v1.json
racer.schema.schemas.orders.version=1.0

# ── Web API ────────────────────────────────────────────────────────────────
racer.web.dlq-enabled=true
racer.web.channels-enabled=true

# ── Priority channels ─────────────────────────────────────────────────────
racer.priority.enabled=false

# ── Pipelining ────────────────────────────────────────────────────────────
racer.pipeline.enabled=false

# ── Sharding ──────────────────────────────────────────────────────────────
racer.sharding.enabled=false
```

---

## Defaults at a Glance

| Property | Default |
|----------|---------|
| `racer.default-channel` | `racer:messages` |
| `racer.channels.<alias>.async` | `true` |
| `racer.channels.<alias>.sender` | `racer` |
| `racer.thread-pool.core-size` | `2 × CPU` |
| `racer.thread-pool.max-size` | `10 × CPU` |
| `racer.thread-pool.queue-capacity` | `1000` |
| `racer.thread-pool.keep-alive-seconds` | `60` |
| `racer.thread-pool.thread-name-prefix` | `racer-worker-` |
| `racer.pubsub.concurrency` | `256` |
| `racer.request-reply.default-timeout` | `5s` |
| `racer.retention.stream-max-len` | `10000` |
| `racer.retention.dlq-max-age-hours` | `72` |
| `racer.retention.schedule-cron` | `0 0 * * * *` (hourly) |
| `racer.schema.enabled` | `false` |
| `racer.schema.validation-mode` | `BOTH` |
| `racer.schema.fail-on-violation` | `true` |
| `racer.web.*-enabled` | `false` (all) |
| `racer.retention-enabled` | `false` |
| `racer.priority.enabled` | `false` |
| `racer.priority.levels` | `HIGH,NORMAL,LOW` |
| `racer.priority.strategy` | `strict` |
| `racer.pipeline.enabled` | `false` |
| `racer.pipeline.max-batch-size` | `100` |
| `racer.sharding.enabled` | `false` |
| `racer.sharding.shard-count` | `4` |
| `racer.consumer.concurrency` | `1` |
| `racer.consumer.name-prefix` | `consumer` |
| `racer.consumer.poll-batch-size` | `1` |
| `racer.consumer.poll-interval-ms` | `200` |
| `racer.consumer-lag.lag-down-threshold` | `10000` |
| `racer.poll.enabled` | `true` |
