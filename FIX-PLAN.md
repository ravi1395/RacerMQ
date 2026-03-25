# Racer Library — Fix Plan

**Last updated:** 2026-03-22
**Current version:** 1.2.0
**All tracked issues resolved — 168 tests passing, BUILD SUCCESS**

---

## Final Status

| ID | Severity | Status | Description |
|----|----------|--------|-------------|
| A1 | CRITICAL | ✅ FIXED | Router infinite loop — `evaluate()` and `DefaultRouteContext.publishTo()` now respect `routed` flag |
| A2 | CRITICAL | ✅ FIXED | `RacerMessage.id` null — `MessageEnvelopeBuilder` generates UUID + timestamp |
| A4 | HIGH | ✅ FIXED | Interceptors for `@RacerStreamListener` — chain injected into stream registrar |
| A6 | LOW | ✅ FIXED | Circuit breaker gauge NaN — strong reference + null-safe value |
| A3 | HIGH | ✅ FIXED | Dedup now uses caller-supplied business ID propagated through `MessageEnvelopeBuilder` |
| A5 | MEDIUM | ✅ FIXED | `/api/dlq` 404 resolved — root `GET /api/dlq` handler added to `DlqController` |
| N1 | CRITICAL | ✅ FIXED | `@RacerListener` Pub/Sub dispatch — per-message `onErrorResume` added to SEQUENTIAL/CONCURRENT path |
| N2 | HIGH | ✅ FIXED | `ConcurrencyMode.CONCURRENT` CPU busy-loop — scheduler isolation added matching AUTO mode |
| N3 | MEDIUM | ✅ FIXED | `racer.dlq.size` gauge wired in `RacerAutoConfiguration`; `racer.dedup.duplicates` now increments correctly |

**Remaining: 0 issues**

---

## 1.2.0 Improvements (from NotifyHub RACER-IMPROVEMENTS.md)

| ID | Area | Change |
|----|------|--------|
| I1 | `@PublishResult` | Now `@Repeatable` via `@PublishResults` container; fan-out to multiple channels from one method |
| I2 | `@PublishResult` | Per-annotation `priority` attribute; overrides `@RacerPriority(defaultLevel)` per channel |
| I3 | `@PublishResult` | Self-invocation caveat added to Javadoc (❌/✅ examples) |
| I4 | `@PublishResult` | `PublishResultMethodValidator` (`SmartInitializingSingleton`) throws `RacerConfigurationException` at startup for void-annotated methods |
| I5 | Router DSL | `drop()` now logs at DEBUG (message ID, channel, truncated payload) |
| I6 | Router DSL | New `dropQuietly()` — silent discard, no logging |
| I7 | Router DSL | New `forwardWithPriority(alias, level)` and `priority(alias, level, delegate)` handlers |
| I8 | Router DSL | `DefaultRouteContext.publishToWithPriority()` accepts optional `RacerPriorityPublisher` |
| I9 | `RacerTransaction` | Pipelined path now uses injected `ObjectMapper` instead of `payload.toString()` |
| I10 | Health | `RacerHealthIndicator` exposes `consumer-lag` detail map; flips to `OUT_OF_SERVICE` above `lag-down-threshold` |
| I11 | Properties | New `racer.consumer-lag.lag-down-threshold=10000` property |

