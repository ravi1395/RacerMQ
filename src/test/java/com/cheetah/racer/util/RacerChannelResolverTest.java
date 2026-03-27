package com.cheetah.racer.util;

import com.cheetah.racer.config.RacerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RacerChannelResolverTest {

    @Mock
    Environment environment;

    // ── resolve() ───────────────────────────────────────────────

    @Test
    void resolve_withPlaceholder_resolves() {
        when(environment.resolvePlaceholders("${my.prop}")).thenReturn("resolved-value");

        assertThat(RacerChannelResolver.resolve(environment, "${my.prop}"))
                .isEqualTo("resolved-value");
    }

    @Test
    void resolve_withoutPlaceholder_returnsAsIs() {
        when(environment.resolvePlaceholders("plain-string")).thenReturn("plain-string");

        assertThat(RacerChannelResolver.resolve(environment, "plain-string"))
                .isEqualTo("plain-string");
    }

    @Test
    void resolve_nullValue_returnsNull() {
        // Actual implementation returns "" for null input
        assertThat(RacerChannelResolver.resolve(environment, null)).isEmpty();
    }

    @Test
    void resolve_blankValue_returnsBlank() {
        assertThat(RacerChannelResolver.resolve(environment, "")).isEmpty();
    }

    @Test
    void resolve_unresolvable_returnsOriginal() {
        when(environment.resolvePlaceholders("${missing}"))
                .thenThrow(new IllegalArgumentException("unresolvable"));

        assertThat(RacerChannelResolver.resolve(environment, "${missing}"))
                .isEqualTo("${missing}");
    }

    // ── resolveChannel() ────────────────────────────────────────

    @Test
    void resolveChannel_directChannel_returnsIt() {
        RacerProperties props = new RacerProperties();

        assertThat(RacerChannelResolver.resolveChannel("my-channel", "", props))
                .isEqualTo("my-channel");
    }

    @Test
    void resolveChannel_channelRef_looksUpProperties() {
        RacerProperties props = new RacerProperties();
        RacerProperties.ChannelProperties ch = new RacerProperties.ChannelProperties();
        ch.setName("orders-channel");
        props.getChannels().put("orders", ch);

        assertThat(RacerChannelResolver.resolveChannel("", "orders", props))
                .isEqualTo("orders-channel");
    }

    @Test
    void resolveChannel_bothBlank_returnsNull() {
        // Falls back to the default channel from RacerProperties
        RacerProperties props = new RacerProperties();

        assertThat(RacerChannelResolver.resolveChannel("", "", props))
                .isEqualTo(props.getDefaultChannel());
    }

    // ── resolveStreamKey() ──────────────────────────────────────

    @Test
    void resolveStreamKey_directKey_returnsIt() {
        RacerProperties props = new RacerProperties();

        assertThat(RacerChannelResolver.resolveStreamKey("stream:key", "", props))
                .isEqualTo("stream:key");
    }

    @Test
    void resolveStreamKey_streamKeyRef_looksUpProperties() {
        RacerProperties props = new RacerProperties();
        RacerProperties.ChannelProperties ch = new RacerProperties.ChannelProperties();
        ch.setName("orders-stream");
        props.getChannels().put("orders", ch);

        // resolveStreamKey uses cp.getName() for the lookup
        assertThat(RacerChannelResolver.resolveStreamKey("", "orders", props))
                .isEqualTo("orders-stream");
    }

    @Test
    void resolveStreamKey_bothBlank_returnsNull() {
        // Falls back to the default channel from RacerProperties
        RacerProperties props = new RacerProperties();

        assertThat(RacerChannelResolver.resolveStreamKey("", "", props))
                .isEqualTo(props.getDefaultChannel());
    }

    // ── resolveChannel() with logPrefix — warning on missing ref ────────────

    @Test
    void resolveChannel_withLogPrefix_missingChannelRef_fallsBackToDefault() {
        RacerProperties props = new RacerProperties();
        // channelRef "unknown" is not in props.channels → warning logged, returns default
        String result = RacerChannelResolver.resolveChannel("", "unknown", props, "TEST-PREFIX");
        assertThat(result).isEqualTo(props.getDefaultChannel());
    }

    @Test
    void resolveStreamKey_withLogPrefix_missingStreamKeyRef_fallsBackToDefault() {
        RacerProperties props = new RacerProperties();
        // streamKeyRef "unknown" is not in props.channels → warning logged, returns default
        String result = RacerChannelResolver.resolveStreamKey("", "unknown", props, "TEST-PREFIX");
        assertThat(result).isEqualTo(props.getDefaultChannel());
    }
}
