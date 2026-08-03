package org.telegram.messenger.authorgram;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
import javax.crypto.spec.SecretKeySpec;

/**
 * Protects derived chat keys at rest.
 *
 * Android Keystore AES-GCM is always preferred. Some vendor Android 10/11
 * implementations intermittently reject otherwise valid AES aliases; on those
 * devices only, AuthorGram falls back to an installation-local random AES key
 * stored in the app's no-backup sandbox. The user's word is never persisted.
 *
 * v1/v2 wrappers remain readable. New software-fallback wrappers use v3s.
 */
final class AuthorGramKeyProtector {
    private static final String ALIAS_V1 = "authorgram.chat.keys.master.v1";
    private static final String ALIAS_V2 = "authorgram.chat.keys.master.v2";
    private static final String PREFIX_V1 = "v1:";
    private static final String PREFIX_V2 = "v2:";
    private static final String PREFIX_SOFTWARE_V3 = "v3s:";
    private static final String SOFTWARE_MASTER_FILE = "authorgram_chat_master_v1.bin";
    private static final int MASTER_KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKey cachedSoftwareMasterKey;

    private AuthorGramKeyProtector() {
    }

    static String wrap(int account, long dialogId, byte[] clearKey)
            throws GeneralSecurityException {
        try {
            return wrapWithKey(
                    PREFIX_V2,
                    getOrCreateHealthyMasterKey(ALIAS_V2),
                    account,
                    dialogId,
                    clearKey
            );
        } catch (GeneralSecurityException keystoreFailure) {
            FileLog.e(
                    "AuthorGram: Android Keystore unavailable; using no-backup device vault",
                    keystoreFailure
            );
            try {
                return wrapWithKey(
                        PREFIX_SOFTWARE_V3,
                        getOrCreateSoftwareMasterKey(),
                        account,
                        dialogId,
                        clearKey
                );
            } catch (GeneralSecurityException fallbackFailure) {
                fallbackFailure.addSuppressed(keystoreFailure);
                throw fallbackFailure;
            }
        }
    }

    static byte[] unwrap(int account, long dialogId, String wrapped)
            throws GeneralSecurityException {
        if (wrapped == null) {
            throw new GeneralSecurityException("Missing key wrapper");
        }

        final SecretKey key;
        final String encoded;
        if (wrapped.startsWith(PREFIX_SOFTWARE_V3)) {
            key = getOrCreateSoftwareMasterKey();
            encoded = wrapped.substring(PREFIX_SOFTWARE_V3.length());
        } else {
            final String alias;
            if (wrapped.startsWith(PREFIX_V2)) {
                alias = ALIAS_V2;
                encoded = wrapped.substring(PREFIX_V2.length());
            } else if (wrapped.startsWith(PREFIX_V1)) {
                alias = ALIAS_V1;
                encoded = wrapped.substring(PREFIX_V1.length());
            } else {
                throw new GeneralSecurityException("Unsupported key wrapper");
            }
            key = existingMasterKey(alias);
            if (key == null) {
                throw new GeneralSecurityException("Wrapping key is unavailable");
            }
        }

        byte[] packed = decodeWrapper(encoded);
        byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(account, dialogId));
        return cipher.doFinal(encrypted);
    }

    private static String wrapWithKey(
            String prefix,
            SecretKey key,
            int account,
            long dialogId,
            byte[] clearKey
    ) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(account, dialogId));
        byte[] encrypted = cipher.doFinal(clearKey);
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return prefix + Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private static byte[] decodeWrapper(String encoded)
            throws GeneralSecurityException {
        final byte[] packed;
        try {
            packed = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed key wrapper", exception);
        }
        if (packed.length < IV_BYTES + 16) {
            throw new GeneralSecurityException("Malformed key wrapper");
        }
        return packed;
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

    private static synchronized SecretKey getOrCreateSoftwareMasterKey()
            throws GeneralSecurityException {
        if (cachedSoftwareMasterKey != null) {
            return cachedSoftwareMasterKey;
        }

        File directory = ApplicationLoader.applicationContext.getNoBackupFilesDir();
        if (directory == null && ApplicationLoader.applicationContext.getFilesDir() != null) {
            directory = new File(
                    ApplicationLoader.applicationContext.getFilesDir(),
                    "no_backup"
            );
        }
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            throw new GeneralSecurityException("Unable to open AuthorGram device vault");
        }

        File target = new File(directory, SOFTWARE_MASTER_FILE);
        byte[] material = readMasterFile(target);
        if (material == null) {
            material = new byte[MASTER_KEY_BYTES];
            RANDOM.nextBytes(material);
            writeMasterFile(directory, target, material);
        }

        try {
            cachedSoftwareMasterKey = new SecretKeySpec(material, "AES");
            return cachedSoftwareMasterKey;
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    private static byte[] readMasterFile(File target)
            throws GeneralSecurityException {
        if (!target.isFile()) {
            return null;
        }
        byte[] material = new byte[MASTER_KEY_BYTES];
        try (FileInputStream input = new FileInputStream(target)) {
            int offset = 0;
            while (offset < material.length) {
                int read = input.read(material, offset, material.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == material.length && input.read() == -1) {
                return material;
            }
        } catch (IOException exception) {
            Arrays.fill(material, (byte) 0);
            throw new GeneralSecurityException(
                    "Unable to read AuthorGram device vault",
                    exception
            );
        }

        Arrays.fill(material, (byte) 0);
        if (!target.delete() && target.exists()) {
            throw new GeneralSecurityException("AuthorGram device vault is corrupted");
        }
        return null;
    }

    private static void writeMasterFile(File directory, File target, byte[] material)
            throws GeneralSecurityException {
        File temporary = null;
        try {
            temporary = File.createTempFile("authorgram-master-", ".tmp", directory);
            temporary.setReadable(false, false);
            temporary.setWritable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(true, true);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(material);
                output.flush();
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                if (target.isFile()) {
                    byte[] existing = readMasterFile(target);
                    if (existing != null) {
                        System.arraycopy(existing, 0, material, 0, MASTER_KEY_BYTES);
                        Arrays.fill(existing, (byte) 0);
                        return;
                    }
                }
                try (FileOutputStream output = new FileOutputStream(target)) {
                    output.write(material);
                    output.flush();
                    output.getFD().sync();
                }
            }
            target.setReadable(false, false);
            target.setWritable(false, false);
            target.setReadable(true, true);
            target.setWritable(true, true);
        } catch (IOException exception) {
            throw new GeneralSecurityException(
                    "Unable to create AuthorGram device vault",
                    exception
            );
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
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
