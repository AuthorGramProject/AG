package org.telegram.messenger.authorgram;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Wraps derived chat keys with a non-exportable Android Keystore key.
 *
 * New writes use v2. Old v1 wrappers remain readable when their legacy entry
 * is healthy. A broken or restored v2 entry is regenerated automatically.
 */
final class AuthorGramKeyProtector {
    private static final String ALIAS_V1 = "authorgram.chat.keys.master.v1";
    private static final String ALIAS_V2 = "authorgram.chat.keys.master.v2";
    private static final String PREFIX_V1 = "v1:";
    private static final String PREFIX_V2 = "v2:";
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
        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateHealthyMasterKey(ALIAS_V2),
                new GCMParameterSpec(TAG_BITS, iv)
        );
        cipher.updateAAD(aad(account, dialogId));
        byte[] encrypted = cipher.doFinal(clearKey);
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return PREFIX_V2 + Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    static byte[] unwrap(int account, long dialogId, String wrapped)
            throws GeneralSecurityException {
        if (wrapped == null) {
            throw new GeneralSecurityException("Missing key wrapper");
        }

        final String alias;
        final String encoded;
        if (wrapped.startsWith(PREFIX_V2)) {
            alias = ALIAS_V2;
            encoded = wrapped.substring(PREFIX_V2.length());
        } else if (wrapped.startsWith(PREFIX_V1)) {
            alias = ALIAS_V1;
            encoded = wrapped.substring(PREFIX_V1.length());
        } else {
            throw new GeneralSecurityException("Unsupported key wrapper");
        }

        final byte[] packed;
        try {
            packed = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed key wrapper", exception);
        }
        if (packed.length < IV_BYTES + 16) {
            throw new GeneralSecurityException("Malformed key wrapper");
        }

        SecretKey key = existingMasterKey(alias);
        if (key == null) {
            throw new GeneralSecurityException("Wrapping key is unavailable");
        }

        byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(TAG_BITS, iv)
        );
        cipher.updateAAD(aad(account, dialogId));
        return cipher.doFinal(encrypted);
    }

    private static SecretKey getOrCreateHealthyMasterKey(String alias)
            throws GeneralSecurityException {
        try {
            SecretKey existing = existingMasterKey(alias);
            if (existing != null) {
                Cipher probe = Cipher.getInstance("AES/GCM/NoPadding");
                probe.init(Cipher.ENCRYPT_MODE, existing);
                return existing;
            }
        } catch (GeneralSecurityException exception) {
            deleteAlias(alias);
        }

        try {
            return generateMasterKey(alias);
        } catch (GeneralSecurityException firstFailure) {
            deleteAlias(alias);
            try {
                return generateMasterKey(alias);
            } catch (GeneralSecurityException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    private static SecretKey existingMasterKey(String alias)
            throws GeneralSecurityException {
        KeyStore store = loadStore();
        final Key existing;
        try {
            existing = store.getKey(alias, null);
        } catch (Exception exception) {
            throw new GeneralSecurityException(
                    "Unable to read Android Keystore key",
                    exception
            );
        }
        return existing instanceof SecretKey ? (SecretKey) existing : null;
    }

    private static SecretKey generateMasterKey(String alias)
            throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(
                new KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setKeySize(256)
                        .build()
        );
        return generator.generateKey();
    }

    private static KeyStore loadStore() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (Exception exception) {
            throw new GeneralSecurityException(
                    "Unable to load Android Keystore",
                    exception
            );
        }
        return store;
    }

    private static void deleteAlias(String alias) {
        try {
            KeyStore store = loadStore();
            if (store.containsAlias(alias)) {
                store.deleteEntry(alias);
            }
        } catch (GeneralSecurityException ignored) {
            // The next generation attempt exposes the real error.
        }
    }

    private static byte[] aad(int account, long dialogId) {
        return (account + ":" + dialogId).getBytes(StandardCharsets.UTF_8);
    }
}
