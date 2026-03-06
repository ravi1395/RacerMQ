package com.cheetah.racer.security;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerSecurityException;
import com.cheetah.racer.model.RacerMessage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Provides transparent HMAC-SHA256 message signing and verification for Racer messages.
 *
 * <p>Activated via {@code racer.security.signing.enabled=true}.
 * The shared secret ({@code racer.security.signing.secret}) must be identical
 * on all publisher and consumer instances in the same environment.
 *
 * <h3>Signature scope</h3>
 * The HMAC is computed over the pipe-delimited concatenation of:
 * {@code id | sender | payload | channel}
 * where {@code payload} is in whatever form it will be transmitted — if encryption
 * is also active the <em>encrypted</em> payload is signed (encrypt-then-sign), ensuring
 * the ciphertext cannot be silently replaced or truncated.
 *
 * <h3>Verification failure</h3>
 * When {@link #verify} returns {@code false} the consuming registrar routes the message
 * to the DLQ rather than delivering it to the handler.
 */
public class RacerMessageSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec signingKey;

    /**
     * Initialises the signer from {@code racer.security.signing.secret}.
     *
     * @throws IllegalArgumentException if the secret is blank
     */
    public RacerMessageSigner(RacerProperties properties) {
        String secret = properties.getSecurity().getSigning().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "racer.security.signing.secret must be set when racer.security.signing.enabled=true");
        }
        this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * Computes the HMAC-SHA256 signature for the given message fields.
     *
     * @param id      message id (may be {@code null})
     * @param sender  sender identifier (may be {@code null})
     * @param payload payload string — use the transmitted form (encrypted if encryption is on)
     * @param channel channel or stream key (may be {@code null})
     * @return lower-case hex-encoded HMAC-SHA256 signature
     * @throws RacerSecurityException if the MAC computation fails
     */
    public String sign(String id, String sender, String payload, String channel) {
        String input = nullSafe(id) + "|" + nullSafe(sender) + "|"
                + nullSafe(payload) + "|" + nullSafe(channel);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(signingKey);
            byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new RacerSecurityException("HMAC-SHA256 signing failed", e);
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature recorded in {@link RacerMessage#getSignature()}.
     *
     * <p>Call this <em>before</em> decrypting the payload so that the same form
     * (encrypted or plain) that was signed on the publish side is used for verification.
     *
     * @param message the deserialized message with its {@code signature} field populated
     * @return {@code true} if the signature is present and matches the expected value
     */
    public boolean verify(RacerMessage message) {
        if (message.getSignature() == null || message.getSignature().isBlank()) {
            return false;
        }
        String expected = sign(message.getId(), message.getSender(),
                message.getPayload(), message.getChannel());
        return expected.equals(message.getSignature());
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
