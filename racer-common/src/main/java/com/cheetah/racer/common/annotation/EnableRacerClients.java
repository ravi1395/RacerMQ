package com.cheetah.racer.common.annotation;

import com.cheetah.racer.common.requestreply.RacerClientRegistrar;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enables scanning for {@link RacerClient}-annotated interfaces and registers
 * Spring bean proxies for each one.
 *
 * <p>Place this annotation on a {@code @Configuration} class alongside {@link EnableRacer}.
 *
 * <h3>Example</h3>
 * <pre>
 * &#64;SpringBootApplication
 * &#64;EnableRacer
 * &#64;EnableRacerClients(basePackages = "com.example.clients")
 * public class MyApp { ... }
 * </pre>
 *
 * @see RacerClient
 * @see RacerRequestReply
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RacerClientRegistrar.class)
public @interface EnableRacerClients {

    /**
     * Base packages to scan for {@link RacerClient}-annotated interfaces.
     * Defaults to the package of the annotated configuration class.
     */
    String[] basePackages() default {};

    /**
     * Type-safe alternative to {@link #basePackages()}.
     * The package of each specified class is used for scanning.
     */
    Class<?>[] basePackageClasses() default {};
}
