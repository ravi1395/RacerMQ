package com.cheetah.racer.health;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Boot Actuator {@link ReactiveHealthIndicator} for the Racer framework.
 *
 * <p>Reports:
 * <ul>
 *   <li><b>Redis connectivity</b> — a PING command against the configured Redis instance.</li>
 *   <li><b>DLQ depth</b> — current number of messages in {@code racer:dlq}.</li>
 * </ul>
 *
 * <p>Registered automatically by {@link RacerHealthAutoConfiguration} when
 * {@code spring-boot-starter-actuator} (which brings {@code ReactiveHealthIndicator})
 * is on the classpath.
 */
public class RacerHealthIndicator implements ReactiveHealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Nullable
    private final DeadLetterQueueService dlqService;

    @Nullable
    private final RacerConsumerLagMonitor lagMonitor;

    @Nullable
    private final RacerProperties racerProperties;

    public RacerHealthIndicator(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Nullable DeadLetterQueueService dlqService) {
        this(redisTemplate, dlqService, null, null);
    }

    public RacerHealthIndicator(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Nullable DeadLetterQueueService dlqService,
            @Nullable RacerConsumerLagMonitor lagMonitor,
            @Nullable RacerProperties racerProperties) {
        this.redisTemplate = redisTemplate;
        this.dlqService = dlqService;
        this.lagMonitor = lagMonitor;
        this.racerProperties = racerProperties;
    }

    @Override
    public Mono<Health> health() {
        return checkRedis()
                .flatMap(health -> {
                    if (dlqService == null) {
                        return Mono.just(health);
                    }
                    return enrichWithDlqDepth(health);
                })
                .map(this::enrichWithConsumerLag)
                .onErrorResume(ex -> Mono.just(
                        Health.down(ex)
                              .withDetail("component", "racer")
                              .build()));
    }

    private Mono<Health> checkRedis() {
        return Mono.usingWhen(
                Mono.fromSupplier(() -> redisTemplate.getConnectionFactory().getReactiveConnection()),
                conn -> conn.ping().timeout(PING_TIMEOUT),
                conn -> Mono.fromRunnable(conn::close))
                .map(pong -> Health.up()
                        .withDetail("redis.ping", pong)
                        .build())
                .onErrorResume(ex -> Mono.just(
                        Health.down(ex)
                              .withDetail("redis.ping", "FAILED")
                              .build()));
    }

    private Mono<Health> enrichWithDlqDepth(Health redisHealth) {
        return dlqService.size()
                .map(depth -> {
                    Health.Builder builder = Health.status(redisHealth.getStatus());
                    redisHealth.getDetails().forEach(builder::withDetail);
                    builder.withDetail("dlq.depth", depth);
                    return builder.build();
                })
                .onErrorResume(ex -> {
                    Health.Builder builder = Health.status(redisHealth.getStatus());
                    redisHealth.getDetails().forEach(builder::withDetail);
                    builder.withDetail("dlq.depth", "unavailable");
                    return Mono.just(builder.build());
                });
    }

    private Health enrichWithConsumerLag(Health current) {
        if (lagMonitor == null) {
            return current;
        }

        Map<String, AtomicLong> counters = lagMonitor.getLagCounters();
        if (counters.isEmpty()) {
            return current;
        }

        long downThreshold = (racerProperties != null)
                ? racerProperties.getConsumerLag().getLagDownThreshold()
                : 10_000;

        Map<String, Long> lagDetails = new LinkedHashMap<>();
        boolean breached = false;
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            long lag = entry.getValue().get();
            lagDetails.put(entry.getKey(), lag);
            if (downThreshold > 0 && lag > downThreshold) {
                breached = true;
            }
        }

        Status status = breached ? new Status("OUT_OF_SERVICE") : current.getStatus();
        Health.Builder builder = Health.status(status);
        current.getDetails().forEach(builder::withDetail);
        builder.withDetail("consumer-lag", lagDetails);
        if (breached) {
            builder.withDetail("consumer-lag.threshold-breached", true);
        }
        return builder.build();
    }
}
