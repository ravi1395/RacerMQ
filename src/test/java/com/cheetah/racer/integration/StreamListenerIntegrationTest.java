package com.cheetah.racer.integration;

import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.model.RacerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code @RacerStreamListener} — verifies that messages
 * added to a Redis Stream via XADD are consumed by the annotated handler.
 */
@Import(StreamListenerIntegrationTest.StreamReceiverConfig.class)
class StreamListenerIntegrationTest extends RacerIntegrationTestBase {

    static final String STREAM_KEY = "racer:it:stream";
    static final String GROUP      = "racer-it-group";

    // ── Test stream consumer bean ────────────────────────────────────────────

    static class StreamReceiver {

        final List<RacerMessage> received  = new ArrayList<>();
        final List<String>       rawValues = new ArrayList<>();

        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>();
        final AtomicReference<CountDownLatch> errorLatch   = new AtomicReference<>();

        /** Normal handler — captures received messages. */
        @RacerStreamListener(streamKey = STREAM_KEY, group = GROUP)
        public void onMessage(RacerMessage msg) {
            received.add(msg);
            CountDownLatch l = messageLatch.get();
            if (l != null) l.countDown();
        }
    }

    @TestConfiguration
    static class StreamReceiverConfig {
        @Bean
        StreamReceiver streamReceiver() { return new StreamReceiver(); }
    }

    // ── Auto-wired state ─────────────────────────────────────────────────────

    @Autowired
    private StreamReceiver streamReceiver;

    @BeforeEach
    void reset() {
        streamReceiver.received.clear();
        clearDlq();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void streamEntry_triggersHandler() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        streamReceiver.messageLatch.set(latch);

        xaddToStream(STREAM_KEY, message(STREAM_KEY, "stream-payload"));

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(streamReceiver.received).hasSize(1);
        assertThat(streamReceiver.received.get(0).getPayload()).isEqualTo("stream-payload");
    }

    @Test
    void multipleStreamEntries_allDelivered() throws Exception {
        int count = 5;
        CountDownLatch latch = new CountDownLatch(count);
        streamReceiver.messageLatch.set(latch);

        for (int i = 0; i < count; i++) {
            xaddToStream(STREAM_KEY, message(STREAM_KEY, "batch-" + i));
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(streamReceiver.received).hasSize(count);
    }
}
