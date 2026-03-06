package com.cheetah.racer.health;

import com.cheetah.racer.config.RacerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.time.Duration;

/**
 * Validates the Redis connection and permissions at application startup.
 *
 * <h3>TLS enforcement ({@code racer.redis-health.require-tls=true})</h3>
 * When enabled, inspects the Lettuce connection factory for SSL configuration
 * and throws {@link IllegalStateException} at startup if TLS is not active.
 *
 * <h3>Permission probe ({@code racer.redis-health.validate-permissions-on-startup=true})</h3>
 * Sends PING, PUBLISH, SET, and GET commands against transient test keys to verify
 * that the configured Redis user has the minimum permissions required by Racer.
 * A failed probe causes the application to refuse to start with a descriptive error.
 */
@Slf4j
public class RacerConnectionValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final String PROBE_CHANNEL = "racer:health:probe";
    private static final String PROBE_KEY     = "racer:health:check";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RacerProperties properties;

    public RacerConnectionValidator(ReactiveRedisTemplate<String, String> redisTemplate,
                                    RacerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties    = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        RacerProperties.RedisHealthProperties health = properties.getRedisHealth();
        if (health.isRequireTls()) {
            validateTls();
        }
        if (health.isValidatePermissionsOnStartup()) {
            validatePermissions();
        }
    }

    // ── TLS check ────────────────────────────────────────────────────────────

    private void validateTls() {
        ReactiveRedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lcf) {
            if (!lcf.isUseSsl()) {
                throw new IllegalStateException(
                        "[Racer] racer.redis-health.require-tls=true but the Redis connection " +
                        "is not using TLS/SSL. Enable TLS via spring.data.redis.ssl.enabled=true.");
            }
            log.info("[Racer] Redis TLS requirement satisfied — connection is using SSL.");
        } else {
            log.warn("[Racer] racer.redis-health.require-tls=true is set but cannot verify TLS " +
                    "for non-Lettuce connection factory '{}'. Please ensure TLS is configured externally.",
                    factory == null ? "null" : factory.getClass().getSimpleName());
        }
    }

    // ── Permission probe ─────────────────────────────────────────────────────

    private void validatePermissions() {
        log.info("[Racer] Validating Redis permissions on startup...");
        try {
            // PING — basic connectivity
            String pong = redisTemplate.getConnectionFactory()
                    .getReactiveConnection()
                    .ping()
                    .block();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis PING returned unexpected response: " + pong);
            }

            // PUBLISH — required for Pub/Sub publishers
            redisTemplate.convertAndSend(PROBE_CHANNEL, "racer-probe").block();

            // SET / GET — required for deduplication and health features
            redisTemplate.opsForValue()
                    .set(PROBE_KEY, "1", Duration.ofSeconds(5))
                    .block();
            String got = redisTemplate.opsForValue().get(PROBE_KEY).block();
            if (!"1".equals(got)) {
                throw new IllegalStateException(
                        "Redis SET/GET probe failed — wrote '1' but read '" + got + "'");
            }

            log.info("[Racer] Redis permissions validated successfully (PING, PUBLISH, SET, GET).");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[Racer] Redis permission probe failed. Verify your Redis ACL configuration " +
                    "or disable the check with racer.redis-health.validate-permissions-on-startup=false. " +
                    "Cause: " + e.getMessage(), e);
        }
    }
}
