package com.cheetah.racer.common.config;

import com.cheetah.racer.common.aspect.PublishResultAspect;
import com.cheetah.racer.common.listener.RacerDeadLetterHandler;
import com.cheetah.racer.common.listener.RacerListenerRegistrar;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.poll.RacerPollRegistrar;
import com.cheetah.racer.common.processor.RacerPublisherFieldProcessor;
import com.cheetah.racer.common.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.common.publisher.RacerPriorityPublisher;
import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import com.cheetah.racer.common.publisher.RacerShardedStreamPublisher;
import com.cheetah.racer.common.publisher.RacerStreamPublisher;
import com.cheetah.racer.common.requestreply.RacerResponderRegistrar;
import com.cheetah.racer.common.router.RacerRouterService;
import com.cheetah.racer.common.schema.RacerSchemaRegistry;
import com.cheetah.racer.common.service.DeadLetterQueueService;
import com.cheetah.racer.common.service.DlqReprocessorService;
import com.cheetah.racer.common.service.RacerRetentionService;
import com.cheetah.racer.common.stream.RacerStreamListenerRegistrar;
import com.cheetah.racer.common.tx.RacerTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Optional;

/**
 * Core Racer auto-configuration.
 *
 * <p>Imported by {@link com.cheetah.racer.common.annotation.EnableRacer}.
 * Registers:
 * <ul>
 *   <li>{@link RacerPublisherRegistry} — multi-channel registry driven by
 *       {@code racer.channels.*} properties</li>
 *   <li>{@link PublishResultAspect} — AOP advice for {@code @PublishResult}</li>
 *   <li>{@link RacerPublisherFieldProcessor} — BeanPostProcessor for {@code @RacerPublisher} field injection</li>
 *   <li>{@link RacerMetrics} (conditional) — Micrometer instrumentation, active only when
 *       {@code micrometer-core} is on the classpath</li>
 *   <li>{@link RacerStreamPublisher} — durable stream publishing for {@code @PublishResult(durable=true)}</li>
 *   <li>{@link RacerRouterService} — content-based router scanning {@code @RacerRoute} beans</li>
 *   <li>{@link RacerTransaction} — atomic multi-channel publish via {@code Flux.concat}</li>
 *   <li>{@link RacerPipelinedPublisher} — parallel batch publisher (R-9)</li>
 *   <li>{@link RacerShardedStreamPublisher} (conditional) — shard-aware stream publisher (R-8)</li>
 *   <li>{@link RacerPriorityPublisher} (conditional) — priority sub-channel publisher (R-10)</li>
 *   <li>{@link RacerListenerRegistrar} (conditional) — BeanPostProcessor for {@code @RacerListener}
 *       channel consumers; active when a {@link ReactiveRedisMessageListenerContainer} is present</li>
 * </ul>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(RacerProperties.class)
public class RacerAutoConfiguration {

    @Bean
    public RacerPublisherRegistry racerPublisherRegistry(
            RacerProperties racerProperties,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry) {

        return new RacerPublisherRegistry(
                racerProperties,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics,
                racerSchemaRegistry);
    }

    @Bean
    public PublishResultAspect publishResultAspect(
            RacerPublisherRegistry racerPublisherRegistry,
            RacerStreamPublisher racerStreamPublisher) {

        return new PublishResultAspect(racerPublisherRegistry, racerStreamPublisher);
    }

    @Bean
    public RacerPublisherFieldProcessor racerPublisherFieldProcessor() {
        return new RacerPublisherFieldProcessor();
    }

    // ── Metrics (optional — requires micrometer-core on classpath) ──────────

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    public RacerMetrics racerMetrics(MeterRegistry meterRegistry) {
        return new RacerMetrics(meterRegistry);
    }

    // ── Durable stream publisher ─────────────────────────────────────────────

    @Bean
    public RacerStreamPublisher racerStreamPublisher(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper) {
        return new RacerStreamPublisher(reactiveStringRedisTemplate, objectMapper);
    }

    // ── Content-based router ─────────────────────────────────────────────────

    @Bean
    public RacerRouterService racerRouterService(
            ApplicationContext applicationContext,
            RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper) {
        return new RacerRouterService(applicationContext, racerPublisherRegistry, objectMapper);
    }

    // ── Transaction support ──────────────────────────────────────────────────

    @Bean
    public RacerTransaction racerTransaction(RacerPublisherRegistry racerPublisherRegistry,
                                              Optional<RacerPipelinedPublisher> pipelinedPublisher) {
        return new RacerTransaction(racerPublisherRegistry, pipelinedPublisher.orElse(null));
    }

    // ── R-9: Pipelined batch publisher ───────────────────────────────────────

    @Bean
    public RacerPipelinedPublisher racerPipelinedPublisher(
            RacerProperties racerProperties,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry) {
        return new RacerPipelinedPublisher(
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties.getPipeline().getMaxBatchSize(),
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null));
    }

    // ── R-8: Sharded stream publisher (optional) ─────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.sharding.enabled", havingValue = "true")
    public RacerShardedStreamPublisher racerShardedStreamPublisher(
            RacerProperties racerProperties,
            RacerStreamPublisher racerStreamPublisher) {
        return new RacerShardedStreamPublisher(
                racerStreamPublisher,
                racerProperties.getSharding().getShardCount());
    }

    // ── R-10: Priority publisher (optional) ─────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.priority.enabled", havingValue = "true")
    public RacerPriorityPublisher racerPriorityPublisher(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerSchemaRegistry> racerSchemaRegistry) {
        return new RacerPriorityPublisher(
                reactiveStringRedisTemplate,
                objectMapper,
                racerSchemaRegistry.orElse(null));
    }

    // ── R-7: Schema registry (optional — enabled by racer.schema.enabled=true) ──

    @Bean
    @ConditionalOnProperty(name = "racer.schema.enabled", havingValue = "true")
    public RacerSchemaRegistry racerSchemaRegistry(
            RacerProperties racerProperties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper) {
        return new RacerSchemaRegistry(racerProperties, resourceLoader, objectMapper);
    }

    // ── R-11: Polling registrar (@RacerPoll processor) ───────────────────

    @Bean
    public RacerPollRegistrar racerPollRegistrar(
            RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics) {
        return new RacerPollRegistrar(racerPublisherRegistry, objectMapper, racerMetrics.orElse(null));
    }

    // ── @RacerListener registrar ─────────────────────────────────────────

    /**
     * Registers the {@link RacerListenerRegistrar} that scans for {@code @RacerListener}
     * methods and subscribes them to their configured Redis Pub/Sub channels.
     *
     * <p>Only activated when a {@link ReactiveRedisMessageListenerContainer} bean is
     * present in the context (i.e. in apps that have the listener infrastructure set up,
     * such as racer-client).
     */
    @Bean
    @ConditionalOnBean(ReactiveRedisMessageListenerContainer.class)
    public RacerListenerRegistrar racerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            RacerProperties racerProperties,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerRouterService> racerRouterService,
            Optional<RacerDeadLetterHandler> deadLetterHandler) {
        return new RacerListenerRegistrar(
                listenerContainer,
                objectMapper,
                racerProperties,
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null),
                racerRouterService.orElse(null),
                deadLetterHandler.orElse(null));
    }

    // ── Dead Letter Queue ────────────────────────────────────────────────────

    @Bean
    public DeadLetterQueueService deadLetterQueueService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper) {
        return new DeadLetterQueueService(reactiveStringRedisTemplate, objectMapper);
    }

    // ── DLQ Reprocessor ──────────────────────────────────────────────────────

    @Bean
    public DlqReprocessorService dlqReprocessorService(
            DeadLetterQueueService deadLetterQueueService,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics) {
        return new DlqReprocessorService(
                deadLetterQueueService,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics.orElse(null));
    }

    // ── Retention Service ────────────────────────────────────────────────────

    @Bean
    public RacerRetentionService racerRetentionService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            DeadLetterQueueService deadLetterQueueService,
            ObjectMapper objectMapper,
            RacerProperties racerProperties) {
        return new RacerRetentionService(
                reactiveStringRedisTemplate,
                deadLetterQueueService,
                objectMapper,
                racerProperties.getRetention().getStreamMaxLen(),
                racerProperties.getRetention().getDlqMaxAgeHours());
    }

    // ── @RacerStreamListener registrar ──────────────────────────────────────

    @Bean
    public RacerStreamListenerRegistrar racerStreamListenerRegistrar(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerDeadLetterHandler> deadLetterHandler) {
        return new RacerStreamListenerRegistrar(
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties,
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null),
                deadLetterHandler.orElse(null));
    }

    // ── @RacerResponder registrar ────────────────────────────────────────────

    @Bean
    public RacerResponderRegistrar racerResponderRegistrar(
            Optional<ReactiveRedisMessageListenerContainer> listenerContainer,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics) {
        return new RacerResponderRegistrar(
                listenerContainer.orElse(null),
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties,
                racerMetrics.orElse(null));
    }

    // ── Retention scheduling (opt-in via racer.retention-enabled=true) ──────

    /**
     * Inner configuration class that activates {@code @EnableScheduling} only
     * when {@code racer.retention-enabled=true}.  This avoids globally enabling
     * Spring's scheduling infrastructure for all consumers of the library.
     */
    @Configuration
    @ConditionalOnProperty(name = "racer.retention-enabled", havingValue = "true")
    @EnableScheduling
    static class RacerRetentionSchedulingConfiguration {
        // @EnableScheduling activates @Scheduled on RacerRetentionService
    }
}
