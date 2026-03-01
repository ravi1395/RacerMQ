package com.cheetah.racer.common.publisher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Shard-aware Redis Streams publisher (R-8 — Consumer Scaling).
 *
 * <p>Routes each message to one of N shard streams based on a shard key.
 * The shard index is derived from the key using a CRC-16-style hash identical
 * to Redis Cluster's hash-slot algorithm, so messages with the same key always
 * land on the same shard — enabling per-key ordering guarantees.
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

    public RacerShardedStreamPublisher(RacerStreamPublisher delegate, int shardCount) {
        this.delegate   = delegate;
        this.shardCount = shardCount;
    }

    /**
     * Publishes {@code payload} to a shard of the given base stream key.
     * The shard is selected by hashing {@code shardKey} modulo {@link #shardCount}.
     *
     * @param baseStreamKey the unsharded stream key (e.g. {@code racer:orders:stream})
     * @param payload       the object to write
     * @param sender        sender identifier
     * @param shardKey      value used to determine the target shard (e.g. order ID, user ID)
     * @return Mono of the assigned stream entry {@link RecordId}
     */
    public Mono<RecordId> publishToShard(String baseStreamKey, Object payload,
                                         String sender, String shardKey) {
        int shard = shardFor(shardKey);
        String shardedKey = baseStreamKey + ":" + shard;
        log.debug("[racer-shard] key='{}' → shard={} stream='{}'", shardKey, shard, shardedKey);
        return delegate.publishToStream(shardedKey, payload, sender);
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
    // Shard-index calculation — mirrors Redis Cluster CRC-16 hash-slot algorithm
    // -------------------------------------------------------------------------

    /**
     * Computes a deterministic shard index for the given key.
     * Uses CRC-16/CCITT (the same polynomial used by Redis Cluster hash slots)
     * and reduces modulo {@link #shardCount}.
     */
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
