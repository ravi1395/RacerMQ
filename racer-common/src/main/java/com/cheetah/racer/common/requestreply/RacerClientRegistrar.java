package com.cheetah.racer.common.requestreply;

import com.cheetah.racer.common.annotation.EnableRacerClients;
import com.cheetah.racer.common.annotation.RacerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans for {@link RacerClient}-annotated interfaces and registers a
 * {@link RacerClientFactoryBean} for each one, enabling Spring to inject typed proxies.
 *
 * <p>Triggered by {@link EnableRacerClients}.
 */
@Slf4j
public class RacerClientRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        Set<String> basePackages = getBasePackages(importingClassMetadata);

        ClassPathScanningCandidateComponentProvider scanner = buildScanner();
        for (String basePackage : basePackages) {
            scanner.findCandidateComponents(basePackage).forEach(candidate -> {
                String className = candidate.getBeanClassName();
                try {
                    Class<?> clientInterface = Class.forName(className);
                    registerClientBean(registry, clientInterface);
                } catch (ClassNotFoundException e) {
                    log.error("[RACER-CLIENT] Cannot load @RacerClient interface '{}': {}", className, e.getMessage());
                }
            });
        }
    }

    private void registerClientBean(BeanDefinitionRegistry registry, Class<?> clientInterface) {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
                .genericBeanDefinition(RacerClientFactoryBean.class);
        builder.addConstructorArgValue(clientInterface);
        builder.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);

        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        beanDefinition.setPrimary(false);

        String beanName = StringUtils.uncapitalize(clientInterface.getSimpleName());
        BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, beanName);
        BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);

        log.info("[RACER-CLIENT] Registered @RacerClient proxy for '{}' as bean '{}'",
                clientInterface.getName(), beanName);
    }

    private Set<String> getBasePackages(AnnotationMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableRacerClients.class.getName());
        Set<String> packages = new HashSet<>();

        if (attrs != null) {
            for (String pkg : (String[]) attrs.get("basePackages")) {
                if (StringUtils.hasText(pkg)) packages.add(pkg);
            }
            for (Class<?> cls : (Class<?>[]) attrs.get("basePackageClasses")) {
                packages.add(ClassUtils.getPackageName(cls));
            }
        }

        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packages;
    }

    private ClassPathScanningCandidateComponentProvider buildScanner() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isInterface();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(RacerClient.class));
        return scanner;
    }
}
