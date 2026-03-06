package com.cheetah.racer.security;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Provides transparent AES-256-GCM payload encryption and decryption for Racer messages.
 *
 * <p>Activated via {@code racer.security.encryption.enabled=true}.
 * The shared key ({@code racer.security.encryption.key}) must be a base64-encoded
 * 256-bit (32-byte) key, e.g. generated with {@code openssl rand -base64 32}.
 *
 * <h3>Wire format</h3>
 * The encrypted payload stored in the message envelope is:
 * {@code base64(iv[12 bytes] || ciphertext+authTag)}
 * where a fresh random IV is generated for every message.
 *
 * <h3>Security properties</h3>
 * <ul>
 *   <li>Authenticated encryption — tampering with the ciphertext is detected via the GCM auth tag.</li>
 *   <li>Each message uses a unique IV — identical plaintexts produce different ciphertexts.</li>
 *   <li>Key material is never logged or serialized beyond the configuration value.</li>
 * </ul>
 */
public class RacerPayloadEncryptor {

    private static final String ALGORITHM     = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH = 12;   // 96-bit IV (GCM recommended)
    private static final int    GCM_TAG_BITS  = 128;  // 128-bit authentication tag

    private final SecretKeySpec keySpec;
    private final SecureRandom  random = new SecureRandom();

    /**
     * Initialises the encryptor from {@code racer.security.encryption.key} (base64, 32 bytes).
     *
     * @throws IllegalArgumentException if the key is missing, malformed, or not 256-bit
     */
    public RacerPayloadEncryptor(RacerProperties properties) {
        String keyBase64 = properties.getSecurity().getEncryption().getKey();
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalArgumentException(
                    "racer.security.encryption.key must be set when racer.security.encryption.enabled=true");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "racer.security.encryption.key must be a valid base64-encoded value", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "racer.security.encryption.key must decode to exactly 32 bytes (256-bit AES key). Got: "
                    + keyBytes.length + " bytes");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts {@code plaintext} using AES-256-GCM with a fresh random IV.
     *
     * @param plaintext the UTF-8 string to encrypt
     * @return base64-encoded {@code iv || ciphertext+authTag}
     * @throws RacerSecurityException if encryption fails
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined   = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,             GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RacerSecurityException("AES-256-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a base64-encoded {@code iv || ciphertext+authTag} value produced by {@link #encrypt}.
     *
     * @param ciphertext base64-encoded encrypted payload
     * @return decrypted UTF-8 string
     * @throws RacerSecurityException if decryption or authentication fails
     */
    public String decrypt(String ciphertext) {
        try {
            byte[] combined  = Base64.getDecoder().decode(ciphertext);
            byte[] iv        = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0,             iv,        0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RacerSecurityException("AES-256-GCM decryption failed", e);
        }
    }
}
