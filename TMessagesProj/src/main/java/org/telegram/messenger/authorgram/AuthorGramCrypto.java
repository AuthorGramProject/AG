package org.telegram.messenger.authorgram;

import android.util.Base64;

import org.telegram.messenger.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AuthorGram AES-256-GCM system-key compatibility for private Main builds. */
public final class AuthorGramCrypto {
    public static final String MARKER = "🛡AG:";

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int MAX_ENCODED_PAYLOAD_CHARS = 65_536;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AuthorGramCrypto() {
    }

    public static boolean isAuthorGramPayload(String text) {
        return text != null && text.startsWith(MARKER);
    }

    public static String encryptText(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || isAuthorGramPayload(plaintext)) {
            return plaintext;
        }

        byte[] key = systemKeyOrNull();
        if (key == null || !AuthorGramBuildIntegrity.canUseSystemKey()) {
            wipe(key);
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            );
            byte[] ciphertextAndTag =
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertextAndTag.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertextAndTag, 0, payload, iv.length, ciphertextAndTag.length);
            return MARKER + Base64.encodeToString(payload, Base64.NO_WRAP);
        } catch (GeneralSecurityException exception) {
            return null;
        } finally {
            wipe(key);
        }
    }

    public static String decryptTextOrNull(String payload) {
        if (!isAuthorGramPayload(payload)) {
            return null;
        }

        byte[] key = systemKeyOrNull();
        if (key == null || !AuthorGramBuildIntegrity.canUseSystemKey()) {
            wipe(key);
            return null;
        }

        try {
            String encoded = payload.substring(MARKER.length());
            if (encoded.isEmpty() || encoded.length() > MAX_ENCODED_PAYLOAD_CHARS) {
                return null;
            }
            byte[] packed = Base64.decode(encoded, Base64.DEFAULT);
            if (packed.length < IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH_BYTES);
            byte[] ciphertextAndTag =
                    Arrays.copyOfRange(packed, IV_LENGTH_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            );
            return new String(
                    cipher.doFinal(ciphertextAndTag),
                    StandardCharsets.UTF_8
            );
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return null;
        } finally {
            wipe(key);
        }
    }

    private static byte[] systemKeyOrNull() {
        if (!AuthorGramPlayPolicy.hasEmbeddedSystemKey()) {
            return null;
        }
        String value = BuildConfig.AUTHORGRAM_SYSTEM_KEY_HEX;
        if (value == null || value.length() != 64) {
            return null;
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                wipe(result);
                return null;
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
