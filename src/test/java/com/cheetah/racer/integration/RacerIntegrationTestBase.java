package com.cheetah.racer.integration;

import com.cheetah.racer.config.RacerAutoConfiguration;
import com.cheetah.racer.config.RedisConfig;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;

/**
 * Abstract base for all Racer integration tests.
 *
 * <p>Starts a Redis 7 container via the Docker CLI (avoiding docker-java API
 * version incompatibilities with Docker Desktop 29.x), configures Spring Data
 * Redis to connect to it, and imports the core Racer auto-configurations so
 * the full listener/publisher stack is wired up.
 */
@SpringBootTest(
        classes = RacerIntegrationTestBase.TestBoot.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
abstract class RacerIntegrationTestBase {

    // ── Redis container managed via Docker CLI ───────────────────────────────

    static final DockerRedisExtension REDIS = new DockerRedisExtension();

    @RegisterExtension
    static final DockerRedisExtension redisExtension = REDIS;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", REDIS::getPort);
    }

    // ── Minimal Spring Boot application bootstrap ────────────────────────────

    @SpringBootApplication(scanBasePackages = {})
    @Import({ RedisConfig.class, TestInfraConfig.class, RacerAutoConfiguration.class })
    static class TestBoot {}

    @TestConfiguration
    static class TestInfraConfig {
        @Bean
        ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer(
                ReactiveRedisConnectionFactory factory) {
            return new ReactiveRedisMessageListenerContainer(factory);
        }
    }

    // ── Shared autowired beans ───────────────────────────────────────────────

    @Autowired
    protected ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate;

    @Autowired
    protected DeadLetterQueueService dlqService;

    @Autowired
    protected ObjectMapper objectMapper;

    // ── Test helper methods ──────────────────────────────────────────────────

    protected void publishPubSub(String channel, RacerMessage msg) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            reactiveStringRedisTemplate.convertAndSend(channel, json).block();
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish test message", e);
        }
    }

    protected void clearDlq() {
        reactiveStringRedisTemplate.delete("racer:dlq").block();
    }

    protected void xaddToStream(String streamKey, RacerMessage msg) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            reactiveStringRedisTemplate.opsForStream()
                    .add(streamKey, java.util.Map.of("data", json))
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Failed to XADD test message", e);
        }
    }

    protected static RacerMessage message(String channel, String payload) {
        return RacerMessage.create(channel, payload, "integration-test");
    }

    // ── JUnit 5 extension: start/stop Redis via Docker CLI ──────────────────

    /**
     * JUnit 5 extension that starts a Redis container using the Docker CLI,
     * avoiding docker-java API version incompatibilities with Docker Desktop 29.x.
     */
    static class DockerRedisExtension
            implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

        private static final ExtensionContext.Namespace NS =
                ExtensionContext.Namespace.create(DockerRedisExtension.class);

        private static volatile int mappedPort = 0;
        private static volatile String containerId;
        private static volatile boolean started = false;

        @Override
        public void beforeAll(ExtensionContext ctx) throws Exception {
            if (started) return;
            synchronized (DockerRedisExtension.class) {
                if (started) return;
                Assumptions.assumeTrue(isDockerAvailable(),
                        "Docker not available — skipping integration tests");
                start();
                started = true;
                // Register for cleanup at the root context
                ctx.getRoot().getStore(NS).put("redis", this);
            }
        }

        private static boolean isDockerAvailable() {
            try {
                Process p = new ProcessBuilder("docker", "info")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                return p.waitFor() == 0;
            } catch (Exception e) {
                return false;
            }
        }

        private void start() throws IOException, InterruptedException {
            String name = "racer-it-redis-" + UUID.randomUUID().toString().substring(0, 8);
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "-d", "--rm",
                    "-p", "0:6379",
                    "--name", name,
                    "redis:7-alpine"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("Failed to start Redis container: " + output);
            }
            // docker run -d prints just the full container ID
            containerId = output.lines().filter(l -> l.length() >= 12).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No container ID in: " + output));
            mappedPort = resolvePort(name);
        }

        private int resolvePort(String name) throws IOException, InterruptedException {
            for (int i = 0; i < 10; i++) {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "port", name, "6379"
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                if (!out.isEmpty()) {
                    // output is like "0.0.0.0:55123" or ":::55123"
                    String portStr = out.contains(":") ? out.substring(out.lastIndexOf(':') + 1) : out;
                    return Integer.parseInt(portStr.trim());
                }
                Thread.sleep(200);
            }
            throw new IllegalStateException("Could not determine mapped Redis port");
        }

        @Override
        public void close() {
            if (containerId != null && !containerId.isEmpty()) {
                try {
                    new ProcessBuilder("docker", "stop", containerId)
                            .inheritIO().start().waitFor();
                } catch (Exception ignored) {}
            }
        }

        int getPort() {
            return mappedPort;
        }
    }
}
