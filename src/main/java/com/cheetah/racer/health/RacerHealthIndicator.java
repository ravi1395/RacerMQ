package com.cheetah.racer.health;

import com.cheetah.racer.service.DeadLetterQueueService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.time.Duration;

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

    public RacerHealthIndicator(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Nullable DeadLetterQueueService dlqService) {
        this.redisTemplate = redisTemplate;
        this.dlqService = dlqService;
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
                .onErrorResume(ex -> Mono.just(
                        Health.down(ex)
                              .withDetail("component", "racer")
                              .build()));
    }

    private Mono<Health> checkRedis() {
        return redisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .ping()
                .timeout(PING_TIMEOUT)
                .map(pong -> Health.up()
                        .withDetail("redis.ping", pong)
                        .build())
                .onErrorResume(ex -> Mono.just(
                        Health.down(ex)
                              .withDetail("redis.ping", "FAILED")
                              .build()));
    }

    private Mono<Health> enrichWithDlqDepth(Health redisHealth) {
        return dlqService.peekAll()
                .count()
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
}
