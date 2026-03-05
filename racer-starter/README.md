# racer-starter

Spring Boot Starter for **Racer** — reactive Redis messaging with annotations.

## Usage

Add a single dependency to any Spring Boot project:

```xml
<dependency>
    <groupId>com.cheetah</groupId>
    <artifactId>racer-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.cheetah:racer-starter:0.0.1-SNAPSHOT")
```

## What you get

| Feature | Description |
|---------|-------------|
| `@EnableRacer` | Explicit opt-in (still works, but no longer required) |
| `@RacerPublisher` | Field-inject a `RacerChannelPublisher` for any named channel |
| `@PublishResult` | Auto-publish method return values to Redis channels |
| `@RacerListener` | Declaratively subscribe a method to a Redis Pub/Sub channel |
| `@RacerStreamListener` | Subscribe a method to a Redis Stream with configurable concurrency |
| `@RacerResponder` | Mark a method as a request-reply handler (Pub/Sub or Stream transport) |
| `@RacerClient` / `@EnableRacerClients` | Generate type-safe request-reply client proxies |
| `RacerProperties` | `racer.default-channel`, `racer.channels.<alias>.*` config |
| `RacerPublisherRegistry` | Programmatic access to all registered channel publishers |
| Models | `RacerMessage`, `RacerRequest`, `RacerReply`, `DeadLetterMessage` |
| `ReactiveRedisTemplate` beans | Pre-configured String and JSON templates |

## Minimal example

```properties
# application.properties
spring.data.redis.host=localhost
spring.data.redis.port=6379

racer.default-channel=racer:messages
racer.channels.orders.name=racer:orders
```

```java
@SpringBootApplication
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}

@Service
class OrderService {

    @RacerPublisher("orders")
    private RacerChannelPublisher ordersPublisher;

    public Mono<Void> placeOrder(Order order) {
        return ordersPublisher.publishAsync(order).then();
    }

    @PublishResult(channelRef = "orders")
    public Mono<Order> createOrder(OrderRequest req) {
        return orderRepo.save(req.toOrder());
    }
}
```

## Running the demo

`racer-demo` is the reference application bundled with this repository. It demonstrates
all of the above features with sample listeners, publishers, and responders on port 8080:

```bash
# from the root of the Racer repository
mvn -pl :racer-demo spring-boot:run
```

The demo requires Redis to be running (see `compose.yaml` at the repository root).
