package com.cheetah.racer.client.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.model.RacerMessage;
import com.cheetah.racer.common.router.RacerRouterService;
import com.cheetah.racer.common.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The main consumer service that subscribes to Redis Pub/Sub channels
 * and dispatches messages to the configured processor (sync or async).
 * Failed messages are routed to the Dead Letter Queue.
 */
@Slf4j
@Service
public class ConsumerSubscriber {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final MessageProcessor syncProcessor;
    private final MessageProcessor asyncProcessor;
    private final DeadLetterQueueService dlqService;
    private final ObjectMapper objectMapper;

    /** Optional — active only when a {@code @RacerRoute}-annotated bean is present. */
    @Nullable
    @Autowired(required = false)
    private RacerRouterService racerRouter;

    /** Optional — active only when {@code micrometer-core} is on the classpath. */
    @Nullable
    @Autowired(required = false)
    private RacerMetrics racerMetrics;

    /** Optional — active only when {@code racer.schema.enabled=true}. */
    @Nullable
    @Autowired(required = false)
    private RacerSchemaRegistry racerSchemaRegistry;

    private final AtomicReference<String> currentMode = new AtomicReference<>("ASYNC");
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    private Disposable messageSubscription;
    private Disposable notificationSubscription;

    @Value("${racer.client.processing-mode:ASYNC}")
    private String initialMode;

    public ConsumerSubscriber(
            ReactiveRedisMessageListenerContainer listenerContainer,
            @Qualifier("syncProcessor") MessageProcessor syncProcessor,
            @Qualifier("asyncProcessor") MessageProcessor asyncProcessor,
            DeadLetterQueueService dlqService,
            ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.syncProcessor = syncProcessor;
        this.asyncProcessor = asyncProcessor;
        this.dlqService = dlqService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        currentMode.set(initialMode.toUpperCase());
        log.info("Starting consumer subscriber in {} mode", currentMode.get());

        subscribeToMessages();
        subscribeToNotifications();
    }

    @PreDestroy
    public void stop() {
        if (messageSubscription != null && !messageSubscription.isDisposed()) {
            messageSubscription.dispose();
        }
        if (notificationSubscription != null && !notificationSubscription.isDisposed()) {
            notificationSubscription.dispose();
        }
        log.info("Consumer subscriber stopped. Processed={}, Failed={}", processedCount.get(), failedCount.get());
    }

    /**
     * Subscribe to the main message channel.
     */
    private void subscribeToMessages() {
        messageSubscription = listenerContainer
                .receive(ChannelTopic.of(RedisChannels.MESSAGE_CHANNEL))
                .flatMap(this::handleMessage)
                .subscribe();

        log.info("Subscribed to channel: {}", RedisChannels.MESSAGE_CHANNEL);
    }

    /**
     * Subscribe to the notification channel.
     */
    private void subscribeToNotifications() {
        notificationSubscription = listenerContainer
                .receive(ChannelTopic.of(RedisChannels.NOTIFICATION_CHANNEL))
                .doOnNext(msg -> log.info("[NOTIFICATION] {}", msg.getMessage()))
                .subscribe();

        log.info("Subscribed to channel: {}", RedisChannels.NOTIFICATION_CHANNEL);
    }

    /**
     * Handle an incoming Redis message: deserialize and route to the active processor.
     */
    private Mono<Void> handleMessage(ReactiveSubscription.Message<String, String> redisMessage) {
        return Mono.defer(() -> {
            try {
                RacerMessage message = objectMapper.readValue(redisMessage.getMessage(), RacerMessage.class);
                log.debug("Received message id={} on channel={}", message.getId(), redisMessage.getChannel());

                // Content-based routing — if a rule matches, the router re-publishes and we stop here
                if (racerRouter != null && racerRouter.route(message)) {
                    log.debug("[racer-router] Message id={} routed — skipping local processing", message.getId());
                    return Mono.empty();
                }

                // R-7: validate payload against registered schema on the consume path
                if (racerSchemaRegistry != null) {
                    try {
                        racerSchemaRegistry.validateForConsume(
                                message.getChannel(), message.getPayload());
                    } catch (com.cheetah.racer.common.schema.SchemaValidationException e) {
                        log.warn("[racer-schema] Consume validation failed id={}: {}",
                                message.getId(), e.getMessage());
                        return dlqService.enqueue(message, e).then();
                    }
                }

                MessageProcessor processor = getActiveProcessor();

                return processor.process(message)
                        .doOnSuccess(v -> {
                            processedCount.incrementAndGet();
                            if (racerMetrics != null) {
                                racerMetrics.recordConsumed(
                                        String.valueOf(redisMessage.getChannel()), currentMode.get());
                            }
                        })
                        .onErrorResume(error -> {
                            failedCount.incrementAndGet();
                            log.error("Processing failed for message id={}: {}", message.getId(), error.getMessage());
                            if (racerMetrics != null) {
                                racerMetrics.recordFailed(
                                        String.valueOf(redisMessage.getChannel()),
                                        error.getClass().getSimpleName());
                            }
                            return dlqService.enqueue(message, error).then();
                        });

            } catch (Exception e) {
                log.error("Failed to deserialize message from channel={}", redisMessage.getChannel(), e);
                return Mono.empty();
            }
        });
    }

    /**
     * Get the currently active processor based on the configured mode.
     */
    private MessageProcessor getActiveProcessor() {
        return "SYNC".equals(currentMode.get()) ? syncProcessor : asyncProcessor;
    }

    /**
     * Switch the processing mode at runtime.
     */
    public String switchMode(String mode) {
        String newMode = mode.toUpperCase();
        if (!"SYNC".equals(newMode) && !"ASYNC".equals(newMode)) {
            throw new IllegalArgumentException("Invalid mode: " + mode + ". Must be SYNC or ASYNC.");
        }
        String oldMode = currentMode.getAndSet(newMode);
        log.info("Processing mode switched from {} to {}", oldMode, newMode);
        return newMode;
    }

    public String getCurrentMode() {
        return currentMode.get();
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }
}
