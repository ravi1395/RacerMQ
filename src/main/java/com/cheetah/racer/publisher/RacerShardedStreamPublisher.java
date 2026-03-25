package com.cheetah.racer.publisher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Shard-aware Redis Streams publisher (R-8 — Consumer Scaling).
 *
 * <p>Routes each message to one of N shard streams based on a shard key.
 * By default the shard index is derived from the key using a CRC-16-style hash
 * identical to Redis Cluster's hash-slot algorithm, so messages with the same
 * key always land on the same shard — enabling per-key ordering guarantees.
 *
 * <h3>Phase 4.1 — Cluster-Aware Publishing</h3>
 * When {@code consistentHashEnabled=true} a virtual-node consistent-hash ring
 * ({@link RacerConsistentHashRing}) is used instead of simple CRC-16 mod N.
 * This allows shards to be added or removed with minimal redistribution.
 * If {@code failoverEnabled=true} and a publish attempt fails, the next shard
 * in the ring is tried automatically.
 *
 * <h3>Stream naming</h3>
 * <pre>
 * baseStreamKey:0   — shard 0
 * baseStreamKey:1   — shard 1
 * …
 * baseStreamKey:N-1 — shard N-1
 * </pre>
 *
 * <h3>Configuration</h3>
 * <pre>
 * racer.sharding.enabled=true
 * racer.sharding.shard-count=4
 * racer.sharding.streams=racer:orders:stream,racer:audit:stream
 * # Phase 4.1 additions:
 * racer.sharding.consistent-hash-enabled=true
 * racer.sharding.virtual-nodes-per-shard=150
 * racer.sharding.failover-enabled=true
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 * racerShardedStreamPublisher.publishToShard("racer:orders:stream", payload, sender, orderId);
 * </pre>
 */
@Slf4j
public class RacerShardedStreamPublisher {

    private final RacerStreamPublisher delegate;
    private final int shardCount;

    // Phase 4.1 — optional consistent-hash ring + failover
    private final RacerConsistentHashRing hashRing;
    private final boolean failoverEnabled;

    /** Legacy constructor — CRC-16 routing only, no failover. */
    public RacerShardedStreamPublisher(RacerStreamPublisher delegate, int shardCount) {
        this(delegate, shardCount, null, false);
    }

    /**
     * Phase 4.1 constructor — supports consistent-hash ring and shard failover.
     *
     * @param delegate       underlying stream publisher
     * @param shardCount     total number of physical shards
     * @param hashRing       optional consistent-hash ring; {@code null} falls back to CRC-16
     * @param failoverEnabled when {@code true} a publish failure triggers a retry on the
     *                        failover shard returned by the hash ring
     */
    public RacerShardedStreamPublisher(RacerStreamPublisher delegate, int shardCount,
                                        RacerConsistentHashRing hashRing, boolean failoverEnabled) {
        this.delegate        = delegate;
        this.shardCount      = shardCount;
        this.hashRing        = hashRing;
        this.failoverEnabled = failoverEnabled && hashRing != null;
    }

    /**
     * Publishes {@code payload} to a shard of the given base stream key.
     * The shard is selected by hashing {@code shardKey} modulo {@link #shardCount}
     * (or via the consistent-hash ring when enabled).
     *
     * @param baseStreamKey the unsharded stream key (e.g. {@code racer:orders:stream})
     * @param payload       the object to write
     * @param sender        sender identifier
     * @param shardKey      value used to determine the target shard (e.g. order ID, user ID)
     * @return Mono of the assigned stream entry {@link RecordId}
     */
    public Mono<RecordId> publishToShard(String baseStreamKey, Object payload,
                                         String sender, String shardKey) {
        int shard = primaryShardFor(shardKey);
        String shardedKey = baseStreamKey + ":" + shard;
        log.debug("[racer-shard] key='{}' → shard={} stream='{}'", shardKey, shard, shardedKey);

        Mono<RecordId> primary = delegate.publishToStream(shardedKey, payload, sender);

        if (failoverEnabled) {
            return primary.onErrorResume(ex -> {
                int failoverShard = hashRing.getFailoverShardFor(shardKey);
                String failoverKey = baseStreamKey + ":" + failoverShard;
                log.warn("[racer-shard] Primary shard {} failed for key='{}', failing over to shard {} (stream='{}'): {}",
                        shard, shardKey, failoverShard, failoverKey, ex.getMessage());
                return delegate.publishToStream(failoverKey, payload, sender);
            });
        }
        return primary;
    }

    /**
     * Returns all shard stream keys for the given base stream key.
     * Useful when initialising consumer groups for each shard.
     */
    public List<String> allShardKeys(String baseStreamKey) {
        java.util.List<String> keys = new java.util.ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            keys.add(baseStreamKey + ":" + i);
        }
        return keys;
    }

    /**
     * Returns the number of shards this publisher manages.
     */
    public int getShardCount() {
        return shardCount;
    }

    // -------------------------------------------------------------------------
    // Shard-index calculation
    // -------------------------------------------------------------------------

    /**
     * Returns the primary shard index for the given key.
     * Uses the consistent-hash ring when available, otherwise falls back to
     * CRC-16/CCITT mod N.
     */
    int primaryShardFor(String key) {
        if (hashRing != null) {
            return hashRing.getShardFor(key);
        }
        return shardFor(key);
    }

    /**
     * Computes a deterministic shard index using CRC-16/CCITT (the same
     * polynomial used by Redis Cluster hash slots) modulo {@link #shardCount}.
     *
     * @deprecated Retained for backward-compatibility; prefer {@link #primaryShardFor(String)}.
     */
    @Deprecated(since = "1.3.0")
    int shardFor(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        int crc = crc16(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Math.abs(crc) % shardCount;
    }

    /** CRC-16/CCITT (poly 0x1021, init 0x0000) — same as Redis Cluster. */
    private static int crc16(byte[] bytes) {
        int crc = 0x0000;
        for (byte b : bytes) {
            for (int i = 0; i < 8; i++) {
                boolean mix = ((crc ^ (b << 8)) & 0x8000) != 0;
                crc  = (crc << 1) & 0xFFFF;
                b    = (byte) (b << 1);
                if (mix) crc ^= 0x1021;
            }
        }
        return crc;
    }
}
