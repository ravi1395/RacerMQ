package com.cheetah.racer.poll;

import com.cheetah.racer.annotation.RacerPoll;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link BeanPostProcessor} that discovers methods annotated with {@link RacerPoll}
 * and registers a reactive polling loop for each one.
 *
 * <p>On each tick the annotated method is called with no arguments. The method is
 * responsible for fetching or computing the data to publish. The return value is
 * then published to the configured Racer channel:
 * <ul>
 *   <li>{@code String} - published as-is.</li>
 *   <li>Any object - serialized to JSON before publishing.</li>
 *   <li>{@code Mono} - subscribed to; the emitted value is published.</li>
 *   <li>{@code void} or {@code null} - skipped (nothing published for that tick).</li>
 * </ul>
 */
@Slf4j
public class RacerPollRegistrar implements BeanPostProcessor, EnvironmentAware {

    private final RacerPublisherRegistry publisherRegistry;
    private final ObjectMapper objectMapper;
    private final RacerMetricsPort racerMetrics;

    private Environment environment;

    private final List<Disposable> subscriptions = new ArrayList<>();
    private final AtomicLong totalPolls  = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    public RacerPollRegistrar(RacerPublisherRegistry publisherRegistry,
                              ObjectMapper objectMapper,
                              @Nullable RacerMetrics racerMetrics) {
        this.publisherRegistry = publisherRegistry;
        this.objectMapper      = objectMapper;
        this.racerMetrics      = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            RacerPoll annotation = method.getAnnotation(RacerPoll.class);
            if (annotation != null) {
                registerPoll(bean, method, annotation, beanName);
            }
        }
        return bean;
    }

    @PreDestroy
    public void stop() {
        subscriptions.forEach(d -> { if (!d.isDisposed()) d.dispose(); });
        log.info("[RACER-POLL] Stopped. totalPolls={} totalErrors={}",
                totalPolls.get(), totalErrors.get());
    }

    private void registerPoll(Object bean, Method method, RacerPoll ann, String beanName) {
        String channel    = resolve(ann.channel());
        String channelRef = resolve(ann.channelRef());
        String sender     = resolve(ann.sender());
        String cronExpr   = resolve(ann.cron());
        boolean async     = ann.async();
        long fixedRate    = ann.fixedRate();
        long initialDelay = ann.initialDelay();

        method.setAccessible(true);

        RacerChannelPublisher publisher = !channelRef.isEmpty()
                ? publisherRegistry.getPublisher(channelRef)
                : publisherRegistry.getPublisher("");

        String resolvedChannel = !channel.isEmpty() ? channel : publisher.getChannelName();

        log.info("[RACER-POLL] Registered {}.{}() -> channel '{}' ({})",
                beanName, method.getName(), resolvedChannel,
                cronExpr.isEmpty() ? "fixedRate=" + fixedRate + "ms" : "cron=" + cronExpr);

        Flux<Long> ticker;
        if (!cronExpr.isEmpty()) {
            AtomicReference<LocalDateTime> lastCronFired = new AtomicReference<>(LocalDateTime.MIN);
            ticker = Flux.interval(Duration.ofSeconds(1))
                    .filter(n -> CronMatcher.matchesOnce(cronExpr, lastCronFired))
                    .publishOn(Schedulers.boundedElastic());
        } else {
            ticker = Flux.interval(Duration.ofMillis(initialDelay), Duration.ofMillis(fixedRate))
                    .publishOn(Schedulers.boundedElastic());
        }

        Disposable sub = ticker
                .flatMap(tick -> invokeThenPublish(bean, method, publisher,
                        resolvedChannel, sender, async), 1)
                .subscribe(
                        v -> {},
                        ex -> log.error("[RACER-POLL] Fatal error in poller {}.{}: {}",
                                beanName, method.getName(), ex.getMessage(), ex));

        subscriptions.add(sub);
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> invokeThenPublish(Object bean, Method method,
                                          RacerChannelPublisher publisher,
                                          String channelName, String sender,
                                          boolean async) {
        return Mono.fromCallable(() -> {
            if (method.getParameterCount() != 0) {
                log.warn("[RACER-POLL] {} must have no parameters - skipping.", method.getName());
                return null;
            }
            return method.invoke(bean);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(result -> {
            if (result == null) return Mono.empty();
            if (result instanceof Mono<?> mono) {
                return ((Mono<Object>) mono)
                        .flatMap(v -> publish(v, publisher, channelName, sender, async));
            }
            return publish(result, publisher, channelName, sender, async);
        })
        .onErrorResume(ex -> {
            totalErrors.incrementAndGet();
            log.warn("[RACER-POLL] Poll error in {}: {}", method.getName(), ex.getMessage());
            return Mono.empty();
        });
    }

    private Mono<Void> publish(Object payload, RacerChannelPublisher publisher,
                                String channelName, String sender, boolean async) {
        totalPolls.incrementAndGet();
        racerMetrics.recordPublished(channelName, async ? "async" : "sync");
        String payloadStr;
        try {
            payloadStr = payload instanceof String s ? s
                    : objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("[RACER-POLL] Serialization failed: {}", e.getMessage());
            return Mono.empty();
        }
        log.debug("[RACER-POLL] Publishing to '{}': {}", channelName,
                payloadStr.length() > 120 ? payloadStr.substring(0, 120) + "..." : payloadStr);
        if (async) {
            return publisher.publishAsync(payloadStr, sender).then();
        } else {
            publisher.publishSync(payloadStr);
            return Mono.empty();
        }
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty()) return value;
        try { return environment.resolvePlaceholders(value); }
        catch (Exception e) { return value; }
    }

    public long getTotalPolls()  { return totalPolls.get(); }
    public long getTotalErrors() { return totalErrors.get(); }

    static class CronMatcher {
        private CronMatcher() {}

        /**
         * Returns {@code true} if the given cron expression matches the current second
         * AND this second has not already been fired (prevents duplicate firings within
         * the same calendar second caused by jitter or multiple interval ticks).
         *
         * @param cron      Spring 6-field cron expression
         * @param lastFired per-poller reference tracking the last second that fired;
         *                  updated atomically on a successful match
         */
        static boolean matchesOnce(String cron, AtomicReference<LocalDateTime> lastFired) {
            try {
                org.springframework.scheduling.support.CronExpression expr =
                        org.springframework.scheduling.support.CronExpression.parse(cron);
                LocalDateTime now  = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
                LocalDateTime next = expr.next(now.minusSeconds(1));
                if (next == null || next.isAfter(now)) {
                    return false;
                }
                // Atomically claim this second — only the first tick per second fires.
                // Subsequent duplicate ticks within the same second will find `lastFired`
                // already set to `now` and return false.
                LocalDateTime prev = lastFired.get();
                if (prev.equals(now)) {
                    return false; // already fired this second
                }
                return lastFired.compareAndSet(prev, now);
            } catch (Exception e) {
                return false;
            }
        }

        /** @deprecated use {@link #matchesOnce(String, AtomicReference)} to avoid duplicate fires */
        @Deprecated
        static boolean matches(String cron) {
            try {
                org.springframework.scheduling.support.CronExpression expr =
                        org.springframework.scheduling.support.CronExpression.parse(cron);
                java.time.LocalDateTime now  = java.time.LocalDateTime.now();
                java.time.LocalDateTime next = expr.next(now.minusSeconds(1));
                return next != null && !next.isAfter(now);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
