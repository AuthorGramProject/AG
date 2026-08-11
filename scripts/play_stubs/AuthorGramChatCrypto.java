package org.telegram.messenger.authorgram;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Play build: receive-only per-chat decryption; outgoing custom crypto is absent. */
public final class AuthorGramChatCrypto {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = 16;
    private static final int MAX_ENCODED_PAYLOAD_CHARS = 65_536;

    private AuthorGramChatCrypto() { }

    public static String encryptText(int account, long dialogId, String plaintext) {
        return null;
    }

    public static String decryptTextOrNull(int account, long dialogId, String payload) {
        if (!AuthorGramCrypto.isAuthorGramPayload(payload)
                || AuthorGramPlayPolicy.isEncryptionForbidden(dialogId)) {
            return null;
        }

        ArrayList<byte[]> keys = AuthorGramChatKeyStore.getDecryptionKeys(account, dialogId);
        try {
            for (byte[] key : keys) {
                String plaintext = decryptWithKey(payload, key);
                if (plaintext != null) return plaintext;
            }
        } finally {
            for (byte[] key : keys) Arrays.fill(key, (byte) 0);
        }
        return null;
    }

    private static String decryptWithKey(String payload, byte[] key) {
        try {
            String encoded = payload.substring(AuthorGramCrypto.MARKER.length());
            if (encoded.isEmpty() || encoded.length() > MAX_ENCODED_PAYLOAD_CHARS) return null;
            byte[] packed = Base64.decode(encoded, Base64.DEFAULT);
            if (packed.length < IV_BYTES + TAG_BYTES) return null;
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return null;
        }
    }
}
