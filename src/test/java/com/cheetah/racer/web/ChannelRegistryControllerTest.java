package com.cheetah.racer.web;

import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelRegistryControllerTest {

    @Mock private RacerPublisherRegistry registry;
    @InjectMocks private ChannelRegistryController controller;

    @Test
    void listChannels_returnsEmpty_whenNoPublishers() {
        when(registry.getAll()).thenReturn(Map.of());

        StepVerifier.create(controller.listChannels())
                .assertNext(map -> assertThat(map).isEmpty())
                .verifyComplete();
    }

    @Test
    void listChannels_returnsChannelEntries() {
        RacerChannelPublisher pub1 = mock(RacerChannelPublisher.class);
        when(pub1.getChannelName()).thenReturn("racer:orders");

        RacerChannelPublisher pub2 = mock(RacerChannelPublisher.class);
        when(pub2.getChannelName()).thenReturn("racer:events");

        when(registry.getAll()).thenReturn(Map.of("orders", pub1, "events", pub2));

        StepVerifier.create(controller.listChannels())
                .assertNext(map -> {
                    assertThat(map).hasSize(2);
                    assertThat(map).containsKey("orders");
                    assertThat(map).containsKey("events");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ordersEntry = (Map<String, Object>) map.get("orders");
                    assertThat(ordersEntry.get("channel")).isEqualTo("racer:orders");
                })
                .verifyComplete();
    }
}
