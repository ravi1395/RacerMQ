package com.cheetah.racer.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight, built-in circuit breaker for a single Racer listener.
 *
 * <h3>State machine</h3>
 * <pre>
 * CLOSED ─── failure rate &ge; threshold ──► OPEN
 *   ▲                                         │
 *   │                              wait duration elapsed
 *   │                                         │
 *   └─── all probe calls succeed ──── HALF_OPEN
 * </pre>
 *
 * <ul>
 *   <li><b>CLOSED</b> — normal operation; all calls permitted.</li>
 *   <li><b>OPEN</b> — all calls refused immediately; protects the downstream service.</li>
 *   <li><b>HALF_OPEN</b> — a limited number of probe calls are allowed to test recovery.</li>
 * </ul>
 *
 * <p>The failure rate is evaluated over a count-based sliding window.
 * Only after {@code slidingWindowSize} calls have been recorded does the rate trigger OPEN.
 */
@Slf4j
public class RacerCircuitBreaker {

    /** Visible for testing. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int slidingWindowSize;
    private final float failureRateThreshold; // 1–100 %
    private final long waitDurationMs;
    private final int permittedCallsInHalfOpen;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    /** Sliding window: {@code true} = success, {@code false} = failure. */
    private final ConcurrentLinkedDeque<Boolean> window = new ConcurrentLinkedDeque<>();
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    /** Probe-call counter used in HALF_OPEN state. */
    private final AtomicInteger halfOpenProbes = new AtomicInteger(0);

    /** Total number of state transitions (CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED, etc.). */
    private final AtomicLong transitionCount = new AtomicLong(0);
    /** Total number of calls rejected while the circuit was OPEN. */
    private final AtomicLong rejectedCount = new AtomicLong(0);

    public RacerCircuitBreaker(String name,
                               int slidingWindowSize,
                               float failureRateThreshold,
                               long waitDurationMs,
                               int permittedCallsInHalfOpen) {
        this.name = name;
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationMs = waitDurationMs;
        this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
    }

    /**
     * Returns {@code true} if a call is permitted in the current state.
     * Transitions OPEN → HALF_OPEN automatically once the wait duration has elapsed.
     */
    public boolean isCallPermitted() {
        State s = state.get();
        return switch (s) {
            case CLOSED -> true;
            case OPEN -> {
                long elapsed = System.currentTimeMillis() - openedAt.get();
                if (elapsed >= waitDurationMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenProbes.set(0);
                        transitionCount.incrementAndGet();
                        log.info("[CIRCUIT-BREAKER] '{}' → HALF_OPEN (wait elapsed)", name);
                    }
                    yield state.get() != State.OPEN; // allow only if CAS succeeded or another thread beat us
                }
                rejectedCount.incrementAndGet();
                yield false;
            }
            case HALF_OPEN -> halfOpenProbes.incrementAndGet() <= permittedCallsInHalfOpen;
        };
    }

    /**
     * Records a successful call outcome.
     * In HALF_OPEN state, closes the circuit if all permitted probes have returned successfully.
     */
    public void onSuccess() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            // All probes have gone through and this one succeeded — close the circuit
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                window.clear();
                failureCount.set(0);
                transitionCount.incrementAndGet();
                log.info("[CIRCUIT-BREAKER] '{}' → CLOSED (probes successful)", name);
            }
        } else if (s == State.CLOSED) {
            recordOutcome(true);
        }
    }

    /**
     * Records a failed call outcome.
     * In HALF_OPEN state, re-opens the circuit immediately.
     * In CLOSED state, checks whether the failure rate threshold has been breached.
     */
    public void onFailure() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                transitionCount.incrementAndGet();
                log.warn("[CIRCUIT-BREAKER] '{}' → OPEN (probe failed)", name);
            }
        } else if (s == State.CLOSED) {
            recordOutcome(false);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void recordOutcome(boolean success) {
        window.addLast(success);
        if (!success) {
            failureCount.incrementAndGet();
        }

        // Trim oldest entry when window exceeds capacity
        while (window.size() > slidingWindowSize) {
            Boolean removed = window.pollFirst();
            if (Boolean.FALSE.equals(removed)) {
                failureCount.decrementAndGet();
            }
        }

        // Only evaluate threshold after the window is fully populated
        if (window.size() >= slidingWindowSize) {
            float rate = (float) failureCount.get() / window.size() * 100.0f;
            if (rate >= failureRateThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt.set(System.currentTimeMillis());
                    transitionCount.incrementAndGet();
                    log.warn("[CIRCUIT-BREAKER] '{}' → OPEN (failure rate {:.1f}% >= threshold {}%)",
                            name, rate, failureRateThreshold);
                }
            }
        }
    }

    /** Returns the current state — useful for metrics and health checks. */
    public State getState() {
        return state.get();
    }

    /** Returns the listener name this breaker is associated with. */
    public String getName() {
        return name;
    }

    /** Returns the total number of state transitions since creation. */
    public long getTransitionCount() {
        return transitionCount.get();
    }

    /** Returns the total number of rejected calls (while OPEN) since creation. */
    public long getRejectedCount() {
        return rejectedCount.get();
    }
}
