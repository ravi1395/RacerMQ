package com.cheetah.racer.tracing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RacerTraceContext}.
 */
class RacerTraceContextTest {

    // ── generate ─────────────────────────────────────────────────────────────

    @Test
    void generate_producesValidTraceparent() {
        String tp = RacerTraceContext.generate();
        assertThat(RacerTraceContext.isValid(tp)).isTrue();
    }

    @Test
    void generate_hasCorrectFormat() {
        String tp = RacerTraceContext.generate();
        String[] parts = tp.split("-");

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("00");       // version
        assertThat(parts[1]).hasSize(32);            // trace-id: 16 bytes hex
        assertThat(parts[2]).hasSize(16);            // parent-id: 8 bytes hex
        assertThat(parts[3]).isEqualTo("01");        // flags: sampled
    }

    @Test
    void generate_producesUniqueValues() {
        String tp1 = RacerTraceContext.generate();
        String tp2 = RacerTraceContext.generate();
        assertThat(tp1).isNotEqualTo(tp2);
    }

    // ── child ────────────────────────────────────────────────────────────────

    @Test
    void child_inheritsTraceId() {
        String parent = RacerTraceContext.generate();
        String child  = RacerTraceContext.child(parent);

        assertThat(RacerTraceContext.extractTraceId(child))
                .isEqualTo(RacerTraceContext.extractTraceId(parent));
    }

    @Test
    void child_hasNewSpanId() {
        String parent = RacerTraceContext.generate();
        String child  = RacerTraceContext.child(parent);

        assertThat(RacerTraceContext.extractParentId(child))
                .isNotEqualTo(RacerTraceContext.extractParentId(parent));
    }

    @Test
    void child_producesValidTraceparent() {
        String parent = RacerTraceContext.generate();
        String child  = RacerTraceContext.child(parent);
        assertThat(RacerTraceContext.isValid(child)).isTrue();
    }

    @Test
    void child_withNullParent_generatesNewRoot() {
        String child = RacerTraceContext.child(null);
        assertThat(RacerTraceContext.isValid(child)).isTrue();
    }

    @Test
    void child_withBlankParent_generatesNewRoot() {
        String child = RacerTraceContext.child("  ");
        assertThat(RacerTraceContext.isValid(child)).isTrue();
    }

    @Test
    void child_withInvalidParent_generatesNewRoot() {
        String child = RacerTraceContext.child("not-a-traceparent");
        assertThat(RacerTraceContext.isValid(child)).isTrue();
    }

    // ── extractTraceId ────────────────────────────────────────────────────────

    @Test
    void extractTraceId_returnsTraceIdPart() {
        String tp = RacerTraceContext.generate();
        String traceId = RacerTraceContext.extractTraceId(tp);
        assertThat(traceId).isNotNull().hasSize(32);
    }

    @Test
    void extractTraceId_returnsNullForNull() {
        assertThat(RacerTraceContext.extractTraceId(null)).isNull();
    }

    @Test
    void extractTraceId_returnsNullForMalformed() {
        assertThat(RacerTraceContext.extractTraceId("bad-value")).isNull();
    }

    // ── extractParentId ───────────────────────────────────────────────────────

    @Test
    void extractParentId_returnsParentIdPart() {
        String tp = RacerTraceContext.generate();
        String parentId = RacerTraceContext.extractParentId(tp);
        assertThat(parentId).isNotNull().hasSize(16);
    }

    @Test
    void extractParentId_returnsNullForNull() {
        assertThat(RacerTraceContext.extractParentId(null)).isNull();
    }

    // ── isValid ───────────────────────────────────────────────────────────────

    @Test
    void isValid_trueForGeneratedValue() {
        assertThat(RacerTraceContext.isValid(RacerTraceContext.generate())).isTrue();
    }

    @Test
    void isValid_falseForNull() {
        assertThat(RacerTraceContext.isValid(null)).isFalse();
    }

    @Test
    void isValid_falseForBlank() {
        assertThat(RacerTraceContext.isValid("")).isFalse();
    }

    @Test
    void isValid_falseForTooFewParts() {
        assertThat(RacerTraceContext.isValid("00-abc-def")).isFalse();
    }

    @Test
    void isValid_falseForWrongTraceIdLength() {
        // trace-id must be 32 hex chars
        assertThat(RacerTraceContext.isValid("00-abc-00f067aa0ba902b7-01")).isFalse();
    }

    @Test
    void isValid_falseForWrongParentIdLength() {
        // parent-id must be 16 hex chars
        assertThat(RacerTraceContext.isValid("00-4bf92f3577b34da6a3ce929d0e0e4736-abc-01")).isFalse();
    }

    @Test
    void isValid_falseForNonHexTraceId() {
        // trace-id has correct length but contains non-hex character 'g'
        assertThat(RacerTraceContext.isValid("00-gf92f3577b34da6a3ce929d0e0e47369-00f067aa0ba902b7-01")).isFalse();
    }

    @Test
    void isValid_falseForAllZerosTraceId() {
        assertThat(RacerTraceContext.isValid(
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01")).isFalse();
    }

    @Test
    void isValid_falseForAllZerosParentId() {
        assertThat(RacerTraceContext.isValid(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01")).isFalse();
    }
}
