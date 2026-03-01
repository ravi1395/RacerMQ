package com.cheetah.racer.client.service;

import com.cheetah.racer.common.model.PriorityLevel;
import com.cheetah.racer.common.model.RacerMessage;
import com.cheetah.racer.common.publisher.RacerPriorityPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Priority-aware Pub/Sub consumer (R-10 — Message Priority).
 *
 * <p>Subscribes to all priority sub-channels for each configured base channel alias
 * (e.g. {@code racer:orders:priority:HIGH}, {@code racer:orders:priority:NORMAL},
 * {@code racer:orders:priority:LOW}) and processes messages in priority order.
 *
 * <h3>Strict strategy (default)</h3>
 * The internal drain loop always empties the {@code HIGH} queue completely before
 * processing any {@code NORMAL} messages, and empties {@code NORMAL} before {@code LOW}.
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer.priority.enabled=true
 * racer.priority.levels=HIGH,NORMAL,LOW
 * racer.priority.strategy=strict         # or "weighted"
 * racer.priority.channels=orders,notifications
 * </pre>
 *
 * <p><b>Note:</b> This bean is only created when {@code racer.priority.enabled=true}.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "racer.priority.enabled", havingValue = "true")
public class RacerPriorityConsumerService {

    private static final int QUEUE_CAPACITY       = 10_000;
    private static final Duration DRAIN_TICK       = Duration.ofMillis(10);
    private static final int MAX_DRAIN_PER_TICK    = 256;

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final MessageProcessor syncProcessor;
    private final MessageProcessor asyncProcessor;
    private final DeadLetterQueueService dlqService;
    private final ObjectMapper objectMapper;

    @Value("${racer.priority.levels:HIGH,NORMAL,LOW}")
    private String levelsConfig;

    @Value("${racer.priority.strategy:strict}")
    private String strategy;

    @Value("${racer.priority.channels:}")
    private String channelAliasesConfig;

    @Value("${racer.client.processing-mode:ASYNC}")
    private String processingMode;

    /** Priority-ordered queue: lower weight = higher priority. */
    private final PriorityBlockingQueue<PrioritizedMessage> queue =
            new PriorityBlockingQueue<>(QUEUE_CAPACITY,
                    Comparator.comparingInt(m -> PriorityLevel.of(m.priority()).getWeight()));

    private final List<Disposable> subscriptions = new ArrayList<>();
    private Disposable drainSubscription;

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount    = new AtomicLong(0);

    public RacerPriorityConsumerService(
            ReactiveRedisMessageListenerContainer listenerContainer,
            @Qualifier("syncProcessor")  MessageProcessor syncProcessor,
            @Qualifier("asyncProcessor") MessageProcessor asyncProcessor,
            DeadLetterQueueService dlqService,
            ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.syncProcessor     = syncProcessor;
        this.asyncProcessor    = asyncProcessor;
        this.dlqService        = dlqService;
        this.objectMapper      = objectMapper;
    }

    @PostConstruct
    public void start() {
        List<String> levels   = parseLevels();
        List<String> channels = parseChannels();

        if (channels.isEmpty()) {
            log.info("[PRIORITY-CONSUMER] No priority channels configured — not started. " +
                     "Set racer.priority.channels to enable.");
            return;
        }

        log.info("[PRIORITY-CONSUMER] Starting. strategy={} levels={} channels={}",
                strategy, levels, channels);

        // Subscribe to every priority sub-channel
        for (String baseChannelName : channels) {
            for (String level : levels) {
                String priorityChannel = RacerPriorityPublisher.priorityChannelName(baseChannelName, level);
                Disposable sub = listenerContainer
                        .receive(ChannelTopic.of(priorityChannel))
                        .flatMap(msg -> enqueue(msg.getMessage(), level))
                        .subscribe(
                                n -> {},
                                ex -> log.error("[PRIORITY-CONSUMER] Subscription error on '{}': {}",
                                        priorityChannel, ex.getMessage()));
                subscriptions.add(sub);
                log.info("[PRIORITY-CONSUMER] Subscribed to '{}'", priorityChannel);
            }
        }

        // Start the drain loop on a dedicated elastic thread (blocking queue poll)
        drainSubscription = Flux.interval(DRAIN_TICK)
                .publishOn(Schedulers.boundedElastic())
                .flatMap(tick -> drainBatch(), 1) // concurrency=1 for ordering
                .subscribe(
                        n -> {},
                        ex -> log.error("[PRIORITY-CONSUMER] Drain loop error: {}", ex.getMessage()));
    }

    @PreDestroy
    public void stop() {
        subscriptions.forEach(Disposable::dispose);
        if (drainSubscription != null) drainSubscription.dispose();
        log.info("[PRIORITY-CONSUMER] Stopped. processed={} failed={}", processedCount.get(), failedCount.get());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Mono<Void> enqueue(String rawJson, String level) {
        return Mono.fromRunnable(() -> {
            try {
                queue.add(new PrioritizedMessage(rawJson, level));
            } catch (Exception ex) {
                log.error("[PRIORITY-CONSUMER] Failed to enqueue message: {}", ex.getMessage());
            }
        });
    }

    /**
     * Drains up to {@link #MAX_DRAIN_PER_TICK} messages from the priority queue
     * per tick, processing them sequentially in priority order.
     */
    private Mono<Void> drainBatch() {
        List<PrioritizedMessage> batch = new ArrayList<>();
        PrioritizedMessage item;
        int count = 0;
        while (count < MAX_DRAIN_PER_TICK && (item = queue.poll()) != null) {
            batch.add(item);
            count++;
        }
        if (batch.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(batch)
                .concatMap(this::processMessage)
                .then();
    }

    private Mono<Void> processMessage(PrioritizedMessage item) {
        return Mono.defer(() -> {
            try {
                // Envelope: { channel, sender, priority, payload: <json> }
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = objectMapper.readValue(item.rawJson(), Map.class);
                Object payloadObj = envelope.get("payload");
                String channel    = (String) envelope.getOrDefault("channel", "unknown");
                String payloadJson = payloadObj instanceof String s
                        ? s
                        : objectMapper.writeValueAsString(payloadObj);

                RacerMessage msg = objectMapper.readValue(payloadJson, RacerMessage.class);
                if (msg.getPriority() == null || msg.getPriority().isBlank()) {
                    msg.setPriority(item.priority());
                }

                log.debug("[PRIORITY-CONSUMER] Processing priority={} channel={} id={}",
                        item.priority(), channel, msg.getId());

                MessageProcessor processor = "SYNC".equalsIgnoreCase(processingMode)
                        ? syncProcessor : asyncProcessor;

                return processor.process(msg)
                        .doOnSuccess(v -> processedCount.incrementAndGet())
                        .onErrorResume(error -> {
                            failedCount.incrementAndGet();
                            log.error("[PRIORITY-CONSUMER] Failed id={}: {}", msg.getId(), error.getMessage());
                            return dlqService.enqueue(msg, error).then();
                        });
            } catch (Exception ex) {
                log.error("[PRIORITY-CONSUMER] Deserialization error: {}", ex.getMessage());
                return Mono.empty();
            }
        });
    }

    private List<String> parseLevels() {
        return Arrays.stream(levelsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Resolves configured aliases/channel names to their Redis channel keys.
     * Supports both direct Redis channel names (e.g. {@code racer:orders}) and
     * alias names configured under {@code racer.channels.*}.
     * Falls back to the raw alias value when no mapping is found.
     */
    private List<String> parseChannels() {
        if (channelAliasesConfig == null || channelAliasesConfig.isBlank()) {
            return List.of();
        }
        return Arrays.stream(channelAliasesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public long getProcessedCount() { return processedCount.get(); }
    public long getFailedCount()    { return failedCount.get(); }

    // -------------------------------------------------------------------------
    // Internal value type
    // -------------------------------------------------------------------------

    private record PrioritizedMessage(String rawJson, String priority) {}
}
