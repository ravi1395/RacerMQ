# Build a New Application with Racer — From Scratch

This tutorial builds a complete **Inventory Management** Spring Boot application from
scratch using the Racer messaging framework. By the end you will have a running service
that:

- Receives REST requests and publishes domain events to Redis channels
- Uses `@RacerPublisher` for injected, property-driven publishers
- Uses `@PublishResult` to auto-publish method return values without boilerplate
- Applies content-based routing with `@RacerRoute` to fan events to dedicated channels
- Consumes its own events to maintain an in-memory audit log
- Ships messages to the DLQ automatically when processing fails
- Exposes Actuator metrics via Micrometer

> This tutorial is self-contained. You do **not** need to run `racer-demo` or any other
> Racer module alongside your application. Your new application acts as both publisher and subscriber.

---

## Prerequisites

| Requirement   | Version / Notes                                                 |
|---------------|------------------------------------------------------------------|
| Java          | **21** – pin via `JAVA_HOME`                                     |
| Maven         | 3.9+                                                             |
| Docker        | Any recent Desktop version                                       |
| Racer JARs    | Already installed to local Maven (`mvn clean install` in Racer)  |

> **Verify your JDK:**
> ```bash
> export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
> java -version   # must print openjdk version "21…"
> ```

---

## Part 1 — Infrastructure

### Step 1.1 — Start Redis

Open **Terminal A** inside the Racer repository and start the single-node Redis:

```bash
cd /path/to/racer
docker compose -f compose.yaml up -d
```

Verify it is ready:

```bash
docker ps --filter name=redis
redis-cli ping     # → PONG
```

The `compose.yaml` runs Redis 7 on the default port `6379` with a persistent named
volume so data survives restarts.

---

## Part 2 — Project Skeleton

### Step 2.1 — Directory layout

Create the following structure anywhere on your machine (not inside the Racer repo):

```
inventory-service/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/example/inventory/
        │       ├── InventoryApplication.java
        │       ├── model/
        │       │   └── InventoryItem.java
        │       ├── service/
        │       │   ├── InventoryService.java
        │       │   └── InventoryAuditConsumer.java
        │       ├── router/
        │       │   └── InventoryEventRouter.java
        │       └── controller/
        │           └── InventoryController.java
        └── resources/
            └── application.properties
```

```bash
mkdir -p inventory-service/src/main/java/com/example/inventory/{model,service,router,controller}
mkdir -p inventory-service/src/main/resources
cd inventory-service
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
    <artifactId>inventory-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>inventory-service</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!--
          ┌─────────────────────────────────────────────────┐
          │  racer-starter — one dependency for everything  │
          │  Brings in:                                     │
          │    • racer-common (annotations, models, config) │
          │    • spring-boot-starter-data-redis-reactive    │
          │    • spring-boot-starter-aop                    │
          │    • jackson-datatype-jsr310                    │
          └─────────────────────────────────────────────────┘
        -->
        <dependency>
            <groupId>com.cheetah</groupId>
            <artifactId>racer-starter</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>

        <!-- Reactive HTTP layer -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Actuator — metrics + health endpoints -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Lombok for concise domain models -->
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
            <!-- Ensure Lombok annotation processing works with JDK 21 -->
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

> **Why `racer-starter` and not `racer-common` directly?**
> `racer-starter` is a zero-code aggregator (same pattern as Spring Boot starters) that
> pulls all required transitive dependencies in a single `<dependency>` block. It mirrors
> what `spring-boot-starter-web` does: you don't add Tomcat, Jackson, and the web MVC
> framework individually — the starter handles that for you.

---

### Step 2.3 — `application.properties`

```properties
# ── Server ──────────────────────────────────────────────────────────────────
server.port=8090

# ── Redis ────────────────────────────────────────────────────────────────────
spring.data.redis.host=localhost
spring.data.redis.port=6379

# ── Racer ────────────────────────────────────────────────────────────────────
# Default channel (used when @RacerPublisher has no alias)
racer.default-channel=racer:inventory:events

# Named channel: stock — async, high-throughput stock level updates
racer.channels.stock.name=racer:inventory:stock
racer.channels.stock.async=true
racer.channels.stock.sender=inventory-service

# Named channel: alerts — sync, low-latency critical alerts
racer.channels.alerts.name=racer:inventory:alerts
racer.channels.alerts.async=false
racer.channels.alerts.sender=inventory-service

# Named channel: audit — async append-only audit trail
racer.channels.audit.name=racer:inventory:audit
racer.channels.audit.async=true
racer.channels.audit.sender=inventory-service

# ── Actuator ─────────────────────────────────────────────────────────────────
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
```

---

## Part 3 — Application Code

### Step 3.1 — Main class

```java
// src/main/java/com/example/inventory/InventoryApplication.java
package com.example.inventory;

import com.cheetah.racer.common.annotation.EnableRacer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRacer   // enables @RacerPublisher injection, @PublishResult AOP, channel registry
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
```

> **`@EnableRacer` is optional** when using `racer-starter` because
> `RacerAutoConfiguration` is registered automatically via
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
> Add it explicitly as self-documenting intent that your application uses the Racer
> framework, or when you depend on `racer-common` directly instead of the starter.

---

### Step 3.2 — Domain model

```java
// src/main/java/com/example/inventory/model/InventoryItem.java
package com.example.inventory.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class InventoryItem {
    private String sku;
    private String name;
    private int    quantity;
    private String location;
    private String eventType;    // e.g. STOCK_UPDATED, LOW_STOCK_ALERT, ITEM_CREATED
    private String correlationId;
    private Instant updatedAt;
}
```

---

### Step 3.3 — Inventory service

This is where the main features of Racer come together.

```java
// src/main/java/com/example/inventory/service/InventoryService.java
package com.example.inventory.service;

import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import com.example.inventory.model.InventoryItem;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryService {

    // ── In-memory store (replace with a database in production) ──────────────
    private final Map<String, InventoryItem> store = new ConcurrentHashMap<>();

    /**
     * @RacerPublisher injects a publisher pre-configured for the "alerts" channel.
     * No constructor or @Autowired needed — the RacerPublisherFieldProcessor handles it.
     */
    @RacerPublisher("alerts")
    private RacerChannelPublisher alertsPublisher;

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE — @PublishResult auto-publishes the return value to racer:inventory:stock
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new item. The returned InventoryItem is automatically serialized to JSON
     * and published to racer:inventory:stock (the "stock" channel alias) by the
     * @PublishResult AOP interceptor — no explicit publish call needed.
     */
    @PublishResult(channelRef = "stock", sender = "inventory-service", async = true)
    public Mono<InventoryItem> createItem(String sku, String name, int qty, String location) {
        InventoryItem item = InventoryItem.builder()
                .sku(sku)
                .name(name)
                .quantity(qty)
                .location(location)
                .eventType("ITEM_CREATED")
                .correlationId(UUID.randomUUID().toString())
                .updatedAt(Instant.now())
                .build();
        store.put(sku, item);
        return Mono.just(item);
        // ↑ @PublishResult intercepts this Mono, taps the emitted value, and
        //   publishes it to racer:inventory:stock BEFORE forwarding to the caller.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE STOCK — @PublishResult + conditional low-stock alert
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates stock level. If quantity drops below 10, also publishes a LOW_STOCK_ALERT
     * directly using the injected alertsPublisher (synchronous — waits for Redis).
     */
    @PublishResult(channelRef = "stock", sender = "inventory-service", async = true)
    public Mono<InventoryItem> updateStock(String sku, int delta) {
        return Mono.justOrEmpty(store.get(sku))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("SKU not found: " + sku)))
                .flatMap(item -> {
                    InventoryItem updated = InventoryItem.builder()
                            .sku(item.getSku())
                            .name(item.getName())
                            .quantity(item.getQuantity() + delta)
                            .location(item.getLocation())
                            .eventType("STOCK_UPDATED")
                            .correlationId(UUID.randomUUID().toString())
                            .updatedAt(Instant.now())
                            .build();
                    store.put(sku, updated);

                    Mono<InventoryItem> result = Mono.just(updated);

                    // Sidecar alert when stock is critically low
                    if (updated.getQuantity() < 10) {
                        InventoryItem alert = InventoryItem.builder()
                                .sku(sku)
                                .name(updated.getName())
                                .quantity(updated.getQuantity())
                                .location(updated.getLocation())
                                .eventType("LOW_STOCK_ALERT")
                                .correlationId(updated.getCorrelationId())
                                .updatedAt(Instant.now())
                                .build();
                        // publishAsync returns Mono<Long> (subscriber count); flatMap chains it
                        return alertsPublisher.publishAsync(alert).then(result);
                    }

                    return result;
                });
        // ↑ @PublishResult publishes the final InventoryItem (STOCK_UPDATED) to racer:inventory:stock
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET (no publishing)
    // ─────────────────────────────────────────────────────────────────────────

    public Mono<InventoryItem> getItem(String sku) {
        return Mono.justOrEmpty(store.get(sku));
    }
}
```

---

### Step 3.4 — Content-based event router

`@RacerRoute` lets you declaratively fan-out events to channel aliases based on a JSON
field pattern. No routing code is required inside your message processors.

```java
// src/main/java/com/example/inventory/router/InventoryEventRouter.java
package com.example.inventory.router;

import com.cheetah.racer.common.annotation.RacerRoute;
import com.cheetah.racer.common.annotation.RacerRouteRule;
import org.springframework.stereotype.Service;

/**
 * Routes incoming messages on racer:inventory:events by their "eventType" field:
 *
 *   eventType = "STOCK_UPDATED"  →  racer:inventory:stock  (alias: stock)
 *   eventType = "LOW_STOCK_ALERT" → racer:inventory:alerts (alias: alerts)
 *   eventType = "ITEM_CREATED"   →  racer:inventory:audit  (alias: audit)
 *
 * Rules are evaluated in declaration order. First match wins.
 * The annotated class acts as a config container — no methods needed.
 */
@Service
@RacerRoute({
    @RacerRouteRule(field = "eventType", matches = "STOCK_UPDATED",   to = "stock"),
    @RacerRouteRule(field = "eventType", matches = "LOW_STOCK.*",     to = "alerts"),
    @RacerRouteRule(field = "eventType", matches = "ITEM_.*",         to = "audit")
})
public class InventoryEventRouter {
    // No methods required — Racer's RacerRouterService reads the annotations at startup
}
```

---

### Step 3.5 — In-process audit consumer

Subscribe to the `audit` channel within the same application using `@RacerListener` to
maintain a live audit log — no manual container setup required.

```java
// src/main/java/com/example/inventory/service/InventoryAuditConsumer.java
package com.example.inventory.service;

import com.cheetah.racer.common.annotation.RacerListener;
import com.cheetah.racer.common.model.RacerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class InventoryAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryAuditConsumer.class);

    // In-memory audit log (replace with a persistent store in production)
    private final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());

    /**
     * @RacerListener binds this method to racer:inventory:audit at startup.
     * RacerListenerRegistrar (BeanPostProcessor) handles subscription,
     * deserialization, DLQ forwarding, and metrics — no boilerplate needed.
     */
    @RacerListener(channel = "racer:inventory:audit", id = "audit-consumer")
    public void onAuditEvent(RacerMessage message) {
        String entry = "[" + message.getTimestamp() + "] "
                + message.getSender() + " → " + message.getPayload();
        auditLog.add(entry);
        log.info("AUDIT: {}", entry);
    }

    /** Returns all recorded audit entries (newest last). */
    public List<String> getAuditLog() {
        return Collections.unmodifiableList(auditLog);
    }
}
```

> `@RacerListener` also integrates with metrics (`racer.listener.processed`,
> `racer.listener.failed`) and the DLQ — exceptions thrown inside `onAuditEvent` are
> automatically forwarded to the Dead Letter Queue.

---

### Step 3.6 — REST controller

```java
// src/main/java/com/example/inventory/controller/InventoryController.java
package com.example.inventory.controller;

import com.example.inventory.model.InventoryItem;
import com.example.inventory.service.InventoryAuditConsumer;
import com.example.inventory.service.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryAuditConsumer auditConsumer;

    public InventoryController(InventoryService inventoryService,
                               InventoryAuditConsumer auditConsumer) {
        this.inventoryService = inventoryService;
        this.auditConsumer = auditConsumer;
    }

    /** Create a new inventory item — publishes ITEM_CREATED to racer:inventory:stock */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<InventoryItem> create(@RequestBody Map<String, Object> body) {
        return inventoryService.createItem(
                (String) body.get("sku"),
                (String) body.get("name"),
                ((Number) body.get("quantity")).intValue(),
                (String) body.getOrDefault("location", "WAREHOUSE-A")
        );
    }

    /** Get a single item */
    @GetMapping("/{sku}")
    public Mono<InventoryItem> get(@PathVariable String sku) {
        return inventoryService.getItem(sku)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU not found: " + sku)));
    }

    /**
     * Adjust stock level (positive = restock, negative = consume).
     * Publishes STOCK_UPDATED to racer:inventory:stock.
     * If new quantity < 10, also publishes LOW_STOCK_ALERT to racer:inventory:alerts.
     */
    @PatchMapping("/{sku}/stock")
    public Mono<InventoryItem> adjustStock(@PathVariable String sku,
                                           @RequestBody Map<String, Object> body) {
        int delta = ((Number) body.get("delta")).intValue();
        return inventoryService.updateStock(sku, delta)
                .onErrorMap(IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    /** Return the live audit log captured from racer:inventory:audit */
    @GetMapping("/audit")
    public List<String> auditLog() {
        return auditConsumer.getAuditLog();
    }
}
```

---

## Part 4 — Build and Run

### Step 4.1 — Build

```bash
cd inventory-service
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
mvn clean package -DskipTests
```

Expected output:
```
[INFO] BUILD SUCCESS
[INFO] inventory-service 1.0.0-SNAPSHOT
```

### Step 4.2 — Run

```bash
java -jar target/inventory-service-1.0.0-SNAPSHOT.jar
```

Or with Maven directly:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Startup log should contain:
```
Started InventoryApplication in X.XXX seconds
[racer] Default channel registered: 'racer:inventory:events'
[racer] Channel 'stock'   registered → 'racer:inventory:stock'
[racer] Channel 'alerts'  registered → 'racer:inventory:alerts'
[racer] Channel 'audit'   registered → 'racer:inventory:audit'
```

---

## Part 5 — Exercises

Work through these in order; each builds on the previous one.

---

### Exercise 1 — Create an item

```bash
curl -s -X POST http://localhost:8090/api/inventory \
  -H "Content-Type: application/json" \
  -d '{"sku":"WIDGET-001","name":"Blue Widget","quantity":50,"location":"SHELF-B3"}' | jq
```

Expected response:
```json
{
  "sku":           "WIDGET-001",
  "name":          "Blue Widget",
  "quantity":      50,
  "location":      "SHELF-B3",
  "eventType":     "ITEM_CREATED",
  "correlationId": "...",
  "updatedAt":     "2026-03-01T12:00:00Z"
}
```

What happened in the background:

1. `InventoryService.createItem()` returned the `InventoryItem` mono.
2. The `@PublishResult` AOP interceptor tapped the reactive pipeline and published the
   serialized item to `racer:inventory:stock`.
3. The `InventoryEventRouter` (if a consumer is running on `racer:inventory:events`)
   would route an `ITEM_CREATED` event to the `audit` channel alias.
4. `InventoryAuditConsumer` would append it to the in-memory audit log.

---

### Exercise 2 — Read the item back

```bash
curl -s http://localhost:8090/api/inventory/WIDGET-001 | jq
```

---

### Exercise 3 — Restock (normal update)

```bash
curl -s -X PATCH http://localhost:8090/api/inventory/WIDGET-001/stock \
  -H "Content-Type: application/json" \
  -d '{"delta":20}' | jq
```

The `STOCK_UPDATED` event is published to `racer:inventory:stock`. Quantity is now 70 —
no alert triggered.

---

### Exercise 4 — Drain stock below alert threshold

```bash
curl -s -X PATCH http://localhost:8090/api/inventory/WIDGET-001/stock \
  -H "Content-Type: application/json" \
  -d '{"delta":-65}' | jq
```

Quantity drops to 5. Watch the application logs — you should see **two** Redis publishes:

```
[racer] publishing to racer:inventory:stock   ← @PublishResult (STOCK_UPDATED)
[racer] publishing to racer:inventory:alerts  ← alertsPublisher.publishAsync (LOW_STOCK_ALERT)
```

To observe the raw messages in Redis:

```bash
redis-cli SUBSCRIBE racer:inventory:alerts
# (leave this running, then run the PATCH above in another terminal)
```

---

### Exercise 5 — Inspect the audit log

```bash
curl -s http://localhost:8090/api/inventory/audit | jq
```

You should see entries for every event that was routed through `racer:inventory:audit`.

---

### Exercise 6 — Watch activity in Redis directly

Open two terminals:

**Terminal X** — subscribe to all inventory channels:
```bash
redis-cli PSUBSCRIBE "racer:inventory:*"
```

**Terminal Y** — run a batch of creates and updates:
```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:8090/api/inventory \
    -H "Content-Type: application/json" \
    -d "{\"sku\":\"SKU-$i\",\"name\":\"Item $i\",\"quantity\":$((i * 5))}" > /dev/null
done
```

Terminal X will show every message as it arrives on each channel.

---

### Exercise 7 — Verify metrics

```bash
curl -s http://localhost:8090/actuator/metrics | jq '.names | map(select(startswith("racer")))'
```

Individual metric:
```bash
curl -s "http://localhost:8090/actuator/metrics/racer.messages.published" | jq
```

Prometheus scrape endpoint (if you have Prometheus running):
```bash
curl -s http://localhost:8090/actuator/prometheus | grep racer
```

---

## Part 6 — How it all fits together

```
HTTP Request
    │
    ▼
InventoryController
    │
    ▼
InventoryService.createItem()  ──── @PublishResult ──►  racer:inventory:stock
    │                                                          │
    ▼ (if qty < 10)                                            │
alertsPublisher.publishAsync()  ───────────────────►  racer:inventory:alerts
                                                               │
                                              @RacerListener("racer:inventory:audit")
                                              (InventoryAuditConsumer.onAuditEvent)
                                              appends to in-memory audit log
                                                               │
                                              GET /api/inventory/audit
```

**Racer annotations used:**

| Annotation | Where | What it does |
|---|---|---|
| `@EnableRacer` | `InventoryApplication` | Activates auto-configuration, AOP, registry, field processor |
| `@RacerPublisher("alerts")` | `InventoryService` | Injects a publisher bound to `racer:inventory:alerts` |
| `@PublishResult(channelRef="stock")` | `createItem`, `updateStock` | Auto-publishes the return value to `racer:inventory:stock` without any `publishAsync()` call |
| `@RacerRoute` + `@RacerRouteRule` | `InventoryEventRouter` | Declaratively fans out events from the default channel to dedicated sub-channels |
| `@RacerListener(channel="racer:inventory:audit")` | `InventoryAuditConsumer` | Subscribes the method to the audit channel; handles deserialization, metrics, and DLQ automatically |

---

## Part 7 — What's next

Once comfortably running, explore these Racer capabilities:

| Next step | Tutorial |
|---|---|
| Durable delivery with Redis Streams (`@PublishResult(durable=true)`) | [Tutorial 11](TUTORIALS.md#tutorial-11--durable-publishing-publishresult-durabletrue) |
| Dead Letter Queue — automatic retry on failure | [Tutorial 4](TUTORIALS.md#tutorial-4--dead-letter-queue--reprocessing) |
| Request-Reply (send a message, wait for a typed response) | [Tutorial 5](TUTORIALS.md#tutorial-5--two-way-request-reply-over-pubsub) |
| Atomic multi-channel batch publish (`RacerTransaction`) | [Tutorial 14](TUTORIALS.md#tutorial-14--atomic-batch-publishing-racertransaction) |
| Promethues / Actuator metrics deep-dive | [Tutorial 12](TUTORIALS.md#tutorial-12--metrics--observability-actuator--prometheus) |
| High availability with Redis Sentinel | [Tutorial 15](TUTORIALS.md#tutorial-15--high-availability-sentinel--cluster) |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ExceptionInInitializerError: TypeTag :: UNKNOWN` | Maven is using JDK 24/25 | `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| `Connection refused` on Redis | Redis not running | `docker compose -f /path/to/racer/compose.yaml up -d` |
| `NoSuchBeanDefinitionException: RacerChannelPublisher` | `@EnableRacer` missing or `racer-starter` not on classpath | Check POM and add `@EnableRacer` to main class |
| `@RacerPublisher` field is `null` at runtime | Bean is not a Spring-managed proxy (e.g. `new MyService()`) | Ensure the class is annotated `@Service` / `@Component` and obtained from the context |
| `@PublishResult` not intercepting calls | AOP proxy not applied — bean called from within the same class | Move the `@PublishResult` method to a separate `@Service` bean |
| Channel not found for alias | Alias missing in `application.properties` | Add `racer.channels.<alias>.name=...` |
