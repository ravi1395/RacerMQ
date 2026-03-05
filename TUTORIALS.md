# Racer — Tutorials

Step-by-step guides for every feature of the Racer messaging framework.
Each tutorial is self-contained and builds on a running Racer instance.

---

## Table of Contents

0. [Build a New App from Scratch with Racer →](TUTORIAL-NEW-APP.md) *(standalone guide — start here if you are building a new service)*

1. [Tutorial 1 — Getting Started: Boot Redis + Run Racer](#tutorial-1--getting-started-boot-redis--run-racer)
2. [Tutorial 2 — Fire-and-Forget Publishing](#tutorial-2--fire-and-forget-publishing)
3. [Tutorial 3 — Consuming Messages (Sync & Async Modes)](#tutorial-3--consuming-messages-sync--async-modes)
4. [Tutorial 4 — Dead Letter Queue & Reprocessing](#tutorial-4--dead-letter-queue--reprocessing)
5. [Tutorial 5 — Two-Way Request-Reply over Pub/Sub](#tutorial-5--two-way-request-reply-over-pubsub)
6. [Tutorial 6 — Two-Way Request-Reply over Redis Streams](#tutorial-6--two-way-request-reply-over-redis-streams)
7. [Tutorial 7 — Annotation-Driven Publishing (@RacerPublisher & @PublishResult)](#tutorial-7--annotation-driven-publishing-racerpublisher--publishresult)
8. [Tutorial 8 — Multiple Channels with Property Configuration](#tutorial-8--multiple-channels-with-property-configuration)
9. [Tutorial 9 — Using Racer as a Library in a New Project](#tutorial-9--using-racer-as-a-library-in-a-new-project)
10. [Tutorial 10 — Content-Based Routing (@RacerRoute)](#tutorial-10--content-based-routing-racerroute)
11. [Tutorial 11 — Durable Publishing (@PublishResult durable=true)](#tutorial-11--durable-publishing-publishresult-durabletrue)
12. [Tutorial 12 — Metrics & Observability (Actuator + Prometheus)](#tutorial-12--metrics--observability-actuator--prometheus)
13. [Tutorial 13 — Retention & DLQ Pruning](#tutorial-13--retention--dlq-pruning)
14. [Tutorial 14 — Atomic Batch Publishing (RacerTransaction)](#tutorial-14--atomic-batch-publishing-racertransaction)
15. [Tutorial 15 — High Availability (Sentinel & Cluster)](#tutorial-15--high-availability-sentinel--cluster)
16. [Tutorial 16 — Consumer Scaling & Stream Sharding](#tutorial-16--consumer-scaling--stream-sharding)
17. [Tutorial 17 — Pipelined Batch Publishing](#tutorial-17--pipelined-batch-publishing)
18. [Tutorial 18 — Message Priority Channels](#tutorial-18--message-priority-channels)
19. [Tutorial 19 — Declarative Channel Consumers (@RacerListener)](#tutorial-19--declarative-channel-consumers-racerlistener)

---

## Tutorial 1 — Getting Started: Boot Redis + Run Racer

### What you'll learn
- Start Redis via Docker Compose
- Build all Racer modules
- Launch `racer-demo` (port 8080) — the single combined demo application
- Confirm everything is connected

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 (set via `JAVA_HOME`) |
| Maven | 3.9+ |
| Docker Desktop | Any recent version |

> **Why JDK 21?** Racer uses Lombok 1.18.x which is incompatible with JDK 25's compiler internals.
> If you have multiple JDKs installed, pin to 21 for every terminal session.

---

### Step 1 — Start Redis

Open **Terminal A**:
```bash
cd /path/to/racer
docker compose -f compose.yaml up -d
```

Verify Redis is up:
```bash
docker ps | grep redis
redis-cli ping          # expected: PONG
```

The `compose.yaml` starts Redis 7-alpine on port `6379` with a named volume `redis-data`
so data survives container restarts.

---

### Step 2 — Build all modules

Open **Terminal B**, pin JDK 21:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
# export JAVA_HOME=/usr/lib/jvm/java-21-openjdk     # Linux

cd /path/to/racer
mvn clean install -DskipTests
```

Expected output:
```
[INFO] racer .............................................. SUCCESS
[INFO] racer-common ....................................... SUCCESS
[INFO] racer-starter ...................................... SUCCESS
[INFO] racer-demo ......................................... SUCCESS
[INFO] BUILD SUCCESS
```

> **Library JARs installed to local Maven repo**
> `mvn install` publishes all four modules to `~/.m2/repository/com/cheetah/`.
> Any other project on your machine can now import `racer-starter` (which pulls in
> `racer-common` and all required transitive dependencies) just by adding the
> dependency below — no manual JAR copying needed:
> ```xml
> <dependency>
>     <groupId>com.cheetah</groupId>
>     <artifactId>racer-starter</artifactId>
>     <version>0.0.1-SNAPSHOT</version>
> </dependency>
> ```
> See [Tutorial 9](#tutorial-9--using-racer-as-a-library-in-a-new-project) for a
> complete walkthrough of building a fresh application this way.

---

### Step 3 — Run racer-demo

In **Terminal B**:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-demo -am spring-boot:run
```

You should see:
```
Started RacerDemoApplication in X.XXX seconds
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
[racer] Channel 'audit'         registered → 'racer:audit'
[racer] @RacerListener registered: DemoConsumer.onMessage → racer:messages
[racer] @RacerStreamListener registered: DemoStreamConsumer.onOrderEvent (group=orders-group, concurrency=2)
```

---

### Step 4 — Smoke test

```bash
# Is racer-demo up?
curl http://localhost:8080/actuator/health
# → {"status":"UP"}

# Are channels registered? (requires racer.web.channels-enabled=true)
curl http://localhost:8080/api/channels
# → {"__default__":{"channel":"racer:messages"}, ...}
```

**Racer is running.** Continue to the next tutorials.

---

## Tutorial 2 — Fire-and-Forget Publishing

### What you'll learn
- Publish messages using `@RacerPublisher` (programmatic injection)
- Publish messages as a side-effect using `@PublishResult`
- Target different channels via property-configured aliases
- Observe messages in `racer-demo` logs

### Prerequisites
Tutorial 1 complete (`racer-demo` running).

---

### Step 1 — Understand the publishing model

Racer publishing is **annotation-driven** and **code-first**. There are two approaches:

| Approach | When to use |
|----------|------------|
| `@RacerPublisher("alias")` field injection | Imperative publishing (explicit call in code) |
| `@PublishResult(channelRef="alias")` on a method | Automatic side-effect — publish method return value |

Both approaches use the channel aliases configured in `application.properties`:
```properties
racer.channels.orders.name=racer:orders
racer.channels.notifications.name=racer:notifications
racer.channels.audit.name=racer:audit
```

---

### Step 2 — Injected publisher (`@RacerPublisher`)

In `racer-demo`, `DemoPublisherService` injects channel publishers by alias:

```java
@Service
public class DemoPublisherService {

    @RacerPublisher("orders")
    private RacerChannelPublisher ordersPublisher;

    @RacerPublisher("notifications")
    private RacerChannelPublisher notificationsPublisher;

    // Asynchronous (fire-and-forget)
    public void sendOrderAsync(String payload) {
        ordersPublisher.publishAsync(payload).subscribe();
    }

    // Synchronous (blocks until Redis confirms delivery)
    public Long sendOrderSync(String payload) {
        return ordersPublisher.publishSync(payload).block();
    }
}
```

Call `sendOrderAsync("order-123")` from another bean or run the included demo endpoint.

---

### Step 3 — Annotation-driven side-effect (`@PublishResult`)

`@PublishResult` publishes the **return value** of a method as a reactive side-effect:

```java
@Service
public class OrderService {

    @PublishResult(channelRef = "orders", sender = "order-service")
    public Mono<Order> createOrder(CreateOrderRequest req) {
        // business logic — the returned Order is automatically
        // published to racer:orders when the Mono completes
        return orderRepository.save(new Order(req));
    }
}
```

The caller receives the `Order` object normally; publishing happens transparently.

---

### Step 4 — Publish to a specific channel

To target the default `racer:messages` channel without a named alias, use:

```java
@RacerPublisher   // no alias → default channel
private RacerChannelPublisher defaultPublisher;

defaultPublisher.publishAsync("Hello world").subscribe();
```

Or use the `channel` attribute directly on `@PublishResult`:
```java
@PublishResult(channel = "racer:notifications", sender = "alert-service")
public Mono<Alert> createAlert(AlertRequest req) { ... }
```

---

### Step 5 — Observe messages in racer-demo logs

With `racer-demo` running, any `@RacerListener`-annotated methods in the application
will log received messages. For example, `DemoConsumer`:

```java
@Component
public class DemoConsumer {

    @RacerListener(channel = "racer:messages", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
    public Mono<Void> onMessage(RacerMessage msg) {
        log.info("[racer:messages] id={} payload={}", msg.getId(), msg.getPayload());
        return Mono.empty();
    }
}
```

You'll see the log lines like:
```
[racer:messages] id=<uuid> payload=Hello world
```

---

---

## Tutorial 3 — Consuming Messages (@RacerListener & @RacerStreamListener)

### What you'll learn
- Declare Pub/Sub consumers with `@RacerListener`
- Control concurrency per consumer
- Declare durable stream consumers with `@RacerStreamListener`
- Observe how failures are routed to the DLQ

### Prerequisites
Tutorial 1 complete.

---

### Step 1 — Declare a Pub/Sub listener

Add `@RacerListener` to any Spring bean method to subscribe to a Pub/Sub channel:

```java
@Slf4j
@Component
public class OrderConsumer {

    @RacerListener(channel = "racer:orders", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
    public Mono<Void> onOrder(RacerMessage message) {
        log.info("[orders] payload={}", message.getPayload());
        return Mono.empty();
    }
}
```

`RacerListenerRegistrar` (a `BeanPostProcessor`) automatically subscribes at startup
via `ReactiveRedisMessageListenerContainer`. No XML wiring needed.

**Supported parameter types:**

| Parameter type | What gets passed |
|---------------|-----------------|
| `RacerMessage` | Full envelope (id, payload, sender, timestamp) |
| `String` | Raw payload string |
| `MyDto` | `objectMapper.readValue(payload, MyDto.class)` |

---

### Step 2 — Concurrency modes

`@RacerListener` supports two concurrency modes:

```java
// Sequential — messages processed one at a time, in order
@RacerListener(channel = "racer:orders", mode = ConcurrencyMode.SEQUENTIAL)
public Mono<Void> onOrderSequential(RacerMessage msg) { ... }

// Concurrent — up to N messages processed in parallel
@RacerListener(channel = "racer:orders", mode = ConcurrencyMode.CONCURRENT, concurrency = 8)
public Mono<Void> onOrderConcurrent(RacerMessage msg) { ... }
```

`SEQUENTIAL` mode is ideal for ordered processing (e.g. account balance updates). 
`CONCURRENT` mode is ideal for high-throughput fan-out processing.

---

### Step 3 — Declare a durable stream listener

For guaranteed delivery even when the consumer is offline, use `@RacerStreamListener`:

```java
@Slf4j
@Component
public class AuditStreamConsumer {

    @RacerStreamListener(
        streamKey      = "racer:audit:stream",
        group          = "audit-group",
        concurrency    = 2,
        batchSize      = 10,
        pollIntervalMs = 100
    )
    public Mono<Void> onAuditEvent(RacerMessage message) {
        log.info("[audit-stream] seq={} payload={}", message.getId(), message.getPayload());
        return Mono.empty();
    }
}
```

Add the stream key to `application.properties`:
```properties
racer.durable.stream-keys=racer:audit:stream
```

`RacerStreamListenerRegistrar` creates the consumer group (`XGROUP CREATE`) at startup
and spawns N independent consumers (`audit-group-0`, `audit-group-1`), each issuing
`XREADGROUP GROUP audit-group consumer-N COUNT 10` in a poll loop.

---

### Step 4 — Trigger a failure and observe DLQ routing

Any exception thrown inside a `@RacerListener` method is forwarded to `RacerDeadLetterHandler`,
which enqueues the failed message to `racer:dlq`:

```java
@RacerListener(channel = "racer:messages")
public Mono<Void> onMessage(RacerMessage msg) {
    if (msg.getPayload().contains("error")) {
        return Mono.error(new RuntimeException("Simulated failure: " + msg.getPayload()));
    }
    return Mono.empty();
}
```

Enable the DLQ REST API to inspect:
```properties
# application.properties
racer.web.dlq-enabled=true
```

```bash
# Check DLQ size
curl http://localhost:8080/api/dlq/size
# → {"dlqSize": 1}

# View DLQ contents
curl http://localhost:8080/api/dlq/messages | jq
```

---

---

## Tutorial 4 — Dead Letter Queue (DLQ)

### What you'll learn
- Understand how messages land in the DLQ
- Enable and use the DLQ REST API
- Inspect DLQ contents
- Republish messages back through the pipeline
- Understand the max-retry limit

### Prerequisites
Tutorial 1 complete. Optionally run Tutorial 3 Step 4 to pre-seed the DLQ.

---

### Step 0 — Enable the DLQ REST API

The DLQ endpoints are **opt-in**. Add to `application.properties`:
```properties
racer.web.dlq-enabled=true
```

Restart `racer-demo`. The `/api/dlq/**` endpoints are now active.

---

### Step 1 — Seed the DLQ

Messages land in the DLQ when a `@RacerListener` method throws an exception.
In `racer-demo`, the `DemoConsumer` listener fails on payloads containing `"error"`:

```java
@RacerListener(channel = "racer:messages")
public Mono<Void> onMessage(RacerMessage msg) {
    if (msg.getPayload().contains("error")) {
        return Mono.error(new RuntimeException("Simulated failure"));
    }
    return Mono.empty();
}
```

Use `redis-cli` to publish directly to Redis, or programmatically via `RacerChannelPublisher`.
Since there's no REST publish endpoint, trigger DLQ seeding from your own code or test:

```bash
# From redis-cli (simplest in a tutorial):
redis-cli PUBLISH racer:messages '{"id":"t4-1","payload":"error message 1","sender":"tutorial-4","timestamp":"2026-01-01T00:00:00Z","retryCount":0}'
redis-cli PUBLISH racer:messages '{"id":"t4-2","payload":"error message 2","sender":"tutorial-4","timestamp":"2026-01-01T00:00:00Z","retryCount":0}'

sleep 1  # give the listener time to process and DLQ them
curl -s http://localhost:8080/api/dlq/size | jq
# { "dlqSize": 2 }
```

---

### Step 2 — Inspect DLQ contents

```bash
curl -s http://localhost:8080/api/dlq/messages | jq
```

Each entry is a `DeadLetterMessage`:
```json
[
  {
    "id":              "<uuid>",
    "originalMessage": {
      "id":         "t4-1",
      "payload":    "error message 1",
      "sender":     "tutorial-4",
      "retryCount": 0
    },
    "errorMessage":   "Simulated failure",
    "exceptionClass": "java.lang.RuntimeException",
    "failedAt":       "2026-03-01T12:00:00Z",
    "attemptCount":   1
  }
]
```

---

### Step 3 — View stats

```bash
curl -s http://localhost:8080/api/dlq/stats | jq
```

```json
{
  "queueSize":        2,
  "totalReprocessed": 0,
  "permanentlyFailed": 0
}
```

---

### Step 4 — Republish a DLQ message back to the channel

Republishing pops a message from the DLQ and re-publishes it to its original channel.
The message flows through subscribed `@RacerListener` methods again with an incremented `retryCount`.

```bash
# Republish one message
curl -s -X POST http://localhost:8080/api/dlq/republish/one | jq
```

```json
{
  "republished": true,
  "subscribers": 1
}
```

To drain the entire DLQ at once:
```bash
curl -s -X POST http://localhost:8080/api/dlq/republish/all | jq
```

```json
{
  "republished": true,
  "count":       1
}
```

> **Note:** DLQ entries containing `"error"` in the payload will fail again and be re-enqueued with
> an incremented `retryCount`. Once `retryCount > MAX_RETRY_ATTEMPTS` (default 3), the message is
> permanently discarded.

---

### Step 5 — Clear the DLQ

When you want a clean slate:
```bash
curl -s -X DELETE http://localhost:8080/api/dlq/clear | jq
# { "cleared": true }
curl -s http://localhost:8080/api/dlq/size | jq
# { "dlqSize": 0 }
```

---

### DLQ lifecycle summary

```
@RacerListener method throws
    → retryCount = 0, message enqueued in racer:dlq

POST /api/dlq/republish/one (requires racer.web.dlq-enabled=true)
    → retryCount++
    → if retryCount > MAX_RETRY_ATTEMPTS (3): permanently discarded
    → else: message re-published to original channel → listeners process again
```

---

## Tutorial 5 — Two-Way Request-Reply over Pub/Sub

### What you'll learn
- Declare a Pub/Sub responder with `@RacerResponder`
- Call it from a typed client interface with `@RacerClient` / `@RacerRequestReply`
- Handle timeouts and error replies
- Send multiple concurrent requests

### Prerequisites
Tutorial 1 complete (`racer-demo` running on port 8080, Redis running).

### How it works

```
racer-demo                              Redis                      racer-demo
(caller / @RacerClient)                                       (@RacerResponder)
────────────────────────────────────────────────────────────────────────────────
EchoClient.echo("ping")
  → generates correlationId = "abc"
  → subscribes to racer:reply:abc ──────────────────────────────────────────→
  → PUBLISH racer:requests ──────────→ Pub/Sub ──────────────────→ EchoResponder
                                                                    handleRequest()
                                                                    processes payload
                                     ←── PUBLISH racer:reply:abc ──
  ← Mono<String> resolves "Processed: ping [by racer-demo]" ───────────────────
```

---

### Step 1 — Define a responder

In `racer-demo`, create a `@Component` with an `@RacerResponder`-annotated method:

```java
package com.cheetah.racer.demo.service;

import com.cheetah.racer.common.annotation.RacerResponder;
import com.cheetah.racer.common.model.RacerMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EchoResponder {

    @RacerResponder(requestChannel = "racer:requests")
    public Mono<String> handleRequest(RacerMessage request) {
        String payload = request.getPayload();
        if (payload.contains("error")) {
            return Mono.error(new RuntimeException("Processing failed: " + payload));
        }
        return Mono.just("Processed: " + payload + " [by racer-demo]");
    }
}
```

- `requestChannel` — the Redis Pub/Sub channel this responder listens on
- The method is called for every inbound request; the returned value is published to the ephemeral reply channel
- A `Mono.error(...)` becomes a failure reply (`success: false, errorMessage: "..."`)

---

### Step 2 — Declare a typed client interface

```java
package com.cheetah.racer.demo.client;

import com.cheetah.racer.common.annotation.RacerClient;
import com.cheetah.racer.common.annotation.RacerRequestReply;
import reactor.core.publisher.Mono;

@RacerClient(timeout = 10)           // default timeout: 10 seconds
public interface EchoClient {

    @RacerRequestReply(channel = "racer:requests")
    Mono<String> echo(String payload);
}
```

Register it in the application class:

```java
@SpringBootApplication
@EnableRacer
@EnableRacerClients(basePackages = "com.cheetah.racer.demo.client")
public class RacerDemoApplication { ... }
```

Inject and call it from any Spring bean:

```java
@Autowired
private EchoClient echoClient;

// ...
echoClient.echo("ping")
    .doOnNext(reply -> log.info("Reply: {}", reply))
    .subscribe();
```

---

### Step 3 — Observe a successful round-trip

Restart `racer-demo` with the new classes in place, then watch the log:

```
[racer-responder] Received request on racer:requests correlationId=abc
[racer-responder] Sent reply to racer:reply:abc: Processed: ping [by racer-demo]
[echo-client] Reply: Processed: ping [by racer-demo]
```

Each request is matched to its reply by `correlationId`. Concurrent requests do not interfere.

---

### Step 4 — Custom timeout

Pass `timeout` on `@RacerClient` to override the default for all methods, or per method:

```java
@RacerClient(timeout = 5)               // 5-second default for all methods
public interface EchoClient {

    @RacerRequestReply(channel = "racer:requests", timeout = 2)   // override to 2 s
    Mono<String> echoFast(String payload);
}
```

If no reply arrives within the timeout a `TimeoutException` propagates in the `Mono`.

---

### Step 5 — Trigger a failure reply

Call `echoClient.echo("this will error")` — the responder returns `Mono.error(...)`:

```
[echo-client] Reply failed: Processing failed: this will error
```

The `Mono` emits an error signal. Wrap in `.onErrorResume(...)` to handle gracefully:

```java
echoClient.echo("this will error")
    .onErrorResume(e -> Mono.just("Error: " + e.getMessage()))
    .doOnNext(log::info)
    .subscribe();
```

---

### Step 6 — Concurrent requests

Because each request uses a unique `correlationId` and ephemeral reply channel, concurrent
calls are fully independent:

```java
Flux.range(1, 3)
    .flatMap(i -> echoClient.echo("concurrent request " + i))
    .doOnNext(reply -> log.info("Got: {}", reply))
    .blockLast();
```

All three replies arrive independently, typically within milliseconds of each other.

---

## Tutorial 6 — Two-Way Request-Reply over Redis Streams

### What you'll learn
- Declare a Stream-based responder with `@RacerResponder(transport = Transport.STREAM)`
- Understand how stream consumer groups provide durable request processing
- Compare Streams vs Pub/Sub for request-reply durability

### Prerequisites
Tutorial 1 complete (`racer-demo` running on port 8080, Redis running).

### How it works

```
racer-demo                             Redis                      racer-demo
(caller / @RacerClient)                                       (@RacerResponder STREAM)
────────────────────────────────────────────────────────────────────────────────────
StreamEchoClient.echo("ping")
  → generates correlationId = "xyz"
  → XADD racer:stream:requests ──────→ Stream ──────────────────→ StreamEchoResponder
                                        (consumer group)           processes request
                                      ←── XADD racer:stream:─────
                                              response:xyz
  polls racer:stream:response:xyz
  (every 200ms, up to timeout)
  ← Mono<String> resolves ─────────────────────────────────────────────────────────
    XACK racer:stream:requests         (entry acknowledged)
    DEL racer:stream:response:xyz      (cleanup)
```

**Key differences vs Pub/Sub:**
- Messages persist in the stream until ACK'd — no message loss if the application restarts mid-flight
- Consumer groups allow multiple instances to share load
- Each entry is a durable record, not a transient broadcast

---

### Step 1 — Define a Stream responder

In `racer-demo`, declare a `@RacerResponder` with `transport = Transport.STREAM`:

```java
package com.cheetah.racer.demo.service;

import com.cheetah.racer.common.annotation.RacerResponder;
import com.cheetah.racer.common.annotation.Transport;
import com.cheetah.racer.common.model.RacerMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class StreamEchoResponder {

    @RacerResponder(requestChannel = "racer:stream:requests", transport = Transport.STREAM)
    public Mono<String> handleStreamRequest(RacerMessage request) {
        String payload = request.getPayload();
        if (payload.contains("error")) {
            return Mono.error(new RuntimeException("Stream processing failed: " + payload));
        }
        return Mono.just("Stream-processed: " + payload + " [by racer-demo]");
    }
}
```

---

### Step 2 — Declare a Stream client interface

```java
package com.cheetah.racer.demo.client;

import com.cheetah.racer.common.annotation.RacerClient;
import com.cheetah.racer.common.annotation.RacerRequestReply;
import com.cheetah.racer.common.annotation.Transport;
import reactor.core.publisher.Mono;

@RacerClient(timeout = 15)
public interface StreamEchoClient {

    @RacerRequestReply(channel = "racer:stream:requests", transport = Transport.STREAM)
    Mono<String> echo(String payload);
}
```

Register with `@EnableRacerClients` in the application class (same as Tutorial 5).

---

### Step 3 — Observe a successful round-trip

Inject `StreamEchoClient` into any bean and call it:

```java
streamEchoClient.echo("stream ping")
    .doOnNext(reply -> log.info("Stream reply: {}", reply))
    .subscribe();
```

Expected log output:
```
[racer-stream-responder] Received entry on racer:stream:requests correlationId=xyz
[racer-stream-responder] Sent reply to racer:stream:response:xyz
[stream-echo-client] Stream reply: Stream-processed: stream ping [by racer-demo]
```

---

### Step 4 — Inspect stream state with redis-cli

While `racer-demo` is running, inspect the streams:
```bash
redis-cli
> XLEN racer:stream:requests
(integer) 0           # 0 — entries are ACK'd and removed after delivery
> KEYS racer:stream:response:*
(empty list)          # response stream deleted after read
```

---

### Step 5 — Durable delivery: restart scenario

Because stream entries persist until ACK'd, in-flight requests survive a restart:

1. Stop `racer-demo` immediately after sending a request (before the response arrives)
2. Verify the entry still exists in Redis:
   ```bash
   redis-cli XRANGE racer:stream:requests - +
   # Entry is still there — not yet ACK'd
   ```
3. Restart `racer-demo` — the responder's consumer group picks up the pending entry automatically:
   ```
   [racer-stream-responder] Reclaimed pending entry on racer:stream:requests correlationId=xyz
   [racer-stream-responder] Sent reply to racer:stream:response:xyz
   ```

---

### Pub/Sub vs Streams — quick reference

| Trait | Pub/Sub (`Transport.PUBSUB`) | Stream (`Transport.STREAM`) |
|-------|----------------------------|-----------------------------|
| Durability | Transient — lost if no subscriber | Persistent until ACK'd |
| Restart recovery | Message lost | Pending entry reclaimed |
| Load sharing | Not supported | Consumer group fan-out |
| Latency | Lowest | Slightly higher (poll interval) |
| Use when | Low-latency fire-and-forget reply | At-least-once reply guarantee |

---

## Tutorial 7 — Annotation-Driven Publishing (@RacerPublisher & @PublishResult)

### What you'll learn
- Activate the Racer annotation framework with `@EnableRacer`
- Inject channel publishers into any Spring bean with `@RacerPublisher`
- Automatically publish method return values with `@PublishResult`
- Use the Channel Registry API to inspect and publish to channels

### Prerequisites
Tutorial 1 complete. `racer-demo` has `@EnableRacer` active.

---

### Step 1 — Inspect registered channels

The `RacerPublisherRegistry` registers a publisher for every alias at startup.
To expose the Channel Registry REST endpoints, enable them in `racer-demo/src/main/resources/application.properties`:

```properties
racer.web.channels-enabled=true
```

Then query the registry at runtime:

```bash
curl -s http://localhost:8080/api/channels | jq
```

```json
{
  "__default__":   { "channel": "racer:messages" },
  "orders":        { "channel": "racer:orders" },
  "notifications": { "channel": "racer:notifications" },
  "audit":         { "channel": "racer:audit" }
}
```

---

### Step 2 — Publish to a named channel via the API

> **Requires** `racer.web.channels-enabled=true` in `application.properties`.

```bash
curl -s -X POST http://localhost:8080/api/channels/publish/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","item":"Widget","qty":3}' | jq
```

```json
{
  "published":   true,
  "alias":       "orders",
  "channel":     "racer:orders",
  "subscribers": 1
}
```

Internally, `ChannelRegistryController` has a field:
```java
@RacerPublisher("orders")
private RacerChannelPublisher ordersPublisher;
```
That field was injected automatically by `RacerPublisherFieldProcessor` — no `@Autowired` needed.

---

### Step 3 — Live @PublishResult demo

`POST /api/channels/publish-annotated` calls a method annotated with `@PublishResult`.
The return value is published to `racer:orders` as a side-effect:

```bash
curl -s -X POST http://localhost:8080/api/channels/publish-annotated \
  -H "Content-Type: application/json" \
  -d '{"item":"Gadget","qty":5}' | jq
```

**What happens under the hood:**
1. WebFlux calls `ChannelRegistryController.publishAnnotated(body)`
2. That delegates to `buildOrderEvent(body)`, which is annotated:
   ```java
   @PublishResult(channelRef = "orders", sender = "channel-controller", async = true)
   public Mono<Map<String, Object>> buildOrderEvent(Map<String, Object> request) { ... }
   ```
3. `PublishResultAspect` wraps the returned `Mono` with `.doOnNext(value → publish to racer:orders)`
4. The HTTP caller receives the enriched map
5. The same map is also published to `racer:orders` in the background

Response:
```json
{
  "item":        "Gadget",
  "qty":         5,
  "eventType":   "ORDER_CREATED",
  "processedAt": "2026-03-01T12:00:00Z",
  "source":      "racer-demo"
}
```

---

### Step 4 — Use @RacerPublisher in your own service

> **Works in any project — not just racer-demo.**
> The snippets below show `racer-demo` as the host, but they apply equally to any
> Spring Boot application that has `racer-starter` on its classpath.
> See [Tutorial 9](#tutorial-9--using-racer-as-a-library-in-a-new-project) for a
> full new-project setup.

Add this to any service that has `racer-starter` as a dependency:

```java
@Service
public class ShipmentService {

    // Injected automatically — no @Autowired, no constructor injection needed
    @RacerPublisher("notifications")
    private RacerChannelPublisher notificationsPublisher;

    @RacerPublisher   // → default channel (racer:messages)
    private RacerChannelPublisher defaultPublisher;

    public Mono<Shipment> ship(Order order) {
        Shipment shipment = Shipment.from(order);
        return shipmentRepository.save(shipment)
                // Notify via the notifications channel
                .flatMap(saved -> notificationsPublisher
                        .publishAsync(Map.of("event", "SHIPPED", "orderId", order.getId()))
                        .thenReturn(saved));
    }
}
```

---

### Step 5 — Use @PublishResult in your own service

```java
@Service
public class InventoryService {

    // Every object returned by reserveStock() is automatically
    // published to racer:audit (blocking — guaranteed delivery)
    @PublishResult(channelRef = "audit", async = false, sender = "inventory-service")
    public StockReservation reserveStock(String sku, int qty) {
        StockReservation reservation = inventoryRepository.reserve(sku, qty);
        return reservation;   // ← this value is published to racer:audit
    }

    // Works with reactive return types too
    @PublishResult(channelRef = "orders")
    public Mono<Order> fulfillOrder(OrderRequest request) {
        return orderRepository.save(request.toOrder());
        // ← the Order inside the Mono is published to racer:orders
    }
}
```

> **Self-invocation warning:** Calling an `@PublishResult` method from within the **same class**
> bypasses the Spring AOP proxy — the annotation will not fire. Call it from another bean,
> or inject `ApplicationContext` and look up `self` to force proxy invocation.

---

### Step 6 — Demo injected publisher endpoints

> **Requires** `racer.web.channels-enabled=true` in `application.properties`.

```bash
# Publish to orders via injected @RacerPublisher("orders") field
curl -s -X POST http://localhost:8080/api/channels/demo/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-002","status":"CONFIRMED"}' | jq
# → { "channel": "racer:orders", "subscribers": 1 }

# Publish to notifications via injected @RacerPublisher("notifications") field
curl -s -X POST http://localhost:8080/api/channels/demo/notifications \
  -H "Content-Type: application/json" \
  -d '{"message":"Your order has shipped"}' | jq
# → { "channel": "racer:notifications", "subscribers": 1 }
```

---

### Step 7 — Concurrent Flux publishing with `mode = CONCURRENT`

By default, when a method annotated with `@PublishResult` returns `Flux<T>`, each element is published as a fire-and-forget side-effect via `doOnNext` (**SEQUENTIAL** mode). This is fine for low-volume streams.

For high-throughput Flux pipelines, set `mode = ConcurrencyMode.CONCURRENT` to publish up to **N** elements to Redis simultaneously using reactive `flatMap`. The subscriber still receives every element downstream, but up to N Redis `PUBLISH` commands are in-flight at once:

```java
import com.cheetah.racer.common.annotation.ConcurrencyMode;

@Service
public class EventBroadcastService {

    // Publish up to 6 events to Redis in parallel.
    // Each event is only delivered downstream after its corresponding publish completes.
    @PublishResult(
        channel     = "racer:events",
        sender      = "event-service",
        mode        = ConcurrencyMode.CONCURRENT,
        concurrency = 6
    )
    public Flux<String> broadcastEvents() {
        return Flux.range(1, 30).map(i -> "event-" + i);
        // → 30 messages are published in batches of 6 concurrent Redis calls
    }
}
```

| Mode | Implementation | When to use |
|------|----------------|-------------|
| `SEQUENTIAL` (default) | `doOnNext` — fire-and-forget | Low-volume; preserve element ordering downstream |
| `CONCURRENT` | `flatMap(publish, N)` — N in-flight | High-throughput Flux pipelines; Redis fanout is the bottleneck |

> **`async` and `CONCURRENT`:** when `mode = CONCURRENT` the pipeline is always reactive
> (non-blocking). The `async` attribute is effectively ignored; all publishes use the
> async path so that `flatMap` controls the actual in-flight concurrency.

---

## Tutorial 8 — Multiple Channels with Property Configuration

### What you'll learn
- Declare and manage multiple Redis channels from `application.properties`
- Understand the `async` and `sender` channel-level defaults
- Add a new channel without touching any Java code
- Observe channel registration at startup

### Prerequisites
Tutorial 1 complete.

> **Using `application.properties` in an external project**
> Everything in this tutorial applies verbatim to any project that imports `racer-starter`.
> Drop the same `racer.channels.*` properties into your own `application.properties`
> and the channels are registered automatically — no configuration class needed.
> See [Tutorial 9](#tutorial-9--using-racer-as-a-library-in-a-new-project) for a
> complete working example.

---

### Step 1 — Understand the current channel config

Open `racer-demo/src/main/resources/application.properties`:

```properties
racer.default-channel=racer:messages

racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=true
racer.channels.notifications.sender=notification-service

racer.channels.audit.name=racer:audit
racer.channels.audit.async=false        # ← blocking: waits for Redis confirmation
racer.channels.audit.sender=audit-service
```

| Alias | Redis channel | async | sender |
|-------|---------------|-------|--------|
| *(default)* | `racer:messages` | — | `racer` |
| `orders` | `racer:orders` | `true` | `order-service` |
| `notifications` | `racer:notifications` | `true` | `notification-service` |
| `audit` | `racer:audit` | `false` | `audit-service` |

---

### Step 2 — Add a new channel (no Java changes)

Add these lines to `racer-demo/src/main/resources/application.properties`:

```properties
racer.channels.payments.name=racer:payments
racer.channels.payments.async=false
racer.channels.payments.sender=payment-gateway
```

Rebuild and restart `racer-demo`:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-demo -am spring-boot:run
```

Startup log now includes:
```
[racer] Channel 'payments' registered → 'racer:payments'
```

---

### Step 3 — Verify via the registry API

> **Requires** `racer.web.channels-enabled=true` in `application.properties`.

```bash
curl -s http://localhost:8080/api/channels | jq
```

```json
{
  "__default__":  { "channel": "racer:messages" },
  "orders":       { "channel": "racer:orders" },
  "notifications":{ "channel": "racer:notifications" },
  "audit":        { "channel": "racer:audit" },
  "payments":     { "channel": "racer:payments" }
}
```

---

### Step 4 — Publish to the new channel

> **Requires** `racer.web.channels-enabled=true` in `application.properties`.

```bash
curl -s -X POST http://localhost:8080/api/channels/publish/payments \
  -H "Content-Type: application/json" \
  -d '{"txId":"TXN-9001","amount":199.99,"currency":"USD"}' | jq
```

```json
{
  "published":   true,
  "alias":       "payments",
  "channel":     "racer:payments",
  "subscribers": 0
}
```

> `subscribers: 0` because the client is not yet subscribed to `racer:payments`.
> See below to add a subscriber.

---

### Step 5 — Subscribe to the new channel

In `racer-demo`, add a `@RacerListener` for the new channel:

```java
@Component
public class PaymentChannelListener {

    @RacerListener(channel = "racer:payments", id = "payments-listener")
    public void onPayment(RacerMessage message) {
        log.info("[payments-listener] received: {}", message.getPayload());
    }
}
```

Restart `racer-demo`. The listener subscribes automatically at startup.

---

### Step 6 — Use @RacerPublisher with the new channel

Once the new alias is declared in properties, you can inject the publisher immediately:

```java
@Service
public class PaymentService {

    @RacerPublisher("payments")
    private RacerChannelPublisher paymentsPublisher;

    public Mono<Payment> charge(ChargeRequest req) {
        return paymentGateway.charge(req)
                .flatMap(payment -> paymentsPublisher
                        .publishAsync(payment)
                        .thenReturn(payment));
    }
}
```

Or annotate the method:
```java
@PublishResult(channelRef = "payments", sender = "payment-gateway", async = false)
public Mono<Payment> charge(ChargeRequest req) {
    return paymentGateway.charge(req);
}
```

**Zero Java changes to `racer-common`** — the new channel exists purely through config.

---

### Step 7 — Channel-level async flag

The `async` flag controls the default `publishSync` vs `publishAsync` call in `RacerChannelPublisherImpl`:

| `async=true` | Returns immediately after sending data to Redis |
| `async=false` | Blocks until Redis confirms the publish (`.block(10s)`) |

For critical channels (payments, audit), always use `async=false` to ensure delivery
confirmation before your service method returns.

---

### Summary: channel configuration cheat-sheet

```properties
# Minimum required per channel:
racer.channels.<alias>.name=racer:<your-key>

# Optional (with defaults):
racer.channels.<alias>.async=true            # default: true
racer.channels.<alias>.sender=my-service     # default: "racer"
```

Rules:
- `alias` = the string you pass to `@RacerPublisher("alias")` or `@PublishResult(channelRef = "alias")`
- `name` = the actual Redis Pub/Sub channel key
- Aliases are case-sensitive
- Missing `name` → channel skipped at startup with a warning log
- Unknown alias in `@RacerPublisher` → falls back to default channel (logged as warning)

---

## Tutorial 9 — Using Racer as a Library in a New Project

### What you'll learn
- Create a brand-new Spring Boot application from scratch
- Import `racer-starter` as a Maven dependency
- Configure channels in `application.properties`
- Inject publishers with `@RacerPublisher`
- Auto-publish return values with `@PublishResult`
- Send messages from your new app and observe them via `redis-cli` or `racer-demo`

### Prerequisites
- Tutorial 1 **Step 2** complete — `mvn clean install` run inside the Racer repo so all
  JARs are in your local Maven cache (`~/.m2`)
- Redis running (`docker compose -f /path/to/racer/compose.yaml up -d`)
- `racer-demo` running on port 8080 (`mvn -pl :racer-demo -am spring-boot:run`) — optional,
  only needed if you want a live consumer to observe

> **No need to run a separate consumer for this tutorial.**
> You can verify message delivery by subscribing with `redis-cli` directly.

---

### Step 1 — Create the project skeleton

Use [Spring Initializr](https://start.spring.io) or create the files manually.
The minimum dependencies you need from Initializr: **Spring Reactive Web**.

Create the directory layout:

```
my-racer-app/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/example/myapp/
        │       ├── MyRacerAppApplication.java
        │       ├── service/
        │       │   ├── OrderService.java
        │       │   └── NotificationService.java
        │       └── controller/
        │           └── OrderController.java
        └── resources/
            └── application.properties
```

---

### Step 2 — Write the POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.3</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>my-racer-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>my-racer-app</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- Racer starter — brings in racer-common, reactive Redis, AOP, Jackson -->
        <dependency>
            <groupId>com.cheetah</groupId>
            <artifactId>racer-starter</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>

        <!-- WebFlux for reactive HTTP endpoints -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Lombok (optional, but matches what Racer uses) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

> **Why `racer-starter` and not `racer-common` directly?**
> `racer-starter` is a thin aggregator that also declares the transitive dependencies
> (reactive Redis, AOP, Jackson) that `racer-common` needs.
> It mirrors the Spring Boot starter pattern — one line in your POM and you're done.

---

### Step 3 — Configure `application.properties`

```properties
# ── Server ──────────────────────────────────────────────────────────────
server.port=8090

# ── Redis ────────────────────────────────────────────────────────────────
spring.data.redis.host=localhost
spring.data.redis.port=6379

# ── Racer ────────────────────────────────────────────────────────────────
# Default channel (used when no alias is specified)
racer.default-channel=racer:messages

# Named channel: orders
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=my-order-service

# Named channel: notifications (blocking — waits for Redis confirmation)
racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=false
racer.channels.notifications.sender=my-notification-service
```

---

### Step 4 — Main application class

```java
package com.example.myapp;

import com.cheetah.racer.common.annotation.EnableRacer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRacer          // activates RacerAutoConfiguration, AOP, registry, field processor
public class MyRacerAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyRacerAppApplication.class, args);
    }
}
```

> **`@EnableRacer` is optional** when `racer-starter` is on the classpath, because the
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file
> registers `RacerAutoConfiguration` automatically.
> Add it explicitly when you want self-documenting intent, or when your project
> does not use the starter (e.g. you import `racer-common` directly).

---

### Step 5 — OrderService — `@PublishResult` and `@RacerPublisher`

```java
package com.example.myapp.service;

import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    /**
     * Injected automatically by RacerPublisherFieldProcessor —
     * no @Autowired, no constructor wiring needed.
     */
    @RacerPublisher("notifications")
    private RacerChannelPublisher notificationsPublisher;

    /**
     * The Map returned here is also published to racer:orders as a side-effect.
     * The HTTP caller still receives the full return value.
     */
    @PublishResult(channelRef = "orders", sender = "my-order-service", async = true)
    public Mono<Map<String, Object>> placeOrder(String item, int qty) {
        Map<String, Object> order = Map.of(
                "orderId",   UUID.randomUUID().toString(),
                "item",      item,
                "qty",       qty,
                "status",    "CREATED",
                "createdAt", Instant.now().toString()
        );
        // Return value is automatically published to racer:orders by @PublishResult
        return Mono.just(order);
    }

    /**
     * Manual publish using @RacerPublisher-injected field.
     */
    public Mono<Void> notifyShipped(String orderId) {
        Map<String, Object> event = Map.of(
                "event",   "ORDER_SHIPPED",
                "orderId", orderId,
                "at",      Instant.now().toString()
        );
        return notificationsPublisher.publishAsync(event).then();
    }
}
```

---

### Step 6 — NotificationService — default channel

```java
package com.example.myapp.service;

import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class NotificationService {

    // No alias → injects the default channel publisher (racer:messages)
    @RacerPublisher
    private RacerChannelPublisher defaultPublisher;

    public Mono<Long> broadcast(String message) {
        return defaultPublisher.publishAsync(Map.of("message", message));
    }
}
```

---

### Step 7 — REST controller

```java
package com.example.myapp.controller;

import com.example.myapp.service.NotificationService;
import com.example.myapp.service.OrderService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final NotificationService notificationService;

    public OrderController(OrderService orderService,
                           NotificationService notificationService) {
        this.orderService = orderService;
        this.notificationService = notificationService;
    }

    /** Place an order — return value is published to racer:orders via @PublishResult */
    @PostMapping("/orders")
    public Mono<Map<String, Object>> placeOrder(@RequestBody Map<String, Object> body) {
        String item = (String) body.get("item");
        int qty = ((Number) body.get("qty")).intValue();
        return orderService.placeOrder(item, qty);
    }

    /** Manually trigger a shipment notification to racer:notifications */
    @PostMapping("/orders/{orderId}/ship")
    public Mono<Map<String, String>> shipOrder(@PathVariable String orderId) {
        return orderService.notifyShipped(orderId)
                .thenReturn(Map.of("status", "notified", "orderId", orderId));
    }

    /** Broadcast a message to the default channel (racer:messages) */
    @PostMapping("/broadcast")
    public Mono<Map<String, Object>> broadcast(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        return notificationService.broadcast(message)
                .map(subscribers -> Map.of(
                        "message",     message,
                        "subscribers", subscribers,
                        "channel",     "racer:messages"
                ));
    }
}
```

---

### Step 8 — Build and run

```bash
cd my-racer-app
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean package -DskipTests
java -jar target/my-racer-app-1.0.0-SNAPSHOT.jar
```

Or run directly with Maven:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Startup log should include:
```
Started MyRacerAppApplication in X.XXX seconds
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
```

---

### Step 9 — Test it end-to-end

To observe received messages, open a redis-cli subscriber in a separate terminal:
```bash
redis-cli SUBSCRIBE racer:orders racer:notifications racer:messages
```

From another terminal, exercise the app:

**Place an order (published to racer:orders via @PublishResult):**
```bash
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{"item":"Widget","qty":3}' | jq
```

```json
{
  "orderId":   "550e8400-...",
  "item":      "Widget",
  "qty":       3,
  "status":    "CREATED",
  "createdAt": "2026-03-01T12:00:00Z"
}
```

In a Redis client you can verify the message was published:
```bash
redis-cli SUBSCRIBE racer:orders
# (in another terminal:)
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{"item":"Gadget","qty":1}'
```

**Ship an order (published to racer:notifications via @RacerPublisher):**
```bash
curl -s -X POST http://localhost:8090/api/orders/ORD-123/ship | jq
```

```json
{ "status": "notified", "orderId": "ORD-123" }
```

The notification appears in the `redis-cli SUBSCRIBE` output on the `racer:notifications` channel.

**Broadcast to the default channel:**
```bash
curl -s -X POST http://localhost:8090/api/broadcast \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello from my-racer-app"}' | jq
```

```json
{
  "message":     "Hello from my-racer-app",
  "subscribers": 1,
  "channel":     "racer:messages"
}
```

---

### What you built

```
my-racer-app (port 8090)                Redis               redis-cli / racer-demo
──────────────────────────────────────────────────────────────────────────────────────
POST /api/orders
  → OrderService.placeOrder()
    @PublishResult intercepts ─────────→ racer:orders ──────────────────────────────→
  ← HTTP 200 (order map)

POST /api/orders/{id}/ship
  → notificationsPublisher.publishAsync ─→ racer:notifications ────────────────────→
  ← HTTP 200

POST /api/broadcast
  → defaultPublisher.publishAsync ──────→ racer:messages ─────────────────────────→
  ← HTTP 200
```

---

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Could not find artifact com.cheetah:racer-starter` | Run `mvn clean install -DskipTests` inside the Racer repo first |
| `@RacerPublisher` field is `null` at runtime | Ensure the bean is a Spring-managed `@Component`/`@Service` — not instantiated with `new` |
| `@PublishResult` method never publishes | The method must be called via the Spring proxy (from another bean, not from within the same class) |
| `WRONGTYPE Operation against a key` in Redis | A key was previously used as a different data type; flush with `redis-cli FLUSHDB` |
| `Connection refused` on Redis | Redis is not running — `docker compose -f /path/to/racer/compose.yaml up -d` |
| `@EnableRacer` not found | Add `racer-starter` (or `racer-common`) to your POM |

---

## Next Steps

| Feature | Docs |
|---------|------|
| Full API reference | [README.md — API Reference](README.md#api-reference--server-port-8080) |
| Message schemas | [README.md — Message Schemas](README.md#message-schemas) |
| End-to-end flow diagrams | [README.md — End-to-End Flows](README.md#end-to-end-flows) |
| Extending Racer | [README.md — Extending the Application](README.md#extending-the-application) |
| Error handling & DLQ behaviour table | [README.md — Error Handling](README.md#error-handling--dlq-behaviour) |
| Observability | [README.md — Observability & Metrics](README.md#observability--metrics) |
| High Availability | [README.md — High Availability](README.md#high-availability) |

---

## Tutorial 10 — Content-Based Routing (@RacerRoute)

### What you'll learn
- Define routing rules with `@RacerRoute` and `@RacerRouteRule`
- How `RacerRouterService` evaluates regex rules against inbound message fields
- Inspect compiled rules at runtime via `GET /api/router/rules`
- Dry-run routing with `POST /api/router/test`

### Prerequisites
- `racer-demo` running on port 8080 — see Tutorial 1
- At least two channel aliases configured (e.g. `orders` and `notifications`)

---

### Step 1 — Define a router bean

Create a `@Component` class in `racer-demo` (or your own library module) annotated with `@RacerRoute`:

```java
@Component
@RacerRoute({
    @RacerRouteRule(field = "type", matches = "^ORDER.*",        to = "racer:orders"),
    @RacerRouteRule(field = "type", matches = "^NOTIFICATION.*", to = "racer:notifications"),
    @RacerRouteRule(field = "sender", matches = "payment-svc",   to = "racer:payments",
                    sender = "router")
})
public class MessageRouter { }
```

- `field` — any top-level key in the JSON payload
- `matches` — a Java regex applied to that field's value
- `to` — the target Redis channel/key if the rule fires
- Rules are evaluated in order; the **first match wins**

---

### Step 2 — Restart and verify compiled rules

To expose the Router REST endpoints, enable them in `racer-demo/src/main/resources/application.properties`:

```properties
racer.web.router-enabled=true
```

Restart `racer-demo`, then list the compiled rules:

```bash
curl -s http://localhost:8080/api/router/rules | python3 -m json.tool
```

Expected output:
```json
[
  { "index": 0, "field": "type",   "pattern": "^ORDER.*",        "to": "racer:orders" },
  { "index": 1, "field": "type",   "pattern": "^NOTIFICATION.*", "to": "racer:notifications" },
  { "index": 2, "field": "sender", "pattern": "payment-svc",     "to": "racer:payments" }
]
```

---

### Step 3 — Dry-run a message

Use `POST /api/router/test` to see which rule (if any) would match:

```bash
# Should match rule index 0
curl -s -X POST http://localhost:8080/api/router/test \
  -H "Content-Type: application/json" \
  -d '{"type":"ORDER_CREATED","id":"123","amount":49.99}' | python3 -m json.tool
```

Expected:
```json
{
  "matched":   true,
  "ruleIndex": 0,
  "field":     "type",
  "pattern":   "^ORDER.*",
  "to":        "racer:orders"
}
```

```bash
# Should not match
curl -s -X POST http://localhost:8080/api/router/test \
  -H "Content-Type: application/json" \
  -d '{"type":"UNKNOWN","id":"999"}' | python3 -m json.tool
```

Expected:
```json
{ "matched": false }
```

---

### Step 4 — Publish a real message and observe routing

Publish a message whose `type` starts with `ORDER` directly via redis-cli:

```bash
redis-cli PUBLISH racer:messages '{"type":"ORDER_PLACED","id":"42","sender":"checkout"}'
```

In `racer-demo` logs you should see the router dispatch the message to `racer:orders` rather than the default channel path.

---

### What you built
A content-based message router that evaluates inbound messages against regex rules and forwards them to the correct Redis channel — equivalent to RabbitMQ topic exchange semantics, without leaving the Redis ecosystem.

---

## Tutorial 11 — Durable Publishing (@PublishResult durable=true)

### What you'll learn
- Use `@PublishResult(durable=true)` to write to a Redis Stream instead of Pub/Sub
- Configure `racer-demo` to consume durable streams via `@RacerStreamListener`
- Verify guaranteed delivery when the consumer was offline at publish time

### Prerequisites
- `racer-demo` running — see Tutorial 1

---

### Step 1 — Annotate a method for durable publish

In your service (inside `racer-demo`), add:

```java
@PublishResult(durable = true, streamKey = "racer:orders:stream", sender = "order-svc")
public Mono<String> placeOrder(String orderJson) {
    // your business logic
    return Mono.just(orderJson);
}
```

- `durable = true` switches from `PUBLISH` (Pub/Sub) to `XADD` (Stream)
- `streamKey` is the Redis key of the stream to write to
- The return value is still passed through to the caller unchanged

---

### Step 2 — Configure the stream consumer

In `racer-demo/src/main/resources/application.properties`:

```properties
racer.durable.stream-keys=racer:orders:stream
```

Or use a `@RacerStreamListener` directly in your component:

```java
@Component
public class DurableOrderConsumer {

    @RacerStreamListener(
        streamKey   = "racer:orders:stream",
        groupName   = "racer-durable-group",
        consumerName = "demo-consumer",
        batchSize   = 10
    )
    public void onDurableOrder(RacerMessage message) {
        log.info("[durable] Consumed from racer:orders:stream: {}", message.getPayload());
    }
}
```

`RacerStreamConsumerService` will automatically create the consumer group on first startup.

---

### Step 3 — Publish with the consumer offline

Stop `racer-demo`:
```bash
# Press Ctrl+C in the racer-demo terminal
```

Publish a durable message directly to the stream:
```bash
redis-cli XADD racer:orders:stream '*' payload '{"type":"ORDER_DURABLE","id":"101"}' sender test
```

Verify the entry was written:
```bash
redis-cli XLEN racer:orders:stream
# Expected: 1
redis-cli XRANGE racer:orders:stream - +
```

---

### Step 4 — Restart the consumer and verify delivery

Restart `racer-demo`:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-demo -am spring-boot:run
```

Watch the logs — within a few seconds you should see:
```
[durable] Consumed from racer:orders:stream: {"type":"ORDER_DURABLE","id":"101"}
```

Verify the entry has been acknowledged (no pending entries):
```bash
redis-cli XPENDING racer:orders:stream racer-durable-group - + 10
# Expected: (empty list or [])
```

---

### What you built
At-least-once guaranteed delivery: the message was stored in a Redis Stream while the consumer was offline, and was processed exactly once after it came back online.

---

## Tutorial 12 — Metrics & Observability (Actuator + Prometheus)

### What you'll learn
- Access Spring Boot Actuator health and metrics endpoints
- Query individual Racer metrics by name
- Scrape the Prometheus endpoint for integration with Grafana

### Prerequisites
- `racer-demo` running on port 8080 — see Tutorial 1
- `racer-demo/src/main/resources/application.properties` has:
  ```properties
  management.endpoints.web.exposure.include=health,info,metrics,prometheus
  ```

---

### Step 1 — Check health

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

Expected:
```json
{ "status": "UP" }
```

---

### Step 2 — List all metric names

```bash
curl -s http://localhost:8080/actuator/metrics | python3 -m json.tool | grep racer
```

You should see entries like:
```
"racer.published",
"racer.consumed",
"racer.failed",
"racer.dlq.size",
"racer.requestreply.latency",
"racer.listener.processed"
```

---

### Step 3 — Generate traffic and query a metric

Publish some messages via redis-cli to generate traffic:
```bash
for i in $(seq 1 10); do
  redis-cli PUBLISH racer:messages "{\"payload\":\"hello $i\",\"sender\":\"tutorial\"}"
done
```

Now query the consume counter:
```bash
curl -s "http://localhost:8080/actuator/metrics/racer.consumed" | python3 -m json.tool
```

Expected:
```json
{
  "name": "racer.consumed",
  "measurements": [{ "statistic": "COUNT", "value": 10.0 }],
  "availableTags": [{ "tag": "listener", "values": ["default-listener"] }]
}
```

---

### Step 4 — Prometheus scrape

```bash
curl -s http://localhost:8080/actuator/prometheus | grep "^racer"
```

Expected output (sample):
```
racer_published_total{application="racer-demo",transport="pubsub",} 10.0
racer_consumed_total{application="racer-demo",} 10.0
racer_dlq_size{application="racer-demo",} 0.0
```

This endpoint is ready to be scraped by Prometheus. Add to your `prometheus.yml`:
```yaml
scrape_configs:
  - job_name: racer-demo
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']
```

---

### Step 5 — Request-reply latency

After exercising the `@RacerClient` / `@RacerResponder` pair (see Tutorial 5), check the latency timer:

```bash
curl -s "http://localhost:8080/actuator/metrics/racer.requestreply.latency" | python3 -m json.tool
```

---

### What you built
Full operational visibility into Racer. Every publish, consume, failure, and round-trip latency is tracked in Micrometer and exportable to any observability backend.

---

## Tutorial 13 — Retention & DLQ Pruning

### What you'll learn
- Configure automatic stream trimming and DLQ age-based pruning
- Trigger an immediate on-demand retention run
- Inspect current retention settings via the REST API

### Prerequisites
- `racer-demo` running on port 8080 — see Tutorial 1
- Enable the REST APIs in `racer-demo/src/main/resources/application.properties`:
  ```properties
  racer.web.dlq-enabled=true
  racer.web.retention-enabled=true
  ```

---

### Step 1 — Configure retention in racer-demo

Edit `racer-demo/src/main/resources/application.properties`:

```properties
# Keep at most 100 entries per durable stream (for demo purposes)
racer.retention.stream-max-len=100

# Prune DLQ entries older than 1 hour
racer.retention.dlq-max-age-hours=1

# Run every minute (for demo) — change to hourly (0 0 * * * *) for production
racer.retention.schedule-cron=0 * * * * *
```

Restart `racer-demo`.

---

### Step 2 — View current retention config

```bash
curl -s http://localhost:8080/api/retention/config | python3 -m json.tool
```

Expected:
```json
{
  "streamMaxLen":   100,
  "dlqMaxAgeHours": 1,
  "scheduleCron":   "0 * * * * *"
}
```

---

### Step 3 — Generate some DLQ entries

Force processing failures by publishing a message that triggers a listener error.
Add a failing `@RacerListener` in `racer-demo` (see Tutorial 19, Step 6), then publish:

```bash
for i in $(seq 1 5); do
  redis-cli PUBLISH racer:messages '{"payload":"__FORCE_FAIL__","sender":"tutorial"}'
done
```

Or use the channels REST API (requires `racer.web.channels-enabled=true`):
```bash
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/api/channels/publish/__default__ \
    -H "Content-Type: application/json" \
    -d '{"payload":"__FORCE_FAIL__","sender":"tutorial"}' > /dev/null
done
```

Check DLQ depth:
```bash
curl -s http://localhost:8080/api/dlq/size
redis-cli LLEN racer:dlq
```

---

### Step 4 — Trigger immediate trim

Rather than waiting for the scheduler, trigger manually:
```bash
curl -s -X POST http://localhost:8080/api/retention/trim | python3 -m json.tool
```

Expected:
```json
{
  "status":    "trimmed",
  "timestamp": "2026-03-01T10:00:00Z"
}
```

Check `racer-demo` logs for:
```
[racer-retention] Trimmed stream racer:orders:stream to max 100 entries
[racer-retention] Pruned 5 DLQ entries older than 1 hour(s)
```

---

### Step 5 — Verify DLQ is pruned

```bash
redis-cli LLEN racer:dlq
# Expected: 0 (entries were recent, but were pruned by the max-age rule)
```

> **Note:** The age-based prune only removes entries where `failedAt` is older than `dlqMaxAgeHours`. If your test entries were just created, adjust `dlq-max-age-hours=0` or wait.

---

### What you built
Automatic memory management for Redis. Durable streams stay bounded and old DLQ entries are evicted on a schedule — preventing unbounded growth in production.

---

## Tutorial 14 — Atomic Batch Publishing (RacerTransaction)

### What you'll learn
- Publish to multiple channels in a guaranteed ordered sequence with `RacerTransaction`
- Understand sequential vs. parallel fan-out publishing modes
- Inject `RacerTransaction` into any Spring bean

### Prerequisites
- `racer-demo` running — see Tutorial 1
- Multiple channel aliases configured (`orders`, `audit`, `notifications`)

---

### Step 1 — Understand the publishing modes

| Method | Execution | Order guaranteed |
|--------|-----------|-----------------|
| `RacerTransaction.execute(tx, false)` | Parallel (`Flux.merge`) | ❌ No |
| `RacerTransaction.execute(tx, true)` *(default)* | Sequential (`Flux.concat`) | ✅ Yes |

Use sequential mode when the processing order matters — e.g. you need the audit event recorded before the notification is sent.

---

### Step 2 — Sequential atomic publish (programmatic)

Inject `RacerTransaction` into a service bean in `racer-demo`:

```java
@Service
public class CheckoutService {

    @Autowired
    private RacerTransaction racerTx;

    public Mono<Void> checkout(String orderId) {
        return racerTx.execute(tx -> {
            tx.publish("orders",        "Order #50 placed",  "checkout");
            tx.publish("audit",         "Audit log #50",     "checkout");
            tx.publish("notifications", "Your order is in!", "checkout");
        }).doOnNext(counts -> log.info("Subscriber counts: {}", counts))
          .then();
    }
}
```

The three publishes execute sequentially — `racer:orders` is published first, then `racer:audit`, then `racer:notifications`. Each channel subscriber receives the message before the next publish begins.

---

### Step 3 — Observe ordering in racer-demo logs

Add `@RacerListener` methods for all three channels (see Tutorial 19) and call `checkout()`.
In the `racer-demo` log, messages arrive in exact order:

```
[orders-listener]        received: Order #50 placed
[audit-listener]         received: Audit log #50
[notifications-listener] received: Your order is in!
```

---

### Step 4 — Parallel publish (unordered fan-out)

For high-throughput scenarios where order does not matter, pass `false` as the second argument:

```java
racerTx.execute(tx -> {
    tx.publish("orders",        "Order #51",    "checkout");
    tx.publish("audit",         "Audit log #51","checkout");
    tx.publish("notifications", "Order in!",    "checkout");
}, false)  // parallel = true uses Flux.merge instead of Flux.concat
.subscribe();
```

All three `PUBLISH` commands are sent to Redis simultaneously — lower latency, but no ordering guarantee.

---

### What you built
Ordered fan-out: three channels receive the same batch of messages in strict order using a single `RacerTransaction` call — equivalent to a multi-step saga where each step must commit before the next begins.

---

## Tutorial 15 — High Availability (Sentinel & Cluster)

### What you'll learn
- Start Redis in **Sentinel** mode for automatic failover
- Start Redis in **Cluster** mode for horizontal scale-out
- Configure `application.properties` for each HA mode
- Simulate a primary failover and verify Racer reconnects

### Prerequisites
- Docker Desktop running
- Racer built (`mvn clean install -DskipTests`)

---

### Part A — Sentinel Mode

Sentinel mode provides automatic failover with 1 primary, 1 replica, and 3 Sentinel nodes.

#### Step A-1 — Start the Sentinel stack

```bash
docker compose -f compose.sentinel.yaml up -d
```

Verify all containers are healthy:
```bash
docker ps | grep racer
# Expected: racer-redis-primary, racer-redis-replica, racer-sentinel-1/2/3 — all Up
```

Verify Sentinel can see the primary:
```bash
docker exec racer-sentinel-1 redis-cli -p 26379 SENTINEL masters
# Look for: name=mymaster, status=ok
```

#### Step A-2 — Configure application.properties

In `racer-demo/src/main/resources/application.properties`, **comment out** the standalone lines and **add**:

```properties
# Comment out standalone mode:
# spring.data.redis.host=localhost
# spring.data.redis.port=6379

# Enable Sentinel:
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=localhost:26379,localhost:26380,localhost:26381
```

Restart `racer-demo`.

#### Step A-3 — Publish and verify

```bash
redis-cli -p 26379 PUBLISH racer:messages '{"payload":"sentinel test","sender":"tutorial"}'
# Verify in racer-demo log: [racer] Consumed message: sentinel test
```

#### Step A-4 — Simulate failover

Stop the primary:
```bash
docker stop racer-redis-primary
```

Watch Sentinel logs — a new primary should be elected within ~5 seconds:
```bash
docker logs -f racer-sentinel-1
# Look for: +elected-leader, +promoted-slave, +switch-master
```

Publish again — Racer should reconnect automatically:
```bash
redis-cli -p 26379 PUBLISH racer:messages '{"payload":"after failover","sender":"tutorial"}'
```

Restart the original primary (it will re-join as a replica):
```bash
docker start racer-redis-primary
```

---

### Part B — Cluster Mode

Redis Cluster provides horizontal sharding across 6 nodes (3 primaries + 3 replicas).

#### Step B-1 — Start the cluster

```bash
docker compose -f compose.cluster.yaml up -d
```

Wait ~10 seconds for the auto-init container to configure cluster slots, then verify:
```bash
docker exec racer-cluster-node-1 redis-cli -p 7001 CLUSTER INFO | grep cluster_state
# Expected: cluster_state:ok
```

#### Step B-2 — Configure application.properties

```properties
# Comment out standalone mode:
# spring.data.redis.host=localhost
# spring.data.redis.port=6379

# Enable Cluster:
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
```

Restart `racer-demo` and test as in Step A-3.

---

### Part C — Reverting to standalone

When done with HA testing, switch back to standalone mode:
```bash
docker compose -f compose.sentinel.yaml down   # or compose.cluster.yaml
docker compose -f compose.yaml up -d
```

Restore `application.properties` to the standalone settings.

---

### What you built
Production-grade Redis deployments that survive single-node failures (Sentinel) or support horizontal scale-out (Cluster) — with zero changes to Racer's application code.

---

## Tutorial 16 — Consumer Scaling & Stream Sharding

### What you'll learn
- How to run concurrent stream consumers within `racer-demo` using `@RacerStreamListener`.
- How to tune `concurrency`, `batchSize`, and `pollIntervalMs` per listener.
- How to distribute published messages across **sharded streams** using CRC-16 key routing.

### Prerequisites
- Tutorials 1, 11 (durable publishing) completed.
- Standalone Redis running (`docker compose -f compose.yaml up -d`).

---

### Part A — Increasing Consumer Concurrency

#### A-1: Baseline — single consumer

Start `racer-demo` and add a single `@RacerStreamListener`:

```java
@Component
public class OrderStreamConsumer {

    @RacerStreamListener(
        streamKey    = "racer:orders:stream",
        groupName    = "orders-group",
        consumerName = "consumer-0"
    )
    public void onOrder(RacerMessage message) {
        log.info("[consumer-0] Received: {}", message.getPayload());
    }
}
```

In `racer-demo` startup logs:
```
Started consumer consumer-0 on racer:orders:stream
```

#### A-2: Scale to multiple concurrent consumers

Use `concurrency` to fan-out across N virtual threads, and tune the read parameters:

```java
@RacerStreamListener(
    streamKey       = "racer:orders:stream",
    groupName       = "orders-group",
    consumerName    = "worker",
    concurrency     = 3,          // 3 concurrent message processors
    batchSize       = 10,         // read up to 10 entries per poll
    pollIntervalMs  = 100         // poll every 100ms
)
public void onOrder(RacerMessage message) {
    log.info("[worker] processing order: {}", message.getPayload());
}
```

Restart `racer-demo`. You should see in the log:
```
Started consumer worker (concurrency=3) on racer:orders:stream
```

#### A-3: Publish messages and observe distribution

Write 30 entries to the stream directly in redis-cli:

```bash
for i in $(seq 1 30); do
  redis-cli XADD racer:orders:stream '*' payload "{\"order\":\"order-$i\"}" sender bench
done
```

Expect the 30 messages to be processed in parallel across the 3 concurrent workers.

---

### Part B — Stream Sharding (CRC-16 key routing)

#### B-1: Enable sharding in racer-demo

In `racer-demo/src/main/resources/application.properties`:

```properties
racer.sharding.enabled=true
racer.sharding.shard-count=4
racer.sharding.streams=racer:orders:stream
```

Restart `racer-demo`.

#### B-2: Configure per-shard listeners

Add one `@RacerStreamListener` per shard in your consumer component:

```java
@Component
public class ShardedOrderConsumer {

    @RacerStreamListener(streamKey = "racer:orders:stream:0", groupName = "orders-group", consumerName = "shard-0")
    public void onShard0(RacerMessage msg) { log.info("[shard-0] {}", msg.getPayload()); }

    @RacerStreamListener(streamKey = "racer:orders:stream:1", groupName = "orders-group", consumerName = "shard-1")
    public void onShard1(RacerMessage msg) { log.info("[shard-1] {}", msg.getPayload()); }

    @RacerStreamListener(streamKey = "racer:orders:stream:2", groupName = "orders-group", consumerName = "shard-2")
    public void onShard2(RacerMessage msg) { log.info("[shard-2] {}", msg.getPayload()); }

    @RacerStreamListener(streamKey = "racer:orders:stream:3", groupName = "orders-group", consumerName = "shard-3")
    public void onShard3(RacerMessage msg) { log.info("[shard-3] {}", msg.getPayload()); }
}
```

#### B-3: Publish with a shard key

Use `RacerShardedStreamPublisher` in a service:

```java
@Autowired
private RacerShardedStreamPublisher shardedPublisher;

for (int i = 1; i <= 20; i++) {
    shardedPublisher.publish("racer:orders:stream", "order-" + i, "bench");
}
```

Verify the messages are distributed across shards in Redis:
```bash
redis-cli XLEN racer:orders:stream:0
redis-cli XLEN racer:orders:stream:1
redis-cli XLEN racer:orders:stream:2
redis-cli XLEN racer:orders:stream:3
```

You should see the messages distributed evenly across the 4 shards.

---

### `@RacerStreamListener` parameter reference

| Parameter | Default | Description |
|-----------|---------|-------------|
| `streamKey` | *(required)* | Redis stream key to consume |
| `groupName` | `"racer-durable-group"` | Consumer group name |
| `consumerName` | `"racer-consumer"` | Consumer name within the group |
| `concurrency` | `1` | Number of concurrent message processors |
| `batchSize` | `10` | Entries per poll (`XREADGROUP COUNT`) |
| `pollIntervalMs` | `100` | Milliseconds between polls |

---

### What you built
A multi-consumer stream pipeline in a single `racer-demo` process — using annotation-driven `@RacerStreamListener` concurrency, with optional CRC-16-based sharding so producers can partition load across independent stream keys.

---

## Tutorial 17 — Pipelined Batch Publishing

### What you'll learn
- How Lettuce's reactive pipelining reduces N round-trips to ~1.
- Use `RacerPipelinedPublisher` for high-throughput single-channel batches.
- Use `RacerTransaction` in pipelined mode for ordered multi-channel fan-out.
- Benchmark sequential vs. pipelined publishing programmatically.

### Prerequisites
- Tutorial 14 (RacerTransaction) completed.
- `racer-demo` running — see Tutorial 1.

---

### Part A — Single-channel pipelined publish

#### A-1: Sequential baseline (for comparison)

Inject `RacerChannelPublisher` into a test service and publish sequentially:

```java
@Service
public class BenchmarkService {

    @RacerPublisher("orders")
    private RacerChannelPublisher ordersPublisher;

    // Sequential: each publish() call waits for Redis reply before firing the next
    public Mono<Void> publishSequential(List<String> payloads) {
        return Flux.fromIterable(payloads)
            .concatMap(p -> ordersPublisher.publishAsync(Map.of("payload", p)))
            .then();
    }
}
```

#### A-2: Pipelined publish with `RacerPipelinedPublisher`

```java
@Autowired
private RacerPipelinedPublisher pipelinedPublisher;

// Pipelined: all PUBLISH commands fired before waiting for any reply
public Mono<List<Long>> publishPipelined(List<String> payloads, String channel) {
    return pipelinedPublisher.publishItems(
        payloads.stream()
            .map(p -> RacerMessage.create(channel, p, "bench"))
            .collect(Collectors.toList()),
        channel
    );
}
```

`Flux.flatMap(concurrency = N)` fires up to N `PUBLISH` commands simultaneously — Lettuce's reactive pipeline queues them into as few TCP writes as possible. At 100+ payloads, pipelined mode is measurably faster than sequential `concatMap`.

#### A-3: Benchmark comparison

```java
StopWatch sw = new StopWatch();

sw.start("sequential");
publishSequential(payloads).block();
sw.stop();

sw.start("pipelined");
publishPipelined(payloads, "racer:orders").block();
sw.stop();

log.info(sw.prettyPrint());
```

---

### Part B — Multi-channel pipelined fan-out with `RacerTransaction`

Ensure channel aliases are configured in `racer-demo/src/main/resources/application.properties`:

```properties
racer.channels.orders.name=racer:orders
racer.channels.orders.sender=checkout
racer.channels.audit.name=racer:audit
racer.channels.audit.sender=audit-service
```

#### B-1: Sequential multi-channel (ordered)

```java
// Default: sequential — all publishes ordered via Flux.concat
racerTx.execute(tx -> {
    tx.publish("orders", "order-1", "checkout");
    tx.publish("audit",  "audit-1", "checkout");
}).subscribe();
```

#### B-2: Pipelined multi-channel (unordered, fastest)

```java
// Pipelined: all PUBLISH commands fire in parallel via Flux.merge
racerTx.execute(tx -> {
    tx.publish("orders", "order-2", "checkout");
    tx.publish("audit",  "audit-2", "checkout");
    tx.publish("events", "event-2", "checkout");
}, false)   // false = parallel / pipelined mode
.subscribe();
```

When `pipelined = false` (parallel), `RacerPipelinedPublisher.publishItems()` is used internally — lowest latency for high-throughput fan-out scenarios.

---

### Mode comparison

| Mode | API | Redis commands | Ordering | Latency |
|------|-----|----------------|----------|---------|
| Sequential (default) | `racerTx.execute(tx)` | Flux.concat | ✅ Guaranteed | Higher |
| Parallel/Pipelined | `racerTx.execute(tx, false)` | Flux.merge | ❌ None | Lowest |
| Single-channel pipeline | `RacerPipelinedPublisher` | flatMap(N) | ❌ None | Lowest |

---

### What you built
High-throughput batch messaging using Lettuce's auto-pipelining via reactive `Flux.flatMap` concurrency — with support for both single-channel and multi-channel fan-out in pipelined mode.

---

## Tutorial 18 — Message Priority Channels

### What you'll learn
- How to enable `RacerPriorityPublisher` on the server to route messages to `{channel}:priority:{LEVEL}` sub-channels.
- How to configure `RacerPriorityConsumerService` on the client to drain messages in strict priority order.
- How to use `@RacerPriority` for declarative priority publishing.

### Prerequisites
- Tutorials 1–3 completed.
- `racer-demo` running on port 8080.

---

### Part A — Enable priority routing

Edit `racer-demo/src/main/resources/application.properties`:

```properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=orders
```

Restart `racer-demo`.

#### A-1: Verify sub-channels are available

Open two terminals:

**Terminal 1** — subscribe to all priority channels:
```bash
redis-cli SUBSCRIBE racer:orders:priority:HIGH racer:orders:priority:NORMAL racer:orders:priority:LOW
```

**Terminal 2** — publish messages at different priorities directly via Redis:
```bash
redis-cli PUBLISH racer:orders:priority:HIGH \
  '{"id":"1","payload":"important order","sender":"checkout","channel":"racer:orders","priority":"HIGH"}'

redis-cli PUBLISH racer:orders:priority:NORMAL \
  '{"id":"2","payload":"normal order","sender":"checkout","channel":"racer:orders","priority":"NORMAL"}'

redis-cli PUBLISH racer:orders:priority:LOW \
  '{"id":"3","payload":"low priority batch","sender":"checkout","channel":"racer:orders","priority":"LOW"}'
```

> Alternatively, enable the channels API (`racer.web.channels-enabled=true`) and use
> `POST http://localhost:8080/api/channels/publish/orders` with a `priority` field in the body.

In Terminal 1 you should see each message arrive on its corresponding sub-channel (`racer:orders:priority:HIGH`, etc.).

---

### Part B — Enable priority consumer

Edit `racer-demo/src/main/resources/application.properties` (same file as Part A):

```properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=racer:orders,racer:notifications
```

Restart `racer-demo`.

#### B-1: Observe strict-order drain

Send a burst of mixed-priority messages directly via Redis:

```bash
for level in LOW LOW LOW NORMAL NORMAL HIGH; do
  redis-cli PUBLISH "racer:orders:priority:$level" \
    "{\"payload\":\"msg-$level\",\"sender\":\"bench\",\"channel\":\"racer:orders\",\"priority\":\"$level\"}" > /dev/null
done
```

In the `racer-demo` log, all `HIGH` messages should be processed before `NORMAL` messages, which are processed before `LOW` messages — regardless of the order they were published.

---

### Part C — Declarative priority with `@RacerPriority`

In a service bean inside `racer-demo`:

```java
import com.cheetah.racer.common.annotation.RacerPriority;
import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.model.RacerMessage;

@Service
public class OrderService {

    @PublishResult(channelRef = "orders", sender = "order-service")
    @RacerPriority(defaultLevel = "HIGH")
    public RacerMessage placeUrgentOrder(String orderId) {
        // The returned message's priority field takes precedence;
        // if blank, @RacerPriority defaultLevel is used.
        return RacerMessage.create("racer:orders", orderId, "order-service", "HIGH");
    }
}
```

#### C-1: Confirm sub-channel naming in Redis

```bash
redis-cli PUBSUB CHANNELS "racer:*:priority:*"
```

Expected output (when at least one subscriber is active):
```
1) "racer:orders:priority:HIGH"
2) "racer:orders:priority:NORMAL"
3) "racer:orders:priority:LOW"
```

---

### Part D — Revert to standard publishing

Set `racer.priority.enabled=false` in `racer-demo/src/main/resources/application.properties` and restart. All messages are then published to the base channel as normal.

---

### What you built
A priority-aware messaging pipeline where `HIGH` urgency messages are published to dedicated sub-channels and consumers process them ahead of `NORMAL` and `LOW` traffic, using only application-properties configuration — no code changes required.

---

## Tutorial 19 — Declarative Channel Consumers (@RacerListener)

### What you'll learn
- Annotate a Spring method with `@RacerListener` to subscribe it to a Redis Pub/Sub channel
- Understand `SEQUENTIAL` vs `CONCURRENT` processing modes
- Receive typed POJOs directly (automatic JSON deserialization)
- Resolve the channel name from `application.properties` with `channelRef`
- Observe how failed messages are forwarded to the Dead Letter Queue
- Use metrics per-listener to track processed and failed counts

### Prerequisites
- Tutorial 1 complete (`racer-demo` on port 8080, Redis running)
- Familiarity with Tutorial 7 (`@RacerPublisher`, `@PublishResult`) recommended

---

### How it works

```
redis-cli PUBLISH racer:orders '{...}'
        │
        ▼
Redis Pub/Sub
        │
        ▼
RacerListenerRegistrar (BeanPostProcessor)
  └─ subscribes every @RacerListener method at startup
  └─ receives ReactiveRedisMessage
  └─ routes through schema validator (optional)
  └─ routes through RacerRouterService (optional)
  └─ deserialises payload → RacerMessage | String | POJO<T>
  └─ dispatches to annotated method on boundedElastic()
        │
        ├─ SEQUENTIAL mode: flatMap(concurrency = 1)
        └─ CONCURRENT mode: flatMap(concurrency = N)
              │
              ├─ success → processedCount++
              └─ failure → failedCount++, RacerDeadLetterHandler.enqueue(...)
```

---

### Step 1 — Receive the full message envelope

Add this class to `racer-demo` (or any Spring Boot project with `racer-starter` on the classpath):

```java
package com.cheetah.racer.demo.service;

import com.cheetah.racer.common.annotation.RacerListener;
import com.cheetah.racer.common.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderListenerService {

    @RacerListener(channel = "racer:orders")
    public void onOrder(RacerMessage message) {
        log.info("[order-listener] received id={} payload={}",
                message.getId(), message.getPayload());
    }
}
```

Restart `racer-demo`, then publish a message:
```bash
redis-cli PUBLISH racer:orders \
  '{"id":"test-1","payload":"order-001","sender":"tutorial","channel":"racer:orders"}'
```

You should see the log line in `racer-demo`:
```
[order-listener] received id=test-1 payload=order-001
```

---

### Step 2 — Receive the raw payload as a String

Declare the parameter as `String` instead of `RacerMessage` to receive only the payload value:

```java
@RacerListener(channel = "racer:orders", id = "orders-string-listener")
public void onOrderString(String payload) {
    log.info("[orders-string-listener] payload={}", payload);
}
```

> Having two `@RacerListener` methods subscribed to the same channel is supported — each gets its own independent subscription.

---

### Step 3 — Automatic POJO deserialization

Declare any concrete type and the registrar calls `objectMapper.readValue(payload, YourType.class)` automatically:

```java
public record OrderEvent(String orderId, String item, int qty) {}

@RacerListener(channel = "racer:orders")
public Mono<Void> onOrderEvent(OrderEvent event) {
    log.info("[order-pojo] orderId={} item={} qty={}",
            event.orderId(), event.item(), event.qty());
    return Mono.empty();
}
```

Publish a compatible JSON payload:
```bash
redis-cli PUBLISH racer:orders \
  '{"id":"ord-pojo","payload":"{\"orderId\":\"ORD-42\",\"item\":\"Widget\",\"qty\":3}","sender":"tutorial","channel":"racer:orders"}'
```

`racer-demo` log:
```
[order-pojo] orderId=ORD-42 item=Widget qty=3
```

> If deserialization fails (e.g. malformed JSON or wrong field names) the exception is caught, `failedCount` is incremented, and the message is forwarded to the DLQ.

---

### Step 4 — CONCURRENT mode with multiple workers

Use `mode = ConcurrencyMode.CONCURRENT` to process up to N messages in parallel:

```java
import com.cheetah.racer.common.annotation.ConcurrencyMode;

@RacerListener(
    channel     = "racer:shipments",
    mode        = ConcurrencyMode.CONCURRENT,
    concurrency = 8,
    id          = "shipment-worker"
)
public Mono<Void> processShipment(RacerMessage message) {
    return Mono.fromCallable(() -> {
        Thread.sleep(100); // simulate I/O
        log.info("[shipment-worker] processed {}", message.getId());
        return null;
    }).then();
}
```

Publish a burst and observe that they are handled in parallel:
```bash
for i in $(seq 1 20); do
  redis-cli PUBLISH racer:shipments \
    "{\"id\":\"ship-$i\",\"payload\":\"shipment-$i\",\"sender\":\"tutorial\",\"channel\":\"racer:shipments\"}" > /dev/null
done
```

All 20 messages should complete in roughly 100 ms total (instead of 2 seconds sequentially) because 8 are handled simultaneously.

---

### Step 5 — Resolve the channel from application.properties

Use `channelRef` instead of a hard-coded channel name to read the Redis key from `racer.channels.<alias>.name`:

**`application.properties`:**
```properties
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service
```

**Listener:**
```java
@RacerListener(channelRef = "orders", id = "orders-ref-listener")
public void onOrderRef(RacerMessage message) {
    log.info("[orders-ref-listener] payload={}", message.getPayload());
}
```

This approach lets you change the Redis channel name without recompiling — just update the property and restart.

---

### Step 6 — DLQ on failure

Any uncaught exception in a `@RacerListener` method is forwarded to `DeadLetterQueueService` (which implements `RacerDeadLetterHandler`).

Create a listener that throws:
```java
@RacerListener(channel = "racer:orders", id = "failing-listener")
public void failingListener(RacerMessage message) {
    if (message.getPayload().contains("fail")) {
        throw new RuntimeException("Listener failure: " + message.getPayload());
    }
    log.info("[failing-listener] processed {}", message.getPayload());
}
```

Publish a failing message:
```bash
redis-cli PUBLISH racer:orders \
  '{"id":"fail-1","payload":"fail-this-one","sender":"tutorial","channel":"racer:orders"}'
```

Verify the DLQ received it (requires `racer.web.dlq-enabled=true` in `application.properties`):
```bash
curl -s http://localhost:8080/api/dlq/size | jq
# { "dlqSize": 1 }

curl -s http://localhost:8080/api/dlq/messages | jq '.[0].errorMessage'
# "Listener failure: fail-this-one"
```

Refer to [Tutorial 4](#tutorial-4--dead-letter-queue--reprocessing) for reprocessing options.

---

### Step 7 — Observability: processed and failed counts

`RacerListenerRegistrar` tracks per-listener counters. With Micrometer on the classpath they are published as Actuator metrics.

Generate traffic:
```bash
for i in $(seq 1 10); do
  redis-cli PUBLISH racer:orders \
    "{\"id\":\"msg-$i\",\"payload\":\"hello-$i\",\"sender\":\"tutorial\",\"channel\":\"racer:orders\"}" > /dev/null
done
```

Query the metrics (Actuator):
```bash
curl -s 'http://localhost:8080/actuator/metrics/racer.listener.processed' | python3 -m json.tool
```

Expected output:
```json
{
  "name": "racer.listener.processed",
  "measurements": [{ "statistic": "COUNT", "value": 10.0 }],
  "availableTags": [{ "tag": "listener", "values": ["orders-ref-listener"] }]
}
```

Prometheus scrape:
```bash
curl -s http://localhost:8080/actuator/prometheus | grep racer_listener
```

---

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `@RacerListener` method never fires | Ensure `ReactiveRedisMessageListenerContainer` is in the context (it is declared in `racer-demo`'s `RedisListenerConfig`). `RacerListenerRegistrar` is conditional on that bean. |
| `channelRef` resolves to `null` | Verify `racer.channels.<alias>.name` is set in `application.properties` and the alias spelling matches exactly. |
| POJO deserialization fails silently | Enable DEBUG logging for `com.cheetah.racer.common.listener` — the exact `JsonProcessingException` is logged before the DLQ enqueue. |
| DLQ not receiving failures | Ensure `DeadLetterQueueService` is on the classpath and implements `RacerDeadLetterHandler`. In `racer-demo` this is already wired. In a custom app, provide your own `RacerDeadLetterHandler` bean. |
| Two listeners on the same channel — only one fires | Both listeners subscribe independently and both will fire. If only one fires, check that both beans are Spring-managed `@Component` / `@Service` (not created with `new`). |
| `concurrency` setting ignored | `concurrency` is only honoured when `mode = ConcurrencyMode.CONCURRENT`. In `SEQUENTIAL` mode the value is ignored and the pipeline runs with `flatMap(concurrency = 1)`. |

---

### What you built

```
Publisher (redis-cli / @RacerPublisher)    Redis              racer-demo
────────────────────────────────────────────────────────────────────────
redis-cli PUBLISH racer:orders '{...}'
  ─────────────────────────────────────→ Pub/Sub ──────────────→ @RacerListener
                                                                method(RacerMessage)
                                                                method(String)
                                                                method(OrderEvent)  ← auto-deserialized
                 ◄── DLQ ─────────────────────────────────────── exception → DeadLetterQueueService
```

Declarative, annotation-driven channel subscriptions with zero boilerplate — no `listenerContainer.receive()` calls, no manual `Disposable` management, and full integration with the existing schema, routing, metrics, and DLQ infrastructure.
