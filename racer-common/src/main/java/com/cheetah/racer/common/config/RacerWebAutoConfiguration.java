package com.cheetah.racer.common.config;

import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import com.cheetah.racer.common.router.RacerRouterService;
import com.cheetah.racer.common.service.DeadLetterQueueService;
import com.cheetah.racer.common.service.DlqReprocessorService;
import com.cheetah.racer.common.service.RacerRetentionService;
import com.cheetah.racer.common.web.ChannelRegistryController;
import com.cheetah.racer.common.web.DlqController;
import com.cheetah.racer.common.web.RetentionController;
import com.cheetah.racer.common.web.RouterController;
import com.cheetah.racer.common.web.SchemaController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.DispatcherHandler;

/**
 * Optional web-layer auto-configuration for Racer.
 *
 * <p>Each controller is independently opt-in via a {@code racer.web.*-enabled=true}
 * property, allowing library consumers to expose only the endpoints that make sense
 * for their deployment.
 *
 * <p>All beans are gated on both:
 * <ul>
 *   <li>{@code @ConditionalOnWebApplication(REACTIVE)} — only activates when Spring
 *       WebFlux is on the classpath and an active web context is running.</li>
 *   <li>A per-controller {@code racer.web.*-enabled=true} property.</li>
 * </ul>
 *
 * <h3>Example configuration</h3>
 * <pre>
 * racer:
 *   web:
 *     dlq-enabled: true        # expose  GET/POST/DELETE /api/dlq/**
 *     schema-enabled: true     # expose  GET /api/schema/**  POST /api/schema/validate
 *     router-enabled: true     # expose  GET /api/router/rules  POST /api/router/test
 *     channels-enabled: true   # expose  GET /api/channels
 *     retention-enabled: true  # expose  GET /api/retention/config  POST /api/retention/trim
 * </pre>
 */
@Configuration
@ConditionalOnClass(DispatcherHandler.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class RacerWebAutoConfiguration {

    // ── DLQ controller ───────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.dlq-enabled", havingValue = "true")
    @ConditionalOnBean({DeadLetterQueueService.class, DlqReprocessorService.class, RacerRetentionService.class})
    public DlqController dlqController(
            DeadLetterQueueService dlqService,
            DlqReprocessorService reprocessorService,
            RacerRetentionService retentionService) {
        return new DlqController(dlqService, reprocessorService, retentionService);
    }

    // ── Schema controller ────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.schema-enabled", havingValue = "true")
    public SchemaController schemaController() {
        return new SchemaController();
    }

    // ── Router controller ────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.router-enabled", havingValue = "true")
    @ConditionalOnBean(RacerRouterService.class)
    public RouterController routerController(RacerRouterService racerRouterService) {
        return new RouterController(racerRouterService);
    }

    // ── Channel registry controller (read-only) ──────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.channels-enabled", havingValue = "true")
    @ConditionalOnBean(RacerPublisherRegistry.class)
    public ChannelRegistryController channelRegistryController(RacerPublisherRegistry registry) {
        return new ChannelRegistryController(registry);
    }

    // ── Retention controller ─────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.retention-enabled", havingValue = "true")
    @ConditionalOnBean(RacerRetentionService.class)
    public RetentionController retentionController(RacerRetentionService retentionService) {
        return new RetentionController(retentionService);
    }
}
