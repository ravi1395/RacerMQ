package com.cheetah.racer.schema;

import com.cheetah.racer.config.RacerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerSchemaRegistry}.
 * Uses an inline JSON Schema to avoid file-system dependency.
 */
@ExtendWith(MockitoExtension.class)
class RacerSchemaRegistryTest {

    // Schema: object with required "name" (string) field.
    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"required\":[\"name\"]," +
            "\"properties\":{\"name\":{\"type\":\"string\"}}}";

    private static final String CHANNEL = "test-channel";

    @Mock
    ResourceLoader resourceLoader;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerSchemaRegistry registry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = buildProperties(
                RacerProperties.SchemaValidationMode.BOTH,
                /* failOnViolation */ true);
        registry = new RacerSchemaRegistry(properties, resourceLoader, objectMapper);
        registry.init();
    }

    // ------------------------------------------------------------------
    // init() — schema loading
    // ------------------------------------------------------------------

    @Test
    void init_registersInlineSchema() {
        assertThat(registry.hasSchema(CHANNEL)).isTrue();
    }

    @Test
    void getSchemaJson_returnsNonNullJson() {
        assertThat(registry.getSchemaJson(CHANNEL)).isNotNull();
    }

    @Test
    void getDefinitions_containsRegisteredAlias() {
        assertThat(registry.getDefinitions()).containsKey(CHANNEL);
    }

    // ------------------------------------------------------------------
    // validateForPublish — valid payload
    // ------------------------------------------------------------------

    @Test
    void validateForPublish_validPayload_doesNotThrow() {
        // Should not throw
        registry.validateForPublish(CHANNEL, "{\"name\":\"Alice\"}");
    }

    @Test
    void validateForPublish_validPojoPayload_doesNotThrow() {
        // POJO payload is serialized to JSON before validation
        var payload = Map.of("name", "Bob");
        registry.validateForPublish(CHANNEL, payload);
    }

    // ------------------------------------------------------------------
    // validateForPublish — invalid payload, failOnViolation=true
    // ------------------------------------------------------------------

    @Test
    void validateForPublish_invalidPayload_throwsSchemaValidationException() {
        assertThatThrownBy(() ->
                registry.validateForPublish(CHANNEL, "{\"age\":25}"))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining(CHANNEL);
    }

    @Test
    void validateForPublish_invalidPayload_exceptionContainsViolations() {
        SchemaValidationException ex = null;
        try {
            registry.validateForPublish(CHANNEL, "{\"age\":25}");
        } catch (SchemaValidationException e) {
            ex = e;
        }

        assertThat(ex).isNotNull();
        assertThat(ex.getViolations()).isNotEmpty();  // safe: ex is checked non-null above
        assertThat(ex.getChannel()).isEqualTo(CHANNEL);
    }

    // ------------------------------------------------------------------
    // validateForPublish — invalid payload, failOnViolation=false
    // ------------------------------------------------------------------

    @Test
    void validateForPublish_invalidPayload_warnOnly_doesNotThrow() {
        RacerProperties lenientProps = buildProperties(
                RacerProperties.SchemaValidationMode.BOTH,
                /* failOnViolation */ false);
        RacerSchemaRegistry lenientReg =
                new RacerSchemaRegistry(lenientProps, resourceLoader, objectMapper);
        lenientReg.init();

        // Should log a warning but not throw
        lenientReg.validateForPublish(CHANNEL, "{\"age\":25}");
    }

    // ------------------------------------------------------------------
    // validateForPublish — mode=CONSUME means publish path is skipped
    // ------------------------------------------------------------------

    @Test
    void validateForPublish_whenModeIsConsume_skipsValidation() {
        RacerProperties consumeOnlyProps = buildProperties(
                RacerProperties.SchemaValidationMode.CONSUME,
                /* failOnViolation */ true);
        RacerSchemaRegistry reg =
                new RacerSchemaRegistry(consumeOnlyProps, resourceLoader, objectMapper);
        reg.init();

        // Invalid payload but mode=CONSUME → skips publish-side check → no throw
        reg.validateForPublish(CHANNEL, "{\"age\":25}");
    }

    // ------------------------------------------------------------------
    // validateForConsume — mode=PUBLISH means consume path is skipped
    // ------------------------------------------------------------------

    @Test
    void validateForConsume_whenModeIsPublish_skipsValidation() {
        RacerProperties publishOnlyProps = buildProperties(
                RacerProperties.SchemaValidationMode.PUBLISH,
                /* failOnViolation */ true);
        RacerSchemaRegistry reg =
                new RacerSchemaRegistry(publishOnlyProps, resourceLoader, objectMapper);
        reg.init();

        // Invalid payload but mode=PUBLISH → skips consume-side check → no throw
        reg.validateForConsume(CHANNEL, "{\"age\":25}");
    }

    // ------------------------------------------------------------------
    // validateForConsume — valid / invalid payloads
    // ------------------------------------------------------------------

    @Test
    void validateForConsume_validPayload_doesNotThrow() {
        registry.validateForConsume(CHANNEL, "{\"name\":\"Carol\"}");
    }

    @Test
    void validateForConsume_invalidPayload_throwsSchemaValidationException() {
        assertThatThrownBy(() ->
                registry.validateForConsume(CHANNEL, "{\"wrong\":true}"))
                .isInstanceOf(SchemaValidationException.class);
    }

    // ------------------------------------------------------------------
    // validateAdHoc — returns violations without throwing
    // ------------------------------------------------------------------

    @Test
    void validateAdHoc_validPayload_returnsEmptyList() {
        List<SchemaViolation> violations =
                registry.validateAdHoc(CHANNEL, "{\"name\":\"Dave\"}");
        assertThat(violations).isEmpty();
    }

    @Test
    void validateAdHoc_invalidPayload_returnsViolations() {
        List<SchemaViolation> violations =
                registry.validateAdHoc(CHANNEL, "{\"age\":42}");
        assertThat(violations).isNotEmpty();
    }

    @Test
    void validateAdHoc_unknownChannel_returnsEmptyList() {
        List<SchemaViolation> violations =
                registry.validateAdHoc("no-such-channel", "{\"name\":\"x\"}");
        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------
    // No schema registered — pass-through
    // ------------------------------------------------------------------

    @Test
    void validateForPublish_noSchemaForChannel_doesNotThrow() {
        // Use a channel that was never registered
        registry.validateForPublish("unregistered:channel", "{\"bad\":\"payload\"}");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private RacerProperties buildProperties(
            RacerProperties.SchemaValidationMode mode, boolean failOnViolation) {

        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");
        props.getSchema().setEnabled(true);
        props.getSchema().setValidationMode(mode);
        props.getSchema().setFailOnViolation(failOnViolation);

        RacerProperties.SchemaDefinition def = new RacerProperties.SchemaDefinition();
        def.setInline(SCHEMA_JSON);
        def.setVersion("1.0");
        def.setDescription("Test schema");
        props.getSchema().getSchemas().put(CHANNEL, def);

        return props;
    }

    // ------------------------------------------------------------------
    // validateForPublishReactive
    // ------------------------------------------------------------------

    @Test
    void validateForPublishReactive_validPayload_completesEmpty() {
        StepVerifier.create(registry.validateForPublishReactive(CHANNEL, "{\"name\":\"Eve\"}"))
                .verifyComplete();
    }

    @Test
    void validateForPublishReactive_invalidPayload_emitsError() {
        StepVerifier.create(registry.validateForPublishReactive(CHANNEL, "{\"age\":99}"))
                .expectError(SchemaValidationException.class)
                .verify();
    }

    // ------------------------------------------------------------------
    // init() — alias-to-channel mapping stores schema under both keys
    // ------------------------------------------------------------------

    @Test
    void init_registersSchemaUnderBothAliasAndChannelName() {
        RacerProperties props = new RacerProperties();
        props.getSchema().setEnabled(true);
        props.getSchema().setValidationMode(RacerProperties.SchemaValidationMode.BOTH);
        props.getSchema().setFailOnViolation(true);

        // Set up a channel alias "orders" → "racer:orders"
        RacerProperties.ChannelProperties cp = new RacerProperties.ChannelProperties();
        cp.setName("racer:orders");
        props.getChannels().put("orders", cp);

        // Register schema under the alias key "orders"
        RacerProperties.SchemaDefinition def = new RacerProperties.SchemaDefinition();
        def.setInline(SCHEMA_JSON);
        def.setVersion("1.0");
        props.getSchema().getSchemas().put("orders", def);

        RacerSchemaRegistry reg = new RacerSchemaRegistry(props, resourceLoader, objectMapper);
        reg.init();

        // Schema accessible via alias AND resolved channel name
        assertThat(reg.hasSchema("orders")).isTrue();
        assertThat(reg.hasSchema("racer:orders")).isTrue();
    }

    // ------------------------------------------------------------------
    // loadSchema — error paths
    // ------------------------------------------------------------------

    @Test
    void init_skipsSchema_whenBothInlineAndLocationBlank() {
        RacerProperties props = new RacerProperties();
        props.getSchema().setEnabled(true);
        props.getSchema().setValidationMode(RacerProperties.SchemaValidationMode.BOTH);

        RacerProperties.SchemaDefinition badDef = new RacerProperties.SchemaDefinition();
        // Neither inline nor location set — should throw IAE, caught by init() and logged
        props.getSchema().getSchemas().put("broken", badDef);

        RacerSchemaRegistry reg = new RacerSchemaRegistry(props, resourceLoader, objectMapper);
        reg.init(); // should not throw

        assertThat(reg.hasSchema("broken")).isFalse();
    }

    @Test
    void init_skipsSchema_whenLocationResourceDoesNotExist() {
        RacerProperties props = new RacerProperties();
        props.getSchema().setEnabled(true);
        props.getSchema().setValidationMode(RacerProperties.SchemaValidationMode.BOTH);

        RacerProperties.SchemaDefinition locationDef = new RacerProperties.SchemaDefinition();
        locationDef.setLocation("classpath:schemas/nonexistent.json");
        props.getSchema().getSchemas().put("missing-resource", locationDef);

        Resource missingResource = mock(Resource.class);
        when(missingResource.exists()).thenReturn(false);
        when(resourceLoader.getResource(anyString())).thenReturn(missingResource);

        RacerSchemaRegistry reg = new RacerSchemaRegistry(props, resourceLoader, objectMapper);
        reg.init(); // should not throw

        assertThat(reg.hasSchema("missing-resource")).isFalse();
    }

    // ------------------------------------------------------------------
    // toJsonString — non-JSON String wrapping
    // ------------------------------------------------------------------

    @Test
    void validateForPublish_plainStringPayload_wrapsAsJsonString() {
        // "plain text" is not valid JSON — toJsonString wraps it as a JSON string value
        // With our schema requiring an object, this should fail validation
        assertThatThrownBy(() -> registry.validateForPublish(CHANNEL, "plain text"))
                .isInstanceOf(SchemaValidationException.class);
    }

    // ------------------------------------------------------------------
    // validateAdHoc — parse-error on malformed JSON
    // ------------------------------------------------------------------

    @Test
    void validateAdHoc_malformedJson_returnsParseError() {
        List<SchemaViolation> violations =
                registry.validateAdHoc(CHANNEL, "{not valid json");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).path()).isEqualTo("$");
    }
}
