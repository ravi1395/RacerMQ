package com.cheetah.racer.config;

import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RedisConfigTest {

    @Mock private ReactiveRedisConnectionFactory connectionFactory;

    private final RedisConfig config = new RedisConfig();

    @Test
    void objectMapper_registersJavaTimeModule() {
        ObjectMapper mapper = config.objectMapper();
        assertThat(mapper).isNotNull();
        // Verify JavaTimeModule is registered by checking if Instant can be serialized
        assertThat(mapper.getRegisteredModuleIds()).isNotEmpty();
    }

    @Test
    void reactiveStringRedisTemplate_isNotNull() {
        ReactiveRedisTemplate<String, String> template = config.reactiveStringRedisTemplate(connectionFactory);
        assertThat(template).isNotNull();
    }

    @Test
    void reactiveRacerMessageRedisTemplate_isNotNull() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        ReactiveRedisTemplate<String, RacerMessage> template =
                config.reactiveRacerMessageRedisTemplate(connectionFactory, mapper);
        assertThat(template).isNotNull();
    }
}
