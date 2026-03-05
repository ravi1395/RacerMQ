package com.cheetah.racer.demo;

import com.cheetah.racer.common.annotation.EnableRacer;
import com.cheetah.racer.common.annotation.EnableRacerClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Racer Demo Application — combined showcase of all Racer annotations.
 *
 * <p>Demonstrations included:
 * <ul>
 *   <li>{@code @RacerListener}      — Pub/Sub consumer</li>
 *   <li>{@code @RacerStreamListener}— Durable Redis Streams consumer</li>
 *   <li>{@code @PublishResult}      — AOP-driven channel publisher</li>
 *   <li>{@code @RacerPublisher}     — Injected channel publisher</li>
 *   <li>{@code @RacerResponder}     — Request-reply responder</li>
 *   <li>{@code @RacerClient}        — Request-reply proxy caller (via {@code @EnableRacerClients})</li>
 *   <li>{@code @RacerRoute}         — Content-based message router</li>
 *   <li>{@code @RacerPoll}          — Scheduled polling publisher</li>
 * </ul>
 *
 * <p>Optional REST endpoints are exposed through {@code racer.web.*-enabled} properties.
 * See {@code application.yaml} for the full configuration reference.
 */
@SpringBootApplication
@EnableRacer
@EnableScheduling
@EnableRacerClients(basePackages = "com.cheetah.racer.demo.client")
public class RacerDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RacerDemoApplication.class, args);
    }
}
