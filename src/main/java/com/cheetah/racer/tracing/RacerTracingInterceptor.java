package com.cheetah.racer.tracing;

import com.cheetah.racer.listener.InterceptorContext;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Mono;

/**
 * Phase 4.2 — Distributed Tracing interceptor.
 *
 * <p>Activated when {@code racer.tracing.enabled=true}.  This interceptor runs
 * at {@link Order#value() Order(1)} — before all user-defined interceptors — and
 * does three things for every consumed message:
 *
 * <ol>
 *   <li><strong>Populate traceparent</strong> — if the incoming message carries no
 *       {@code traceparent}, a new root span is generated via
 *       {@link RacerTraceContext#generate()}.</li>
 *   <li><strong>MDC propagation</strong> — when {@code propagateToMdc=true}
 *       (default), the traceparent value is placed in MDC under the key
 *       {@code "traceparent"} so that SLF4J appenders can embed it in log lines
 *       automatically.</li>
 *   <li><strong>Reactor context</strong> — the traceparent is written into the
 *       Reactor {@link reactor.util.context.Context} under the key
 *       {@code "racer.traceparent"} so that nested reactive operators can access
 *       it without explicit parameter threading.</li>
 * </ol>
 *
 * <h3>MDC clean-up</h3>
 * The MDC key is removed after processing completes via
 * {@link Mono#doFinally(java.util.function.Consumer)} to prevent stale values from
 * leaking across thread pool re-uses.
 *
 * <h3>Registration</h3>
 * Registered as a Spring bean by {@link com.cheetah.racer.config.RacerAutoConfiguration}
 * when {@code racer.tracing.enabled=true}.  No manual registration is required.
 */
@Order(1)
@Slf4j
public class RacerTracingInterceptor implements RacerMessageInterceptor {

    /** Reactor context key for the current traceparent value. */
    public static final String REACTOR_CONTEXT_KEY = "racer.traceparent";

    /** MDC key used when {@code propagateToMdc=true}. */
    public static final String MDC_KEY = "traceparent";

    private final boolean propagateToMdc;

    public RacerTracingInterceptor(boolean propagateToMdc) {
        this.propagateToMdc = propagateToMdc;
    }

    @Override
    public Mono<RacerMessage> intercept(RacerMessage message, InterceptorContext context) {
        // Resolve or generate the traceparent for this hop
        String incoming = message.getTraceparent();
        final String traceParent;
        if (incoming != null && RacerTraceContext.isValid(incoming)) {
            // Generate a child span — same trace-id, new parent-id
            traceParent = RacerTraceContext.child(incoming);
        } else {
            // No traceparent present — start a new root trace
            traceParent = RacerTraceContext.generate();
        }

        // Stamp the message with the resolved traceparent
        RacerMessage traced = message.toBuilder()
                .traceparent(traceParent)
                .build();

        log.trace("[racer-tracing] channel={} listenerId={} traceparent={}",
                context.channel(), context.listenerId(), traceParent);

        Mono<RacerMessage> result = Mono.just(traced)
                .contextWrite(ctx -> ctx.put(REACTOR_CONTEXT_KEY, traceParent));

        if (propagateToMdc) {
            return result
                    .doFirst(() -> MDC.put(MDC_KEY, traceParent))
                    .doFinally(signal -> MDC.remove(MDC_KEY));
        }
        return result;
    }
}
