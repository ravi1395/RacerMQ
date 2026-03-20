package com.cheetah.racer.config;

import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.annotation.PublishResults;
import com.cheetah.racer.exception.RacerConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans all Spring beans at startup for {@code @PublishResult} methods and
 * throws a {@link RacerConfigurationException} if any of them return {@code void}.
 *
 * <p>{@code @PublishResult} works by publishing the <em>return value</em> of the
 * annotated method. A {@code void} return type means there is nothing to publish,
 * which is almost certainly a configuration mistake.
 */
@Slf4j
public class PublishResultMethodValidator implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;

    public PublishResultMethodValidator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> violations = new ArrayList<>();

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = applicationContext.getType(beanName);
            } catch (Exception ignored) {
                continue;
            }
            if (beanType == null) continue;

            ReflectionUtils.doWithMethods(beanType, method -> {
                if (isAnnotatedWithPublishResult(method) && isVoidReturn(method)) {
                    violations.add(beanType.getName() + "#" + method.getName() + "()");
                }
            });
        }

        if (!violations.isEmpty()) {
            throw new RacerConfigurationException(
                    "@PublishResult is declared on void method(s) — there is no return value to publish. " +
                    "Either change the return type or remove the annotation. Violations: " + violations);
        }

        log.debug("[racer] @PublishResult method validation passed");
    }

    private static boolean isAnnotatedWithPublishResult(Method method) {
        return AnnotationUtils.findAnnotation(method, PublishResult.class) != null
                || AnnotationUtils.findAnnotation(method, PublishResults.class) != null;
    }

    private static boolean isVoidReturn(Method method) {
        Class<?> returnType = method.getReturnType();
        return returnType == void.class || returnType == Void.class;
    }
}
