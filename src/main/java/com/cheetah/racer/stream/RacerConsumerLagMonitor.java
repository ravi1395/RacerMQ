package com.cheetah.racer.stream;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.metrics.RacerMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically scrapes Redis {@code XPENDING} to export consumer-group lag as
 * Micrometer gauges.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>{@link RacerStreamListenerRegistrar} calls {@link #trackStream(String, String)}
 *       for each {@code @RacerStreamListener} it registers, supplying the stream key and
 *       consumer-group name.</li>
 *   <li>This monitor registers one {@code racer.stream.consumer.lag} gauge per
 *       (stream, group) pair, backed by an {@link AtomicLong}.</li>
 *   <li>A {@code Flux.interval} loop runs every {@code racer.consumer-lag.scrape-interval-seconds}
 *       seconds, issuing {@code XPENDING <stream> <group>} and updating the backing
 *       {@link AtomicLong}.</li>
 *   <li>When the lag exceeds {@code racer.consumer-lag.lag-warn-threshold} a WARN log
 *       is emitted.</li>
 * </ol>
 *
 * <p>Activated when {@code racer.consumer-lag.enabled=true} AND Micrometer is on the
 * classpath ({@link RacerMetrics} bean is present).
 *
 * <pre>
 * # application.properties
 * racer.consumer-lag.enabled=true
 * racer.consumer-lag.scrape-interval-seconds=15
 * racer.consumer-lag.lag-warn-threshold=1000
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class RacerConsumerLagMonitor {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RacerMetrics racerMetrics;
    private final RacerProperties racerProperties;

    /** Key = "streamKey|group", value = backing counter for the Micrometer gauge. */
    private final Map<String, AtomicLong> lagCounters = new ConcurrentHashMap<>();
    private volatile Disposable scrapeLoop;

    /**
     * Registers a (stream, group) pair for lag tracking.
     * Safe to call multiple times with the same pair (idempotent).
     * Typically called by {@link RacerStreamListenerRegistrar} at startup.
     *
     * @param streamKey Redis stream key
     * @param group     consumer group name
     */
    public void trackStream(String streamKey, String group) {
        String mapKey = streamKey + "|" + group;
        lagCounters.computeIfAbsent(mapKey, k -> {
            AtomicLong counter = new AtomicLong(0);
            // Register the Micrometer gauge — tag by stream and group
            racerMetrics.registerStreamConsumerLagGauge(streamKey + "/" + group, counter::get);
            log.info("[RACER-LAG] Tracking consumer lag for stream='{}' group='{}'", streamKey, group);
            return counter;
        });
    }

    /** Returns an unmodifiable snapshot of tracked (mapKey → lag) pairs. */
    public Map<String, AtomicLong> getLagCounters() {
        return Collections.unmodifiableMap(lagCounters);
    }

    @PostConstruct
    public void start() {
        RacerProperties.ConsumerLagProperties cfg = racerProperties.getConsumerLag();
        log.info("[RACER-LAG] Consumer lag monitor started — scrapeInterval={}s warnThreshold={}",
                cfg.getScrapeIntervalSeconds(), cfg.getLagWarnThreshold());

        scrapeLoop = Flux.interval(Duration.ofSeconds(cfg.getScrapeIntervalSeconds()))
                .flatMap(tick -> scrapeAll()
                        .onErrorResume(ex -> {
                            log.error("[RACER-LAG] Scrape loop error: {}", ex.getMessage(), ex);
                            return Flux.empty();
                        }))
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (scrapeLoop != null && !scrapeLoop.isDisposed()) {
            scrapeLoop.dispose();
        }
    }

    // ── Scrape ────────────────────────────────────────────────────────────────

    private Flux<Void> scrapeAll() {
        return Flux.fromIterable(lagCounters.entrySet())
                .flatMap(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    if (parts.length < 2) return Mono.empty();
                    String streamKey = parts[0];
                    String group     = parts[1];
                    return scrapeOne(streamKey, group, entry.getValue());
                });
    }

    private Mono<Void> scrapeOne(String streamKey, String group, AtomicLong counter) {
        return redisTemplate.opsForStream()
                .<String, String>pending(streamKey, group)
                .map(PendingMessagesSummary::getTotalPendingMessages)
                .doOnNext(lag -> {
                    counter.set(lag);
                    long threshold = racerProperties.getConsumerLag().getLagWarnThreshold();
                    if (lag > threshold) {
                        log.warn("[RACER-LAG] stream='{}' group='{}' lag={} exceeds warn threshold={}",
                                streamKey, group, lag, threshold);
                    } else {
                        log.debug("[RACER-LAG] stream='{}' group='{}' lag={}", streamKey, group, lag);
                    }
                })
                .onErrorResume(ex -> {
                    log.debug("[RACER-LAG] Could not scrape XPENDING for stream='{}' group='{}': {}",
                            streamKey, group, ex.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
