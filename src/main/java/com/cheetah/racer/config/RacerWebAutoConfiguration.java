package com.cheetah.racer.config;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.ratelimit.RacerRateLimiter;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import com.cheetah.racer.service.RacerRetentionService;
import com.cheetah.racer.web.ChannelRegistryController;
import com.cheetah.racer.web.DlqController;
import com.cheetah.racer.web.RacerAdminController;
import com.cheetah.racer.web.RetentionController;
import com.cheetah.racer.web.RouterController;
import com.cheetah.racer.web.SchemaController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.ObjectProvider;
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
 * <h3>Security notice</h3>
 * <p><strong>These endpoints are unauthenticated by default.</strong> Several endpoints
 * are destructive ({@code DELETE /api/dlq/clear}, {@code POST /api/dlq/republish/all},
 * {@code POST /api/retention/trim}) and must be protected before exposing them in
 * production. Configure Spring Security in the consuming application to restrict
 * access to trusted principals — for example:
 * <pre>
 * http.authorizeExchange(ex -&gt; ex
 *     .pathMatchers("/api/dlq/**", "/api/retention/**").hasRole("ADMIN")
 *     .pathMatchers("/api/schema/**", "/api/router/**", "/api/channels/**").hasRole("OPS")
 *     .anyExchange().authenticated());
 * </pre>
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
@AutoConfigureAfter(RacerAutoConfiguration.class)
@ConditionalOnClass(DispatcherHandler.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Slf4j
public class RacerWebAutoConfiguration {

    // ── DLQ controller ───────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.dlq-enabled", havingValue = "true")
    @ConditionalOnBean({DeadLetterQueueService.class, DlqReprocessorService.class})
    public DlqController dlqController(
            DeadLetterQueueService dlqService,
            DlqReprocessorService reprocessorService) {
        log.info("[racer-web] DLQ controller activated at /api/dlq/**");
        return new DlqController(dlqService, reprocessorService);
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

    // ── Admin controller (Phase 4.4) ─────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "racer.web.admin-enabled", havingValue = "true")
    @ConditionalOnBean(RacerPublisherRegistry.class)
    public RacerAdminController racerAdminController(
            RacerPublisherRegistry racerPublisherRegistry,
            RacerProperties racerProperties,
            ObjectProvider<RacerCircuitBreakerRegistry> circuitBreakerRegistryProvider,
            ObjectProvider<RacerRateLimiter> rateLimiterProvider) {
        log.info("[racer-web] Admin controller activated at /api/admin/**");
        return new RacerAdminController(
                racerPublisherRegistry,
                racerProperties,
                circuitBreakerRegistryProvider.getIfAvailable(),
                rateLimiterProvider.getIfAvailable());
    }
}
