# Racer — Production Deployment Guide

> **Stack:** Spring Boot 3.4.3 · Java 21 · Reactive WebFlux · Lettuce Redis  
> **Version:** 1.3.0+

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Security Checklist](#security-checklist)
3. [Redis Connectivity](#redis-connectivity)
4. [Recommended `application.properties`](#recommended-applicationproperties)
5. [Docker / Docker Compose](#docker--docker-compose)
6. [Kubernetes Deployment](#kubernetes-deployment)
7. [Thread-Pool Sizing](#thread-pool-sizing)
8. [Observability](#observability)
9. [Common Pitfalls](#common-pitfalls)

---

## Prerequisites

| Requirement | Minimum | Notes |
|-------------|---------|-------|
| Java | 21 | Virtual threads ready; GraalVM Native not tested |
| Redis | 6.2 | Streams (XADD/XREAD) required; 7.x recommended |
| Spring Boot | 3.4.3 | Included via `racer` starter |
| Optional | Micrometer + Prometheus | For metrics scraping |
| Optional | Spring Security | For Admin UI / API auth |

---

## Security Checklist

> ⚠️ Racer's security features are **opt-in**. None are active by default.

### Enable authentication (required for production)

Add `spring-boot-starter-security` to your application:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Then enable Racer's built-in security chain in `application.properties`:

```properties
racer.web.security.enabled=true
```

This registers a `SecurityFilterChain` covering both:
- `/api/**` — Racer REST endpoints (DLQ, schema, router, channels, retention)
- `/racer-admin/**` — Admin UI static dashboard

The security chain requires `RACER_ADMIN` role by default. Configure users in your
`SecurityConfig` or via your identity provider.

### Minimal Spring Security config

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public MapReactiveUserDetailsService users() {
        UserDetails admin = User.withDefaultPasswordEncoder()
            .username("racer-admin")
            .password("change-me")          // Use a secret — never hardcode in production
            .roles("RACER_ADMIN")
            .build();
        return new MapReactiveUserDetailsService(admin);
    }
}
```

**Production pattern:** Load credentials from Kubernetes Secrets or Vault:

```yaml
# application.yaml
spring:
  security:
    user:
      name: ${RACER_ADMIN_USER}
      password: ${RACER_ADMIN_PASSWORD}
      roles: RACER_ADMIN
```

### Disable unused endpoints

Only expose what you actually use:

```properties
racer.web.dlq-enabled=true          # expose /api/dlq/**
racer.web.schema-enabled=false
racer.web.router-enabled=false
racer.web.channels-enabled=false
racer.web.retention-enabled=false
racer.web.admin-ui-enabled=false    # disable Admin UI entirely if not needed
```

### HTTPS

Always terminate TLS at the load balancer or Kubernetes Ingress. If using Racer
embedded, configure `server.ssl.*` or place it behind an HTTPS proxy.

---

## Redis Connectivity

### Standalone (simplest)

```properties
spring.data.redis.host=redis.example.com
spring.data.redis.port=6379
spring.data.redis.password=${REDIS_PASSWORD}
spring.data.redis.ssl.enabled=true
```

### Sentinel (recommended for HA)

```properties
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=sentinel1:26379,sentinel2:26379,sentinel3:26379
spring.data.redis.sentinel.password=${SENTINEL_PASSWORD}
spring.data.redis.password=${REDIS_PASSWORD}
```

See `compose.sentinel.yaml` for a working local Sentinel topology (1 master + 2 replicas
+ 3 sentinels).

### Cluster

```properties
spring.data.redis.cluster.nodes=node1:6379,node2:6379,node3:6379
spring.data.redis.cluster.max-redirects=3
spring.data.redis.password=${REDIS_PASSWORD}
```

See `compose.cluster.yaml` for a working local 3-node cluster.

Racer uses **consistent-hash publishing** in cluster mode (Phase 4.1): each channel
key maps to a deterministic primary node, so all publishers for the same channel
always target the same slot.

### Lettuce connection pool (production tuning)

```properties
spring.data.redis.lettuce.pool.enabled=true
spring.data.redis.lettuce.pool.min-idle=2
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.max-active=16
spring.data.redis.lettuce.pool.max-wait=1s
```

---

## Recommended `application.properties`

The following template covers the most important production settings. Uncomment and
tune the sections relevant to your deployment.

```properties
# ── Redis ────────────────────────────────────────────────────────────────────
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.ssl.enabled=${REDIS_TLS:false}

# Lettuce pool
spring.data.redis.lettuce.pool.enabled=true
spring.data.redis.lettuce.pool.min-idle=2
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.max-active=16

# ── Channel definitions ───────────────────────────────────────────────────────
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=true

# racer.channels.audit.name=racer:audit
# racer.channels.audit.async=false       # blocking — use for critical audit events

# ── Security (opt-in) ────────────────────────────────────────────────────────
racer.web.security.enabled=true

# ── Web endpoints (opt-in) ───────────────────────────────────────────────────
racer.web.dlq-enabled=true
racer.web.schema-enabled=false
racer.web.router-enabled=false
racer.web.channels-enabled=false
racer.web.retention-enabled=false

# ── Circuit breaker ───────────────────────────────────────────────────────────
racer.circuit-breaker.enabled=true
racer.circuit-breaker.failure-rate-threshold=50
racer.circuit-breaker.sliding-window-size=10
racer.circuit-breaker.wait-duration-in-open-state-seconds=30
racer.circuit-breaker.permitted-calls-in-half-open-state=3

# ── Back-pressure ─────────────────────────────────────────────────────────────
racer.backpressure.enabled=true
racer.backpressure.queue-threshold=0.80
racer.backpressure.check-interval-ms=1000

# ── Deduplication ─────────────────────────────────────────────────────────────
# racer.dedup.enabled=true
# racer.dedup.ttl-seconds=300

# ── Retention ─────────────────────────────────────────────────────────────────
# racer.retention-enabled=true
# racer.retention.stream-max-len=50000
# racer.retention.dlq-max-age-hours=48
# racer.retention.schedule-cron=0 0 * * * *   # hourly

# ── Distributed tracing (W3C traceparent) ────────────────────────────────────
racer.tracing.enabled=true
racer.tracing.propagate-to-mdc=true
racer.tracing.inject-into-envelope=true

# ── Rate limiting ─────────────────────────────────────────────────────────────
# racer.rate-limit.enabled=true
# racer.rate-limit.default-capacity=100
# racer.rate-limit.default-refill-rate=100

# ── Actuator (health + metrics) ───────────────────────────────────────────────
management.endpoints.web.exposure.include=health,prometheus,info
management.endpoint.health.show-details=when-authorized
management.health.redis.enabled=true

# ── Logging ──────────────────────────────────────────────────────────────────
logging.level.com.cheetah.racer=INFO
# Use JSON structured logging for log aggregators (ELK / Loki):
# logging.structured.format.console=ecs
```

---

## Docker / Docker Compose

### Standalone (development)

```bash
docker compose up -d          # Redis standalone
```

### Sentinel (HA — recommended for most prod deployments)

```bash
docker compose -f compose.sentinel.yaml up -d
```

This starts: 1 Redis master + 2 replicas + 3 Sentinel nodes.

### Cluster (high-throughput / large-scale)

```bash
docker compose -f compose.cluster.yaml up -d
```

This starts a 3-node Redis cluster (each with 1 primary + 1 replica = 6 containers).

---

## Kubernetes Deployment

### ConfigMap for channel configuration

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: racer-config
data:
  application.properties: |
    racer.channels.orders.name=racer:orders
    racer.channels.orders.async=true
    racer.web.security.enabled=true
    racer.web.dlq-enabled=true
    racer.circuit-breaker.enabled=true
    racer.tracing.enabled=true
    management.endpoints.web.exposure.include=health,prometheus
```

### Secret for Redis credentials

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: racer-secrets
type: Opaque
stringData:
  REDIS_PASSWORD: "your-redis-password"
  RACER_ADMIN_USER: "racer-admin"
  RACER_ADMIN_PASSWORD: "your-admin-password"
```

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-racer-service
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: app
          image: my-service:latest
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_CONFIG_LOCATION
              value: /config/application.properties
            - name: REDIS_HOST
              value: redis-sentinel  # or redis-cluster service name
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: racer-secrets
                  key: REDIS_PASSWORD
            - name: RACER_ADMIN_USER
              valueFrom:
                secretKeyRef:
                  name: racer-secrets
                  key: RACER_ADMIN_USER
            - name: RACER_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: racer-secrets
                  key: RACER_ADMIN_PASSWORD
          volumeMounts:
            - name: config
              mountPath: /config
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
      volumes:
        - name: config
          configMap:
            name: racer-config
```

### Graceful shutdown

Racer implements `SmartLifecycle` for graceful shutdown. Configure your JVM and
Kubernetes to allow in-flight messages to drain:

```properties
# application.properties
spring.lifecycle.timeout-per-shutdown-phase=30s
server.shutdown=graceful
```

```yaml
# Kubernetes pod spec
spec:
  terminationGracePeriodSeconds: 45   # > spring lifecycle timeout
```

---

## Thread-Pool Sizing

Racer uses a dedicated thread pool per listener (named `racer-<listenerId>-N`).
The pool size is controlled per listener via `@RacerListener`:

```java
@RacerListener(
    channel = "orders",
    concurrencyMode = ConcurrencyMode.CONCURRENT,
    concurrency = 4           // 4 parallel message handlers; default=1
)
public void handleOrder(RacerMessage msg) { ... }
```

**Sizing guidelines:**

| Workload | Recommended `concurrency` |
|----------|--------------------------|
| CPU-bound (transformation, computation) | `Runtime.getRuntime().availableProcessors()` |
| I/O-bound (DB calls, HTTP) | 4–16 (tune based on downstream latency) |
| Strict ordering required | 1 (default) — `ConcurrencyMode.SEQUENTIAL` |

**Back-pressure protection:** Enable `racer.backpressure.enabled=true` to
automatically throttle stream polling when the thread pool queue is > 80% full.

---

## Observability

### Health check (`/actuator/health`)

Racer registers a `RacerHealthIndicator` that reports:

```json
{
  "status": "UP",
  "components": {
    "racer": {
      "status": "UP",
      "details": {
        "redis": "OK",
        "listeners": 3,
        "dlqSize": 0,
        "circuitBreaker": "CLOSED"
      }
    }
  }
}
```

Wire the Kubernetes readiness probe to `/actuator/health/readiness`.

### Prometheus metrics

Add `micrometer-registry-prometheus` to your `pom.xml`:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Expose the scrape endpoint:

```properties
management.endpoints.web.exposure.include=health,prometheus
```

**Key Racer metrics:**

| Metric | Description |
|--------|-------------|
| `racer_messages_published_total` | Messages successfully published |
| `racer_messages_processed_total` | Messages handled by listeners |
| `racer_messages_failed_total` | Messages that caused handler errors |
| `racer_messages_dlq_total` | Messages sent to DLQ |
| `racer_consumer_lag_messages` | Consumer lag gauge per channel |

**Recommended alert rules (Prometheus):**

```yaml
groups:
  - name: racer
    rules:
      - alert: RacerHighDlqRate
        expr: rate(racer_messages_dlq_total[5m]) > 5
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Racer DLQ rate elevated ({{ $value }}/s)"

      - alert: RacerHighConsumerLag
        expr: racer_consumer_lag_messages > 1000
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Racer consumer lag > 1000 on {{ $labels.channel }}"

      - alert: RacerCircuitBreakerOpen
        expr: racer_circuit_breaker_state == 2
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Racer circuit breaker OPEN for {{ $labels.listener }}"
```

### Distributed tracing

Enable W3C `traceparent` propagation for log correlation:

```properties
racer.tracing.enabled=true
racer.tracing.propagate-to-mdc=true   # adds traceparent to MDC for each consumed message
```

To export traces to Jaeger/Zipkin, add the OTel Spring Boot starter (planned for v1.4):

```xml
<!-- Not yet available in v1.3 — planned for v1.4 -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

---

## Common Pitfalls

### 1. Router fires FORWARDED but message is lost
**Symptom:** Messages seem to be forwarded (logged as FORWARDED) but never arrive at the target channel.  
**Cause (pre-v1.3.1):** Fire-and-forget router publish — Redis errors were silently swallowed.  
**Fix (v1.3.1+):** Router publishing is now fully reactive. Redis errors propagate as exceptions and the message is sent to DLQ rather than silently dropped.

### 2. Admin UI accessible without authentication
**Symptom:** `/racer-admin/` is reachable without credentials.  
**Cause:** `racer.web.security.enabled` defaults to `false`.  
**Fix:** Set `racer.web.security.enabled=true` and add Spring Security to the classpath. The security chain now covers both `/api/**` and `/racer-admin/**`.

### 3. Stale messages due to missing dedup
**Symptom:** At-least-once delivery causes duplicate processing after Redis failover.  
**Fix:** Enable dedup with a TTL matching your maximum processing window:
```properties
racer.dedup.enabled=true
racer.dedup.ttl-seconds=300
```
Also annotate listeners: `@RacerListener(dedup = true)`.

### 4. `@RacerPoll` methods stop executing silently
**Symptom:** Polled methods stop running after any error without recovery.  
**Cause:** An uncaught exception kills the poll loop.  
**Fix:** Ensure the polled method does not throw unchecked exceptions, or wrap it in a try/catch. The poll loop uses `onErrorContinue` internally.

### 5. High Redis memory on Streams
**Symptom:** Redis memory grows unboundedly on stream channels.  
**Fix:** Enable retention:
```properties
racer.retention-enabled=true
racer.retention.stream-max-len=50000
racer.retention.schedule-cron=0 0 * * * *
```
Also add `@EnableScheduling` to your Spring Boot application.

### 6. Thread pool starvation under burst load
**Symptom:** `racer-<listenerId>` threads are exhausted; messages queue up.  
**Fix:** Increase `concurrency` on the listener and enable back-pressure:
```java
@RacerListener(channel = "orders", concurrency = 8)
```
```properties
racer.backpressure.enabled=true
racer.backpressure.queue-threshold=0.80
```

### 7. `stale target/ artifacts` cause test failures
**Symptom:** `ClassNotFoundException: ServerHttpSecurity` or "Corrupted channel" in tests.  
**Fix:** Always run `./mvnw clean test` (not just `./mvnw test`) for reliable results.

---

*See `README.md` for feature documentation and `TUTORIALS.md` for integration examples.*
