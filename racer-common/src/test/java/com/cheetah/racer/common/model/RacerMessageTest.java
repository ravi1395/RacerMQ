package com.cheetah.racer.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RacerMessageTest {

    // ------------------------------------------------------------------
    // create(channel, payload, sender)
    // ------------------------------------------------------------------

    @Test
    void create_populatesAllRequiredFields() {
        Instant before = Instant.now();
        RacerMessage msg = RacerMessage.create("racer:orders", "hello", "test-service");
        Instant after = Instant.now();

        assertThat(msg.getId()).isNotNull().isNotBlank();
        assertThat(msg.getChannel()).isEqualTo("racer:orders");
        assertThat(msg.getPayload()).isEqualTo("hello");
        assertThat(msg.getSender()).isEqualTo("test-service");
        assertThat(msg.getTimestamp()).isBetween(before, after);
        assertThat(msg.getRetryCount()).isEqualTo(0);
    }

    @Test
    void create_defaultPriorityIsNORMAL() {
        RacerMessage msg = RacerMessage.create("ch", "payload", "sender");
        assertThat(msg.getPriority()).isEqualTo("NORMAL");
    }

    @Test
    void createTwoMessages_haveDistinctIds() {
        RacerMessage a = RacerMessage.create("ch", "p", "s");
        RacerMessage b = RacerMessage.create("ch", "p", "s");
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    // ------------------------------------------------------------------
    // create(channel, payload, sender, priority)
    // ------------------------------------------------------------------

    @Test
    void createWithPriority_setsGivenPriority() {
        RacerMessage msg = RacerMessage.create("ch", "p", "s", "HIGH");
        assertThat(msg.getPriority()).isEqualTo("HIGH");
    }

    @Test
    void createWithNullPriority_fallsBackToNORMAL() {
        RacerMessage msg = RacerMessage.create("ch", "p", "s", null);
        assertThat(msg.getPriority()).isEqualTo("NORMAL");
    }

    // ------------------------------------------------------------------
    // Builder default — priority
    // ------------------------------------------------------------------

    @Test
    void builderWithoutPriority_defaultsToNORMAL() {
        RacerMessage msg = RacerMessage.builder()
                .id("id-1")
                .channel("ch")
                .payload("p")
                .build();
        assertThat(msg.getPriority()).isEqualTo("NORMAL");
    }

    @Test
    void builderWithPriority_overridesDefault() {
        RacerMessage msg = RacerMessage.builder()
                .id("id-2")
                .priority("LOW")
                .build();
        assertThat(msg.getPriority()).isEqualTo("LOW");
    }

    // ------------------------------------------------------------------
    // Mutable fields
    // ------------------------------------------------------------------

    @Test
    void setRetryCount_updatesField() {
        RacerMessage msg = RacerMessage.create("ch", "p", "s");
        msg.setRetryCount(2);
        assertThat(msg.getRetryCount()).isEqualTo(2);
    }
}
