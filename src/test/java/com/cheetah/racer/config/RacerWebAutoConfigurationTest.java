package com.cheetah.racer.config;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import com.cheetah.racer.service.RacerRetentionService;
import com.cheetah.racer.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerWebAutoConfiguration} — exercises each bean factory
 * method directly without a Spring ApplicationContext.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerWebAutoConfigurationTest {

    private final RacerWebAutoConfiguration config = new RacerWebAutoConfiguration();

    @Mock DeadLetterQueueService  dlqService;
    @Mock DlqReprocessorService   reprocessorService;
    @Mock RacerRouterService      racerRouterService;
    @Mock RacerPublisherRegistry  registry;
    @Mock RacerRetentionService   retentionService;
    @Mock RacerProperties         racerProperties;

    // ── DLQ controller ───────────────────────────────────────────────────────

    @Test
    void dlqController_returnsNonNullBean() {
        DlqController ctrl = config.dlqController(dlqService, reprocessorService);
        assertThat(ctrl).isNotNull();
    }

    // ── Schema controller ────────────────────────────────────────────────────

    @Test
    void schemaController_returnsNonNullBean() {
        SchemaController ctrl = config.schemaController();
        assertThat(ctrl).isNotNull();
    }

    // ── Router controller ────────────────────────────────────────────────────

    @Test
    void routerController_returnsNonNullBean() {
        RouterController ctrl = config.routerController(racerRouterService);
        assertThat(ctrl).isNotNull();
    }

    // ── Channel registry controller ──────────────────────────────────────────

    @Test
    void channelRegistryController_returnsNonNullBean() {
        ChannelRegistryController ctrl = config.channelRegistryController(registry);
        assertThat(ctrl).isNotNull();
    }

    // ── Retention controller ─────────────────────────────────────────────────

    @Test
    void retentionController_returnsNonNullBean() {
        RetentionController ctrl = config.retentionController(retentionService);
        assertThat(ctrl).isNotNull();
    }

    // ── Admin controller ─────────────────────────────────────────────────────

    @Test
    void racerAdminController_withNoCbRegistry_returnsNonNullBean() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RacerCircuitBreakerRegistry> cbProvider = mock(ObjectProvider.class);
        when(cbProvider.getIfAvailable()).thenReturn(null);

        RacerAdminController ctrl = config.racerAdminController(registry, racerProperties, cbProvider);
        assertThat(ctrl).isNotNull();
    }

    @Test
    void racerAdminController_withCbRegistry_returnsNonNullBean() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RacerCircuitBreakerRegistry> cbProvider = mock(ObjectProvider.class);
        RacerCircuitBreakerRegistry cbRegistry = mock(RacerCircuitBreakerRegistry.class);
        when(cbProvider.getIfAvailable()).thenReturn(cbRegistry);

        RacerAdminController ctrl = config.racerAdminController(registry, racerProperties, cbProvider);
        assertThat(ctrl).isNotNull();
    }
}
