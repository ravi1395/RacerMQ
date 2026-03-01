package com.cheetah.racer.server.controller;

import com.cheetah.racer.common.schema.RacerSchemaRegistry;
import com.cheetah.racer.common.schema.SchemaViolation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for inspecting and exercising the R-7 Schema Registry.
 *
 * <p>All endpoints are gracefully disabled when {@code racer.schema.enabled=false}
 * (the {@link RacerSchemaRegistry} bean is simply absent).
 *
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /api/schema              – list all registered schemas
 * GET  /api/schema/{alias}      – fetch raw JSON Schema for alias
 * POST /api/schema/validate     – ad-hoc validation { "channel": "...", "payload": {...} }
 * </pre>
 */
@RestController
@RequestMapping("/api/schema")
@Slf4j
public class SchemaController {

    @Nullable
    @Autowired(required = false)
    private RacerSchemaRegistry schemaRegistry;

    // -----------------------------------------------------------------------
    // GET /api/schema
    // -----------------------------------------------------------------------

    /**
     * Lists all schemas registered in the {@link RacerSchemaRegistry}.
     *
     * <pre>
     * curl http://localhost:8080/api/schema
     * </pre>
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> listSchemas() {
        if (schemaRegistry == null) {
            return Mono.just(ResponseEntity.ok(schemaDisabledResponse()));
        }

        Map<String, Object> definitions = new LinkedHashMap<>();
        schemaRegistry.getDefinitions().forEach((alias, def) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("version", def.getVersion());
            info.put("description", def.getDescription());
            info.put("hasLocation", def.getLocation() != null && !def.getLocation().isBlank());
            info.put("hasInline", def.getInline() != null && !def.getInline().isBlank());
            definitions.put(alias, info);
        });

        return Mono.just(ResponseEntity.ok(Map.of(
                "enabled", true,
                "count", definitions.size(),
                "schemas", definitions
        )));
    }

    // -----------------------------------------------------------------------
    // GET /api/schema/{alias}
    // -----------------------------------------------------------------------

    /**
     * Returns the raw JSON Schema document for the given alias or channel name.
     *
     * <pre>
     * curl http://localhost:8080/api/schema/orders
     * </pre>
     */
    @GetMapping("/{alias}")
    public Mono<ResponseEntity<Object>> getSchema(@PathVariable String alias) {
        if (schemaRegistry == null) {
            return Mono.just(ResponseEntity.ok((Object) schemaDisabledResponse()));
        }

        String schemaJson = schemaRegistry.getSchemaJson(alias);
        if (schemaJson == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No schema registered for alias: " + alias)));
        }
        // Return the raw JSON string wrapped in a "schema" field
        return Mono.just(ResponseEntity.ok((Object) Map.of(
                "alias", alias,
                "schema", schemaJson
        )));
    }

    // -----------------------------------------------------------------------
    // POST /api/schema/validate
    // -----------------------------------------------------------------------

    /**
     * Validates an ad-hoc payload against the schema registered for the given channel.
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/schema/validate \
     *      -H 'Content-Type: application/json' \
     *      -d '{"channel":"racer:orders","payload":{"orderId":"123","amount":99.9}}'
     * </pre>
     *
     * @param body map with {@code channel} (String) and {@code payload} (any JSON value)
     */
    @PostMapping("/validate")
    public Mono<ResponseEntity<Map<String, Object>>> validate(@RequestBody Map<String, Object> body) {
        if (schemaRegistry == null) {
            return Mono.just(ResponseEntity.ok(schemaDisabledResponse()));
        }

        String channel = (String) body.get("channel");
        Object payload = body.get("payload");

        if (channel == null || channel.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(
                    Map.of("error", "'channel' field is required")));
        }

        if (!schemaRegistry.hasSchema(channel)) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", "No schema registered for channel: " + channel)));
        }

        List<SchemaViolation> violations = schemaRegistry.validateAdHoc(channel, payload);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel);
        result.put("valid", violations.isEmpty());
        if (!violations.isEmpty()) {
            result.put("violations", violations.stream()
                    .map(v -> Map.of("type", v.type(), "path", v.path(), "message", v.message()))
                    .toList());
        }
        return Mono.just(ResponseEntity.ok(result));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> schemaDisabledResponse() {
        return Map.of(
                "enabled", false,
                "message", "Schema registry is disabled. Set racer.schema.enabled=true to activate."
        );
    }
}
