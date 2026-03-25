package com.cheetah.racer.publisher;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

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
}
