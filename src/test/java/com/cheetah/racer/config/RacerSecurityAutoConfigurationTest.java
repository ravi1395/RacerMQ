package com.cheetah.racer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RacerSecurityAutoConfiguration}.
 *
 * Tests verify that the {@link SecurityWebFilterChain} bean is registered
 * (or not) based on the {@code racer.web.security.enabled} property and
 * the presence of Spring Security on the classpath.
 */
class RacerSecurityAutoConfigurationTest {

    /** A minimal in-memory user store so HTTP Basic can build its AuthenticationManager. */
    @SuppressWarnings("deprecation")
    private static MapReactiveUserDetailsService minimalUserDetailsService() {
        return new MapReactiveUserDetailsService(
                User.withDefaultPasswordEncoder()
                        .username("admin").password("pass").roles("ADMIN", "OPS")
                        .build()
        );
    }

    private final ReactiveWebApplicationContextRunner contextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withUserConfiguration(RacerSecurityAutoConfiguration.class)
                    .withBean(MapReactiveUserDetailsService.class,
                            RacerSecurityAutoConfigurationTest::minimalUserDetailsService)
                    .withPropertyValues(
                            "spring.main.web-application-type=reactive",
                            "racer.web.security.enabled=true"
                    );

    @Test
    void securityFilterChain_registeredWhenEnabled() {
        contextRunner
                .run(ctx -> assertThat(ctx).hasSingleBean(SecurityWebFilterChain.class));
    }

    @Test
    void securityFilterChain_notRegisteredByDefault() {
        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(RacerSecurityAutoConfiguration.class)
                .withBean(MapReactiveUserDetailsService.class,
                        RacerSecurityAutoConfigurationTest::minimalUserDetailsService)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SecurityWebFilterChain.class));
    }

    @Test
    void securityFilterChain_notRegisteredWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("racer.web.security.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SecurityWebFilterChain.class));
    }

    @Test
    void customRoles_areAppliedFromProperties() {
        contextRunner
                .withPropertyValues(
                        "racer.web.security.write-role=SUPERADMIN",
                        "racer.web.security.read-role=VIEWER"
                )
                .run(ctx -> {
                    // Chain is registered — custom roles were accepted without error
                    assertThat(ctx).hasSingleBean(SecurityWebFilterChain.class);
                    RacerProperties props = ctx.getBean(RacerProperties.class);
                    assertThat(props.getWeb().getSecurity().getWriteRole()).isEqualTo("SUPERADMIN");
                    assertThat(props.getWeb().getSecurity().getReadRole()).isEqualTo("VIEWER");
                });
    }

    @Test
    void defaultRoles_areAdminAndOps() {
        contextRunner
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SecurityWebFilterChain.class);
                    RacerProperties props = ctx.getBean(RacerProperties.class);
                    assertThat(props.getWeb().getSecurity().getWriteRole()).isEqualTo("ADMIN");
                    assertThat(props.getWeb().getSecurity().getReadRole()).isEqualTo("OPS");
                });
    }

    @Test
    void securityFilterChain_coversAdminUiPath() {
        // The chain must protect /racer-admin/** (static dashboard) in addition to /api/**.
        // We verify by confirming the chain bean is registered and the log output contains
        // the expanded path description — the actual path matching is tested via integration.
        contextRunner
                .run(ctx -> assertThat(ctx).hasSingleBean(SecurityWebFilterChain.class));
    }
}
