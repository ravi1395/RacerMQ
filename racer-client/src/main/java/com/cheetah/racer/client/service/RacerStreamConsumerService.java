package com.cheetah.racer.client.service;

import com.cheetah.racer.common.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer-group reader for durable Redis Streams published via
 * {@code @PublishResult(durable = true)}.
 *
 * <p>Starts a polling loop that reads from each configured durable stream key using
 * the consumer group {@code racer-durable-consumers}. Deserialized messages are
 * dispatched to the active {@link MessageProcessor}; failures are routed to the
 * {@link DeadLetterQueueService}. Each successfully processed entry is ACKed.
 *
 * <h3>Stream key discovery</h3>
 * Stream keys are listed in {@code racer.durable.stream-keys} (comma-separated).
 * Example: {@code racer.durable.stream-keys=racer:orders:stream,racer:audit:stream}
 *
 * <h3>Consumer Scaling (R-8)</h3>
 * <pre>
 * racer.consumer.concurrency=3          # spawn 3 consumers per stream
 * racer.consumer.name-prefix=consumer   # names: consumer-0, consumer-1, consumer-2
 * racer.consumer.poll-batch-size=10     # read up to 10 entries per poll
 * racer.consumer.poll-interval-ms=200   # ms between polls when stream is empty
 * </pre>
 */
@Slf4j
@Service
public class RacerStreamConsumerService {

    private static final String CONSUMER_GROUP = "racer-durable-consumers";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MessageProcessor syncProcessor;
    private final MessageProcessor asyncProcessor;
    private final DeadLetterQueueService dlqService;

    @Value("${racer.durable.stream-keys:}")
    private String streamKeysConfig;

    @Value("${racer.client.processing-mode:ASYNC}")
    private String processingMode;

    // R-8: consumer scaling properties -------------------------------------
    @Value("${racer.consumer.concurrency:1}")
    private int concurrency;

    @Value("${racer.consumer.name-prefix:consumer}")
    private String consumerNamePrefix;

    @Value("${racer.consumer.poll-batch-size:1}")
    private int pollBatchSize;

    @Value("${racer.consumer.poll-interval-ms:200}")
    private long pollIntervalMs;
    // -----------------------------------------------------------------------

    private final List<Disposable> pollingSubscriptions = new ArrayList<>();
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount    = new AtomicLong(0);

    public RacerStreamConsumerService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("syncProcessor")  MessageProcessor syncProcessor,
            @Qualifier("asyncProcessor") MessageProcessor asyncProcessor,
            DeadLetterQueueService dlqService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
        this.syncProcessor = syncProcessor;
        this.asyncProcessor = asyncProcessor;
        this.dlqService    = dlqService;
    }

    @PostConstruct
    public void start() {
        List<String> streamKeys = resolveStreamKeys();
        if (streamKeys.isEmpty()) {
            log.info("[DURABLE-CONSUMER] No durable stream keys configured — consumer not started. " +
                     "Set racer.durable.stream-keys to enable.");
            return;
        }

        Duration pollInterval = Duration.ofMillis(pollIntervalMs);
        log.info("[DURABLE-CONSUMER] Starting. streams={} concurrency={} batchSize={} pollInterval={}ms",
                streamKeys, concurrency, pollBatchSize, pollIntervalMs);

        // Ensure consumer groups exist, then start N consumers per stream.
        // Consumers are chained after group creation to avoid a race where a
        // consumer polls before the group exists (NOGROUP error).
        // Retry up to 5 times with exponential back-off to handle transient
        // Redis unavailability at startup time (BUG-8 fix).
        streamKeys.forEach(key -> {
            ensureConsumerGroup(key)
                    .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                            .doBeforeRetry(rs -> log.warn("[DURABLE-CONSUMER] Retrying group creation on '{}' (attempt {})",
                                    key, rs.totalRetries() + 1)))
                    .doOnSuccess(v -> {
                        log.info("[DURABLE-CONSUMER] Group '{}' ready on '{}'", CONSUMER_GROUP, key);
                        for (int i = 0; i < concurrency; i++) {
                            String consumerName = consumerNamePrefix + "-" + i;
                            Disposable sub = pollStream(key, consumerName, pollInterval)
                                    .subscribe(
                                            n -> {},
                                            ex -> log.error("[DURABLE-CONSUMER] Consumer '{}' on '{}' errored: {}",
                                                    consumerName, key, ex.getMessage()));
                            pollingSubscriptions.add(sub);
                            log.info("[DURABLE-CONSUMER] Consumer '{}' started on '{}'", consumerName, key);
                        }
                    })
                    .doOnError(e -> log.error("[DURABLE-CONSUMER] Failed to init group on '{}': {}", key, e.getMessage()))
                    .subscribe();
        });
    }

    @PreDestroy
    public void stop() {
        pollingSubscriptions.forEach(d -> { if (!d.isDisposed()) d.dispose(); });
        log.info("[DURABLE-CONSUMER] Stopped. Processed={}, Failed={}", processedCount.get(), failedCount.get());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Creates an infinite polling loop for a single (stream, consumer) pair.
     * Reads up to {@link #pollBatchSize} entries per poll, processes each, then
     * delays by {@code pollInterval} before the next iteration.
     */
    private Flux<Void> pollStream(String streamKey, String consumerName, Duration pollInterval) {
        return pollOnce(streamKey, consumerName)
                .repeatWhen(flux -> flux.delayElements(pollInterval));
    }

    private Flux<Void> pollOnce(String streamKey, String consumerName) {
        return redisTemplate.opsForStream()
                .read(Consumer.from(CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(pollBatchSize),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .flatMap(record -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> body = (Map<String, String>) (Map<?, ?>) record.getValue();
                    String data = body.get("data");
                    if (data == null) {
                        log.warn("[DURABLE-CONSUMER] Stream entry {} has no 'data' field — skipping", record.getId());
                        return ackRecord(streamKey, record.getId());
                    }
                    return processEntry(streamKey, record.getId(), data);
                });
    }

    private Mono<Void> processEntry(String streamKey, RecordId recordId, String envelopeJson) {
        return Mono.defer(() -> {
            try {
                // envelope: { id, sender, timestamp, payload: <RacerMessage json> }
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = objectMapper.readValue(envelopeJson, Map.class);
                Object payloadObj = envelope.get("payload");
                String payloadJson = payloadObj instanceof String
                        ? (String) payloadObj
                        : objectMapper.writeValueAsString(payloadObj);

                RacerMessage message = objectMapper.readValue(payloadJson, RacerMessage.class);
                log.debug("[DURABLE-CONSUMER] Processing stream entry recordId={} messageId={}",
                        recordId, message.getId());

                MessageProcessor processor = "SYNC".equalsIgnoreCase(processingMode) ? syncProcessor : asyncProcessor;
                return processor.process(message)
                        .doOnSuccess(v -> {
                            processedCount.incrementAndGet();
                            log.debug("[DURABLE-CONSUMER] Processed recordId={}", recordId);
                        })
                        .then(ackRecord(streamKey, recordId))
                        .onErrorResume(error -> {
                            failedCount.incrementAndGet();
                            log.error("[DURABLE-CONSUMER] Processing failed recordId={}: {}", recordId, error.getMessage());
                            RacerMessage failedMsg = new RacerMessage();
                            failedMsg.setId(recordId.getValue());
                            failedMsg.setPayload(envelopeJson);
                            return dlqService.enqueue(failedMsg, error)
                                    .then(ackRecord(streamKey, recordId)); // ACK to prevent infinite redelivery
                        });

            } catch (JsonProcessingException e) {
                log.error("[DURABLE-CONSUMER] Deserialization failed for recordId={}: {}", recordId, e.getMessage());
                return ackRecord(streamKey, recordId); // skip malformed entries
            }
        });
    }

    private Mono<Void> ackRecord(String streamKey, RecordId recordId) {
        return redisTemplate.opsForStream()
                .acknowledge(streamKey, CONSUMER_GROUP, recordId)
                .doOnSuccess(n -> log.trace("[DURABLE-CONSUMER] ACKed recordId={}", recordId))
                .then();
    }

    private Mono<Void> ensureConsumerGroup(String streamKey) {
        return redisTemplate.opsForStream()
                .createGroup(streamKey, ReadOffset.from("0"), CONSUMER_GROUP)
                .then()
                .onErrorResume(e -> {
                    if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                        return Mono.empty(); // already exists — fine
                    }
                    return Mono.error(e);
                });
    }

    private List<String> resolveStreamKeys() {
        if (streamKeysConfig == null || streamKeysConfig.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(streamKeysConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public long getProcessedCount() { return processedCount.get(); }
    public long getFailedCount()    { return failedCount.get(); }
}
