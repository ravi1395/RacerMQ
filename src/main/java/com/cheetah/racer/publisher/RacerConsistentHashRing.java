package com.cheetah.racer.publisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

/**
 * Phase 4.1 – Consistent-hash ring for shard-key-to-shard-index routing.
 *
 * <p>Each physical shard is represented by {@code virtualNodesPerShard} virtual
 * nodes distributed uniformly around a 32-bit integer ring.  A shard key is
 * hashed with MD5 and the ring is walked clockwise to find the first virtual
 * node whose position is &ge; the key's hash; that node's shard index is
 * returned.
 *
 * <p>This strategy minimises redistribution when shards are added or removed:
 * only the keys previously routed to the affected segment need to move.
 *
 * <p>Thread-safety: the ring is immutable after construction and is safe for
 * concurrent reads without synchronisation.
 */
public class RacerConsistentHashRing {

    /** TreeMap from ring-position → shard index. */
    private final TreeMap<Integer, Integer> ring = new TreeMap<>();

    private final int shardCount;
    private final int virtualNodesPerShard;

    /**
     * Build a new ring.
     *
     * @param shardCount          number of physical shards (must be &gt; 0)
     * @param virtualNodesPerShard virtual nodes per physical shard (must be &gt; 0)
     */
    public RacerConsistentHashRing(int shardCount, int virtualNodesPerShard) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount must be > 0");
        if (virtualNodesPerShard <= 0) throw new IllegalArgumentException("virtualNodesPerShard must be > 0");

        this.shardCount = shardCount;
        this.virtualNodesPerShard = virtualNodesPerShard;

        for (int shard = 0; shard < shardCount; shard++) {
            for (int v = 0; v < virtualNodesPerShard; v++) {
                String vKey = "shard-" + shard + "-vnode-" + v;
                int position = md5Int(vKey);
                // Resolve collisions by linear probing
                while (ring.containsKey(position)) {
                    position++;
                }
                ring.put(position, shard);
            }
        }
    }

    /**
     * Return the primary shard index for the given routing key.
     *
     * @param key routing key (e.g. a message channel name or entity id)
     * @return shard index in {@code [0, shardCount)}
     */
    public int getShardFor(String key) {
        if (ring.isEmpty()) {
            return 0;
        }
        int hash = md5Int(key == null ? "" : key);
        Integer pos = ring.ceilingKey(hash);
        if (pos == null) {
            pos = ring.firstKey();
        }
        return ring.get(pos);
    }

    /**
     * Return a failover shard index for the given routing key — the first shard
     * in the ring that is different from the primary shard.
     *
     * <p>If all virtual nodes belong to the same physical shard (e.g. {@code
     * shardCount == 1}) the primary shard is returned unchanged.
     *
     * @param key routing key
     * @return failover shard index in {@code [0, shardCount)}
     */
    public int getFailoverShardFor(String key) {
        if (shardCount == 1 || ring.isEmpty()) {
            return 0;
        }
        int primaryShard = getShardFor(key);
        int hash = md5Int(key == null ? "" : key);
        Integer pos = ring.ceilingKey(hash);
        if (pos == null) {
            pos = ring.firstKey();
        }
        // Walk the ring clockwise until we find a different shard
        int checked = 0;
        int totalNodes = ring.size();
        while (checked < totalNodes) {
            Integer nextPos = ring.higherKey(pos);
            if (nextPos == null) {
                nextPos = ring.firstKey();
            }
            int candidateShard = ring.get(nextPos);
            if (candidateShard != primaryShard) {
                return candidateShard;
            }
            pos = nextPos;
            checked++;
        }
        // Fallback: rotate primary by 1 mod shardCount
        return (primaryShard + 1) % shardCount;
    }

    /** Exposed for testing – number of virtual nodes in the ring. */
    int ringSize() {
        return ring.size();
    }

    // ── Hashing ───────────────────────────────────────────────────────────────

    private static int md5Int(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // Take first 4 bytes as a signed int — gives uniform 32-bit distribution
            return ((digest[0] & 0xFF) << 24)
                    | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8)
                    | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandated by the JVM spec, this cannot happen
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
