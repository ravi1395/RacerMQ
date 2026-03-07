package com.cheetah.racer.listener;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class shared by {@link RacerListenerRegistrar} and
 * {@link com.cheetah.racer.stream.RacerStreamListenerRegistrar}.
 *
 * <p>Consolidates the duplicated {@link SmartLifecycle} graceful-shutdown logic,
 * per-listener statistics maps, listener-ID resolution, and Dead Letter Queue
 * forwarding that were previously copy-pasted verbatim across both registrar classes.
 *
 * <h3>Template methods (must be implemented by subclasses)</h3>
 * <ul>
 *   <li>{@link #logPrefix()} — log tag, e.g. {@code "RACER-LISTENER"}</li>
 *   <li>{@link #additionalDisposeCleanup()} — hook for subclass-specific teardown
 *       (e.g. {@link com.cheetah.racer.listener.AdaptiveConcurrencyTuner} shutdown)</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractRacerRegistrar
        implements BeanPostProcessor, EnvironmentAware, SmartLifecycle {

    protected final RacerProperties                    racerProperties;
    @Nullable protected final RacerDeadLetterHandler   deadLetterHandler;

    protected Environment environment;

    /** All active subscriptions (pub/sub or polling loops), disposed on shutdown. */
    protected final List<Disposable> subscriptions = new ArrayList<>();

    /** Per-listener message counters. Key = listenerId. */
    protected final Map<String, AtomicLong> processedCounts = new ConcurrentHashMap<>();
    protected final Map<String, AtomicLong> failedCounts    = new ConcurrentHashMap<>();

    private volatile boolean      running       = false;
    private final AtomicBoolean   stopping      = new AtomicBoolean(false);
    private final AtomicInteger   inFlightCount = new AtomicInteger(0);

    protected AbstractRacerRegistrar(RacerProperties racerProperties,
                                      @Nullable RacerDeadLetterHandler deadLetterHandler) {
        this.racerProperties   = racerProperties;
        this.deadLetterHandler = deadLetterHandler;
    }

    // ── Template methods ──────────────────────────────────────────────────────

    /** Returns the log prefix used in all structured log messages, e.g. {@code "RACER-LISTENER"}. */
    protected abstract String logPrefix();

    /**
     * Called at the end of {@link #disposeAll()} to allow subclasses to perform
     * additional teardown (e.g. shutting down adaptive tuners).
     * Default implementation is a no-op.
     */
    protected void additionalDisposeCleanup() {}

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop(Runnable callback) {
        log.info("[{}] Graceful shutdown — waiting up to {}s for in-flight messages to drain...",
                logPrefix(), racerProperties.getShutdown().getTimeoutSeconds());
        stopping.set(true);
        long timeoutMs = racerProperties.getShutdown().getTimeoutSeconds() * 1000L;
        Mono.fromRunnable(() -> awaitDrain(timeoutMs))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    disposeAll();
                    running = false;
                    logStats();
                    callback.run();
                })
                .subscribe();
    }

    @Override
    public void stop() {
        stopping.set(true);
        awaitDrain(racerProperties.getShutdown().getTimeoutSeconds() * 1000L);
        disposeAll();
        running = false;
        logStats();
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public boolean isAutoStartup() { return true; }

    // ── BeanPostProcessor / EnvironmentAware default ──────────────────────────

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    // ── Lifecycle state (for subclass use) ────────────────────────────────────

    /** Returns {@code true} if a graceful shutdown has been initiated. */
    protected boolean isStopping() { return stopping.get(); }

    /** Increments the in-flight counter (call when a message starts processing). */
    protected void incrementInFlight() { inFlightCount.incrementAndGet(); }

    /** Decrements the in-flight counter (call when a message finishes processing). */
    protected void decrementInFlight() { inFlightCount.decrementAndGet(); }

    // ── Statistics management ─────────────────────────────────────────────────

    /** Registers a listener ID in both stat maps (call once at registration time). */
    protected void registerListenerStats(String listenerId) {
        processedCounts.put(listenerId, new AtomicLong(0));
        failedCounts.put(listenerId,    new AtomicLong(0));
    }

    protected void incrementProcessed(String listenerId) {
        processedCounts.get(listenerId).incrementAndGet();
    }

    protected void incrementFailed(String listenerId) {
        failedCounts.get(listenerId).incrementAndGet();
    }

    /** Returns the total successfully-processed message count for the given listener. */
    public long getProcessedCount(String listenerId) {
        AtomicLong c = processedCounts.get(listenerId);
        return c == null ? 0L : c.get();
    }

    /** Returns the total failed message count for the given listener. */
    public long getFailedCount(String listenerId) {
        AtomicLong c = failedCounts.get(listenerId);
        return c == null ? 0L : c.get();
    }

    // ── Listener ID resolution ────────────────────────────────────────────────

    /**
     * Resolves the listener ID from the annotation attribute.
     * Falls back to {@code "<beanName>.<methodName>"} when {@code rawId} is blank.
     */
    protected static String resolveListenerId(String rawId, String beanName, Method method) {
        return rawId.isEmpty() ? beanName + "." + method.getName() : rawId;
    }

    // ── Dead Letter Queue ─────────────────────────────────────────────────────

    /**
     * Enqueues {@code message} to the DLQ if a {@link RacerDeadLetterHandler} is configured.
     * If none is present, logs a WARN and returns an empty {@code Mono}.
     */
    protected Mono<?> enqueueDeadLetter(RacerMessage message, Throwable error) {
        if (deadLetterHandler != null) {
            return deadLetterHandler.enqueue(message, error)
                    .onErrorResume(dlqEx -> {
                        log.error("[{}] DLQ enqueue failed for id={}: {}",
                                logPrefix(), message.getId(), dlqEx.getMessage());
                        return Mono.empty();
                    });
        }
        log.warn("[{}] No DLQ handler — dropping failed message id={}", logPrefix(), message.getId());
        return Mono.empty();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void awaitDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = inFlightCount.get();
        if (remaining > 0) {
            log.warn("[{}] Shutdown timeout — {} message(s) still in-flight after {}ms",
                    logPrefix(), remaining, timeoutMs);
        }
    }

    private void disposeAll() {
        int disposed = 0;
        for (Disposable sub : subscriptions) {
            if (!sub.isDisposed()) { sub.dispose(); disposed++; }
        }
        additionalDisposeCleanup();
        log.info("[{}] Stopped {} subscription(s).", logPrefix(), disposed);
    }

    private void logStats() {
        processedCounts.forEach((id, cnt) ->
                log.info("[{}] Listener '{}': processed={} failed={}",
                        logPrefix(), id, cnt.get(),
                        failedCounts.getOrDefault(id, new AtomicLong()).get()));
    }
}
