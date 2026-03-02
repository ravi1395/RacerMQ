package com.cheetah.racer.common.config;

import com.cheetah.racer.common.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Shared Redis configuration providing reactive templates with JSON serialization.
 */
@Configuration
public class RedisConfig {

    /**
     * Fall-back {@link ObjectMapper} used only when no other ObjectMapper bean is
     * present in the application context (e.g. when Spring Boot's
     * {@code JacksonAutoConfiguration} is not active). In a normal Spring Boot
     * application the auto-configured mapper takes precedence.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisTemplate<>(connectionFactory,
                RedisSerializationContext.string());
    }

    @Bean
    public ReactiveRedisTemplate<String, RacerMessage> reactiveRacerMessageRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {

        Jackson2JsonRedisSerializer<RacerMessage> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, RacerMessage.class);

        RedisSerializationContext<String, RacerMessage> context =
                RedisSerializationContext.<String, RacerMessage>newSerializationContext(new StringRedisSerializer())
                        .value(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
