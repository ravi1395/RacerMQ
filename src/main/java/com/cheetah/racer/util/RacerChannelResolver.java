package com.cheetah.racer.util;

import com.cheetah.racer.config.RacerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

/**
 * Static utilities shared by all Racer registrar and factory-bean classes for
 * resolving Spring placeholders, channel aliases, and stream key aliases.
 *
 * <p>Eliminates the six near-identical private {@code resolve / resolveChannel /
 * resolveStreamKey} methods that were copy-pasted across
 * {@code RacerListenerRegistrar}, {@code RacerStreamListenerRegistrar},
 * {@code RacerResponderRegistrar}, {@code RacerClientFactoryBean},
 * {@code RacerPollRegistrar}, and {@code PublishResultAspect}.
 */
@Slf4j
public final class RacerChannelResolver {

    private RacerChannelResolver() {}

    /**
     * Resolves Spring {@code ${…}} placeholders in {@code value}.
     * Returns an empty string (never {@code null}) when the input is blank.
     */
    public static String resolve(Environment environment, String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        try {
            return environment.resolvePlaceholders(value);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Resolves a Redis channel name.
     * <ol>
     *   <li>If {@code channel} is non-blank, returns it directly.</li>
     *   <li>Otherwise looks up {@code channelRef} in {@code racer.channels.*}.</li>
     *   <li>Falls back to {@code racer.default-channel}.</li>
     * </ol>
     *
     * @param logPrefix if non-null, a WARN is emitted when {@code channelRef} is not found
     */
    public static String resolveChannel(String channel, String channelRef,
                                         RacerProperties racerProperties,
                                         @Nullable String logPrefix) {
        if (!channel.isEmpty()) return channel;
        if (!channelRef.isEmpty()) {
            RacerProperties.ChannelProperties cp = racerProperties.getChannels().get(channelRef);
            if (cp != null && cp.getName() != null && !cp.getName().isBlank()) {
                return cp.getName();
            }
            if (logPrefix != null) {
                log.warn("[{}] channelRef '{}' not found in racer.channels — falling back to default channel.",
                        logPrefix, channelRef);
            }
        }
        return racerProperties.getDefaultChannel();
    }

    /** Convenience overload — no warning on missing ref. */
    public static String resolveChannel(String channel, String channelRef,
                                         RacerProperties racerProperties) {
        return resolveChannel(channel, channelRef, racerProperties, null);
    }

    /**
     * Resolves a Redis Stream key.
     * Same lookup order as {@link #resolveChannel} but without emitting a warning.
     */
    public static String resolveStreamKey(String streamKey, String streamKeyRef,
                                           RacerProperties racerProperties,
                                           @Nullable String logPrefix) {
        if (!streamKey.isEmpty()) return streamKey;
        if (!streamKeyRef.isEmpty()) {
            RacerProperties.ChannelProperties cp = racerProperties.getChannels().get(streamKeyRef);
            if (cp != null && cp.getName() != null && !cp.getName().isBlank()) {
                return cp.getName();
            }
            if (logPrefix != null) {
                log.warn("[{}] streamKeyRef '{}' not found in racer.channels — falling back to default channel.",
                        logPrefix, streamKeyRef);
            }
        }
        return racerProperties.getDefaultChannel();
    }

    /** Convenience overload — no warning on missing ref. */
    public static String resolveStreamKey(String streamKey, String streamKeyRef,
                                           RacerProperties racerProperties) {
        return resolveStreamKey(streamKey, streamKeyRef, racerProperties, null);
    }
}
