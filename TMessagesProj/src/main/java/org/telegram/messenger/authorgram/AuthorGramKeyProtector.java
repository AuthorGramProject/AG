package org.telegram.messenger.authorgram;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps custom chat keys with a non-exportable Android Keystore key. */
final class AuthorGramKeyProtector {
    private static final String ALIAS = "authorgram.chat.keys.master.v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthorGramKeyProtector() {
    }

    static String wrap(int account, long dialogId, byte[] clearKey)
            throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(account, dialogId));
        byte[] encrypted = cipher.doFinal(clearKey);
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return "v1:" + Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    static byte[] unwrap(int account, long dialogId, String wrapped)
            throws GeneralSecurityException {
        if (wrapped == null || !wrapped.startsWith("v1:")) {
            throw new GeneralSecurityException("Unsupported key wrapper");
        }
        final byte[] packed;
        try {
            packed = Base64.decode(wrapped.substring(3), Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed key wrapper", exception);
        }
        if (packed.length < IV_BYTES + 16) {
            throw new GeneralSecurityException("Malformed key wrapper");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(account, dialogId));
        return cipher.doFinal(encrypted);
    }

    private static SecretKey masterKey() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (Exception exception) {
            throw new GeneralSecurityException("Unable to load Android Keystore", exception);
        }
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static byte[] aad(int account, long dialogId) {
        return (account + ":" + dialogId).getBytes(StandardCharsets.UTF_8);
    }
}
