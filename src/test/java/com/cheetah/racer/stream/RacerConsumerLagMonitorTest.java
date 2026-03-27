package com.cheetah.racer.stream;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.metrics.RacerMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerConsumerLagMonitorTest {

    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private RacerMetrics racerMetrics;
    @Mock private RacerProperties racerProperties;
    @Mock private ReactiveStreamOperations<String, Object, Object> streamOps;
    @Mock private PendingMessagesSummary pendingSummary;

    private RacerConsumerLagMonitor monitor;

    @BeforeEach
    void setUp() {
        RacerProperties.ConsumerLagProperties lagProps = new RacerProperties.ConsumerLagProperties();
        lagProps.setScrapeIntervalSeconds(60);
        lagProps.setLagWarnThreshold(1000);
        when(racerProperties.getConsumerLag()).thenReturn(lagProps);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);

        monitor = new RacerConsumerLagMonitor(redisTemplate, racerMetrics, racerProperties);
    }

    // ── trackStream ───────────────────────────────────────────────────────────

    @Test
    void trackStream_registersGaugeAndStoresCounter() {
        monitor.trackStream("orders-stream", "order-group");

        Map<String, AtomicLong> counters = monitor.getLagCounters();
        assertThat(counters).containsKey("orders-stream|order-group");
        assertThat(counters.get("orders-stream|order-group").get()).isEqualTo(0);

        verify(racerMetrics).registerStreamConsumerLagGauge(eq("orders-stream/order-group"), any());
    }

    @Test
    void trackStream_calledTwice_doesNotDuplicate() {
        monitor.trackStream("s", "g");
        monitor.trackStream("s", "g");

        assertThat(monitor.getLagCounters()).hasSize(1);
        verify(racerMetrics, times(1)).registerStreamConsumerLagGauge(anyString(), any());
    }

    @Test
    void trackStream_differentPairs_tracksAll() {
        monitor.trackStream("s1", "g1");
        monitor.trackStream("s2", "g2");

        assertThat(monitor.getLagCounters()).hasSize(2);
        assertThat(monitor.getLagCounters()).containsKey("s1|g1");
        assertThat(monitor.getLagCounters()).containsKey("s2|g2");
    }

    // ── getLagCounters ────────────────────────────────────────────────────────

    @Test
    void getLagCounters_returnsUnmodifiableMap() {
        monitor.trackStream("s", "g");
        Map<String, AtomicLong> counters = monitor.getLagCounters();

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> counters.put("x", new AtomicLong()));
    }

    @Test
    void getLagCounters_empty_returnsEmptyMap() {
        assertThat(monitor.getLagCounters()).isEmpty();
    }

    // ── start / stop lifecycle ────────────────────────────────────────────────

    @Test
    void stop_disposesLoop() {
        when(pendingSummary.getTotalPendingMessages()).thenReturn(0L);
        when(streamOps.pending(anyString(), anyString())).thenReturn(Mono.just(pendingSummary));

        monitor.start();
        monitor.stop();
    }

    @Test
    void stop_whenNotStarted_doesNotThrow() {
        monitor.stop();
    }

    @Test
    void start_andStop_calledMultipleTimes_doesNotThrow() {
        when(pendingSummary.getTotalPendingMessages()).thenReturn(5L);
        when(streamOps.pending(anyString(), anyString())).thenReturn(Mono.just(pendingSummary));

        monitor.start();
        monitor.stop();
        monitor.stop(); // second stop should be a no-op
    }

    // ── scrapeOne (via reflection) — normal path ──────────────────────────────

    @Test
    void scrapeOne_updatesCounterWithPendingMessages() throws Exception {
        when(pendingSummary.getTotalPendingMessages()).thenReturn(42L);
        when(streamOps.pending("my-stream", "my-group")).thenReturn(Mono.just(pendingSummary));

        AtomicLong counter = new AtomicLong(0);

        Method scrapeOne = RacerConsumerLagMonitor.class
                .getDeclaredMethod("scrapeOne", String.class, String.class, AtomicLong.class);
        scrapeOne.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) scrapeOne.invoke(monitor, "my-stream", "my-group", counter);

        StepVerifier.create(result).verifyComplete();
        assertThat(counter.get()).isEqualTo(42L);
    }

    @Test
    void scrapeOne_lagAboveThreshold_logsWarnAndUpdatesCounter() throws Exception {
        // threshold is 1000; set lag above it
        RacerProperties.ConsumerLagProperties lagProps = new RacerProperties.ConsumerLagProperties();
        lagProps.setScrapeIntervalSeconds(60);
        lagProps.setLagWarnThreshold(100); // low threshold
        when(racerProperties.getConsumerLag()).thenReturn(lagProps);

        when(pendingSummary.getTotalPendingMessages()).thenReturn(500L); // > 100
        when(streamOps.pending("s", "g")).thenReturn(Mono.just(pendingSummary));

        AtomicLong counter = new AtomicLong(0);

        Method scrapeOne = RacerConsumerLagMonitor.class
                .getDeclaredMethod("scrapeOne", String.class, String.class, AtomicLong.class);
        scrapeOne.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) scrapeOne.invoke(monitor, "s", "g", counter);

        StepVerifier.create(result).verifyComplete();
        assertThat(counter.get()).isEqualTo(500L);
    }

    @Test
    void scrapeOne_whenPendingReturnsEmpty_setsCounterToZero() throws Exception {
        when(streamOps.pending("s", "g")).thenReturn(Mono.empty());

        AtomicLong counter = new AtomicLong(99);

        Method scrapeOne = RacerConsumerLagMonitor.class
                .getDeclaredMethod("scrapeOne", String.class, String.class, AtomicLong.class);
        scrapeOne.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) scrapeOne.invoke(monitor, "s", "g", counter);

        StepVerifier.create(result).verifyComplete();
        assertThat(counter.get()).isEqualTo(0L);
    }

    @Test
    void scrapeOne_whenRedisErrors_completesGracefully() throws Exception {
        when(streamOps.pending("err-stream", "g"))
                .thenReturn(Mono.error(new RuntimeException("redis failure")));

        AtomicLong counter = new AtomicLong(0);

        Method scrapeOne = RacerConsumerLagMonitor.class
                .getDeclaredMethod("scrapeOne", String.class, String.class, AtomicLong.class);
        scrapeOne.setAccessible(true);

        @SuppressWarnings("unchecked")
        Mono<Void> result = (Mono<Void>) scrapeOne.invoke(monitor, "err-stream", "g", counter);

        // onErrorResume swallows the error — must complete without error signal
        StepVerifier.create(result).verifyComplete();
        assertThat(counter.get()).isEqualTo(0L); // counter unchanged
    }

    // ── scrapeAll (via reflection) ────────────────────────────────────────────

    @Test
    void scrapeAll_withTrackedStreams_scrapeseach() throws Exception {
        when(pendingSummary.getTotalPendingMessages()).thenReturn(10L);
        when(streamOps.pending(anyString(), anyString())).thenReturn(Mono.just(pendingSummary));

        monitor.trackStream("s1", "g1");
        monitor.trackStream("s2", "g2");

        Method scrapeAll = RacerConsumerLagMonitor.class.getDeclaredMethod("scrapeAll");
        scrapeAll.setAccessible(true);

        @SuppressWarnings("unchecked")
        reactor.core.publisher.Flux<Void> result =
                (reactor.core.publisher.Flux<Void>) scrapeAll.invoke(monitor);

        StepVerifier.create(result).verifyComplete();

        verify(streamOps, atLeast(2)).pending(anyString(), anyString());
    }

    @Test
    void scrapeAll_noTrackedStreams_completesImmediately() throws Exception {
        Method scrapeAll = RacerConsumerLagMonitor.class.getDeclaredMethod("scrapeAll");
        scrapeAll.setAccessible(true);

        @SuppressWarnings("unchecked")
        reactor.core.publisher.Flux<Void> result =
                (reactor.core.publisher.Flux<Void>) scrapeAll.invoke(monitor);

        StepVerifier.create(result).verifyComplete();
        verify(streamOps, never()).pending(anyString(), anyString());
    }
}
