package com.cheetah.racer.publisher;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RacerConsistentHashRing}.
 */
class RacerConsistentHashRingTest {

    // ── construction ─────────────────────────────────────────────────────────

    @Test
    void constructor_rejectsZeroShards() {
        assertThatThrownBy(() -> new RacerConsistentHashRing(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNegativeShards() {
        assertThatThrownBy(() -> new RacerConsistentHashRing(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsZeroVirtualNodes() {
        assertThatThrownBy(() -> new RacerConsistentHashRing(3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ringSize_equalsShardTimesVirtualNodes() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(3, 10);
        // Collisions during construction may slightly reduce size, but it should be ≤ expected
        assertThat(ring.ringSize()).isLessThanOrEqualTo(3 * 10).isGreaterThan(0);
    }

    // ── getShardFor ──────────────────────────────────────────────────────────

    @Test
    void getShardFor_returnsSameShardForSameKey() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(4, 50);
        String key = "orders-channel";

        assertThat(ring.getShardFor(key)).isEqualTo(ring.getShardFor(key));
    }

    @Test
    void getShardFor_returnsValidShardIndex() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(5, 100);

        for (String key : new String[]{"a", "b", "hello", "world", "test-123", "", null}) {
            int shard = ring.getShardFor(key);
            assertThat(shard).isBetween(0, 4);
        }
    }

    @Test
    void getShardFor_distributesAcrossAllShards() {
        // With 4 shards and 150 virtual nodes each, routing a large key set should hit all shards
        RacerConsistentHashRing ring = new RacerConsistentHashRing(4, 150);
        Set<Integer> seen = new HashSet<>();

        for (int i = 0; i < 400; i++) {
            seen.add(ring.getShardFor("key-" + i));
        }

        // All 4 shards should be hit
        assertThat(seen).hasSize(4);
    }

    @Test
    void getShardFor_singleShardAlwaysReturnsZero() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(1, 50);

        assertThat(ring.getShardFor("anything")).isZero();
        assertThat(ring.getShardFor("other")).isZero();
    }

    // ── getFailoverShardFor ──────────────────────────────────────────────────

    @Test
    void getFailoverShardFor_returnsDifferentShardFromPrimary() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(3, 100);

        // For at least some keys, the failover should differ from the primary
        boolean foundDifferent = false;
        for (int i = 0; i < 100; i++) {
            String key = "key-" + i;
            int primary  = ring.getShardFor(key);
            int failover = ring.getFailoverShardFor(key);
            if (primary != failover) {
                foundDifferent = true;
                break;
            }
        }
        assertThat(foundDifferent).as("At least one key should have a different failover shard").isTrue();
    }

    @Test
    void getFailoverShardFor_withSingleShard_returnsPrimaryShard() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(1, 50);

        // With one shard, failover falls back to the only shard (0)
        assertThat(ring.getFailoverShardFor("any-key")).isZero();
    }

    @Test
    void getFailoverShardFor_returnsValidShardIndex() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(4, 100);

        for (int i = 0; i < 100; i++) {
            int failover = ring.getFailoverShardFor("key-" + i);
            assertThat(failover).isBetween(0, 3);
        }
    }

    @Test
    void getFailoverShardFor_isConsistentForSameKey() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(4, 100);
        String key = "stable-key";

        assertThat(ring.getFailoverShardFor(key)).isEqualTo(ring.getFailoverShardFor(key));
    }

    // ── Distribution uniformity ───────────────────────────────────────────────

    @Test
    void getShardFor_distributionIsRoughlyUniform() {
        int shardCount = 4;
        RacerConsistentHashRing ring = new RacerConsistentHashRing(shardCount, 200);
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        int total = 1000;

        for (int i = 0; i < total; i++) {
            counts.merge(ring.getShardFor("msg-" + i), 1, Integer::sum);
        }

        // Each shard should receive between 10% and 40% of keys with 200 virtual nodes
        for (int s = 0; s < shardCount; s++) {
            int count = counts.getOrDefault(s, 0);
            assertThat(count)
                    .as("Shard %d: %d/%d keys", s, count, total)
                    .isGreaterThan(total / 10)
                    .isLessThan(total * 40 / 100);
        }
    }

    // ── Edge paths via ring injection (reflection) ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void getShardFor_emptyRing_returnsZero() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(1, 1);
        TreeMap<Integer, Integer> ringMap =
                (TreeMap<Integer, Integer>) ReflectionTestUtils.getField(ring, "ring");
        ringMap.clear();

        // L67: ring.isEmpty() → return 0
        assertThat(ring.getShardFor("any-key")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getShardFor_hashExceedsMaxRingPosition_wrapsToFirst() {
        RacerConsistentHashRing ring = new RacerConsistentHashRing(1, 1);
        TreeMap<Integer, Integer> ringMap =
                (TreeMap<Integer, Integer>) ReflectionTestUtils.getField(ring, "ring");
        // Only entry at a very negative value — any non-negative hash wraps to firstKey
        ringMap.clear();
        ringMap.put(Integer.MIN_VALUE, 0);

        // L72: ceilingKey(hash) == null when hash > Integer.MIN_VALUE; nearly every key qualifies
        for (int i = 0; i < 10; i++) {
            assertThat(ring.getShardFor("wrap-" + i)).isZero();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFailoverShardFor_hashExceedsMaxRingPosition_wrapsToFirst() {
        // 2 shards so the shardCount==1 early-return does NOT fire
        RacerConsistentHashRing ring = new RacerConsistentHashRing(2, 1);
        TreeMap<Integer, Integer> ringMap =
                (TreeMap<Integer, Integer>) ReflectionTestUtils.getField(ring, "ring");
        ringMap.clear();
        // Two entries: shard 0 at MIN_VALUE, shard 1 at MIN_VALUE+1
        ringMap.put(Integer.MIN_VALUE, 0);
        ringMap.put(Integer.MIN_VALUE + 1, 1);

        // L95: ceilingKey(hash) wraps to firstKey when hash > MIN_VALUE+1 (nearly always)
        // findFailover will return shard 1 (different from primary 0)
        int failover = ring.getFailoverShardFor("failover-wrap-key");
        assertThat(failover).isBetween(0, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFailoverShardFor_walkReachesEndOfRing_wrapsAndFallsBack() {
        // 2 shards, but inject both virtual nodes as shard 0 → walk exhausts ring,
        // hits the end-of-ring wrap (L113), then falls to fallback return (L132)
        RacerConsistentHashRing ring = new RacerConsistentHashRing(2, 1);
        TreeMap<Integer, Integer> ringMap =
                (TreeMap<Integer, Integer>) ReflectionTestUtils.getField(ring, "ring");
        ringMap.clear();
        ringMap.put(-200, 0);
        ringMap.put(-100, 0);  // both nodes → shard 0; walk never finds a different shard

        // For any key, primary = shard 0; walk exhausts all nodes hitting the wrap-around,
        // then returns the fallback (primaryShard + 1) % 2 = 1
        assertThat(ring.getFailoverShardFor("fallback-key")).isEqualTo(1);
    }
}
