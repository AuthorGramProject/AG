package org.telegram.messenger.authorgram;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AuthorGram application-level text encryption.
 *
 * Wire format:
 *
 * 🛡AG:<Base64(IV || AES_GCM_CIPHERTEXT_AND_TAG)>
 *
 * AES:
 *   AES-256-GCM
 *
 * IV:
 *   12 fresh random bytes per encryption operation
 *
 * GCM authentication tag:
 *   128 bits
 */
public final class AuthorGramCrypto {

    public static final String MARKER = "🛡AG:";

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int MAX_ENCODED_PAYLOAD_CHARS = 65_536;

    /**
     * Single AuthorGram AES-256 key.
     *
     * 32 bytes / 256 bits.
     *
     * Keep this exact value identical in every AuthorGram build
     * that must be able to communicate with the others.
     */
    private static final String KEY_HEX =
            "6b8ce70d889daed80852c204106d51bf" +
            "91f114ad32936b6b17068e7b399ef3fa";

    private static final byte[] KEY_BYTES = hexToBytes(KEY_HEX);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AuthorGramCrypto() {
    }

    public static boolean isAuthorGramPayload(String text) {
        return text != null && text.startsWith(MARKER);
    }

    /**
     * Encrypts plaintext.
     *
     * Returns null only if encryption itself fails.
     *
     * Already encrypted AuthorGram payloads are returned unchanged,
     * preventing accidental double encryption during retries.
     */
    public static String encryptText(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        if (isAuthorGramPayload(plaintext)) {
            return plaintext;
        }
        if (!AuthorGramBuildIntegrity.canUseSystemKey()) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            SecretKeySpec keySpec =
                    new SecretKeySpec(KEY_BYTES, "AES");

            GCMParameterSpec parameterSpec =
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    keySpec,
                    parameterSpec
            );

            byte[] plaintextBytes =
                    plaintext.getBytes(StandardCharsets.UTF_8);

            /*
             * Android/Java AES-GCM doFinal() returns:
             *
             * ciphertext || authentication_tag
             */
            byte[] ciphertextAndTag =
                    cipher.doFinal(plaintextBytes);

            byte[] payload =
                    new byte[iv.length + ciphertextAndTag.length];

            System.arraycopy(
                    iv,
                    0,
                    payload,
                    0,
                    iv.length
            );

            System.arraycopy(
                    ciphertextAndTag,
                    0,
                    payload,
                    iv.length,
                    ciphertextAndTag.length
            );

            String encoded = Base64.encodeToString(
                    payload,
                    Base64.NO_WRAP
            );

            return MARKER + encoded;

        } catch (GeneralSecurityException exception) {
            return null;
        }
    }

    /**
     * Decrypts an AuthorGram payload.
     *
     * Returns:
     *
     * plaintext — authenticated decryption succeeded
     * null      — malformed payload or GCM authentication failed
     */
    public static String decryptTextOrNull(String payload) {
        if (!isAuthorGramPayload(payload)
                || !AuthorGramBuildIntegrity.canUseSystemKey()) {
            return null;
        }

        try {
            String encoded =
                    payload.substring(MARKER.length());

            if (encoded.isEmpty()
                    || encoded.length() > MAX_ENCODED_PAYLOAD_CHARS) {
                return null;
            }

            byte[] packed = Base64.decode(
                    encoded,
                    Base64.DEFAULT
            );

            /*
             * Minimal valid payload:
             *
             * 12-byte IV
             * +
             * 16-byte GCM authentication tag
             */
            if (packed.length <
                    IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {

                return null;
            }

            byte[] iv = Arrays.copyOfRange(
                    packed,
                    0,
                    IV_LENGTH_BYTES
            );

            byte[] ciphertextAndTag = Arrays.copyOfRange(
                    packed,
                    IV_LENGTH_BYTES,
                    packed.length
            );

            Cipher cipher =
                    Cipher.getInstance("AES/GCM/NoPadding");

            SecretKeySpec keySpec =
                    new SecretKeySpec(KEY_BYTES, "AES");

            GCMParameterSpec parameterSpec =
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            iv
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keySpec,
                    parameterSpec
            );

            byte[] plaintext =
                    cipher.doFinal(ciphertextAndTag);

            return new String(
                    plaintext,
                    StandardCharsets.UTF_8
            );

        } catch (
                GeneralSecurityException |
                IllegalArgumentException exception
        ) {
            /*
             * Authentication failure or malformed Base64.
             *
             * Fail safely:
             * ciphertext remains untouched by the caller.
             */
            return null;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "Invalid AuthorGram AES key"
            );
        }

        byte[] result =
                new byte[hex.length() / 2];

        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(
                    hex.charAt(i * 2),
                    16
            );

            int low = Character.digit(
                    hex.charAt(i * 2 + 1),
                    16
            );

            if (high < 0 || low < 0) {
                throw new IllegalArgumentException(
                        "Invalid AuthorGram AES key"
                );
            }

            result[i] =
                    (byte) ((high << 4) | low);
        }

        if (result.length != 32) {
            throw new IllegalArgumentException(
                    "AuthorGram AES key must be exactly 256 bits"
            );
        }

        return result;
    }
}
