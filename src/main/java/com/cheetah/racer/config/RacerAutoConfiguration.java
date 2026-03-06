package com.cheetah.racer.config;

import com.cheetah.racer.aspect.PublishResultAspect;
import com.cheetah.racer.backpressure.RacerBackPressureMonitor;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.health.RacerConnectionValidator;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.poll.RacerPollRegistrar;
import com.cheetah.racer.processor.RacerPublisherFieldProcessor;
import com.cheetah.racer.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerShardedStreamPublisher;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import com.cheetah.racer.requestreply.RacerResponderRegistrar;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.security.RacerMessageSigner;
import com.cheetah.racer.security.RacerPayloadEncryptor;
import com.cheetah.racer.security.RacerSenderFilter;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import com.cheetah.racer.service.RacerRetentionService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import com.cheetah.racer.tx.RacerTransaction;
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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core Racer auto-configuration.
 *
 * <p>Imported by {@link com.cheetah.racer.annotation.EnableRacer}.
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
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerPayloadEncryptor> racerPayloadEncryptor,
            Optional<RacerMessageSigner> racerMessageSigner) {

        return new RacerPublisherRegistry(
                racerProperties,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics,
                racerSchemaRegistry,
                racerPayloadEncryptor,
                racerMessageSigner);
    }

    @Bean
    public PublishResultAspect publishResultAspect(
            RacerPublisherRegistry racerPublisherRegistry,
            RacerStreamPublisher racerStreamPublisher,
            RacerProperties racerProperties) {

        return new PublishResultAspect(racerPublisherRegistry, racerStreamPublisher, racerProperties);
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

    // ── Durable stream publisher ───────────────────────────────────────────

    @Bean
    public RacerStreamPublisher racerStreamPublisher(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerPayloadEncryptor> racerPayloadEncryptor,
            Optional<RacerMessageSigner> racerMessageSigner) {
        RacerStreamPublisher publisher = new RacerStreamPublisher(reactiveStringRedisTemplate, objectMapper);
        racerPayloadEncryptor.ifPresent(publisher::setPayloadEncryptor);
        racerMessageSigner.ifPresent(publisher::setMessageSigner);
        return publisher;
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

    // ── Dedicated listener thread pool ───────────────────────────────────────

    /**
     * The Racer-owned thread pool that backs all {@code @RacerListener} handler invocations.
     *
     * <p>Exposed as a named bean so that {@link RacerBackPressureMonitor} and
     * {@link RacerMetrics#registerThreadPoolGauges} can consume it without circular
     * dependencies.  The scheduler wrapper's {@code dispose()} will call
     * {@code executor.shutdown()}, so no explicit destroy-method is needed here.
     *
     * <p>Pool size is controlled by {@code racer.thread-pool.*} properties.
     */
    @Bean
    public ThreadPoolExecutor racerListenerExecutor(
            RacerProperties racerProperties,
            Optional<RacerMetrics> racerMetrics) {
        RacerProperties.ThreadPoolProperties tp = racerProperties.getThreadPool();
        AtomicInteger counter = new AtomicInteger(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                tp.getCoreSize(),
                tp.getMaxSize(),
                tp.getKeepAliveSeconds(), TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(tp.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, tp.getThreadNamePrefix() + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(false);
        racerMetrics.ifPresent(m -> m.registerThreadPoolGauges(executor));
        return executor;
    }

    /**
     * A Reactor {@link Scheduler} backed by the Racer-owned thread pool.
     *
     * <p>Scheduler disposal (on context close) will shut down the backing executor.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler racerListenerScheduler(ThreadPoolExecutor racerListenerExecutor) {
        return Schedulers.fromExecutorService(racerListenerExecutor, "racer-listener");
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
            Scheduler racerListenerScheduler,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerRouterService> racerRouterService,
            Optional<RacerDeadLetterHandler> deadLetterHandler,
            Optional<RacerDedupService> racerDedupService,
            Optional<RacerCircuitBreakerRegistry> racerCircuitBreakerRegistry,
            Optional<RacerPayloadEncryptor> racerPayloadEncryptor,
            Optional<RacerMessageSigner> racerMessageSigner,
            Optional<RacerSenderFilter> racerSenderFilter) {
        RacerListenerRegistrar registrar = new RacerListenerRegistrar(
                listenerContainer,
                objectMapper,
                racerProperties,
                racerListenerScheduler,
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null),
                racerRouterService.orElse(null),
                deadLetterHandler.orElse(null));
        racerDedupService.ifPresent(registrar::setDedupService);
        racerCircuitBreakerRegistry.ifPresent(registrar::setCircuitBreakerRegistry);
        racerPayloadEncryptor.ifPresent(registrar::setPayloadEncryptor);
        racerMessageSigner.ifPresent(registrar::setMessageSigner);
        racerSenderFilter.ifPresent(registrar::setSenderFilter);
        return registrar;
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
            Optional<RacerDeadLetterHandler> deadLetterHandler,
            Optional<RacerDedupService> racerDedupService,
            Optional<RacerCircuitBreakerRegistry> racerCircuitBreakerRegistry,
            Optional<RacerPayloadEncryptor> racerPayloadEncryptor,
            Optional<RacerMessageSigner> racerMessageSigner,
            Optional<RacerSenderFilter> racerSenderFilter) {
        RacerStreamListenerRegistrar registrar = new RacerStreamListenerRegistrar(
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties,
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null),
                deadLetterHandler.orElse(null));
        racerDedupService.ifPresent(registrar::setDedupService);
        racerCircuitBreakerRegistry.ifPresent(registrar::setCircuitBreakerRegistry);
        racerPayloadEncryptor.ifPresent(registrar::setPayloadEncryptor);
        racerMessageSigner.ifPresent(registrar::setMessageSigner);
        racerSenderFilter.ifPresent(registrar::setSenderFilter);
        return registrar;
    }

    // ── Phase 3: Message deduplication ───────────────────────────────────────

    /**
     * Activated via {@code racer.dedup.enabled=true}.
     * Uses Redis {@code SET NX EX} to suppress duplicate message processing.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.dedup.enabled", havingValue = "true")
    public RacerDedupService racerDedupService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            Optional<RacerMetrics> racerMetrics) {
        return new RacerDedupService(reactiveStringRedisTemplate, racerProperties,
                racerMetrics.orElse(null));
    }

    // ── Phase 3: Circuit breaker ──────────────────────────────────────────────

    /**
     * Activated via {@code racer.circuit-breaker.enabled=true}.
     * Provides per-listener count-based sliding-window circuit breakers.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.circuit-breaker.enabled", havingValue = "true")
    public RacerCircuitBreakerRegistry racerCircuitBreakerRegistry(
            RacerProperties racerProperties,
            Optional<RacerMetrics> racerMetrics) {
        return new RacerCircuitBreakerRegistry(racerProperties, racerMetrics.orElse(null));
    }

    // ── Phase 3: Back-pressure monitoring ─────────────────────────────────────

    /**
     * Activated via {@code racer.backpressure.enabled=true}.
     * Monitors the listener thread-pool queue and throttles message consumption
     * when the fill ratio exceeds {@code racer.backpressure.queue-threshold}.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.backpressure.enabled", havingValue = "true")
    public RacerBackPressureMonitor racerBackPressureMonitor(
            ThreadPoolExecutor racerListenerExecutor,
            RacerProperties racerProperties,
            Optional<RacerListenerRegistrar> racerListenerRegistrar,
            Optional<RacerStreamListenerRegistrar> racerStreamListenerRegistrar,
            Optional<RacerMetrics> racerMetrics) {
        return new RacerBackPressureMonitor(
                racerListenerExecutor,
                racerProperties,
                racerListenerRegistrar.orElse(null),
                racerStreamListenerRegistrar.orElse(null),
                racerMetrics.orElse(null));
    }

    // ── Phase 3: Consumer group lag dashboard ────────────────────────────────

    /**
     * Activated via {@code racer.consumer-lag.enabled=true} when Micrometer is present.
     * Periodically publishes {@code XPENDING} lag as Micrometer gauges.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.consumer-lag.enabled", havingValue = "true")
    @ConditionalOnBean(RacerMetrics.class)
    public RacerConsumerLagMonitor racerConsumerLagMonitor(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerMetrics racerMetrics,
            RacerProperties racerProperties,
            Optional<RacerStreamListenerRegistrar> streamRegistrar) {
        RacerConsumerLagMonitor monitor =
                new RacerConsumerLagMonitor(reactiveStringRedisTemplate, racerMetrics, racerProperties);
        // Bootstrap lag tracking from streams already registered by RacerStreamListenerRegistrar
        streamRegistrar.ifPresent(r -> r.getTrackedStreamGroups().forEach(monitor::trackStream));
        return monitor;
    }

    // ── Request–Reply responder registrar ────────────────────────────────────

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

    // ── Phase 4: Security ────────────────────────────────────────────────────

    /**
     * Activated via {@code racer.security.encryption.enabled=true}.
     * Provides transparent AES-256-GCM payload encryption/decryption on all channels.
     * Requires {@code racer.security.encryption.key} (base64, 32 bytes).
     */
    @Bean
    @ConditionalOnProperty(name = "racer.security.encryption.enabled", havingValue = "true")
    public RacerPayloadEncryptor racerPayloadEncryptor(RacerProperties racerProperties) {
        return new RacerPayloadEncryptor(racerProperties);
    }

    /**
     * Activated via {@code racer.security.signing.enabled=true}.
     * Provides transparent HMAC-SHA256 message signing on publish and
     * signature verification on consume.
     * Requires {@code racer.security.signing.secret}.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.security.signing.enabled", havingValue = "true")
    public RacerMessageSigner racerMessageSigner(RacerProperties racerProperties) {
        return new RacerMessageSigner(racerProperties);
    }

    /**
     * Always registered. Acts as a no-op filter when both
     * {@code racer.security.allowed-senders} and {@code racer.security.denied-senders} are empty.
     * Becomes active as soon as either list is populated.
     */
    @Bean
    public RacerSenderFilter racerSenderFilter(RacerProperties racerProperties) {
        return new RacerSenderFilter(racerProperties);
    }

    /**
     * Validates Redis connectivity, permissions, and TLS at {@code ApplicationReadyEvent}.
     * Active when {@code racer.redis-health.validate-permissions-on-startup=true}
     * and/or {@code racer.redis-health.require-tls=true} in the application config.
     * The validator is always registered but only performs checks that are explicitly enabled.
     */
    @Bean
    public RacerConnectionValidator racerConnectionValidator(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties) {
        return new RacerConnectionValidator(reactiveStringRedisTemplate, racerProperties);
    }
}
