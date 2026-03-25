# Build a Notification Hub — Complete Racer Feature Tour

This tutorial builds **NotifyHub**, a self-contained Spring Boot service that demonstrates
_every_ Racer capability using a simple notification dispatch domain. Because the domain
logic is trivial (route and log notifications), all attention stays on the framework.

By the end you will have a running service that:

- Publishes messages three different ways: `@RacerPublisher`, `@PublishResult`, `RacerTransaction`
- Routes messages content-based to `email`, `sms`, `push`, and `audit` channels via `@RacerRoute`
- Intercepts every message with logging and sender validation via `RacerMessageInterceptor`
- Consumes messages with plain, dedup, and concurrent listeners via `@RacerListener`
- Sends requests and waits for typed replies via `@RacerClient` / `@RacerResponder`
- Ships failing messages to the Dead Letter Queue automatically and lets you inspect / reprocess them
- Publishes to three channels atomically in one call via `RacerTransaction`
- Routes high-priority notifications to priority sub-channels via `@RacerPriority`
- Protects the SMS consumer with a circuit breaker
- Switches the email channel from transient Pub/Sub to durable Streams by editing one property
- Exposes Micrometer metrics via Spring Actuator

> This tutorial is **self-contained**. You do not need any other Racer module running
> alongside it. `notify-hub` acts as both publisher and subscriber of its own channels.

---

## Capability Map

| # | Racer Feature | Where demonstrated |
|---|---|---|
| 1 | `@EnableRacer` | `NotifyApplication` |
| 2 | `@EnableRacerClients` | `NotifyApplication` |
| 3 | `@RacerPublisher` field injection | `NotificationService` |
| 4 | `@PublishResult` AOP | `NotificationService.dispatchEmail/Sms/Push()` |
| 5 | `RacerTransaction` batch publish | `NotificationService.sendBroadcast()` |
| 6 | `RacerFunctionalRouter` DSL | `NotificationRouterConfig` |
| 7 | `RacerMessageInterceptor` | `CorrelationInterceptor`, `AuditInterceptor` |
| 8 | `@RacerListener` (plain) | `AuditCollector` |
| 9 | `@RacerListener(dedup = true)` | `EmailWorker` |
| 10 | `@RacerListener(mode = CONCURRENT)` | `PushWorker` |
| 11 | `@RacerListener` + exception → DLQ | `SmsWorker` |
| 12 | `@RacerClient` + `@RacerRequestReply` | `NotificationStatusClient` |
| 13 | `@RacerResponder` | `StatusResponder` |
| 14 | `DeadLetterQueueService` + `DlqReprocessorService` | `DlqApiController` |
| 15 | `@RacerPriority` | `NotificationService.sendUrgentPush()` |
| 16 | Circuit breaker | `racer.circuit-breaker.*` + `SmsWorker` |
| 17 | Message deduplication | `racer.dedup.*` + `EmailWorker` |
| 18 | Durable delivery (Streams) | `racer.channels.email.durable=true` |
| 19 | Actuator metrics | `/actuator/metrics` |
| 20 | Built-in web API | `racer.web.dlq-enabled=true` |

---

## Prerequisites

| Requirement | Version / Notes |
|---|---|
| Java | **21** — pin via `JAVA_HOME` |
| Maven | 3.9+ |
| Docker | Any recent Desktop version |
| Racer JARs | Installed locally: run `mvn clean install` inside the Racer repo first |

> **Verify your JDK:**
> ```bash
> export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
> java -version   # must print: openjdk version "21..."
> ```

---

## Part 1 — Infrastructure

### Step 1.1 — Start Redis

Open a terminal inside the Racer repository and start the single-node Redis:

```bash
cd /path/to/racer
docker compose -f compose.yaml up -d
```

Verify:

```bash
docker ps --filter name=redis
redis-cli ping     # PONG
```

The `compose.yaml` runs Redis 7 on port `6379` with a persistent named volume.

---

## Part 2 — Project Skeleton

### Step 2.1 — Directory layout

Create the following structure **anywhere outside** the Racer repository:

```
notify-hub/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/example/notify/
        │       ├── NotifyApplication.java
        │       ├── model/
        │       │   ├── NotificationCommand.java
        │       │   └── NotificationResult.java
        │       ├── service/
        │       │   └── NotificationService.java
        │       ├── router/
        │       │   └── NotificationRouterConfig.java
        │       ├── interceptor/
        │       │   ├── CorrelationInterceptor.java
        │       │   └── AuditInterceptor.java
        │       ├── worker/
        │       │   ├── AuditCollector.java
        │       │   ├── EmailWorker.java
        │       │   ├── PushWorker.java
        │       │   └── SmsWorker.java
        │       ├── requestreply/
        │       │   ├── NotificationStatusClient.java
        │       │   └── StatusResponder.java
        │       └── controller/
        │           ├── NotifyController.java
        │           └── DlqApiController.java
        └── resources/
            └── application.properties
```

```bash
mkdir -p notify-hub/src/main/java/com/example/notify/{model,service,router,interceptor,worker,requestreply,controller}
mkdir -p notify-hub/src/main/resources
cd notify-hub
```

---

### Step 2.2 — `pom.xml`

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
    <artifactId>notify-hub</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>notify-hub</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!--
          One dependency pulls in: annotations, auto-configuration, AOP,
          reactive Redis (Lettuce), Jackson, and Micrometer integration.
        -->
        <dependency>
            <groupId>com.cheetah</groupId>
            <artifactId>racer</artifactId>
            <version>1.3.0</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

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
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

### Step 2.3 — `application.properties`

This single file configures every Racer feature. Each section corresponds to a
capability demonstrated later in the tutorial.

```properties
# ── Server ──────────────────────────────────────────────────────────────────
server.port=8090

# ── Redis ────────────────────────────────────────────────────────────────────
spring.data.redis.host=localhost
spring.data.redis.port=6379

# ── Racer: default channel ────────────────────────────────────────────────────
# Fallback channel when no channelRef or channel is specified.
racer.default-channel=racer:notify:default

# ── Racer: named channel aliases ─────────────────────────────────────────────
#
# Two independent properties control channel behaviour — they are NOT related:
#
#   async  (default: true)  — controls the PUBLISH call semantics only:
#     true  = fire-and-forget (non-blocking); publishAsync() returns as soon as the
#             message is handed to Redis. Transport stays Redis Pub/Sub.
#     false = synchronous; publishAsync() waits until Redis confirms the message was
#             received by at least one subscriber.
#
#   durable (default: false) — controls the TRANSPORT for the channel:
#     false = Redis Pub/Sub (PUBLISH); messages are lost if no subscriber is online.
#     true  = Redis Streams (XADD/XREADGROUP); messages survive offline consumers
#             and are replayed when the consumer reconnects.
#             ⚠ IMPORTANT: when durable=true, workers MUST use @RacerStreamListener
#             (not @RacerListener). @RacerListener listens on Pub/Sub and will never
#             see messages written to a stream. Not switching the annotation silently
#             drops all messages (the publisher writes to the stream; the listener
#             subscribes to a Pub/Sub channel that is never written to).

# email — fire-and-forget publish (async=true); idempotent delivery (EmailWorker uses dedup=true)
racer.channels.email.name=racer:notify:email
racer.channels.email.async=true
racer.channels.email.sender=notify-hub
# Uncomment to make email delivery durable (Part 13 — Durable Delivery):
# racer.channels.email.durable=true
# racer.channels.email.durable-group=email-consumer-group
# racer.channels.email.stream-key=racer:notify:email:stream

# sms — synchronous dispatch; protected by a circuit breaker
racer.channels.sms.name=racer:notify:sms
racer.channels.sms.async=false
racer.channels.sms.sender=notify-hub

# push — async; consumed concurrently by up to 4 workers
racer.channels.push.name=racer:notify:push
racer.channels.push.async=true
racer.channels.push.sender=notify-hub

# audit — append-only record of every dispatched notification
racer.channels.audit.name=racer:notify:audit
racer.channels.audit.async=true
racer.channels.audit.sender=notify-hub

# requests — request-reply channel used by the status client (Part 8)
racer.channels.requests.name=racer:notify:requests
racer.channels.requests.async=false
racer.channels.requests.sender=notify-hub

# ── Racer: message deduplication (Part 7.2) ──────────────────────────────────
# Activated per-listener with @RacerListener(dedup = true).
# Uses Redis SET NX EX — same message ID within the TTL is silently dropped.
racer.dedup.enabled=true
racer.dedup.ttl-seconds=60
racer.dedup.key-prefix=racer:dedup:

# ── Racer: circuit breaker (Part 12) ─────────────────────────────────────────
# Opens after 50% failures in a 5-call sliding window.
# Stays open for 10 s; then allows 2 probe calls before closing.
racer.circuit-breaker.enabled=true
racer.circuit-breaker.failure-rate-threshold=50
racer.circuit-breaker.sliding-window-size=5
racer.circuit-breaker.wait-duration-in-open-state-seconds=10
racer.circuit-breaker.permitted-calls-in-half-open-state=2

# ── Racer: priority channels (Part 11) ───────────────────────────────────────
# Creates sub-channels: racer:notify:push:priority:HIGH / NORMAL / LOW
# and racer:notify:sms:priority:HIGH / NORMAL / LOW
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=push,sms

# ── Racer: back-pressure monitoring ──────────────────────────────────────────
racer.backpressure.enabled=true
racer.backpressure.queue-threshold=0.80
racer.backpressure.check-interval-ms=2000

# ── Racer: built-in web endpoints ────────────────────────────────────────────
racer.web.dlq-enabled=true          # exposes GET/POST /api/dlq/**
racer.web.channels-enabled=true     # exposes GET /api/channels

# ── Actuator ─────────────────────────────────────────────────────────────────
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
```

> **One file, all features.** Each section maps to a Racer `@ConfigurationProperties`
> class: `RacerProperties.DedupProperties`, `CircuitBreakerProperties`,
> `PriorityProperties`, `BackPressureProperties`, `WebProperties`, and so on.

---

### Step 2.4 — Main class

```java
// src/main/java/com/example/notify/NotifyApplication.java
package com.example.notify;

import com.cheetah.racer.annotation.EnableRacer;
import com.cheetah.racer.annotation.EnableRacerClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRacer           // activates: channel registry, @RacerPublisher injection,
                       // @PublishResult AOP, RacerListenerRegistrar, RacerRouterService, DLQ
@EnableRacerClients    // scans this package for @RacerClient interfaces and generates JDK proxies
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
```

> `@EnableRacer` is technically optional when using the `racer` starter
> (AutoConfiguration registers automatically), but it serves as explicit self-documentation.
> `@EnableRacerClients` **is required** for the `NotificationStatusClient` proxy in Part 8.

---

## Part 3 — Domain Model

```java
// src/main/java/com/example/notify/model/NotificationCommand.java
package com.example.notify.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotificationCommand {

    /** Idempotency key — same value within the dedup TTL = same notification. */
    private String id;

    /** EMAIL, SMS, PUSH, or BROADCAST (fan-out to all three channels). */
    private String type;

    /** E-mail address, phone number, or device push token. */
    private String recipient;

    private String subject;
    private String body;

    /** HIGH, NORMAL, or LOW — maps to @RacerPriority sub-channels. */
    private String priority;
}
```

```java
// src/main/java/com/example/notify/model/NotificationResult.java
package com.example.notify.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class NotificationResult {
    private String  id;
    private String  type;           // EMAIL, SMS, PUSH, BROADCAST
    private String  recipient;
    private String  status;         // DISPATCHED, DELIVERED, FAILED
    private String  priority;       // HIGH, NORMAL, LOW — read by @RacerPriority
    private Instant dispatchedAt;
}
```

---

## Part 4 — Three Publishing Patterns

Racer provides three distinct ways to publish messages. All three are used inside
`NotificationService`. Understanding the differences is key to choosing the right
approach in each situation.

| Pattern | How it works | Best for |
|---|---|---|
| `@RacerPublisher` | Field injection — Racer wires a `RacerChannelPublisher` into the field at startup | Full control over **when** and **what** to publish |
| `@PublishResult` | AOP interceptor — taps the reactive pipeline returned by a method and publishes each emitted value automatically | Service methods called **from another bean** (controller → service). Does **not** fire on self-invocation (`this.method()`) |
| `RacerTransaction` | Spring bean — chain multiple `tx.publish(alias, payload)` calls into one reactive sequence | Publishing to **multiple channels** in one business action |

### Step 4.1 — `NotificationService`

> **Why `@RacerPublisher` injection, not `@PublishResult`, inside `send()`**
>
> `@PublishResult` is a Spring AOP annotation. It only fires when a method is called
> **through the Spring proxy** — i.e. when an external bean calls into this one. When
> `send()` calls `this.dispatchEmail()` it bypasses the proxy entirely (Java `this`
> is the raw object, not the proxy), so the annotation is silently ignored and nothing
> reaches Redis.
>
> The fix is to inject `RacerChannelPublisher` beans directly with `@RacerPublisher`
> and call `publishAsync()` — a plain API call that needs no proxy.
>
> `@PublishResult` **is** safe on the two methods a controller calls directly
> (`sendUrgentPush`) and on any method invoked from a different bean.

```java
// src/main/java/com/example/notify/service/NotificationService.java
package com.example.notify.service;

import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.annotation.RacerPriority;
import com.cheetah.racer.annotation.RacerPublisher;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.tx.RacerTransaction;
import com.example.notify.model.NotificationCommand;
import com.example.notify.model.NotificationResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class NotificationService {

    // ── Pattern 1: @RacerPublisher field injection ────────────────────────────
    //
    // RacerPublisherFieldProcessor (a BeanPostProcessor) replaces these nulls at
    // startup with RacerChannelPublisher instances pre-configured for each alias.
    // No @Autowired, no constructor argument — Racer handles it.
    //
    // These publishers are used directly inside send() because send() calls its
    // own dispatch helpers via 'this', which bypasses the Spring AOP proxy and
    // silences any @PublishResult annotation on those helpers.
    @RacerPublisher("email")
    private RacerChannelPublisher emailPublisher;

    @RacerPublisher("sms")
    private RacerChannelPublisher smsPublisher;

    @RacerPublisher("push")
    private RacerChannelPublisher pushPublisher;

    @RacerPublisher("audit")
    private RacerChannelPublisher auditPublisher;

    // RacerTransaction is registered as a Spring bean — wire it normally.
    private final RacerTransaction racerTransaction;

    public NotificationService(RacerTransaction racerTransaction) {
        this.racerTransaction = racerTransaction;
    }

    // ── Pattern 2: @PublishResult (safe for external callers) ─────────────────
    //
    // These methods are annotated with @PublishResult so that any bean that calls
    // them DIRECTLY (e.g. a controller, another service) gets automatic publishing
    // as a side-effect without any extra code.
    //
    // They are NOT called from send() via 'this' — that would bypass the proxy.
    // send() publishes imperatively with publishAsync() instead (see below).

    @PublishResult(channelRef = "email")
    public Mono<NotificationResult> dispatchEmail(NotificationCommand cmd) {
        return buildResult(cmd, "EMAIL");
    }

    @PublishResult(channelRef = "sms")
    public Mono<NotificationResult> dispatchSms(NotificationCommand cmd) {
        return buildResult(cmd, "SMS");
    }

    @PublishResult(channelRef = "push")
    public Mono<NotificationResult> dispatchPush(NotificationCommand cmd) {
        return buildResult(cmd, "PUSH");
    }

    // ── Pattern 2 + @RacerPriority ────────────────────────────────────────────
    //
    // Called directly from the controller, so @PublishResult fires correctly.
    // @RacerPriority reads the "priority" field from the returned result and routes
    // to: racer:notify:push:priority:HIGH   (or NORMAL / LOW)
    // Falls back to defaultLevel="HIGH" when the priority field is blank.
    @PublishResult(channelRef = "push")
    @RacerPriority(defaultLevel = "HIGH")
    public Mono<NotificationResult> sendUrgentPush(NotificationCommand cmd) {
        return Mono.just(NotificationResult.builder()
                .id(cmd.getId())
                .type("PUSH")
                .recipient(cmd.getRecipient())
                .status("DISPATCHED")
                .priority("HIGH")
                .dispatchedAt(Instant.now())
                .build());
    }

    // ── Pattern 3: RacerTransaction ───────────────────────────────────────────
    //
    // Used for type=BROADCAST: publishes the same payload to all four channels
    // in a single reactive chain (sequential Flux.concat).
    // First publish failure aborts the remaining channels (fail-fast).
    public Mono<Void> sendBroadcast(NotificationCommand cmd) {
        NotificationResult result = NotificationResult.builder()
                .id(cmd.getId())
                .type("BROADCAST")
                .recipient(cmd.getRecipient())
                .status("DISPATCHED")
                .dispatchedAt(Instant.now())
                .build();

        return racerTransaction.execute(tx -> {
            tx.publish("email", result);
            tx.publish("sms",   result);
            tx.publish("push",  result);
            tx.publish("audit", result);   // record in audit trail too
        }).then();
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    //
    // Uses publishAsync() directly (Pattern 1) because calling this.dispatchEmail()
    // etc. would be a self-invocation and would silently skip @PublishResult.

    public Mono<NotificationResult> send(NotificationCommand cmd) {
        return buildResult(cmd, cmd.getType().toUpperCase())
                .flatMap(result -> switch (cmd.getType().toUpperCase()) {
                    case "EMAIL" ->
                            emailPublisher.publishAsync(result).thenReturn(result);
                    case "SMS" ->
                            smsPublisher.publishAsync(result).thenReturn(result);
                    case "PUSH" ->
                            pushPublisher.publishAsync(result).thenReturn(result);
                    case "BROADCAST" ->
                            sendBroadcast(cmd).thenReturn(
                                    NotificationResult.builder()
                                            .id(cmd.getId()).type("BROADCAST").status("DISPATCHED")
                                            .recipient(cmd.getRecipient()).dispatchedAt(Instant.now()).build());
                    default -> Mono.error(
                            new IllegalArgumentException("Unknown type: " + cmd.getType()));
                })
                // Also record every dispatch in the audit trail
                .flatMap(result -> auditPublisher.publishAsync(result).thenReturn(result));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Mono<NotificationResult> buildResult(NotificationCommand cmd, String type) {
        return Mono.just(NotificationResult.builder()
                .id(cmd.getId())
                .type(type)
                .recipient(cmd.getRecipient())
                .status("DISPATCHED")
                .priority(cmd.getPriority() != null ? cmd.getPriority() : "NORMAL")
                .dispatchedAt(Instant.now())
                .build());
    }
}
```

---

## Part 5 — Content-Based Routing

`@RacerRoute` evaluates each incoming message against a list of field-matching rules
and re-publishes it to the first matching channel alias — with no handler code inside
the router class itself.

`NotificationRouter` listens on `racer:notify:default` (the `racer.default-channel`)
and fans out messages based on the `type` payload field.

```java
// src/main/java/com/example/notify/router/NotificationRouterConfig.java
package com.example.notify.router;

import com.cheetah.racer.router.dsl.RacerFunctionalRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.cheetah.racer.router.dsl.RouteHandlers.*;
import static com.cheetah.racer.router.dsl.RoutePredicates.*;

/**
 * Registers a {@link RacerFunctionalRouter} bean that routes messages arriving on
 * racer:notify:default by the "type" payload field.
 *
 * Rules are evaluated top-to-bottom; the first matching predicate wins.
 *
 * Handler options (via RouteHandlers):
 *   forward(alias)                 - re-publish to one alias; skip local listeners
 *   forward(alias, sender)         - same, but override the sender name
 *   forwardAndProcess(alias)       - re-publish AND also invoke local @RacerListener handlers
 *   multicast(a, b, ...)           - publish to MULTIPLE aliases in a single rule
 *   multicastAndProcess(a, b, ...) - multicast AND process locally
 *   drop()                         - silently discard
 *   dropToDlq()                    - send to the Dead Letter Queue
 *
 * Predicate options (via RoutePredicates):
 *   fieldEquals(field, value)      - exact JSON payload field match
 *   fieldMatches(field, regex)     - regex JSON payload field match
 *   senderEquals(name)             - match message sender
 *   any()                          - catch-all (always true)
 *   pred1.and(pred2).or(pred3)     - composable boolean logic
 */
@Configuration
public class NotificationRouterConfig {

    @Bean
    public RacerFunctionalRouter notificationRouter() {
        return RacerFunctionalRouter.builder()
                .name("notification-router")
                // Single-destination routes
                .route(fieldEquals("type", "EMAIL"), forward("email"))
                .route(fieldEquals("type", "SMS"),   forward("sms"))
                .route(fieldEquals("type", "PUSH"),  forward("push"))
                // True fan-out: publish to email AND sms in one rule, then process locally
                .route(fieldEquals("type", "BROADCAST"), multicastAndProcess("email", "sms", "push"))
                // Composable predicates: audit only if sender is checkout-service
                .route(fieldEquals("type", "AUDIT")
                               .and(senderEquals("checkout-service")), forward("audit"))
                // Catch-all: discard anything unrecognised
                .defaultRoute(drop())
                .build();
    }
}
```

> **First match wins.** Put more-specific predicates before the `defaultRoute` catch-all.
> Rules are pure Java — no annotation parsing, no reflection, fully testable without a
> Spring context.
>
> **True fan-out** (the `BROADCAST` case) is natively expressed by `multicastAndProcess`.
> The old annotation-based approach required a workaround with `FORWARD_AND_PROCESS` on
> a single alias and could not reliably target more than one destination per rule.

---

## Part 6 — Message Interceptors

`RacerMessageInterceptor` is the Racer equivalent of a servlet filter for incoming
messages. Interceptors run **after** routing decisions and **before** the listener
method is invoked. They are Spring beans auto-discovered by `RacerListenerRegistrar`.

Use `@Order` to control execution order. An interceptor may:
- Pass the message through unchanged (`Mono.just(message)`)
- Mutate the message and pass the modified version through
- Abort processing by returning `Mono.error()`, causing the message to be sent to the DLQ

### Step 6.1 — `CorrelationInterceptor` (log + pass-through)

```java
// src/main/java/com/example/notify/interceptor/CorrelationInterceptor.java
package com.example.notify.interceptor;

import com.cheetah.racer.listener.InterceptorContext;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.model.RacerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Runs first (@Order 10): logs correlation ID, channel, and target listener.
 *
 * ctx.listenerId() lets you filter by listener — for example, to only log for
 * the email-worker: if (!"email-worker".equals(ctx.listenerId())) return Mono.just(message);
 */
@Component
@Order(10)
public class CorrelationInterceptor implements RacerMessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CorrelationInterceptor.class);

    @Override
    public Mono<RacerMessage> intercept(RacerMessage message, InterceptorContext ctx) {
        log.info("[INTERCEPT] id={} channel={} listener={}",
                message.getId(), ctx.channel(), ctx.listenerId());
        return Mono.just(message);   // pass through unchanged
    }
}
```

### Step 6.2 — `AuditInterceptor` (validate + abort)

```java
// src/main/java/com/example/notify/interceptor/AuditInterceptor.java
package com.example.notify.interceptor;

import com.cheetah.racer.listener.InterceptorContext;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.model.RacerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Runs second (@Order 20): rejects messages with no sender.
 *
 * Returning Mono.error() aborts the dispatch chain. The listener method is NOT
 * invoked. RacerListenerRegistrar catches the error and forwards to the DLQ.
 */
@Component
@Order(20)
public class AuditInterceptor implements RacerMessageInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    @Override
    public Mono<RacerMessage> intercept(RacerMessage message, InterceptorContext ctx) {
        if (message.getSender() != null && !message.getSender().isBlank()) {
            log.debug("[AUDIT-FILTER] Accepted from sender='{}'", message.getSender());
            return Mono.just(message);
        }
        return Mono.error(new IllegalStateException(
                "Rejected: message id=" + message.getId() + " has no sender"));
    }
}
```

> **Interceptors apply to `@RacerListener` (Pub/Sub) only.**
> `RacerMessageInterceptor` beans are discovered and applied by `RacerListenerRegistrar`.
> `RacerStreamListenerRegistrar` (used by `@RacerStreamListener`) has no interceptor
> support — interceptors are silently skipped for stream workers.
>
> **Workaround for stream workers:** apply a Spring `@Aspect` on the listener method:
>
> ```java
> @Aspect
> @Component
> public class StreamWorkerAspect {
>     @Around("@annotation(com.cheetah.racer.annotation.RacerStreamListener)")
>     public Object aroundStream(ProceedingJoinPoint pjp) throws Throwable {
>         // pre-processing: validate, log, mutate, etc.
>         return pjp.proceed();
>     }
> }
> ```

---

## Part 7 — Consuming Messages

### Step 7.1 — Plain listener: `AuditCollector`

The simplest form. `@RacerListener` subscribes the method to the `audit` channel at
startup. Racer handles subscription, JSON deserialization, exception-to-DLQ forwarding,
and `racer.listener.processed` / `racer.listener.failed` Micrometer counters.

```java
// src/main/java/com/example/notify/worker/AuditCollector.java
package com.example.notify.worker;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.model.RacerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AuditCollector {

    private static final Logger log = LoggerFactory.getLogger(AuditCollector.class);
    private final List<String> entries = Collections.synchronizedList(new ArrayList<>());

    /**
     * Supported parameter types:
     *   RacerMessage   - full envelope (id, channel, payload, sender, timestamp, priority)
     *   String         - raw JSON payload string
     *   Any POJO type  - payload auto-deserialized via Jackson into that type
     *
     * Supported return types:
     *   void / Void    - fire-and-forget
     *   Mono<Void>     - subscribed by Racer; errors forwarded to DLQ
     */
    @RacerListener(channelRef = "audit", id = "audit-collector")
    public void onAuditEvent(RacerMessage message) {
        String entry = "[" + message.getTimestamp() + "] "
                + message.getSender() + " | id=" + message.getId()
                + " | " + message.getPayload();
        entries.add(entry);
        log.info("AUDIT: {}", entry);
    }

    public List<String> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
```

---

### Step 7.2 — Dedup listener: `EmailWorker`

`dedup = true` causes Racer to call `RacerDedupService.checkAndMarkProcessed()` before
invoking the listener. If the message ID was seen within `racer.dedup.ttl-seconds`, the
message is silently dropped and `racer.dedup.duplicates` is incremented.

```java
// src/main/java/com/example/notify/worker/EmailWorker.java
package com.example.notify.worker;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.model.RacerMessage;
import com.example.notify.model.NotificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private final ObjectMapper objectMapper;

    public EmailWorker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * dedup = true:
     *   1. Racer calls RacerDedupService.checkAndMarkProcessed(message.getId())
     *   2. New ID: Redis SET NX EX sets the key for ttl-seconds; method is invoked
     *   3. Seen ID: message is silently dropped; method is NOT invoked
     *
     * Requires racer.dedup.enabled=true (already set in application.properties).
     */
    @RacerListener(channelRef = "email", id = "email-worker", dedup = true)
    public void onEmailNotification(RacerMessage message) {
        // message.getPayload() is always a JSON String — use readValue, not convertValue
        NotificationResult result;
        try {
            result = objectMapper.readValue(
                    (String) message.getPayload(), NotificationResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid email payload", e);
        }
        log.info("[EMAIL] Sending to {} | id={}", result.getRecipient(), result.getId());
        // Production: call SMTP / SES / SendGrid here
    }
}
```

---

### Step 7.3 — Concurrent listener: `PushWorker`

`mode = ConcurrencyMode.CONCURRENT` with `concurrency = 4` allows up to 4 push
notifications to be in-flight simultaneously on the Racer thread pool. The `Mono<Void>`
return type lets Racer subscribe to the reactive result and catch errors.

```java
// src/main/java/com/example/notify/worker/PushWorker.java
package com.example.notify.worker;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.model.RacerMessage;
import com.example.notify.model.NotificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PushWorker {

    private static final Logger log = LoggerFactory.getLogger(PushWorker.class);
    private final ObjectMapper objectMapper;

    public PushWorker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * ConcurrencyMode.SEQUENTIAL (default) — one message at a time.
     * ConcurrencyMode.CONCURRENT, concurrency=N — up to N messages in parallel.
     * ConcurrencyMode.AUTO — Racer's AdaptiveConcurrencyTuner adjusts N at runtime
     *   based on thread-pool queue saturation (requires racer.backpressure.enabled=true).
     */
    @RacerListener(channelRef = "push", id = "push-worker",
                   mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
    public Mono<Void> onPushNotification(RacerMessage message) {
        // message.getPayload() is always a JSON String — use readValue, not convertValue
        NotificationResult result;
        try {
            result = objectMapper.readValue(
                    (String) message.getPayload(), NotificationResult.class);
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Invalid push payload", e));
        }
        log.info("[PUSH] Dispatching to {} | id={}", result.getRecipient(), result.getId());
        // Production: call FCM / APNs / Web Push API here
        return Mono.empty();
    }
}
```

---

### Step 7.4 — Failure simulation: `SmsWorker`

`SmsWorker` deliberately throws when the notification status is `"FAILED"`. This
demonstrates the automatic DLQ forwarding path and circuit breaker state transitions.

```java
// src/main/java/com/example/notify/worker/SmsWorker.java
package com.example.notify.worker;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.model.RacerMessage;
import com.example.notify.model.NotificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SmsWorker {

    private static final Logger log = LoggerFactory.getLogger(SmsWorker.class);
    private final ObjectMapper objectMapper;

    public SmsWorker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Failure flow when status = "FAILED":
     *   1. RuntimeException is thrown
     *   2. RacerListenerRegistrar catches it
     *   3. DeadLetterQueueService.enqueue() pushes the message to racer:dlq
     *   4. racer.listener.failed counter is incremented
     *   5. RacerCircuitBreaker records the failure in its sliding window
     *   6. After 3/5 calls fail (60% >= 50% threshold) the circuit OPENS
     *   7. Subsequent calls are rejected without invoking this method
     *   8. After 10 s the circuit moves to HALF_OPEN; 2 probe calls; then CLOSED
     */
    @RacerListener(channelRef = "sms", id = "sms-worker")
    public void onSmsNotification(RacerMessage message) {
        // message.getPayload() is always a JSON String — use readValue, not convertValue
        NotificationResult result;
        try {
            result = objectMapper.readValue(
                    (String) message.getPayload(), NotificationResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid SMS payload", e);
        }

        if ("FAILED".equalsIgnoreCase(result.getStatus())) {
            log.error("[SMS] Simulated gateway failure for id={}", result.getId());
            throw new RuntimeException("SMS gateway timeout for id=" + result.getId());
        }

        log.info("[SMS] Sending to {} | id={}", result.getRecipient(), result.getId());
        // Production: call Twilio / AWS SNS here
    }
}
```

---

## Part 8 — Request-Reply

Racer supports non-blocking request-reply over Redis Pub/Sub. The caller publishes a
`RacerRequest` with a unique correlation ID; the responder receives it, runs the
handler, and publishes a `RacerReply` back. The caller's `Mono<T>` resolves when the
correlated reply arrives within the configured timeout.

### Step 8.1 — Client interface (`@RacerClient` + `@RacerRequestReply`)

```java
// src/main/java/com/example/notify/requestreply/NotificationStatusClient.java
package com.example.notify.requestreply;

import com.cheetah.racer.annotation.RacerClient;
import com.cheetah.racer.annotation.RacerRequestReply;
import reactor.core.publisher.Mono;

/**
 * Racer generates a JDK dynamic proxy for this interface at startup
 * (activated by @EnableRacerClients on NotifyApplication).
 *
 * Inject it as any Spring bean and call it reactively:
 *   statusClient.checkStatus("notif-123").subscribe(reply -> log.info(reply));
 */
@RacerClient
public interface NotificationStatusClient {

    /**
     * 1. Serializes notificationId as the RacerRequest payload
     * 2. Publishes to racer:notify:requests with a random correlationId
     * 3. Subscribes to an ephemeral correlation reply channel
     * 4. Resolves when StatusResponder publishes a matching RacerReply
     * 5. Times out with RacerRequestReplyTimeoutException after 5 s
     */
    @RacerRequestReply(channelRef = "requests", timeout = "5s")
    Mono<String> checkStatus(String notificationId);
}
```

### Step 8.2 — Responder (`@RacerResponder`)

```java
// src/main/java/com/example/notify/requestreply/StatusResponder.java
package com.example.notify.requestreply;

import com.cheetah.racer.annotation.RacerResponder;
import com.cheetah.racer.model.RacerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StatusResponder {

    private static final Logger log = LoggerFactory.getLogger(StatusResponder.class);

    /**
     * Subscribed to racer:notify:requests at startup.
     *
     * Supported parameter types (same as @RacerListener):
     *   RacerRequest — full envelope; getPayload() returns the deserialized Object
     *   String       — raw payload string
     *   Any POJO     — payload deserialized into that type via Jackson
     *
     * Supported return types:
     *   String / any POJO — serialized to JSON and sent back as RacerReply payload
     *   Mono<String> / Mono<T> — unwrapped and used as the reply payload
     */
    @RacerResponder(channelRef = "requests")
    public String handleStatusRequest(RacerRequest request) {
        String notificationId = (String) request.getPayload();
        log.info("[RESPONDER] Status query for id={} corrId={}",
                notificationId, request.getCorrelationId());

        // Production: look up actual delivery status from DB / cache
        return "{ "id": "" + notificationId + "", "status": "DELIVERED" }";
    }
}
```

---

## Part 9 — Dead Letter Queue

Every exception thrown inside a `@RacerListener`, `@RacerResponder`, or the interceptor
chain is automatically forwarded to the DLQ (`racer:dlq` Redis list) via
`DeadLetterQueueService.enqueue()`. No boilerplate is needed.

### Step 9.1 — DLQ controller

```java
// src/main/java/com/example/notify/controller/DlqApiController.java
package com.example.notify.controller;

import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Custom DLQ REST API that shows how to use DeadLetterQueueService and
 * DlqReprocessorService programmatically.
 *
 * Note: racer.web.dlq-enabled=true already exposes /api/dlq/** from Racer.
 * This controller at /api/notify/dlq demonstrates the same API wired directly.
 */
@RestController
@RequestMapping("/api/notify/dlq")
public class DlqApiController {

    private final DeadLetterQueueService dlqService;
    private final DlqReprocessorService  dlqReprocessor;

    public DlqApiController(DeadLetterQueueService dlqService,
                             DlqReprocessorService dlqReprocessor) {
        this.dlqService     = dlqService;
        this.dlqReprocessor = dlqReprocessor;
    }

    /**
     * Returns all messages currently in the DLQ without removing them.
     * Each DeadLetterMessage contains: originalMessageId, channel, sender,
     * payload, errorMessage, failedAt timestamp.
     */
    @GetMapping
    public Flux<DeadLetterMessage> peek() {
        return dlqService.peekAll();
    }

    /** Returns the current DLQ depth. */
    @GetMapping("/size")
    public Mono<Long> size() {
        return dlqService.size();
    }

    /**
     * Pops the oldest DLQ entry and re-publishes its payload to the original channel.
     * DlqReprocessorService.republishOne() returns the number of Pub/Sub subscribers
     * that received the message (0 when the DLQ is empty).
     */
    @PostMapping("/reprocess")
    public Mono<String> reprocess() {
        return dlqReprocessor.republishOne()
                .map(count -> count > 0
                        ? "Republished 1 message to its original channel"
                        : "DLQ is empty");
    }
}
```

---

## Part 10 — Batch Publishing with `RacerTransaction`

`RacerTransaction.execute()` publishes to multiple channel aliases in a single reactive
chain (sequential `Flux.concat`). If any publish fails, subsequent channels in the
batch are skipped (fail-fast).

The broadcast flow in `sendBroadcast()` already demonstrates this. Here is the key API:

```java
// Inject as a normal Spring bean
@Autowired
private RacerTransaction racerTransaction;

// Publish to four channels as a single reactive chain:
racerTransaction.execute(tx -> {
    tx.publish("email",  notificationResult);
    tx.publish("sms",    notificationResult);
    tx.publish("push",   notificationResult);
    tx.publish("audit",  notificationResult);
}).subscribe(
    counts -> log.info("Batch complete. Subscriber counts: {}", counts),
    error  -> log.error("Batch failed: {}", error.getMessage())
);
```

> `execute()` returns `Mono<List<Long>>` — one `Long` per channel representing the
> Pub/Sub subscriber count. In durable mode (`XADD`) the value is `0` because stream
> consumers are not counted as Pub/Sub subscribers.

---

## Part 11 — Priority Channels

When `racer.priority.enabled=true` and a channel alias is listed under
`racer.priority.channels`, Racer creates priority sub-channels automatically:

```
racer:notify:push:priority:HIGH
racer:notify:push:priority:NORMAL
racer:notify:push:priority:LOW
```

`@RacerPriority` paired with `@PublishResult` reads the `priority` field from the
returned object and publishes to the matching sub-channel (already wired in
`sendUrgentPush()`).

### Subscribe to a specific priority sub-channel

```java
/**
 * Subscribe directly to the HIGH priority sub-channel.
 * racer.priority.strategy=strict means HIGH is fully drained before NORMAL/LOW.
 *
 * Use channel = "..." (the raw Redis key) for sub-channels because they are not
 * registered as named channel aliases.
 */
@RacerListener(channel = "racer:notify:push:priority:HIGH", id = "push-high-worker")
public void onHighPriorityPush(RacerMessage message) {
    log.info("[PUSH-HIGH] Urgent push: id={}", message.getId());
}
```

Add this inside `PushWorker` (or in a dedicated `PriorityPushWorker` class).

---

## Part 12 — Circuit Breaker

The circuit breaker is configured globally in `application.properties` (already done
in Step 2.3). With `racer.circuit-breaker.enabled=true`, every `@RacerListener`
dispatch is wrapped in a `RacerCircuitBreaker` named after the listener ID.

```
State machine for listener "sms-worker":

CLOSED ─── 3 of 5 calls fail (60% >= 50% threshold) ───► OPEN
  ▲                                                          │
  │                    10 s wait elapses                     │
  │                          ▼                               │
  └──── both probes succeed ─── HALF_OPEN ◄──────────────────┘
                               (2 probe calls allowed)
```

State transitions appear in application logs:

```
[CIRCUIT-BREAKER] 'sms-worker' → OPEN (failure rate 60.00% >= threshold 50.00%)
[CIRCUIT-BREAKER] 'sms-worker' → HALF_OPEN (wait elapsed)
[CIRCUIT-BREAKER] 'sms-worker' → CLOSED (half-open probes all succeeded)
```

When the circuit is OPEN, messages are rejected without invoking `SmsWorker`:

```
[CIRCUIT-BREAKER] 'sms-worker' call rejected — circuit is OPEN
```

Observe transitions in practice via Exercise 7 in Part 16.

---

## Part 13 — Durable Delivery

By default `@RacerListener` uses Redis Pub/Sub: messages published while the consumer
is offline are permanently lost. Switch to durable delivery (Redis Streams + consumer
groups) by editing `application.properties` **and** updating the consumer annotation.

> **Two things must change together:** the channel property (`durable=true`) controls
> what the _publisher_ does (XADD instead of PUBLISH). The _consumer_ annotation must
> also change from `@RacerListener` to `@RacerStreamListener`. These are distinct
> registrars: `@RacerListener` subscribes to Redis Pub/Sub and will never receive
> messages written to a stream. Enabling `durable=true` without changing the annotation
> causes the publisher to write to the stream while the consumer reads from an empty
> Pub/Sub channel — all messages are silently dropped.

### Step 13.1 — Enable durable email in `application.properties`

Uncomment:

```properties
racer.channels.email.durable=true
racer.channels.email.durable-group=email-consumer-group
racer.channels.email.stream-key=racer:notify:email:stream
```

### Step 13.2 — Switch `EmailWorker` to `@RacerStreamListener`

Change the annotation from `@RacerListener` to `@RacerStreamListener`:

```java
// src/main/java/com/example/notify/worker/EmailWorker.java
import com.cheetah.racer.annotation.RacerStreamListener;   // <-- new import
// ...

// Before (Pub/Sub — will not receive any messages when durable=true)
// @RacerListener(channelRef = "email", id = "email-worker", dedup = true)

// After (Redis Streams — polls via XREADGROUP, auto-ACKs on success)
@RacerStreamListener(channelRef = "email", id = "email-stream-worker")
public void onEmailNotification(RacerMessage message) {
    NotificationResult result;
    try {
        result = objectMapper.readValue(
                (String) message.getPayload(), NotificationResult.class);
    } catch (Exception e) {
        throw new RuntimeException("Invalid email payload", e);
    }
    log.info("[EMAIL] Sending to {} | id={}", result.getRecipient(), result.getId());
    // Production: call SMTP / SES / SendGrid here
}
```

> **Note — `dedup = true` is not supported on `@RacerStreamListener`.**
> Redis Streams consumer groups already guarantee at-least-once delivery within the
> group; combined with a unique stream entry ID, duplicate suppression can be
> implemented with a Redis `SET NX EX` guard inside the listener body if needed.

After restarting:

- `@PublishResult(channelRef="email")` uses `XADD` instead of `PUBLISH`
- `EmailWorker`'s `@RacerStreamListener` polls via `XREADGROUP` + auto `XACK`
- Messages published while `EmailWorker` was offline are replayed on reconnect

> **⚠ Do not manually create the consumer group** for a stream managed by racer.
> `RacerStreamListenerRegistrar` issues `XGROUP CREATE … MKSTREAM` at startup and
> silently ignores the `BUSYGROUP` error when the group already exists from a previous
> run. If you pre-create the group with a different offset or a wrong group name,
> racer's startup `ensureGroup` call will silently succeed (BUSYGROUP is swallowed) and
> consumers may miss messages or replay from the wrong offset.

| | `durable=false` (default) | `durable=true` |
|---|---|---|
| Transport | Redis Pub/Sub (`PUBLISH`) | Redis Streams (`XADD`) |
| Consumer annotation | `@RacerListener` | **`@RacerStreamListener`** |
| Consumer protocol | `subscribe(ChannelTopic)` | `XREADGROUP` + `XACK` |
| Messages missed while offline | **Lost** | **Replayed** |
| Properties to add | — | `durable`, `durable-group`, `stream-key` |
| Annotation change required | — | **Yes — `@RacerStreamListener`** |

---

## Part 14 — REST Controller

```java
// src/main/java/com/example/notify/controller/NotifyController.java
package com.example.notify.controller;

import com.example.notify.model.NotificationCommand;
import com.example.notify.model.NotificationResult;
import com.example.notify.requestreply.NotificationStatusClient;
import com.example.notify.service.NotificationService;
import com.example.notify.worker.AuditCollector;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotifyController {

    private final NotificationService      notificationService;
    private final NotificationStatusClient statusClient;
    private final AuditCollector           auditCollector;

    public NotifyController(NotificationService notificationService,
                             NotificationStatusClient statusClient,
                             AuditCollector auditCollector) {
        this.notificationService = notificationService;
        this.statusClient        = statusClient;
        this.auditCollector      = auditCollector;
    }

    /**
     * Send a single notification.
     *   type     : EMAIL | SMS | PUSH | BROADCAST
     *   priority : HIGH | NORMAL | LOW (optional, defaults to NORMAL)
     *   id       : optional idempotency key; a UUID is generated if absent
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<NotificationResult> send(@RequestBody NotificationCommand cmd) {
        if (cmd.getId() == null || cmd.getId().isBlank()) {
            cmd.setId(UUID.randomUUID().toString());
        }
        return notificationService.send(cmd);
    }

    /**
     * Query notification status via request-reply.
     * Publishes a RacerRequest, waits up to 5 s for a correlated RacerReply.
     */
    @GetMapping("/{id}/status")
    public Mono<String> status(@PathVariable String id) {
        return statusClient.checkStatus(id);
    }

    /** Returns all entries from the in-process audit log. */
    @GetMapping("/audit")
    public List<String> audit() {
        return auditCollector.getEntries();
    }
}
```

---

## Part 15 — Build and Run

### Step 15.1 — Build

```bash
cd notify-hub
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
mvn clean package -DskipTests
```

Expected:

```
[INFO] BUILD SUCCESS
[INFO] notify-hub 1.0.0-SNAPSHOT
```

### Step 15.2 — Run

```bash
java -jar target/notify-hub-1.0.0-SNAPSHOT.jar
```

Or with Maven:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Startup log should include:

```
Started NotifyApplication in X.XXX seconds
[racer] Default channel registered: 'racer:notify:default'
[racer] Channel 'email'    registered -> 'racer:notify:email'
[racer] Channel 'sms'      registered -> 'racer:notify:sms'
[racer] Channel 'push'     registered -> 'racer:notify:push'
[racer] Channel 'audit'    registered -> 'racer:notify:audit'
[racer] Channel 'requests' registered -> 'racer:notify:requests'
[racer] Registered listener 'audit-collector' -> racer:notify:audit
[racer] Registered listener 'email-worker'    -> racer:notify:email (dedup)
[racer] Registered listener 'push-worker'     -> racer:notify:push  (concurrent/4)
[racer] Registered listener 'sms-worker'      -> racer:notify:sms
[racer] Registered responder on racer:notify:requests
```

---

## Part 16 — Exercises

Work through these sequentially. Each exercise demonstrates a distinct Racer feature.

---

### Exercise 1 — Send an email notification (`@PublishResult`)

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"EMAIL","recipient":"alice@example.com","subject":"Welcome","body":"Hello Alice"}' | jq
```

Application log sequence:

```
[INTERCEPT]    id=<uuid> channel=racer:notify:email listener=email-worker
[AUDIT-FILTER] Accepted from sender='notify-hub'
[EMAIL]        Sending to alice@example.com | id=<uuid>
AUDIT:         ... | payload={...}
```

**What Racer did:**
1. `dispatchEmail()` returned a `Mono<NotificationResult>`
2. `@PublishResult` AOP tapped the pipeline and published the result to `racer:notify:email`
3. Both interceptors ran before `EmailWorker.onEmailNotification()`
4. `auditPublisher.publishAsync()` also published to `racer:notify:audit`
5. `AuditCollector.onAuditEvent()` appended to the in-memory log

---

### Exercise 2 — Deduplication (`EmailWorker`, `dedup = true`)

Submit the **same notification ID** twice within 60 seconds:

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"id":"dedup-test-001","type":"EMAIL","recipient":"bob@example.com","body":"First"}' | jq

curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"id":"dedup-test-001","type":"EMAIL","recipient":"bob@example.com","body":"First"}' | jq
```

`EmailWorker` logs show exactly **one** entry. The second message is dropped by Redis
`SET NX EX`. Verify:

```bash
redis-cli TTL racer:dedup:dedup-test-001   # remaining seconds until the dedup key expires
```

---

### Exercise 3 — Concurrent push notifications (`PushWorker`, `mode=CONCURRENT`)

Send 10 push notifications in parallel:

```bash
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8090/api/notifications \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"PUSH\",\"recipient\":\"device-$i\",\"body\":\"Msg $i\"}" &
done
wait
```

The log shows 4 concurrent entries processing simultaneously (thread names like
`racer-worker-1` through `racer-worker-4`), rather than sequentially.

---

### Exercise 4 — Broadcast via `RacerTransaction`

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"BROADCAST","recipient":"everyone","subject":"Sale","body":"50% off today!"}' | jq
```

All workers fire in sequence. To observe the four channel messages arrive in Redis
simultaneously, first subscribe to all channels:

```bash
redis-cli PSUBSCRIBE "racer:notify:*"
# Then send the broadcast in another terminal
```

---

### Exercise 5 — Request-Reply (status check)

```bash
# Send a notification with a known ID:
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"id":"rr-test-42","type":"EMAIL","recipient":"carol@example.com","body":"Test"}' | jq

# Query its status via request-reply:
curl -s http://localhost:8090/api/notifications/rr-test-42/status
```

Expected:

```json
{ "id": "rr-test-42", "status": "DELIVERED" }
```

Round-trip path:
```
REST -> NotificationStatusClient.checkStatus()
     -> RacerRequest published to racer:notify:requests
     -> StatusResponder.handleStatusRequest()
     -> RacerReply to ephemeral correlation channel
     -> Mono<String> resolves in the caller
```

---

### Exercise 6 — Trigger DLQ via SMS failure

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"SMS","recipient":"+1555000001","body":"Test","status":"FAILED"}' | jq
```

Log:

```
[SMS]  Simulated gateway failure for id=...
[DLQ]  Enqueuing failed message id=... error='SMS gateway timeout...'
```

Inspect the DLQ:

```bash
curl -s http://localhost:8090/api/notify/dlq | jq
curl -s http://localhost:8090/api/dlq | jq          # built-in Racer endpoint
curl -s http://localhost:8090/api/notify/dlq/size
```

Reprocess:

```bash
curl -s -X POST http://localhost:8090/api/notify/dlq/reprocess
```

---

### Exercise 7 — Trip the circuit breaker

Send 3 failing SMS notifications in quick succession (5-call window, 50% threshold):

```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:8090/api/notifications \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"SMS\",\"recipient\":\"+155500000$i\",\"body\":\"Fail\",\"status\":\"FAILED\"}"
done
```

The third failure raises the failure rate to 60%. Log:

```
[CIRCUIT-BREAKER] 'sms-worker' -> OPEN (failure rate 60.00% >= threshold 50.00%)
```

Now send a normal SMS — it is **rejected** (circuit is OPEN):

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"SMS","recipient":"+1555999999","body":"Are you there?"}' | jq
```

Wait 10 seconds, then send a recovery probe:

```bash
sleep 10
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"SMS","recipient":"+1555999999","body":"Recovery probe 1"}' | jq
```

Log: `[CIRCUIT-BREAKER] 'sms-worker' -> HALF_OPEN (wait elapsed)`

After 2 successful probes: `[CIRCUIT-BREAKER] 'sms-worker' -> CLOSED`

---

### Exercise 8 — Observe interceptor chain

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"PUSH","recipient":"device-XYZ","body":"Test intercept"}' | jq
```

Log (enable DEBUG on `com.example.notify.interceptor` to see AuditInterceptor):

```
[INTERCEPT]    id=... channel=racer:notify:push listener=push-worker    <- @Order(10)
[AUDIT-FILTER] Accepted from sender='notify-hub'                        <- @Order(20)
[PUSH]         Dispatching to device-XYZ | id=...                       <- PushWorker
```

---

### Exercise 9 — Inspect audit trail

```bash
curl -s http://localhost:8090/api/notifications/audit | jq
```

Every notification dispatched through any channel appears here, captured in-process by
`AuditCollector`.

---

### Exercise 10 — Priority sub-channel

```bash
curl -s -X POST http://localhost:8090/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"PUSH","recipient":"vip-device","body":"Breaking news","priority":"HIGH"}' | jq
```

Verify the priority sub-channel key in Redis:

```bash
redis-cli SUBSCRIBE "racer:notify:push:priority:HIGH"
# Then send from another terminal and watch it arrive
```

---

### Exercise 11 — Actuator metrics

```bash
# All Racer-scoped metric names:
curl -s http://localhost:8090/actuator/metrics \
  | jq '.names | map(select(startswith("racer")))'

# Published message count (tagged by channel):
curl -s "http://localhost:8090/actuator/metrics/racer.messages.published" | jq

# Listener processed / failed (tagged by listenerId):
curl -s "http://localhost:8090/actuator/metrics/racer.listener.processed" | jq
curl -s "http://localhost:8090/actuator/metrics/racer.listener.failed"    | jq

# Deduplication drop count:
curl -s "http://localhost:8090/actuator/metrics/racer.dedup.duplicates" | jq

# DLQ depth:
curl -s "http://localhost:8090/actuator/metrics/racer.dlq.size" | jq

# All registered channel aliases:
curl -s http://localhost:8090/api/channels | jq
```

---

### Exercise 12 — Enable durable email delivery

1. Stop the application (`Ctrl+C`).
2. Uncomment the three durable properties in `application.properties`:
   ```properties
   racer.channels.email.durable=true
   racer.channels.email.durable-group=email-consumer-group
   racer.channels.email.stream-key=racer:notify:email:stream
   ```
3. Restart: `mvn spring-boot:run`
4. Send an email notification and check the stream was written to:
   ```bash
   redis-cli XLEN racer:notify:email:stream
   redis-cli XRANGE racer:notify:email:stream - +
   ```
5. Stop the app, add more entries to the stream directly, then restart — `EmailWorker`
   replays the missed entries without any code changes.

---

## Part 17 — Architecture & Capability Map

```
HTTP Request
     |
     v
NotifyController
     |
     +-- POST /notifications -----------------------------------------+
     |         |                                                       |
     |         v                                                       |
     |   NotificationService.send(cmd)                                |
     |         |                                                       |
     |         +-- @PublishResult(channelRef="email") --> racer:notify:email
     |         |               |                              |        |
     |         |   [CorrelationInterceptor @Order(10)]   EmailWorker  |
     |         |   [AuditInterceptor       @Order(20)]   (dedup=true) |
     |         |                                                       |
     |         +-- @PublishResult(channelRef="sms") ---> racer:notify:sms
     |         |                                          SmsWorker    |
     |         |                                          (circuit-  |
     |         |                                           breaker)   |
     |         |                                                       |
     |         +-- @PublishResult(channelRef="push") --> racer:notify:push
     |         |                                          PushWorker   |
     |         |                                          (concurrent/4)
     |         |                                                       |
     |         +-- RacerTransaction (BROADCAST) -----> all 3 + audit  |
     |         |                                                       |
     |         +-- @RacerPublisher("audit") ---------> racer:notify:audit
     |                                                  AuditCollector |
     |                                                                 |
     +-- GET /notifications/{id}/status                               |
     |         |                                                       |
     |         v                                                       |
     |   NotificationStatusClient.checkStatus(id)                     |
     |         +--> RacerRequest --> racer:notify:requests             |
     |                               StatusResponder                   |
     |         <-- RacerReply <-- (ephemeral correlation channel)      |
     |                                                                 |
     +-- GET /notifications/audit ---- AuditCollector.getEntries()    |
```

**Every Racer annotation and API used in NotifyHub:**

| Annotation / API | Class | Purpose |
|---|---|---|
| `@EnableRacer` | `NotifyApplication` | Activate framework |
| `@EnableRacerClients` | `NotifyApplication` | Generate `@RacerClient` proxies |
| `@RacerPublisher("audit")` | `NotificationService` | Inject publisher for audit channel |
| `@PublishResult(channelRef=...)` | `NotificationService` | AOP publish on method return |
| `@RacerPriority(defaultLevel="HIGH")` | `NotificationService.sendUrgentPush()` | Route to priority sub-channel |
| `RacerTransaction.execute(tx -> ...)` | `NotificationService.sendBroadcast()` | Batch multi-channel publish |
| `RacerFunctionalRouter` DSL | `NotificationRouterConfig` | Content-based routing by `type` field with native fan-out |
| `RacerMessageInterceptor` | `CorrelationInterceptor` | Log correlation ID per incoming message |
| `RacerMessageInterceptor` | `AuditInterceptor` | Reject messages with no sender |
| `@RacerListener` (plain) | `AuditCollector` | Subscribe to audit channel |
| `@RacerListener(dedup=true)` | `EmailWorker` | Idempotent email delivery |
| `@RacerListener(mode=CONCURRENT, concurrency=4)` | `PushWorker` | Parallel push dispatch |
| `@RacerListener` + exception | `SmsWorker` | Automatic DLQ forwarding on failure |
| `@RacerClient` + `@RacerRequestReply` | `NotificationStatusClient` | Non-blocking request-reply caller |
| `@RacerResponder` | `StatusResponder` | Request-reply handler |
| `DeadLetterQueueService` | `DlqApiController` | Inspect DLQ entries |
| `DlqReprocessorService` | `DlqApiController` | Re-publish DLQ entries to original channel |
| `racer.circuit-breaker.*` | `application.properties` | Circuit breaker wrapping SmsWorker |
| `racer.dedup.*` | `application.properties` | Idempotency window for EmailWorker |
| `racer.priority.*` | `application.properties` | Priority sub-channels for push and sms |
| `racer.channels.email.durable=true` | `application.properties` | Switch email to Redis Streams |
| `racer.web.dlq-enabled=true` | `application.properties` | Enable built-in DLQ web API |
| Actuator `/metrics` | auto-configured | `racer.messages.published`, `racer.listener.*`, `racer.dedup.*`, `racer.dlq.size` |

---

## Part 18 — What is Not Covered Here

This tutorial demonstrates every annotation and API available in a single-application
scope. A few advanced topics have dedicated tutorials:

| Topic | Tutorial |
|---|---|
| Two-way request-reply over Redis Streams | [Tutorial 6](TUTORIALS.md#tutorial-6--two-way-request-reply-over-redis-streams) |
| Multiple consumer instances per stream | [Tutorial 16](TUTORIALS.md#tutorial-16--consumer-scaling--stream-sharding) |
| Stream sharding for very-high-throughput channels | [Tutorial 16](TUTORIALS.md#tutorial-16--consumer-scaling--stream-sharding) |
| JSON Schema validation on publish / consume | (Tutorial — Schema Registry) |
| Prometheus + Grafana dashboard setup | [Tutorial 12](TUTORIALS.md#tutorial-12--metrics--observability-actuator--prometheus) |
| DLQ pruning by age (RacerRetentionService) | [Tutorial 13](TUTORIALS.md#tutorial-13--retention--dlq-pruning) |
| Pipelined batch publish for maximum throughput | [Tutorial 17](TUTORIALS.md#tutorial-17--pipelined-batch-publishing) |
| High availability with Redis Sentinel / Cluster | [Tutorial 15](TUTORIALS.md#tutorial-15--high-availability-sentinel--cluster) |
| Consumer group lag dashboard | [Tutorial 24](TUTORIALS.md#tutorial-24--consumer-group-lag-dashboard) |

---

## Part 19 — Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ExceptionInInitializerError: TypeTag :: UNKNOWN` | JDK 24/25 in use | `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `Connection refused` (Redis) | Redis not running | `docker compose -f /path/to/racer/compose.yaml up -d` |
| `NoSuchBeanDefinitionException: RacerChannelPublisher` | `@EnableRacer` missing | Add `@EnableRacer` to `NotifyApplication` |
| `@RacerPublisher` field is `null` at runtime | Bean not a Spring-managed proxy | Ensure class has `@Service` / `@Component` and comes from the application context |
| `@PublishResult` does not publish | Self-invocation — `send()` calling `this.dispatchEmail()` bypasses the Spring AOP proxy | Use `@RacerPublisher` field injection and call `publishAsync()` directly inside the routing method, as shown in `NotificationService.send()` |
| `NoSuchBeanDefinitionException: NotificationStatusClient` | `@EnableRacerClients` missing | Add `@EnableRacerClients` to `NotifyApplication` |
| Channel alias not found | Alias missing from `application.properties` | Add `racer.channels.<alias>.name=...` |
| Deduplication not working | `racer.dedup.enabled=false` | Set `racer.dedup.enabled=true` |
| Circuit breaker never opens | `racer.circuit-breaker.enabled=false` | Set `racer.circuit-breaker.enabled=true` |
| DLQ always empty after listener exception | `DeadLetterQueueService` not wired | Ensure the `racer` starter is on the classpath |
| Durable listener misses all messages | `durable=true` set in properties but consumer still uses `@RacerListener` | Switch the consumer annotation to `@RacerStreamListener` — `@RacerListener` reads from Pub/Sub only and will not receive stream messages |
| Durable listener misses messages after restart | `durable=true` only on one side | Set `racer.channels.<alias>.durable=true` on both publisher and consumer sides |
| `ClassCastException` or `JsonMappingException` deserializing `RacerMessage.payload` | `objectMapper.convertValue(message.getPayload(), ...)` called on a `String` | Use `objectMapper.readValue((String) message.getPayload(), YourType.class)` — `payload` is always a JSON String |
| `NoSuchMethodError` / compile error on `dlqReprocessor.reprocessNext()` | Method does not exist | Use `dlqReprocessor.republishOne()` → `Mono<Long>` (subscriber count) or `dlqReprocessor.republishAll()` → `Mono<Long>` (total republished) |
| Interceptors not running for `@RacerStreamListener` workers | `RacerMessageInterceptor` beans only apply to `@RacerListener` (Pub/Sub) | Use a Spring `@Aspect` on `@RacerStreamListener` methods for equivalent pre-processing |
| `async=true` set but messages still lost when listener is offline | `async` controls publish blocking, not transport; Pub/Sub is still used | Set `durable=true` (and switch to `@RacerStreamListener`) to use Redis Streams |
| Request-reply times out immediately | `@RacerResponder` not started or wrong alias | Verify `@RacerResponder(channelRef="requests")` and matching alias on both sides |
| HTTP 400/500 on `POST /notifications` | Unrecognised `type` value | Use `EMAIL`, `SMS`, `PUSH`, or `BROADCAST` (case-insensitive) |
