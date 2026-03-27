package com.cheetah.racer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RacerAutoConfigurationValidatorTest {

    private final RacerAutoConfiguration autoConfig = new RacerAutoConfiguration();

    // ── Valid defaults pass ──────────────────────────────────────────────────

    @Test
    void validDefaultProperties_passes() {
        RacerProperties props = new RacerProperties();
        Object sentinel = autoConfig.racerPropertiesValidator(props);
        assertThat(sentinel).isNotNull();
    }

    // ── Circuit breaker validations ──────────────────────────────────────────

    @Test
    void circuitBreaker_failureRateZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setFailureRateThreshold(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failure-rate-threshold");
    }

    @Test
    void circuitBreaker_failureRate101_throws() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setFailureRateThreshold(101);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failure-rate-threshold");
    }

    @Test
    void circuitBreaker_slidingWindowZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setSlidingWindowSize(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sliding-window-size");
    }

    @Test
    void circuitBreaker_waitDurationZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wait-duration");
    }

    @Test
    void circuitBreaker_permittedCallsZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("permitted-calls");
    }

    @Test
    void circuitBreaker_validValues_passes() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setFailureRateThreshold(50);
        props.getCircuitBreaker().setSlidingWindowSize(10);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(30);
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(3);

        Object sentinel = autoConfig.racerPropertiesValidator(props);
        assertThat(sentinel).isNotNull();
    }

    @Test
    void circuitBreaker_disabled_skipsValidation() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(false);
        // Invalid values should be ignored when disabled
        props.getCircuitBreaker().setFailureRateThreshold(0);

        Object sentinel = autoConfig.racerPropertiesValidator(props);
        assertThat(sentinel).isNotNull();
    }

    // ── Dedup validations ────────────────────────────────────────────────────

    @Test
    void dedup_ttlZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getDedup().setEnabled(true);
        props.getDedup().setTtlSeconds(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ttl-seconds");
    }

    @Test
    void dedup_blankKeyPrefix_throws() {
        RacerProperties props = new RacerProperties();
        props.getDedup().setEnabled(true);
        props.getDedup().setKeyPrefix("");

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-prefix");
    }

    @Test
    void dedup_disabled_skipsValidation() {
        RacerProperties props = new RacerProperties();
        props.getDedup().setEnabled(false);
        props.getDedup().setTtlSeconds(0);

        Object sentinel = autoConfig.racerPropertiesValidator(props);
        assertThat(sentinel).isNotNull();
    }

    // ── DLQ validations ──────────────────────────────────────────────────────

    @Test
    void dlq_maxSizeZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getDlq().setMaxSize(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dlq.max-size");
    }

    // ── Rate limit validations ───────────────────────────────────────────────

    @Test
    void rateLimit_capacityZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setDefaultCapacity(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-capacity");
    }

    @Test
    void rateLimit_refillRateZero_throws() {
        RacerProperties props = new RacerProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setDefaultRefillRate(0);

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-refill-rate");
    }

    @Test
    void rateLimit_blankKeyPrefix_throws() {
        RacerProperties props = new RacerProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setKeyPrefix("");

        assertThatThrownBy(() -> autoConfig.racerPropertiesValidator(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-prefix");
    }

    @Test
    void rateLimit_disabled_skipsValidation() {
        RacerProperties props = new RacerProperties();
        props.getRateLimit().setEnabled(false);
        props.getRateLimit().setDefaultCapacity(0);

        Object sentinel = autoConfig.racerPropertiesValidator(props);
        assertThat(sentinel).isNotNull();
    }
}
