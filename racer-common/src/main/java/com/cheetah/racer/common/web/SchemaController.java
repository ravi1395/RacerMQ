package com.cheetah.racer.common.web;

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
 * REST API for inspecting the Racer Schema Registry.
 *
 * <p>Only registered when {@code racer.web.schema-enabled=true}.  When
 * {@code racer.schema.enabled=false} (no {@link RacerSchemaRegistry} bean), all
 * endpoints still respond – they return a formatted "schema disabled" response
 * rather than a 404/500.
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

    /**
     * GET /api/schema
     * Lists all schemas registered in the {@link RacerSchemaRegistry}.
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
                "count",   definitions.size(),
                "schemas", definitions
        )));
    }

    /**
     * GET /api/schema/{alias}
     * Returns the raw JSON Schema document for the given alias or channel name.
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
        return Mono.just(ResponseEntity.ok((Object) Map.of(
                "alias",  alias,
                "schema", schemaJson
        )));
    }

    /**
     * POST /api/schema/validate
     * Validates an ad-hoc payload against the schema registered for the given channel.
     *
     * <p>Request body: {@code { "channel": "...", "payload": {...} }}
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

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> schemaDisabledResponse() {
        return Map.of(
                "enabled", false,
                "message", "Schema registry is disabled. Set racer.schema.enabled=true to activate."
        );
    }
}
