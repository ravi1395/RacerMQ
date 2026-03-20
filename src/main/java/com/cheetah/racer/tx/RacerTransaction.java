package com.cheetah.racer.tx;

import com.cheetah.racer.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Best-effort batch publisher that sends to multiple channels in a single reactive chain.
 *
 * <p>Channels are published sequentially via {@link Flux#concat}.
 * If any publish fails, the error propagates to the subscriber and subsequent publishes
 * in the batch are skipped (fail-fast).  This provides "all-or-nothing" semantics at the
 * application level, though not a true Redis {@code MULTI/EXEC} atomic transaction.
 *
 * <h3>Usage</h3>
 * <pre>
 * &#64;Autowired
 * private RacerTransaction racerTransaction;
 *
 * racerTransaction.execute(tx -&gt; {
 *     tx.publish("orders",        orderPayload);
 *     tx.publish("notifications", notifyPayload);
 *     tx.publish("audit",         auditPayload);
 * }).subscribe(results -&gt; ...);
 * </pre>
 *
 * <h3>Return value</h3>
 * {@code Mono<List<Long>>} — one {@code Long} per channel, representing the number of
 * Pub/Sub subscribers that received the message.
 */
@Slf4j
public class RacerTransaction {

    private final RacerPublisherRegistry registry;
    private final ObjectMapper objectMapper;
    @Nullable
    private final RacerPipelinedPublisher pipelinedPublisher;

    /** Construct without pipeline support (backward-compatible). */
    public RacerTransaction(RacerPublisherRegistry registry) {
        this(registry, new ObjectMapper(), null);
    }

    /** Construct with optional pipeline support (R-9). */
    public RacerTransaction(RacerPublisherRegistry registry,
                             @Nullable RacerPipelinedPublisher pipelinedPublisher) {
        this(registry, new ObjectMapper(), pipelinedPublisher);
    }

    /** Full constructor with explicit ObjectMapper. */
    public RacerTransaction(RacerPublisherRegistry registry,
                             ObjectMapper objectMapper,
                             @Nullable RacerPipelinedPublisher pipelinedPublisher) {
        this.registry           = registry;
        this.objectMapper       = objectMapper;
        this.pipelinedPublisher = pipelinedPublisher;
    }

    /**
     * Builds and executes a batch publish using sequential {@link Flux#concat}.
     * If a {@link RacerPipelinedPublisher} is wired and the entries all share the
     * same channel, the batch is automatically promoted to pipelined mode.
     *
     * @param configurer lambda that registers one or more
     *                   {@link TxPublisher#publish(String, Object)} calls
     * @return {@code Mono<List<Long>>} — subscriber counts in registration order
     */
    public Mono<List<Long>> execute(Consumer<TxPublisher> configurer) {
        TxPublisher tx = new TxPublisher(registry, objectMapper);
        configurer.accept(tx);
        return tx.commit(pipelinedPublisher)
                .doOnSuccess(results ->
                        log.debug("[racer-tx] Batch complete — {} channel(s)", results.size()))
                .doOnError(ex ->
                        log.error("[racer-tx] Batch publish failed: {}", ex.getMessage()));
    }

    /**
     * Builds and executes a batch publish, explicitly choosing sequential vs pipelined
     * mode regardless of the auto-configuration.
     *
     * @param configurer lambda that registers publish calls
     * @param pipelined  {@code true} to use parallel merging (pipelining),
     *                   {@code false} to use sequential concat (original behaviour)
     * @return {@code Mono<List<Long>>}
     */
    public Mono<List<Long>> execute(Consumer<TxPublisher> configurer, boolean pipelined) {
        TxPublisher tx = new TxPublisher(registry, objectMapper);
        configurer.accept(tx);
        RacerPipelinedPublisher pub = pipelined ? pipelinedPublisher : null;
        return tx.commit(pub)
                .doOnSuccess(results ->
                        log.debug("[racer-tx] Batch complete ({}) — {} channel(s)",
                                pipelined ? "pipelined" : "sequential", results.size()))
                .doOnError(ex ->
                        log.error("[racer-tx] Batch publish failed: {}", ex.getMessage()));
    }

    // -----------------------------------------------------------------------
    // Inner builder: collects (alias, payload, sender) triples
    // -----------------------------------------------------------------------

    public static class TxPublisher {

        private final RacerPublisherRegistry registry;
        private final ObjectMapper objectMapper;
        private final List<PublishEntry> entries = new ArrayList<>();

        TxPublisher(RacerPublisherRegistry registry, ObjectMapper objectMapper) {
            this.registry     = registry;
            this.objectMapper = objectMapper;
        }

        /**
         * Enqueues a publish to the channel identified by {@code alias}.
         * Execution is deferred until {@link #commit()} is called.
         */
        public void publish(String alias, Object payload) {
            entries.add(new PublishEntry(alias, payload, null));
        }

        /** Enqueues a publish with an explicit sender label. */
        public void publish(String alias, Object payload, String sender) {
            entries.add(new PublishEntry(alias, payload, sender));
        }

        Mono<List<Long>> commit(@Nullable RacerPipelinedPublisher pipelinedPublisher) {
            if (entries.isEmpty()) {
                return Mono.just(List.of());
            }

            // When a pipelined publisher is available, build pipeline items and merge
            if (pipelinedPublisher != null) {
                List<RacerPipelinedPublisher.PipelineItem> items = entries.stream()
                        .map(e -> {
                            String channelName = registry.getPublisher(e.alias()).getChannelName();
                            String sender      = e.sender() != null ? e.sender() : "racer";
                            String payloadStr  = serializePayload(e.payload());
                            return new RacerPipelinedPublisher.PipelineItem(channelName, payloadStr, sender);
                        })
                        .toList();
                return pipelinedPublisher.publishItems(items);
            }

            // Default: sequential concat (original behaviour)
            List<Mono<Long>> ops = entries.stream()
                    .map(e -> e.sender != null
                            ? registry.getPublisher(e.alias()).publishAsync(e.payload, e.sender)
                            : registry.getPublisher(e.alias()).publishAsync(e.payload))
                    .toList();
            return Flux.concat(ops).collectList();
        }

        private record PublishEntry(String alias, Object payload, String sender) {}

        /** Serializes non-String payloads to JSON using the configured ObjectMapper. */
        private String serializePayload(Object payload) {
            if (payload == null) return "null";
            if (payload instanceof String s) return s;
            try {
                return objectMapper.writeValueAsString(payload);
            } catch (Exception ex) {
                log.warn("[racer-tx] Failed to serialize payload, falling back to toString(): {}", ex.getMessage());
                return payload.toString();
            }
        }
    }
}
