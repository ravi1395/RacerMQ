# RACER — Comprehensive Bug Plan

> Generated from a full scan of all 97 main source files.
> Version: **1.3.0** | Tests passing: **224** | Build: **SUCCESS**

---

## Summary

| Severity | Count | Status |
|----------|-------|--------|
| HIGH     | 6     | To fix |
| MEDIUM   | 10    | To fix |
| LOW      | 4     | To fix |
| **Total**| **20**|        |

---

## HIGH Severity (6 bugs)

### H-1 · Redis connection leak in health check (+1 per `/actuator/health` call)

**File:** `RacerHealthIndicator.java` — `checkRedis()` (line ~83)

```java
return redisTemplate.getConnectionFactory()
        .getReactiveConnection()   // ← connection opened — NEVER closed
        .ping()
```

**Impact:** Each health-check poll leaks one Redis connection. Under default 10 s Actuator scrape intervals, this exhausts the Lettuce connection pool within hours.

**Fix:** Use `redisTemplate.execute(conn -> conn.ping())` which manages the connection lifecycle automatically, or wrap in `Mono.usingWhen` to close the connection in the cleanup phase.

---

### H-2 · Consumer-lag scrape loop dies permanently on fatal error

**File:** `RacerConsumerLagMonitor.java` — `start()` (line ~95)

```java
scrapeLoop = Flux.interval(...)
        .flatMap(tick -> scrapeAll())
        .subscribe(
                v -> {},
                ex -> log.error(...));  // ← terminal — loop is dead forever
```

**Impact:** A single fatal error (e.g. Redis reconnect timeout, OOM) kills the lag monitor permanently with no recovery. Consumer-lag gauges freeze at their last-scraped values.

**Fix:** Add `.onErrorContinue((ex, obj) -> log.warn(...))` before `.subscribe()`, or use `.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5)))`.

---

### H-3 · Poll loop dies permanently on fatal error

**File:** `RacerPollRegistrar.java` — `registerPoll()` (line ~130)

```java
Disposable sub = ticker
        .flatMap(tick -> invokeThenPublish(...), 1)
        .subscribe(
                v -> {},
                ex -> log.error(...));  // ← terminal — poller is dead forever
```

**Impact:** Identical to H-2 but for `@RacerPoll` methods. A single un-handled error kills the polling loop with no way to restart it short of restarting the application.

**Fix:** Same pattern — add `.onErrorContinue(...)` or `.retryWhen(...)` before `.subscribe()`.

---

### H-4 · `parseDuration("30ms")` silently returns fallback — "ms" branch is dead code

**File:** `RacerClientFactoryBean.java` — `parseDuration()` (line ~284)

```java
if (value.endsWith("s"))  return Duration.ofSeconds(...);  // ← "30ms" matches here!
if (value.endsWith("m"))  return Duration.ofMinutes(...);
if (value.endsWith("ms")) return Duration.ofMillis(...);   // ← dead code — never reached
```

**Impact:** Any `@RacerClient(timeout = "200ms")` silently falls back to the default timeout (5 s) because `"200ms".endsWith("s")` → `true` → tries to parse `"200m"` as a long → `NumberFormatException` → catches → returns `fallback`. The millisecond branch is unreachable.

**Fix:** Reorder: check `endsWith("ms")` **before** `endsWith("s")`.

---

### H-5 · `trimStreams()` is fire-and-forget — errors silently swallowed

**File:** `RacerRetentionService.java` — `trimStreams()` (line ~108)

```java
public void trimStreams() {
    for (String streamKey : streamKeys) {
        redisTemplate.opsForStream()
                .trim(streamKey, streamMaxLen, true)
                .subscribe(                              // ← fire-and-forget
                        trimmed -> { ... },
                        ex -> log.warn(...));            // ← error only logged
    }
}
```

**Also:** `RetentionController.trim()` (line ~51) calls `trimStreams()` synchronously but the method returns `void` while the actual work runs asynchronously. The REST response is sent before trims complete, and any trim failure is invisible to the caller.

**Impact:** Stream bloat goes undetected. REST callers believe the trim succeeded when it may not have even started.

**Fix:** Change `trimStreams()` to return `Flux<Long>` or `Mono<Void>`, chain all trim operations into a reactive pipeline, and return the result to `RetentionController.trim()`.

---

### H-6 · `NoOpRacerMetrics.startRequestReplyTimer()` returns null

**File:** `NoOpRacerMetrics.java` (line ~25)

```java
@Override public Timer.Sample startRequestReplyTimer() { return null; }
```

**Impact:** Any code path that calls `startRequestReplyTimer()` and then invokes a method on the returned `Timer.Sample` without null-checking will throw `NullPointerException`. This affects applications running without Micrometer on the classpath.

**Fix:** Return a real `Timer.Sample` (e.g. `Timer.start()` with a no-op `Clock`) or a static sentinel object, so callers never receive `null`.

---

## MEDIUM Severity (10 bugs)

### M-1 · Health indicator uses `peekAll().count()` instead of `size()`

**File:** `RacerHealthIndicator.java` — `enrichWithDlqDepth()` (line ~96)

```java
return dlqService.peekAll().count().map(depth -> { ... });
```

**Impact:** Materializes the **entire DLQ** just to count entries — O(N) over the wire. `DeadLetterQueueService.size()` uses Redis `LLEN` which is O(1).

**Fix:** Replace with `dlqService.size()`.

---

### M-2 · `AbstractRacerRegistrar.subscriptions` is a plain `ArrayList` — not thread-safe

**File:** `AbstractRacerRegistrar.java` (line ~57)

```java
protected final List<Disposable> subscriptions = new ArrayList<>();
```

**Impact:** `postProcessAfterInitialization` can be called from multiple threads during context initialization. Concurrent `.add()` calls on `ArrayList` can corrupt internal state, causing silent data loss or `ArrayIndexOutOfBoundsException`.

**Fix:** Use `CopyOnWriteArrayList` or `Collections.synchronizedList(new ArrayList<>())`.

---

### M-3 · Race condition in lazy `getDeadLetterHandler()` initialization

**File:** `AbstractRacerRegistrar.java` — `getDeadLetterHandler()` (line ~90)

```java
if (deadLetterHandler == null && deadLetterHandlerProvider != null) {
    deadLetterHandler = deadLetterHandlerProvider.getIfAvailable();
}
```

**Impact:** Two threads can both see `null` and both call `getIfAvailable()`. Outcome is benign (double resolution produces the same singleton), but the pattern is fragile and could mask issues if the provider ever has side effects.

**Fix:** Either accept the benign race (add comment) or use a `synchronized` block / `AtomicReference.compareAndSet`.

---

### M-4 · Fire-and-forget `.subscribe()` in router publish operations

**File:** `RacerRouterService.java` — `applyAction()` (line ~244) and `DefaultRouteContext.publishTo()` (line ~349)

```java
publisher.publishRoutedAsync(message.getPayload(), sender)
        .subscribe(
                count -> log.debug(...),
                ex -> log.error(...));  // ← error only logged, not propagated
```

**Impact:** Routing failures (e.g. Redis down) are silently swallowed. The caller receives `FORWARDED` even though the message was never actually delivered.

**Fix:** Propagate the `Mono` to the caller so errors can be handled, or at minimum add structured error metrics.

---

### M-5 · Synchronous Jackson serialization in reactive chains

**Files:**
- `DeadLetterQueueService.java` — `enqueue()` (line ~63): `objectMapper.writeValueAsString(dlm)` before `Mono`
- `DeadLetterQueueService.java` — `deserializeDlm()` (line ~105): synchronous `readValue()` wrapped in `Mono.just()`
- `DlqReprocessorService.java` — `republishMessage()` (line ~93): `objectMapper.writeValueAsString(message)` before `Mono`

**Impact:** If called from a Netty event-loop thread, blocking Jackson serialization stalls the event loop, degrading all concurrent reactive operations.

**Fix:** Wrap in `Mono.fromCallable(() -> objectMapper.writeValueAsString(...)).subscribeOn(Schedulers.boundedElastic())`.

---

### M-6 · `retentionService.runRetention()` coordination gap

**File:** `RacerRetentionService.java` — `runRetention()` (line ~84)

```java
trimStreams();                   // fire-and-forget void (H-5)
pruneDlq().subscribe();         // another fire-and-forget
```

**Impact:** The two operations run concurrently with no ordering or aggregate error handling on the scheduler thread. Failures are invisible.

**Fix:** After H-5 is fixed (make `trimStreams()` reactive), chain: `trimStreams().then(pruneDlq()).subscribe(...)`.

---

### M-7 · Unsafe cast in `SchemaController.validate()`

**File:** `SchemaController.java` — `validate()` (line ~100)

```java
String channel = (String) body.get("channel");
```

**Impact:** If a caller sends `"channel": 123` (integer), this throws `ClassCastException` → 500 response, leaking an internal stack trace.

**Fix:** Use `body.get("channel") instanceof String s ? s : null` or `String.valueOf(body.get("channel"))`.

---

### M-8 · `AbstractRacerRegistrar.stop(Runnable)` — fire-and-forget lifecycle callback

**File:** `AbstractRacerRegistrar.java` — `stop(Runnable callback)` (line ~118)

```java
Mono.fromRunnable(() -> awaitDrain(timeoutMs))
        .subscribeOn(Schedulers.boundedElastic())
        .doFinally(signal -> { disposeAll(); running = false; logStats(); callback.run(); })
        .subscribe();
```

**Impact:** If `awaitDrain` throws an unexpected exception, `doFinally` still runs (good), but the error vanishes into thin air. The `subscribe()` has no error handler.

**Fix:** Add `.subscribe(v -> {}, ex -> log.error(...))` to capture unexpected shutdown failures.

---

### M-9 · `RacerPollRegistrar.subscriptions` is also a plain `ArrayList`

**File:** `RacerPollRegistrar.java` (line ~57)

```java
private final List<Disposable> subscriptions = new ArrayList<>();
```

**Impact:** Same as M-2 — concurrent `postProcessAfterInitialization` calls can corrupt the list.

**Fix:** Use `CopyOnWriteArrayList`.

---

### M-10 · Empty-string publisher lookup when both `channel` and `channelRef` are empty

**File:** `RacerPollRegistrar.java` — `registerPoll()` (line ~104)

```java
RacerChannelPublisher publisher = !channelRef.isEmpty()
        ? publisherRegistry.getPublisher(channelRef)
        : publisherRegistry.getPublisher("");         // ← empty string
```

**Impact:** When `@RacerPoll(channel = "", channelRef = "")`, the publisher registry is queried with an empty string, which either throws or returns an unpredictable default publisher.

**Fix:** Validate at registration time that at least one of `channel`/`channelRef` is non-empty, and fail fast with a clear error message.

---

## LOW Severity (4 bugs)

### L-1 · `isBusyGroup()` recursive cause-chain traversal — potential infinite loop

**File:** `RacerStreamUtils.java` — `isBusyGroup()` (line ~55)

```java
private static boolean isBusyGroup(Throwable ex) {
    if (ex == null) return false;
    if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) return true;
    return isBusyGroup(ex.getCause());   // ← recursive with no cycle guard
}
```

**Impact:** Circular exception cause chains (rare but possible with custom exception classes) would cause `StackOverflowError`. Even without cycles, deeply nested exception chains could overflow.

**Fix:** Convert to iterative loop with a depth limit (e.g. max 20 levels).

---

### L-2 · `DeadLetterMessage.from()` — `error.getMessage()` can be null

**File:** `DeadLetterMessage.java` — `from()` (line ~31)

```java
.errorMessage(error.getMessage())   // ← null for NPE, StackOverflowError, etc.
```

**Impact:** The `errorMessage` field in the DLQ entry is `null`, which may confuse DLQ reprocessing or admin UI rendering.

**Fix:** Use `error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()`.

---

### L-3 · `RacerCircuitBreaker.recordOutcome()` — non-atomic window + counter update

**File:** `RacerCircuitBreaker.java` — `recordOutcome()` (line ~132)

```java
window.addLast(success);
if (!success) failureCount.incrementAndGet();
while (window.size() > slidingWindowSize) {
    Boolean removed = window.pollFirst();
    if (Boolean.FALSE.equals(removed)) failureCount.decrementAndGet();
}
```

**Impact:** Under high concurrency, `window` (ConcurrentLinkedDeque) and `failureCount` (AtomicInteger) are updated in separate un-synchronized steps. The failure rate calculation can briefly drift from reality, potentially causing a false trip or missed trip.

**Fix:** Synchronize the compound operation, or accept the race and add a comment documenting it. In practice, the drift is bounded and self-correcting.

---

### L-4 · `RacerRouterService.DefaultRouteContext` — `publishToWithPriority()` fire-and-forget

**File:** `RacerRouterService.java` — `DefaultRouteContext.publishToWithPriority()` (line ~365)

Same pattern as M-4 but for the less common priority-publishing path.

**Fix:** Same as M-4.

---

## Execution Plan

### Phase 1 — HIGH fixes (H-1 through H-6)
Priority: immediate. These are crash/leak/data-loss bugs.

1. **H-1**: Rewrite `checkRedis()` to use `redisTemplate.execute()`
2. **H-2**: Add `.onErrorContinue()` to lag monitor interval
3. **H-3**: Add `.onErrorContinue()` to poll registrar interval
4. **H-4**: Reorder `parseDuration()` — check "ms" before "s"
5. **H-5 + M-6**: Rewrite `trimStreams()` to return `Mono<Void>`, chain in controller and `runRetention()`
6. **H-6**: Return a non-null sentinel from `NoOpRacerMetrics.startRequestReplyTimer()`

### Phase 2 — MEDIUM fixes (M-1 through M-10)
Priority: next sprint. These are correctness and resilience issues.

7. **M-1**: Replace `peekAll().count()` with `dlqService.size()`
8. **M-2 + M-9**: Replace `ArrayList` with `CopyOnWriteArrayList` in both registrars
9. **M-3**: Add comment (benign race) or add `synchronized`
10. **M-4 + L-4**: Add error metrics to router fire-and-forget subscribes
11. **M-5**: Wrap synchronous Jackson calls in `Mono.fromCallable().subscribeOn(boundedElastic)`
12. **M-7**: Safe type-check in `SchemaController.validate()`
13. **M-8**: Add error handler to `stop(Runnable)` subscribe
14. **M-10**: Validate at registration time that channel/channelRef is non-empty

### Phase 3 — LOW fixes (L-1 through L-3)
Priority: backlog. These are edge-case / robustness issues.

15. **L-1**: Convert `isBusyGroup()` to iterative loop with depth limit
16. **L-2**: Null-safe error message in `DeadLetterMessage.from()`
17. **L-3**: Document the benign race in circuit breaker (or synchronize)

### Verification
After each phase, run `./mvnw test` to confirm all 224+ tests pass.
