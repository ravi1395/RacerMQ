package com.cheetah.racer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Optional Spring Security auto-configuration for Racer web endpoints.
 *
 * <p>This configuration activates only when <em>all</em> of the following are true:
 * <ol>
 *   <li>{@code spring-security-web} is on the classpath (i.e. the consuming app
 *       has added {@code spring-boot-starter-security}).</li>
 *   <li>The application is a reactive web application (WebFlux).</li>
 *   <li>{@code racer.web.security.enabled=true} is set in application properties.</li>
 * </ol>
 *
 * <p>When active, it registers a {@link SecurityWebFilterChain} at {@link Order} {@code 99}
 * that applies role-based access control to all {@code /api/**} paths:
 * <ul>
 *   <li><strong>Write role</strong> ({@code racer.web.security.write-role}, default {@code ADMIN}):
 *       required for all mutating operations —
 *       {@code POST /api/dlq/republish/**}, {@code DELETE /api/dlq/**},
 *       {@code POST /api/retention/trim}.</li>
 *   <li><strong>Read role</strong> ({@code racer.web.security.read-role}, default {@code OPS}):
 *       required for all remaining read-only {@code GET} and {@code POST} calls
 *       under {@code /api/**}.</li>
 * </ul>
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * racer:
 *   web:
 *     dlq-enabled: true
 *     security:
 *       enabled: true
 *       write-role: ADMIN     # default
 *       read-role: OPS        # default
 * </pre>
 *
 * <p>If you need a different security model (e.g. JWT bearer tokens, custom matchers),
 * leave {@code racer.web.security.enabled=false} and write your own
 * {@link SecurityWebFilterChain} bean that covers the {@code /api/**} paths.
 */
@Configuration
@EnableWebFluxSecurity
@AutoConfigureAfter(RacerWebAutoConfiguration.class)
@ConditionalOnClass(SecurityWebFilterChain.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "racer.web.security.enabled", havingValue = "true")
@EnableConfigurationProperties(RacerProperties.class)
@Slf4j
public class RacerSecurityAutoConfiguration {

    /**
     * Builds a {@link SecurityWebFilterChain} that protects all {@code /api/**} routes
     * with role-based access control.
     *
     * <p>Ordering at {@code 99} ensures the Racer chain runs after any higher-priority
     * application chain (order 0–98) that the consuming app may define.
     *
     * @param http           the reactive security DSL
     * @param racerProperties Racer configuration containing role names
     * @return the configured filter chain
     */
    @Bean
    @Order(99)
    public SecurityWebFilterChain racerSecurityFilterChain(
            ServerHttpSecurity http,
            RacerProperties racerProperties) {

        RacerProperties.WebProperties.SecurityProperties sec =
                racerProperties.getWeb().getSecurity();

        String writeRole = sec.getWriteRole();
        String readRole  = sec.getReadRole();

        log.info("[racer-security] Protecting /api/** — write role: '{}', read role: '{}'",
                writeRole, readRole);

        return http
                .securityMatcher(org.springframework.security.web.server.util.matcher
                        .ServerWebExchangeMatchers.pathMatchers("/api/**"))
                .authorizeExchange(exchanges -> exchanges
                        // Mutating DLQ operations
                        .pathMatchers(HttpMethod.POST, "/api/dlq/republish/**").hasRole(writeRole)
                        .pathMatchers(HttpMethod.DELETE, "/api/dlq/**").hasRole(writeRole)
                        // Mutating retention operation
                        .pathMatchers(HttpMethod.POST, "/api/retention/trim").hasRole(writeRole)
                        // All remaining /api/** endpoints (read-only GETs + schema validate)
                        .anyExchange().hasRole(readRole)
                )
                .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)   // stateless REST API — CSRF not applicable
                .build();
    }
}
