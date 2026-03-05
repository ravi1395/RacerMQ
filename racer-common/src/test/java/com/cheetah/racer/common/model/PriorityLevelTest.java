package com.cheetah.racer.common.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityLevelTest {

    // ------------------------------------------------------------------
    // Weight ordering: HIGH < NORMAL < LOW
    // ------------------------------------------------------------------

    @Test
    void weightOrdering_highBeforeNormalBeforeLow() {
        assertThat(PriorityLevel.HIGH.getWeight())
                .isLessThan(PriorityLevel.NORMAL.getWeight());
        assertThat(PriorityLevel.NORMAL.getWeight())
                .isLessThan(PriorityLevel.LOW.getWeight());
    }

    @Test
    void highWeight_isZero() {
        assertThat(PriorityLevel.HIGH.getWeight()).isEqualTo(0);
    }

    @Test
    void normalWeight_isOne() {
        assertThat(PriorityLevel.NORMAL.getWeight()).isEqualTo(1);
    }

    @Test
    void lowWeight_isTwo() {
        assertThat(PriorityLevel.LOW.getWeight()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // of() — happy paths
    // ------------------------------------------------------------------

    @Test
    void of_high_caseInsensitive() {
        assertThat(PriorityLevel.of("HIGH")).isEqualTo(PriorityLevel.HIGH);
        assertThat(PriorityLevel.of("high")).isEqualTo(PriorityLevel.HIGH);
        assertThat(PriorityLevel.of("High")).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void of_normal() {
        assertThat(PriorityLevel.of("NORMAL")).isEqualTo(PriorityLevel.NORMAL);
        assertThat(PriorityLevel.of("normal")).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test
    void of_low() {
        assertThat(PriorityLevel.of("LOW")).isEqualTo(PriorityLevel.LOW);
    }

    // ------------------------------------------------------------------
    // of() — fallback to NORMAL
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "UNKNOWN", "CRITICAL", "emergency"})
    void of_invalidOrMissing_returnsNormal(String input) {
        assertThat(PriorityLevel.of(input)).isEqualTo(PriorityLevel.NORMAL);
    }

    // ------------------------------------------------------------------
    // of() — with leading/trailing whitespace
    // ------------------------------------------------------------------

    @Test
    void of_trimmedBeforeLookup() {
        assertThat(PriorityLevel.of("  HIGH  ")).isEqualTo(PriorityLevel.HIGH);
        assertThat(PriorityLevel.of(" LOW ")).isEqualTo(PriorityLevel.LOW);
    }
}
