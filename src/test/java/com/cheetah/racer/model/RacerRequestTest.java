package com.cheetah.racer.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RacerRequestTest {

    @Test
    void create_setsCorrelationIdAndTimestamp() {
        RacerRequest req = RacerRequest.create("hello", "test-service");

        assertThat(req.getCorrelationId()).isNotNull().isNotBlank();
        assertThat(req.getPayload()).isEqualTo("hello");
        assertThat(req.getSender()).isEqualTo("test-service");
        assertThat(req.getTimestamp()).isNotNull().isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void builder_setsAllFields() {
        Instant ts = Instant.now();
        RacerRequest req = RacerRequest.builder()
                .correlationId("id-1")
                .channel("racer:orders")
                .payload("{}")
                .sender("svc")
                .timestamp(ts)
                .replyTo("racer:reply:id-1")
                .build();

        assertThat(req.getCorrelationId()).isEqualTo("id-1");
        assertThat(req.getChannel()).isEqualTo("racer:orders");
        assertThat(req.getPayload()).isEqualTo("{}");
        assertThat(req.getSender()).isEqualTo("svc");
        assertThat(req.getTimestamp()).isEqualTo(ts);
        assertThat(req.getReplyTo()).isEqualTo("racer:reply:id-1");
    }

    @Test
    void settersAndGetters_work() {
        RacerRequest req = new RacerRequest();
        req.setCorrelationId("c1");
        req.setChannel("ch");
        req.setPayload("p");
        req.setSender("s");
        Instant t = Instant.now();
        req.setTimestamp(t);
        req.setReplyTo("rt");

        assertThat(req.getCorrelationId()).isEqualTo("c1");
        assertThat(req.getChannel()).isEqualTo("ch");
        assertThat(req.getPayload()).isEqualTo("p");
        assertThat(req.getSender()).isEqualTo("s");
        assertThat(req.getTimestamp()).isEqualTo(t);
        assertThat(req.getReplyTo()).isEqualTo("rt");
    }

    @Test
    void equalsAndHashCode_work() {
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        RacerRequest a = RacerRequest.builder().correlationId("1").payload("p").sender("s").timestamp(ts).build();
        RacerRequest b = RacerRequest.builder().correlationId("1").payload("p").sender("s").timestamp(ts).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsFields() {
        RacerRequest req = RacerRequest.create("test", "svc");
        String str = req.toString();
        assertThat(str).contains("payload=test").contains("sender=svc");
    }

    @Test
    void create_generatesUniqueCorrelationIds() {
        RacerRequest a = RacerRequest.create("a", "s");
        RacerRequest b = RacerRequest.create("b", "s");

        assertThat(a.getCorrelationId()).isNotEqualTo(b.getCorrelationId());
    }
}
