package com.cheetah.racer.demo.router;

import com.cheetah.racer.common.annotation.RacerRoute;
import com.cheetah.racer.common.annotation.RacerRouteRule;
import org.springframework.stereotype.Service;

/**
 * Demonstrates content-based routing via {@code @RacerRoute}.
 *
 * <p>When a message arrives on any Racer channel, the {@code RacerRouterService}
 * inspects incoming payload fields and re-publishes to the first matching channel
 * alias.  No methods are required on this class — it is purely a rule container.
 *
 * <p>Rules below route messages based on the {@code type} field:
 * <ul>
 *   <li>{@code ORDER.*}   → {@code orders} channel alias</li>
 *   <li>{@code NOTIFY.*}  → default channel (racer:messages)</li>
 * </ul>
 */
@Service
@RacerRoute({
        @RacerRouteRule(field = "type", matches = "ORDER.*",  to = "orders",  sender = "demo-router"),
        @RacerRouteRule(field = "type", matches = "NOTIFY.*", to = "default", sender = "demo-router")
})
public class DemoRouter {
    // Rule container — no methods needed.
}
