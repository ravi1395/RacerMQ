package com.cheetah.racer.integration;

import com.cheetah.racer.annotation.RacerRequestReply;
import com.cheetah.racer.annotation.RacerResponder;
import com.cheetah.racer.model.RacerRequest;
import com.cheetah.racer.requestreply.RacerClientFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Integration tests for Racer request-reply over Redis Pub/Sub.
 *
 * <p>Verifies the full roundtrip:
 * <ol>
 *   <li>Client proxy publishes a {@link com.cheetah.racer.model.RacerRequest} to a channel.</li>
 *   <li>A {@code @RacerResponder} handler receives the request, processes it, and publishes
 *       a {@link com.cheetah.racer.model.RacerReply} to the correlating reply channel.</li>
 *   <li>The client proxy receives the reply and returns it to the caller.</li>
 * </ol>
 */
@Import(RequestReplyIntegrationTest.RequestReplyConfig.class)
class RequestReplyIntegrationTest extends RacerIntegrationTestBase {

    static final String REQUEST_CHANNEL = "racer:it:rr-request";

    // ── @RacerClient interface ───────────────────────────────────────────────

    interface EchoClient {
        @RacerRequestReply(channel = REQUEST_CHANNEL, timeout = "10s")
        Mono<String> echo(String payload);
    }

    // ── @RacerResponder handler ──────────────────────────────────────────────

    static class EchoResponder {

        @RacerResponder(channel = REQUEST_CHANNEL)
        public String onRequest(RacerRequest request) {
            // Echo the payload back with a prefix so the test can verify it
            return "echo:" + request.getPayload();
        }
    }

    // ── Test configuration ───────────────────────────────────────────────────

    @TestConfiguration
    static class RequestReplyConfig {

        @Bean
        EchoResponder echoResponder() {
            return new EchoResponder();
        }

        /**
         * Register a {@link RacerClientFactoryBean} for the {@link EchoClient} interface.
         * Spring's field injection ({@code @Autowired}) will populate the factory bean's
         * collaborators before {@code getObject()} is called.
         */
        @Bean
        RacerClientFactoryBean<EchoClient> echoClientFactoryBean() {
            return new RacerClientFactoryBean<>(EchoClient.class);
        }
    }

    // ── Autowired client proxy ───────────────────────────────────────────────

    @Autowired
    private EchoClient echoClient;

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void pubSubRequestReply_roundtrip() {
        StepVerifier.create(echoClient.echo("hello-rr"))
                .expectNext("echo:hello-rr")
                .verifyComplete();
    }

    @Test
    void pubSubRequestReply_multipleRequests_allSucceed() {
        Mono<String> r1 = echoClient.echo("req-1");
        Mono<String> r2 = echoClient.echo("req-2");

        StepVerifier.create(r1).expectNext("echo:req-1").verifyComplete();
        StepVerifier.create(r2).expectNext("echo:req-2").verifyComplete();
    }
}
