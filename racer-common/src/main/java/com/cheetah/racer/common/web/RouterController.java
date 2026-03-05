package com.cheetah.racer.common.web;

import com.cheetah.racer.common.router.RacerRouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing read-only visibility into the Racer content-based router.
 *
 * <p>Only registered when {@code racer.web.router-enabled=true} and a
 * {@link RacerRouterService} bean is present.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/router/rules} — list all compiled routing rules</li>
 *   <li>{@code POST /api/router/test}  — dry-run: evaluate routing without publishing</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/router")
@RequiredArgsConstructor
public class RouterController {

    private final RacerRouterService racerRouterService;

    /**
     * GET /api/router/rules
     * Returns descriptions of every compiled routing rule.
     */
    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getRules() {
        List<String> descriptions = racerRouterService.getRuleDescriptions();
        return ResponseEntity.ok(Map.of(
                "ruleCount", descriptions.size(),
                "rules",     descriptions
        ));
    }

    /**
     * POST /api/router/test
     * Dry-run: evaluate routing rules against the given payload without publishing.
     *
     * @param payload arbitrary JSON object to test against the routing rules
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testRouting(@RequestBody Object payload) {
        String matchedAlias = racerRouterService.dryRun(payload);
        return ResponseEntity.ok(Map.of(
                "matched", matchedAlias != null ? matchedAlias : "null"
        ));
    }
}
