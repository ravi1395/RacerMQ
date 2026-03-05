package com.cheetah.racer.demo.client;

import com.cheetah.racer.common.annotation.RacerClient;
import com.cheetah.racer.common.annotation.RacerRequestReply;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Demonstrates the {@code @RacerClient} proxy mechanism.
 *
 * <p>Annotating an interface with {@code @RacerClient} and enabling scanning via
 * {@code @EnableRacerClients} causes Racer to generate a JDK dynamic proxy at startup.
 * Each method annotated with {@code @RacerRequestReply} is implemented by the proxy,
 * which publishes the call as a request and awaits the correlated reply.
 *
 * <p>Inject this interface anywhere in the application context — Spring will provide
 * the generated proxy transparently.
 *
 * <pre>
 * {@literal @}Autowired
 * private DemoClient demoClient;
 *
 * demoClient.ping("hello")
 *     .subscribe(reply -> log.info("Got reply: {}", reply));
 * </pre>
 */
@RacerClient
public interface DemoClient {

    /**
     * Sends a ping request to {@code racer:demo:ping} and awaits the reply.
     *
     * @param payload the ping body (serialized to JSON)
     * @return reactive reply from the {@link com.cheetah.racer.demo.responder.DemoResponder}
     */
    @RacerRequestReply(channel = "racer:demo:ping", timeout = "5s")
    Mono<Map<String, Object>> ping(Object payload);

    /**
     * Sends an echo request and awaits an identical payload in the reply.
     *
     * @param payload the value to echo
     * @return reactive echo reply
     */
    @RacerRequestReply(channel = "racer:demo:echo", timeout = "3s")
    Mono<Map<String, Object>> echo(Object payload);
}
