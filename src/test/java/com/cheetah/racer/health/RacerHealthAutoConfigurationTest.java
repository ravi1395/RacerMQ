package com.cheetah.racer.health;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RacerHealthAutoConfigurationTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    DeadLetterQueueService dlqService;
    @Mock
    RacerConsumerLagMonitor lagMonitor;
    @Mock
    RacerProperties racerProperties;

    private final RacerHealthAutoConfiguration config = new RacerHealthAutoConfiguration();

    @Test
    void racerHealthIndicator_withAllOptionalsDependencies_returnsNonNull() {
        RacerHealthIndicator indicator = config.racerHealthIndicator(
                redisTemplate,
                Optional.of(dlqService),
                Optional.of(lagMonitor),
                Optional.of(racerProperties));

        assertThat(indicator).isNotNull();
    }

    @Test
    void racerHealthIndicator_withAllOptionalsEmpty_returnsNonNull() {
        RacerHealthIndicator indicator = config.racerHealthIndicator(
                redisTemplate,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertThat(indicator).isNotNull();
    }

    @Test
    void racerHealthIndicator_withSomeDependencies_returnsNonNull() {
        RacerHealthIndicator indicator = config.racerHealthIndicator(
                redisTemplate,
                Optional.of(dlqService),
                Optional.empty(),
                Optional.empty());

        assertThat(indicator).isNotNull();
    }
}
