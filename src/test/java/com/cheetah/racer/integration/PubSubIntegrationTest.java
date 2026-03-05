package com.cheetah.racer.integration;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
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
 * Integration tests for Racer Pub/Sub message delivery via {@code @RacerListener}.
 *
 * <p>Verifies that messages published to a Redis channel are correctly delivered
 * to {@code @RacerListener}-annotated methods, with support for:
 * <ul>
 *   <li>Full {@link RacerMessage} envelope parameter</li>
 *   <li>Raw {@code String} payload parameter</li>
 *   <li>Deserialized POJO payload parameter</li>
 *   <li>CONCURRENT concurrency mode</li>
 * </ul>
 */
@Import(PubSubIntegrationTest.ReceiverConfig.class)
class PubSubIntegrationTest extends RacerIntegrationTestBase {

    static final String CHANNEL_ENVELOPE = "racer:it:envelope";
    static final String CHANNEL_STRING   = "racer:it:string";
    static final String CHANNEL_POJO     = "racer:it:pojo";
    static final String CHANNEL_CONCURRENT = "racer:it:concurrent";

    // ── POJO for payload deserialization test ────────────────────────────────

    static class OrderDto {
        public String orderId;
        public int    quantity;
    }

    // ── Test receiver bean ───────────────────────────────────────────────────

    static class TestReceiver {

        final List<RacerMessage> envelopes   = new ArrayList<>();
        final List<String>       rawPayloads = new ArrayList<>();
        final List<OrderDto>     orders      = new ArrayList<>();
        final AtomicReference<CountDownLatch> envelopeLatch    = new AtomicReference<>();
        final AtomicReference<CountDownLatch> stringLatch      = new AtomicReference<>();
        final AtomicReference<CountDownLatch> pojoLatch        = new AtomicReference<>();
        final AtomicReference<CountDownLatch> concurrentLatch  = new AtomicReference<>();

        @RacerListener(channel = CHANNEL_ENVELOPE)
        public void onEnvelope(RacerMessage msg) {
            envelopes.add(msg);
            CountDownLatch l = envelopeLatch.get();
            if (l != null) l.countDown();
        }

        @RacerListener(channel = CHANNEL_STRING)
        public void onString(String payload) {
            rawPayloads.add(payload);
            CountDownLatch l = stringLatch.get();
            if (l != null) l.countDown();
        }

        @RacerListener(channel = CHANNEL_POJO)
        public void onPojo(OrderDto order) {
            orders.add(order);
            CountDownLatch l = pojoLatch.get();
            if (l != null) l.countDown();
        }

        @RacerListener(channel = CHANNEL_CONCURRENT, mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
        public void onConcurrent(RacerMessage msg) {
            CountDownLatch l = concurrentLatch.get();
            if (l != null) l.countDown();
        }
    }

    @TestConfiguration
    static class ReceiverConfig {
        @Bean
        TestReceiver testReceiver() { return new TestReceiver(); }
    }

    // ── Beans ────────────────────────────────────────────────────────────────

    @Autowired
    private TestReceiver receiver;

    @BeforeEach
    void setUp() {
        receiver.envelopes.clear();
        receiver.rawPayloads.clear();
        receiver.orders.clear();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void messageDelivery_toRacerMessageParam() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        receiver.envelopeLatch.set(latch);

        publishPubSub(CHANNEL_ENVELOPE, message(CHANNEL_ENVELOPE, "hello-envelope"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.envelopes).hasSize(1);
        assertThat(receiver.envelopes.get(0).getPayload()).isEqualTo("hello-envelope");
        assertThat(receiver.envelopes.get(0).getSender()).isEqualTo("integration-test");
    }

    @Test
    void messageDelivery_toStringParam() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        receiver.stringLatch.set(latch);

        publishPubSub(CHANNEL_STRING, message(CHANNEL_STRING, "raw-string-payload"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.rawPayloads).hasSize(1);
        assertThat(receiver.rawPayloads.get(0)).isEqualTo("raw-string-payload");
    }

    @Test
    void messageDelivery_toPojoParam() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        receiver.pojoLatch.set(latch);

        OrderDto order = new OrderDto();
        order.orderId  = "ORD-001";
        order.quantity = 3;
        String orderJson = objectMapper.writeValueAsString(order);

        publishPubSub(CHANNEL_POJO, message(CHANNEL_POJO, orderJson));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.orders).hasSize(1);
        assertThat(receiver.orders.get(0).orderId).isEqualTo("ORD-001");
        assertThat(receiver.orders.get(0).quantity).isEqualTo(3);
    }

    @Test
    void concurrentMode_deliversAllMessages() throws Exception {
        int msgCount = 8;
        CountDownLatch latch = new CountDownLatch(msgCount);
        receiver.concurrentLatch.set(latch);

        for (int i = 0; i < msgCount; i++) {
            publishPubSub(CHANNEL_CONCURRENT, message(CHANNEL_CONCURRENT, "msg-" + i));
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
