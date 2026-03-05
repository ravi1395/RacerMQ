# Racer — Roadmap & Next Steps

## Current State Assessment

**Usable for internal use today.** Core feature set is complete, well-documented, and 115 unit tests pass. Auto-configuration wired via Spring Boot 3 `AutoConfiguration.imports`. Can be dropped into any Spring Boot 3 project and work immediately.

**Blocking a 1.0.0 / public release:** no integration tests against a real Redis, no LICENSE file, no CI/CD, missing `spring-configuration-metadata.json` for IDE support.

---

## Phase 1 — Production Hardening

### 1.1 Integration Tests with Testcontainers Redis

All 11 test files use Mockito mocks — zero proof that pub/sub, streams, DLQ, or request-reply actually work against a real Redis. This is the single biggest gap.

**Scope:**
- Add `org.testcontainers:testcontainers` and `org.testcontainers:junit-jupiter` to `pom.xml`
- Create a `RedisIntegrationTest` base class that spins up a Redis container and wires a full Spring context
- Write end-to-end tests for:
  - Pub/Sub publish → `@RacerListener` (SEQUENTIAL, CONCURRENT, AUTO modes)
  - `@PublishResult` side-effect publishing
  - `@RacerStreamListener` with consumer group XREADGROUP
  - DLQ routing on listener exception, and `/api/dlq/republish/one`
  - Request-reply round-trip (`@RacerResponder` + `@RacerClient`)
  - Content-based routing (`@RacerRoute` / `@RacerRouteRule`)
  - Priority sub-channel publishing and consumption order
  - Adaptive concurrency AUTO mode — verify semaphore permit changes

**POM addition:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

---

### 1.2 `spring-configuration-metadata.json` (IDE Autocomplete)

Without it, no IDE auto-complete or documentation pop-ups for `racer.*` properties. The fix is a 2-line POM change — the annotation processor generates the file automatically at compile time.

**POM addition:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

Verify the generated file appears at `target/classes/META-INF/spring-configuration-metadata.json` and is included in the JAR's `META-INF/`.

---

### 1.3 Spring Boot `HealthIndicator` Beans

Actuator `/actuator/health` currently says nothing about Racer. Add a `RacerHealthIndicator` that reports:
- Redis connectivity (ping)
- Number of active `@RacerListener` subscriptions
- DLQ depth (warn if > threshold, configurable via `racer.health.dlq-warn-threshold`)
- Thread pool utilization: active threads / max threads, queue depth

```java
@Component
@ConditionalOnClass(HealthIndicator.class)
public class RacerHealthIndicator implements HealthIndicator {
    // inject RacerListenerRegistrar, DeadLetterQueueService, ThreadPoolExecutor
    // return Health.up() with details, or Health.down() if Redis unreachable
}
```

---

### 1.4 Thread Pool & Consumer-Lag Metrics

Current metrics: `racer.listener.processed`, `racer.listener.failed` counters.

**Add:**
- `racer.thread-pool.queue-depth` (gauge) — length of `ThreadPoolExecutor` work queue
- `racer.thread-pool.active-threads` (gauge) — `executor.getActiveCount()`
- `racer.thread-pool.pool-size` (gauge) — current pool size vs max
- `racer.stream.consumer.lag` (gauge, per stream + group) — from `XPENDING` count
- `racer.auto.concurrency` (gauge, per listener id) — current `AdaptiveConcurrencyTuner` permit count

---

### 1.5 Exception Hierarchy

Only 2 custom exceptions exist (`SchemaValidationException`, `RacerRequestReplyException`), both `extends RuntimeException` independently.

**Add a base class and richer hierarchy:**
```
RacerException (RuntimeException)
├── RacerPublishException         — failure publishing to Redis
├── RacerListenerException        — failure dispatching to a listener method
├── RacerSchemaValidationException (rename existing)
├── RacerRequestReplyException    (rename existing, add timeout-specific subclass)
│   └── RacerRequestReplyTimeoutException
├── RacerDlqException             — failure enqueuing to DLQ
└── RacerConfigurationException   — startup-time misconfiguration detected
```

---

## Phase 2 — Release Prep

### 2.1 LICENSE File
Add `LICENSE` (Apache 2.0 recommended for Spring ecosystem) to repo root.

### 2.2 CHANGELOG.md
Create `CHANGELOG.md` tracking features from initial commit through 1.0.0.

Format (Keep a Changelog):
```markdown
## [1.0.0] - 2026-MM-DD
### Added
- ConcurrencyMode.AUTO — AIMD adaptive tuner
- racer.thread-pool.* — dedicated listener thread pool
- @PublishResult sender/async channel-property inheritance
- Tutorial 20 — Performance Tuning
...
```

### 2.3 POM Metadata for Maven Central
Add to `pom.xml`:
```xml
<licenses>
    <license>
        <name>Apache License, Version 2.0</name>
        <url>https://www.apache.org/licenses/LICENSE-2.0</url>
    </license>
</licenses>
<developers>
    <developer>
        <name>...</name>
        <email>...</email>
    </developer>
</developers>
<scm>
    <connection>scm:git:git://github.com/...</connection>
    <developerConnection>scm:git:ssh://github.com/...</developerConnection>
    <url>https://github.com/...</url>
</scm>
<distributionManagement>
    <snapshotRepository>
        <id>ossrh</id>
        <url>https://s01.oss.sonatype.org/content/repositories/snapshots</url>
    </snapshotRepository>
</distributionManagement>
```

### 2.4 GitHub Actions CI Pipeline

`.github/workflows/ci.yml`:
- Trigger: push to `main`, pull requests
- Steps: checkout → setup Java 21 → `mvn verify` (with Testcontainers Redis)
- Separate release workflow: tag → `mvn deploy` → Sonatype staging → auto-release

### 2.5 Version Bump to `1.0.0`
Change `pom.xml` version from `0.0.1-SNAPSHOT` to `1.0.0`.

---

## Phase 3 — Post-1.0 Features

### 3.1 Message Deduplication
Idempotency key tracking via a Redis SET with TTL. Prevents re-processing on redelivery.

```properties
racer.dedup.enabled=false
racer.dedup.ttl-seconds=300        # how long to remember a processed message id
racer.dedup.key-prefix=racer:dedup:
```

Annotation opt-in: `@RacerListener(dedup = true)` — checks `id` field of `RacerMessage` against the Redis SET before dispatching, adds after success.

### 3.2 Circuit Breaker Integration
Resilience4j or Spring Cloud Circuit Breaker wrapping listener dispatch. When error rate exceeds threshold, stop consuming and open the circuit — prevents cascading failures when a downstream dependency is down.

```properties
racer.circuit-breaker.enabled=false
racer.circuit-breaker.failure-rate-threshold=50
racer.circuit-breaker.wait-duration-in-open-state=30s
```

### 3.3 Back-pressure Signaling
When the thread pool queue is >80% full:
- Slow down `XREADGROUP` poll rate (back off `racer.consumer.poll-interval-ms` dynamically)
- Pause Pub/Sub subscription (`ReactiveRedisMessageListenerContainer.removeMessageListener(...)`) until queue drains

### 3.4 Consumer Group Lag Dashboard
`XPENDING` + `XINFO GROUPS` scraped on a configurable interval, exported as Micrometer gauges. Prometheus alert on lag > threshold.

---

## Priority Order (single backlog)

| # | Item | Phase | Effort |
|---|------|-------|--------|
| 1 | Integration tests (Testcontainers) | 1 | Large |
| 2 | spring-configuration-metadata.json | 1 | Tiny |
| 3 | HealthIndicator | 1 | Small |
| 4 | Thread pool + lag metrics | 1 | Small |
| 5 | Exception hierarchy | 1 | Small |
| 6 | LICENSE file | 2 | Tiny |
| 7 | CHANGELOG.md | 2 | Small |
| 8 | POM Maven Central metadata | 2 | Small |
| 9 | GitHub Actions CI | 2 | Medium |
| 10 | Version `1.0.0` | 2 | Tiny |
| 11 | Message deduplication | 3 | Medium |
| 12 | Circuit breaker integration | 3 | Medium |
| 13 | Back-pressure signaling | 3 | Large |
| 14 | Consumer lag dashboard | 3 | Small |
| 15 | Multi-module split | 3 | Large |
