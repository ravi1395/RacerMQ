package com.cheetah.racer.config;

import com.cheetah.racer.aspect.PublishResultAspect;
import com.cheetah.racer.backpressure.RacerBackPressureMonitor;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.poll.RacerPollRegistrar;
import com.cheetah.racer.processor.RacerPublisherFieldProcessor;
import com.cheetah.racer.publisher.RacerConsistentHashRing;
import com.cheetah.racer.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerShardedStreamPublisher;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import com.cheetah.racer.ratelimit.RacerRateLimiter;
import com.cheetah.racer.requestreply.RacerResponderRegistrar;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import com.cheetah.racer.service.RacerRetentionService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import com.cheetah.racer.tracing.RacerTracingInterceptor;
import com.cheetah.racer.tx.RacerTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Validates numeric configuration properties at startup and throws
     * {@link IllegalStateException} on invalid values.
     */
    @Bean
    public Object racerPropertiesValidator(RacerProperties props) {
        // ── Circuit breaker ──────────────────────────────────────────────
        RacerProperties.CircuitBreakerProperties cb = props.getCircuitBreaker();
        if (cb.isEnabled()) {
            check(cb.getFailureRateThreshold() >= 1 && cb.getFailureRateThreshold() <= 100,
                    "racer.circuit-breaker.failure-rate-threshold must be between 1 and 100, got " + cb.getFailureRateThreshold());
            check(cb.getSlidingWindowSize() >= 1,
                    "racer.circuit-breaker.sliding-window-size must be >= 1, got " + cb.getSlidingWindowSize());
            check(cb.getWaitDurationInOpenStateSeconds() >= 1,
                    "racer.circuit-breaker.wait-duration-in-open-state-seconds must be >= 1, got " + cb.getWaitDurationInOpenStateSeconds());
            check(cb.getPermittedCallsInHalfOpenState() >= 1,
                    "racer.circuit-breaker.permitted-calls-in-half-open-state must be >= 1, got " + cb.getPermittedCallsInHalfOpenState());
        }
        // ── Dedup ────────────────────────────────────────────────────────
        RacerProperties.DedupProperties dd = props.getDedup();
        if (dd.isEnabled()) {
            check(dd.getTtlSeconds() >= 1,
                    "racer.dedup.ttl-seconds must be >= 1, got " + dd.getTtlSeconds());
            check(dd.getKeyPrefix() != null && !dd.getKeyPrefix().isBlank(),
                    "racer.dedup.key-prefix must not be blank");
        }
        // ── DLQ ──────────────────────────────────────────────────────────
        check(props.getDlq().getMaxSize() >= 1,
                "racer.dlq.max-size must be >= 1, got " + props.getDlq().getMaxSize());
        // ── Rate Limit (4.3) ─────────────────────────────────────────────
        RacerProperties.RateLimitProperties rl = props.getRateLimit();
        if (rl.isEnabled()) {
            check(rl.getDefaultCapacity() >= 1,
                    "racer.rate-limit.default-capacity must be >= 1, got " + rl.getDefaultCapacity());
            check(rl.getDefaultRefillRate() >= 1,
                    "racer.rate-limit.default-refill-rate must be >= 1, got " + rl.getDefaultRefillRate());
            check(rl.getKeyPrefix() != null && !rl.getKeyPrefix().isBlank(),
                    "racer.rate-limit.key-prefix must not be blank");
        }
        return new Object(); // sentinel bean
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("[racer] Invalid configuration: " + message);
        }
    }

    @Bean
    public RacerPublisherRegistry racerPublisherRegistry(
            RacerProperties racerProperties,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerRateLimiter> racerRateLimiter) {

        return new RacerPublisherRegistry(
                racerProperties,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics,
                racerSchemaRegistry,
                racerRateLimiter);
    }

    @Bean
    public PublishResultAspect publishResultAspect(
            RacerPublisherRegistry racerPublisherRegistry,
            RacerStreamPublisher racerStreamPublisher,
            RacerProperties racerProperties,
            Optional<RacerPriorityPublisher> racerPriorityPublisher) {

        return new PublishResultAspect(racerPublisherRegistry, racerStreamPublisher,
                racerProperties, racerPriorityPublisher.orElse(null));
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
            ObjectMapper objectMapper,
            RacerProperties racerProperties) {
        return new RacerStreamPublisher(reactiveStringRedisTemplate, objectMapper,
                racerProperties.getRetention().getStreamMaxLen());
    }

    // ── Content-based router ─────────────────────────────────────────────────

    @Bean
    public RacerRouterService racerRouterService(
            ApplicationContext applicationContext,
            RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper,
            Optional<RacerPriorityPublisher> racerPriorityPublisher,
            Optional<RacerMetrics> racerMetrics) {
        return new RacerRouterService(applicationContext, racerPublisherRegistry, objectMapper,
                racerPriorityPublisher.orElse(null), racerMetrics.orElse(null));
    }

    // ── Transaction support ──────────────────────────────────────────────────

    @Bean
    public RacerTransaction racerTransaction(RacerPublisherRegistry racerPublisherRegistry,
                                              ObjectMapper objectMapper,
                                              Optional<RacerPipelinedPublisher> pipelinedPublisher) {
        return new RacerTransaction(racerPublisherRegistry, objectMapper, pipelinedPublisher.orElse(null));
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
        RacerProperties.ShardingProperties sharding = racerProperties.getSharding();
        RacerConsistentHashRing hashRing = null;
        if (sharding.isConsistentHashEnabled()) {
            hashRing = new RacerConsistentHashRing(
                    sharding.getShardCount(),
                    sharding.getVirtualNodesPerShard());
        }
        return new RacerShardedStreamPublisher(
                racerStreamPublisher,
                sharding.getShardCount(),
                hashRing,
                sharding.isFailoverEnabled());
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
            @Lazy RacerPublisherRegistry racerPublisherRegistry,
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
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerRouterService> racerRouterService,
            ObjectProvider<RacerDeadLetterHandler> deadLetterHandler,
            ObjectProvider<RacerDedupService> racerDedupService,
            ObjectProvider<RacerCircuitBreakerRegistry> racerCircuitBreakerRegistry,
            ApplicationContext applicationContext) {
        RacerListenerRegistrar registrar = new RacerListenerRegistrar(
                listenerContainer,
                objectMapper,
                racerProperties,
                racerListenerScheduler,
                reactiveStringRedisTemplate,
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null),
                racerRouterService.orElse(null),
                null);
        registrar.setDeadLetterHandlerProvider(deadLetterHandler);
        registrar.setDedupServiceProvider(racerDedupService);
        registrar.setCircuitBreakerRegistryProvider(racerCircuitBreakerRegistry);
        List<RacerMessageInterceptor> interceptors = new ArrayList<>(
                applicationContext.getBeansOfType(RacerMessageInterceptor.class).values());
        AnnotationAwareOrderComparator.sort(interceptors);
        registrar.setInterceptors(interceptors);
        return registrar;
    }

    // ── Dead Letter Queue ────────────────────────────────────────────────────

    @Bean
    public DeadLetterQueueService deadLetterQueueService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            RacerProperties racerProperties) {
        return new DeadLetterQueueService(reactiveStringRedisTemplate, objectMapper,
                racerProperties.getDlq().getMaxSize());
    }

    // ── DLQ metrics gauge ────────────────────────────────────────────────────

    /**
     * Registers the {@code racer.dlq.size} Micrometer gauge so that it appears
     * in {@code /actuator/metrics} even when the DLQ is empty.
     *
     * <p>The gauge value is kept in an {@link java.util.concurrent.atomic.AtomicLong}
     * that is refreshed every 30 seconds via a non-blocking reactive subscription.
     * This avoids calling {@code block()} inside the Micrometer scrape path, which
     * runs on the event loop in reactive applications and would cause
     * Micrometer to record {@code NaN} after catching the resulting exception.
     */
    @Bean
    @ConditionalOnBean(RacerMetrics.class)
    public Object racerDlqMetricsRegistration(
            DeadLetterQueueService deadLetterQueueService,
            RacerMetrics racerMetrics) {
        java.util.concurrent.atomic.AtomicLong dlqSizeCache =
                new java.util.concurrent.atomic.AtomicLong(0L);
        // Fetch immediately on startup, then every 30 s
        reactor.core.publisher.Flux.interval(java.time.Duration.ofSeconds(30))
                .startWith(0L)
                .flatMap(tick -> deadLetterQueueService.size().onErrorReturn(0L))
                .subscribe(dlqSizeCache::set);
        racerMetrics.registerDlqSizeGauge(dlqSizeCache::get);
        return "dlq-metrics-registered";
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
                racerProperties.getRetention().getDlqMaxAgeHours(),
                racerProperties);
    }

    // ── @RacerStreamListener registrar ──────────────────────────────────────

    @Bean
    public RacerStreamListenerRegistrar racerStreamListenerRegistrar(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            ObjectProvider<RacerDeadLetterHandler> deadLetterHandler,
            ObjectProvider<RacerDedupService> racerDedupService,
            ObjectProvider<RacerCircuitBreakerRegistry> racerCircuitBreakerRegistry,
            ObjectProvider<RacerConsumerLagMonitor> consumerLagMonitorProvider,
            ApplicationContext applicationContext) {
        RacerStreamListenerRegistrar registrar = new RacerStreamListenerRegistrar(
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties,
                racerMetrics.orElse(null),
                racerSchemaRegistry.orElse(null),
                null);
        registrar.setDeadLetterHandlerProvider(deadLetterHandler);
        registrar.setDedupServiceProvider(racerDedupService);
        registrar.setCircuitBreakerRegistryProvider(racerCircuitBreakerRegistry);
        RacerConsumerLagMonitor lagMonitor = consumerLagMonitorProvider.getIfAvailable();
        if (lagMonitor != null) {
            registrar.setConsumerLagMonitor(lagMonitor);
        }
        List<RacerMessageInterceptor> interceptors = new ArrayList<>(
                applicationContext.getBeansOfType(RacerMessageInterceptor.class).values());
        AnnotationAwareOrderComparator.sort(interceptors);
        registrar.setInterceptors(interceptors);
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

    // ── @PublishResult void-method startup validation ──────────────────────

    @Bean
    public PublishResultMethodValidator publishResultMethodValidator(ApplicationContext applicationContext) {
        return new PublishResultMethodValidator(applicationContext);
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

    // ── Phase 4.2: Distributed Tracing ────────────────────────────────────────

    /**
     * Activated via {@code racer.tracing.enabled=true}.
     * Propagates W3C {@code traceparent} across all consumed messages and writes
     * the value to MDC for automatic log correlation.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.tracing.enabled", havingValue = "true")
    public RacerTracingInterceptor racerTracingInterceptor(RacerProperties racerProperties) {
        return new RacerTracingInterceptor(racerProperties.getTracing().isPropagateToMdc());
    }

    // ── Phase 4.3: Rate Limiting ──────────────────────────────────────────────

    /**
     * Activated via {@code racer.rate-limit.enabled=true}.
     * Redis-backed token-bucket rate limiter injected into every
     * {@link RacerPublisherRegistry} channel publisher.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.rate-limit.enabled", havingValue = "true")
    public RacerRateLimiter racerRateLimiter(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties) {
        RacerProperties.RateLimitProperties props = racerProperties.getRateLimit();
        return new RacerRateLimiter(
                reactiveStringRedisTemplate,
                props.getDefaultCapacity(),
                props.getDefaultRefillRate(),
                props.getKeyPrefix(),
                props.getChannels());
    }
}
