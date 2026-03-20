package com.cheetah.racer.health;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.util.Optional;

/**
 * Auto-configuration that registers {@link RacerHealthIndicator} when
 * {@code spring-boot-starter-actuator} (which provides {@link ReactiveHealthIndicator})
 * is on the classpath.
 *
 * <p>Imported by the Racer {@code AutoConfiguration.imports} entry alongside
 * {@code RacerAutoConfiguration}.
 */
@Configuration
@ConditionalOnClass(ReactiveHealthIndicator.class)
public class RacerHealthAutoConfiguration {

    @Bean
    public RacerHealthIndicator racerHealthIndicator(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            Optional<DeadLetterQueueService> dlqService,
            Optional<RacerConsumerLagMonitor> lagMonitor,
            Optional<RacerProperties> racerProperties) {
        return new RacerHealthIndicator(
                reactiveStringRedisTemplate,
                dlqService.orElse(null),
                lagMonitor.orElse(null),
                racerProperties.orElse(null));
    }
}
