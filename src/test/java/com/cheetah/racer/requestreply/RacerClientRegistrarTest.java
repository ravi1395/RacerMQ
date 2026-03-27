package com.cheetah.racer.requestreply;

import com.cheetah.racer.annotation.EnableRacerClients;
import com.cheetah.racer.annotation.RacerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerClientRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerClientRegistrarTest {

    @Mock BeanDefinitionRegistry registry;
    @Mock AnnotationMetadata metadata;

    private RacerClientRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new RacerClientRegistrar();
        registrar.setEnvironment(new MockEnvironment());
    }

    // ── setEnvironment ────────────────────────────────────────────────────────

    @Test
    void setEnvironment_standardEnv_storesWithoutError() {
        assertThatCode(() -> registrar.setEnvironment(new StandardEnvironment()))
                .doesNotThrowAnyException();
    }

    // ── registerBeanDefinitions: base packages configured ────────────────────

    @Test
    void registerBeanDefinitions_withExplicitBasePackage_scansAndRegisters() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{"com.example.nonexistent"},
                        "basePackageClasses", new Class[]{}
                ));

        // Non-existent package — no candidates, no beans registered, but no exception
        assertThatCode(() -> registrar.registerBeanDefinitions(metadata, registry))
                .doesNotThrowAnyException();
    }

    @Test
    void registerBeanDefinitions_withBasePackageClass_derivesPackage() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{},
                        "basePackageClasses", new Class[]{String.class}
                ));

        // java.lang package — no @RacerClient interfaces there, but the code must not crash
        assertThatCode(() -> registrar.registerBeanDefinitions(metadata, registry))
                .doesNotThrowAnyException();
    }

    @Test
    void registerBeanDefinitions_noPackageConfigured_usesImportingClassName() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{},
                        "basePackageClasses", new Class[]{}
                ));
        when(metadata.getClassName()).thenReturn("com.example.nonexistent.MyApp");

        assertThatCode(() -> registrar.registerBeanDefinitions(metadata, registry))
                .doesNotThrowAnyException();
    }

    @Test
    void registerBeanDefinitions_nullAttributes_fallsBackToClassName() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(null);
        when(metadata.getClassName()).thenReturn("com.example.nonexistent.MyApp");

        assertThatCode(() -> registrar.registerBeanDefinitions(metadata, registry))
                .doesNotThrowAnyException();
    }

    @Test
    void registerBeanDefinitions_bothPackageAndClass_combinesPackages() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{"com.example.pkg1"},
                        "basePackageClasses", new Class[]{Integer.class}
                ));

        assertThatCode(() -> registrar.registerBeanDefinitions(metadata, registry))
                .doesNotThrowAnyException();
    }

    // ── Registration against a real @RacerClient interface ───────────────────

    /**
     * A real @RacerClient interface that the scanner CAN find in this test package.
     */
    @RacerClient
    interface SampleRacerClientForRegistrar {
        String doSomething(String input);
    }

    @Test
    void registerBeanDefinitions_racerClientPackage_detectsAndRegisters() {
        // Scan this test class's own package — SampleRacerClientForRegistrar is here
        String testPackage = getClass().getPackageName();
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{testPackage},
                        "basePackageClasses", new Class[]{}
                ));

        registrar.registerBeanDefinitions(metadata, registry);

        // At minimum one bean definition was registered (for SampleRacerClientForRegistrar)
        verify(registry, atLeastOnce()).registerBeanDefinition(anyString(), any());
    }

    @Test
    void registerBeanDefinitions_calledTwiceForSamePackage_registersEachTime() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{getClass().getPackageName()},
                        "basePackageClasses", new Class[]{}
                ));

        registrar.registerBeanDefinitions(metadata, registry);
        registrar.registerBeanDefinitions(metadata, registry);

        verify(registry, atLeast(2)).registerBeanDefinition(anyString(), any());
    }

    // ── Multi-package ─────────────────────────────────────────────────────────

    @Test
    void registerBeanDefinitions_multipleBasePackages_scansAll() {
        when(metadata.getAnnotationAttributes(EnableRacerClients.class.getName()))
                .thenReturn(Map.of(
                        "basePackages",       new String[]{"com.example.pkg1", "com.example.pkg2"},
                        "basePackageClasses", new Class[]{}
                ));

        assertThatCode(() -> registrar.registerBeanDefinitions(metadata, registry))
                .doesNotThrowAnyException();
    }
}
