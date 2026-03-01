package com.cheetah.racer.common.schema;

import com.cheetah.racer.common.config.RacerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central schema registry for Racer (R-7 — Schema Validation).
 *
 * <p>Activated only when {@code racer.schema.enabled=true}. Loads JSON Schema definitions
 * from the properties config, then exposes validation methods consumed by publishers and
 * consumers.
 *
 * <h3>Schema registration (application.properties)</h3>
 * <pre>
 * racer.schema.enabled=true
 * racer.schema.validation-mode=BOTH        # PUBLISH | CONSUME | BOTH
 * racer.schema.fail-on-violation=true      # throw or just warn
 *
 * # Schemas keyed by channel alias (must match racer.channels.&lt;alias&gt;)
 * # or by literal Redis channel name
 * racer.schema.schemas.orders.location=classpath:schemas/orders-v1.json
 * racer.schema.schemas.orders.version=1.0
 * racer.schema.schemas.orders.description=Order placement payload
 *
 * # Inline schema (takes precedence over location when both present)
 * racer.schema.schemas.notifications.inline={"type":"object","required":["message"]}
 * </pre>
 *
 * <h3>What is validated</h3>
 * The {@code payload} field inside each {@link com.cheetah.racer.common.model.RacerMessage}
 * envelope is validated — not the outer envelope itself. When the payload is a plain String
 * it is treated as a JSON string and parsed before validation. POJOs are serialized to JSON
 * first via Jackson.
 *
 * <h3>Violation handling</h3>
 * <ul>
 *   <li>{@code fail-on-violation=true} (default) — throws {@link SchemaValidationException},
 *       blocking the publish or triggering DLQ on the consume side.</li>
 *   <li>{@code fail-on-violation=false} — logs violations as {@code WARN} and continues.</li>
 * </ul>
 */
@Slf4j
public class RacerSchemaRegistry {

    private final RacerProperties properties;
    private final ResourceLoader  resourceLoader;
    private final ObjectMapper    objectMapper;

    /** channel-name (or alias) → compiled JsonSchema */
    private final Map<String, JsonSchema> schemaMap = new ConcurrentHashMap<>();

    /** alias/key → definition (for the API layer) */
    private final Map<String, RacerProperties.SchemaDefinition> definitions = new LinkedHashMap<>();

    /** Maps alias → resolved Redis channel name (populated from racer.channels.*) */
    private final Map<String, String> aliasToChannel = new ConcurrentHashMap<>();

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public RacerSchemaRegistry(RacerProperties properties,
                                ResourceLoader resourceLoader,
                                ObjectMapper objectMapper) {
        this.properties    = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper  = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Build alias → channel-name reverse map from racer.channels.*
        properties.getChannels().forEach((alias, ch) -> {
            if (ch.getName() != null && !ch.getName().isBlank()) {
                aliasToChannel.put(alias, ch.getName());
            }
        });

        int loaded = 0;
        for (Map.Entry<String, RacerProperties.SchemaDefinition> entry :
                properties.getSchema().getSchemas().entrySet()) {

            String key = entry.getKey();   // alias or literal channel name
            RacerProperties.SchemaDefinition def = entry.getValue();

            try {
                JsonSchema schema = loadSchema(def);
                // Store under both the alias key and the resolved channel name
                schemaMap.put(key, schema);
                String resolvedChannel = aliasToChannel.getOrDefault(key, key);
                if (!resolvedChannel.equals(key)) {
                    schemaMap.put(resolvedChannel, schema);
                }
                definitions.put(key, def);
                log.info("[racer-schema] Schema '{}' v{} registered for key '{}'",
                        def.getDescription().isBlank() ? key : def.getDescription(),
                        def.getVersion(), key);
                loaded++;
            } catch (Exception e) {
                log.error("[racer-schema] Failed to load schema for key '{}': {}", key, e.getMessage());
            }
        }
        log.info("[racer-schema] Schema registry ready — {} schema(s) loaded. mode={} failOnViolation={}",
                loaded,
                properties.getSchema().getValidationMode(),
                properties.getSchema().isFailOnViolation());
    }

    // -------------------------------------------------------------------------
    // Validation API
    // -------------------------------------------------------------------------

    /**
     * Validates {@code payload} against the schema registered for {@code channelName} on
     * the <b>publish</b> path.
     *
     * <p>No-ops when:
     * <ul>
     *   <li>No schema is registered for the channel.</li>
     *   <li>{@code validation-mode} is {@link RacerProperties.SchemaValidationMode#CONSUME}.</li>
     * </ul>
     *
     * @throws SchemaValidationException if violations are found and {@code fail-on-violation=true}
     */
    public void validateForPublish(String channelName, Object payload) {
        if (properties.getSchema().getValidationMode() ==
                RacerProperties.SchemaValidationMode.CONSUME) {
            return;
        }
        validate(channelName, payload, "publish");
    }

    /**
     * Validates {@code payload} against the schema registered for {@code channelName} on
     * the <b>consume</b> path.
     *
     * @throws SchemaValidationException if violations are found and {@code fail-on-violation=true}
     */
    public void validateForConsume(String channelName, Object payload) {
        if (properties.getSchema().getValidationMode() ==
                RacerProperties.SchemaValidationMode.PUBLISH) {
            return;
        }
        validate(channelName, payload, "consume");
    }

    /**
     * Returns {@code true} if a schema is registered for the given channel name or alias.
     */
    public boolean hasSchema(String channelName) {
        return schemaMap.containsKey(channelName);
    }

    /**
     * Returns a snapshot of all registered alias/key → definition mappings.
     * Used by the schema REST API.
     */
    public Map<String, RacerProperties.SchemaDefinition> getDefinitions() {
        return Map.copyOf(definitions);
    }

    /**
     * Returns the raw schema JSON string for a given alias/channel key,
     * or {@code null} when no schema is registered for that key.
     */
    public String getSchemaJson(String key) {
        JsonSchema schema = schemaMap.get(key);
        if (schema == null) return null;
        try {
            return objectMapper.writeValueAsString(schema.getSchemaNode());
        } catch (Exception e) {
            return schema.toString();
        }
    }

    /**
     * Ad-hoc validation — returns violations without throwing.
     * Used by the {@code POST /api/schema/validate} endpoint.
     */
    public List<SchemaViolation> validateAdHoc(String channelKey, Object payload) {
        JsonSchema schema = schemaMap.get(channelKey);
        if (schema == null) {
            return List.of();
        }
        try {
            String json = toJsonString(payload);
            JsonNode node = objectMapper.readTree(json);
            Set<ValidationMessage> messages = schema.validate(node);
            return messages.stream()
                    .map(m -> new SchemaViolation(
                            extractType(m.getType()),
                            m.getInstanceLocation().toString(),
                            m.getMessage()))
                    .toList();
        } catch (Exception e) {
            return List.of(new SchemaViolation("parse-error", "$", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void validate(String channelName, Object payload, String direction) {
        JsonSchema schema = schemaMap.get(channelName);
        if (schema == null) {
            return; // no schema registered — pass-through
        }
        List<SchemaViolation> violations = new ArrayList<>();
        try {
            String json   = toJsonString(payload);
            JsonNode node = objectMapper.readTree(json);
            Set<ValidationMessage> messages = schema.validate(node);
            messages.stream()
                    .map(m -> new SchemaViolation(
                            extractType(m.getType()),
                            m.getInstanceLocation().toString(),
                            m.getMessage()))
                    .forEach(violations::add);
        } catch (SchemaValidationException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[racer-schema] Error during {} validation for channel '{}': {}",
                    direction, channelName, e.getMessage());
            return;
        }

        if (violations.isEmpty()) {
            log.trace("[racer-schema] {} validation passed for channel '{}'", direction, channelName);
            return;
        }

        if (properties.getSchema().isFailOnViolation()) {
            log.warn("[racer-schema] {} validation FAILED for channel '{}' — {} violation(s)",
                    direction, channelName, violations.size());
            throw new SchemaValidationException(channelName, violations);
        } else {
            log.warn("[racer-schema] {} validation warnings for channel '{}': {}",
                    direction, channelName, violations);
        }
    }

    private JsonSchema loadSchema(RacerProperties.SchemaDefinition def) throws Exception {
        // Inline JSON takes precedence
        if (def.getInline() != null && !def.getInline().isBlank()) {
            JsonNode node = objectMapper.readTree(def.getInline());
            return FACTORY.getSchema(node);
        }
        if (def.getLocation() == null || def.getLocation().isBlank()) {
            throw new IllegalArgumentException("Schema definition must have either 'inline' or 'location'");
        }
        Resource resource = resourceLoader.getResource(def.getLocation());
        if (!resource.exists()) {
            throw new IllegalArgumentException("Schema resource not found: " + def.getLocation());
        }
        try (InputStream is = resource.getInputStream()) {
            return FACTORY.getSchema(is);
        }
    }

    private String toJsonString(Object payload) throws Exception {
        if (payload == null) return "null";
        if (payload instanceof String s) {
            // Verify it's valid JSON; if not, wrap as a JSON string value
            try {
                objectMapper.readTree(s);
                return s;
            } catch (Exception e) {
                return objectMapper.writeValueAsString(s);
            }
        }
        return objectMapper.writeValueAsString(payload);
    }

    private static String extractType(String fullType) {
        if (fullType == null) return "unknown";
        int hash = fullType.lastIndexOf('#');
        return hash >= 0 ? fullType.substring(hash + 1) : fullType;
    }
}
