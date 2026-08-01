package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Per-account and per-dialog AuthorGram key storage and passphrase derivation. */
public final class AuthorGramChatKeyStore {
    public static final long SYSTEM_KEY_DIALOG_ID = 6316376597L;

    private static final String PREFS = "authorgram_chat_keys_v1";
    private static final String CURRENT = "current_";
    private static final String HISTORY = "history_";
    private static final String KDF_DOMAIN = "AuthorGram-Chat-KDF-v1";
    private static final int KEY_BYTES = 32;
    private static final int HISTORY_LIMIT = 5;
    private static final int KDF_ITERATIONS = 600_000;
    private static final int MAX_PASSPHRASE_CODE_POINTS = 256;

    private AuthorGramChatKeyStore() {
    }

    public static boolean isSystemKeyLocked(long dialogId) {
        return dialogId == SYSTEM_KEY_DIALOG_ID;
    }

    public static int getMaxPassphraseCodePoints() {
        return MAX_PASSPHRASE_CODE_POINTS;
    }

    public static synchronized boolean hasCustomKey(int account, long dialogId) {
        return dialogId != 0
                && !isSystemKeyLocked(dialogId)
                && preferences().contains(currentName(account, dialogId));
    }

    /**
     * Derives a chat-specific 256-bit key from a human passphrase and stores only
     * the wrapped derived key. The passphrase itself is never persisted or logged.
     */
    public static synchronized void deriveAndStore(
            int account,
            long dialogId,
            char[] passphrase
    ) throws GeneralSecurityException {
        ensureAllowed(dialogId);
        if (passphrase == null) {
            throw new GeneralSecurityException("Missing passphrase");
        }

        byte[] key = null;
        try {
            key = deriveKey(account, dialogId, passphrase);
            store(account, dialogId, key);
        } finally {
            Arrays.fill(passphrase, '\0');
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /**
     * Switches future messages back to the system key while retaining the former
     * custom key in local history so messages already encrypted with it remain readable.
     */
    public static synchronized boolean useSystemKey(int account, long dialogId) {
        if (dialogId == 0 || isSystemKeyLocked(dialogId)) {
            return false;
        }

        SharedPreferences prefs = preferences();
        String currentName = currentName(account, dialogId);
        String previous = prefs.getString(currentName, null);
        if (previous == null) {
            return true;
        }

        SharedPreferences.Editor editor = prefs.edit().remove(currentName);
        putAtHistoryFront(editor, prefs, account, dialogId, previous);
        boolean committed = editor.commit();
        if (!committed) {
            FileLog.e("AuthorGram: unable to switch the chat back to the system key");
        }
        return committed;
    }

    /** Kept for source compatibility with older UI code; history is intentionally retained. */
    @Deprecated
    public static synchronized boolean clearCustomKeys(int account, long dialogId) {
        return useSystemKey(account, dialogId);
    }

    static synchronized byte[] getCurrentKey(int account, long dialogId) {
        if (dialogId == 0 || isSystemKeyLocked(dialogId)) {
            return null;
        }
        try {
            return currentKey(account, dialogId);
        } catch (GeneralSecurityException exception) {
            FileLog.e("AuthorGram: unable to unwrap the current custom chat key", exception);
            return null;
        }
    }

    static synchronized ArrayList<byte[]> getDecryptionKeys(int account, long dialogId) {
        ArrayList<byte[]> result = new ArrayList<>();
        if (dialogId == 0 || isSystemKeyLocked(dialogId)) {
            return result;
        }
        SharedPreferences prefs = preferences();
        addCandidate(result, prefs.getString(currentName(account, dialogId), null), account, dialogId);
        for (int index = 0; index < HISTORY_LIMIT; index++) {
            addCandidate(
                    result,
                    prefs.getString(historyName(account, dialogId, index), null),
                    account,
                    dialogId
            );
        }
        return result;
    }

    static byte[] deriveKeyForTesting(int account, long dialogId, char[] passphrase)
            throws GeneralSecurityException {
        return deriveKey(account, dialogId, passphrase);
    }

    private static byte[] deriveKey(int account, long dialogId, char[] passphrase)
            throws GeneralSecurityException {
        String normalized = normalizePassphrase(passphrase);
        byte[] passwordBytes = normalized.getBytes(StandardCharsets.UTF_8);
        byte[] salt = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            salt = digest.digest(stableKdfScope(account, dialogId).getBytes(StandardCharsets.UTF_8));
            return pbkdf2HmacSha256(passwordBytes, salt, KDF_ITERATIONS, KEY_BYTES);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
            }
        }
    }

    private static String normalizePassphrase(char[] passphrase)
            throws GeneralSecurityException {
        String value = stripUnicodeWhitespace(new String(passphrase));
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        if (value.isEmpty()) {
            throw new GeneralSecurityException("Passphrase must not be empty");
        }
        if (value.codePointCount(0, value.length()) > MAX_PASSPHRASE_CODE_POINTS) {
            throw new GeneralSecurityException("Passphrase is too long");
        }
        return value;
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static String stableKdfScope(int account, long dialogId)
            throws GeneralSecurityException {
        if (dialogId > 0) {
            long ownUserId = UserConfig.getInstance(account).getClientUserId();
            if (ownUserId <= 0) {
                throw new GeneralSecurityException("AuthorGram account identity is unavailable");
            }
            long low = Math.min(ownUserId, dialogId);
            long high = Math.max(ownUserId, dialogId);
            return KDF_DOMAIN + "|private|" + low + "|" + high;
        }
        return KDF_DOMAIN + "|dialog|" + dialogId;
    }

    private static byte[] pbkdf2HmacSha256(
            byte[] password,
            byte[] salt,
            int iterations,
            int outputLength
    ) throws GeneralSecurityException {
        if (iterations < 1 || outputLength < 1) {
            throw new GeneralSecurityException("Invalid KDF parameters");
        }

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        int macLength = mac.getMacLength();
        int blockCount = (outputLength + macLength - 1) / macLength;
        byte[] output = new byte[outputLength];
        byte[] blockInput = new byte[salt.length + 4];
        System.arraycopy(salt, 0, blockInput, 0, salt.length);

        byte[] u = null;
        byte[] block = null;
        try {
            for (int blockIndex = 1; blockIndex <= blockCount; blockIndex++) {
                int offset = salt.length;
                blockInput[offset] = (byte) (blockIndex >>> 24);
                blockInput[offset + 1] = (byte) (blockIndex >>> 16);
                blockInput[offset + 2] = (byte) (blockIndex >>> 8);
                blockInput[offset + 3] = (byte) blockIndex;

                u = mac.doFinal(blockInput);
                block = u.clone();
                for (int iteration = 1; iteration < iterations; iteration++) {
                    byte[] next = mac.doFinal(u);
                    Arrays.fill(u, (byte) 0);
                    u = next;
                    for (int index = 0; index < block.length; index++) {
                        block[index] ^= u[index];
                    }
                }

                int destination = (blockIndex - 1) * macLength;
                int count = Math.min(macLength, outputLength - destination);
                System.arraycopy(block, 0, output, destination, count);
                Arrays.fill(u, (byte) 0);
                Arrays.fill(block, (byte) 0);
                u = null;
                block = null;
            }
            return output;
        } finally {
            Arrays.fill(blockInput, (byte) 0);
            if (u != null) {
                Arrays.fill(u, (byte) 0);
            }
            if (block != null) {
                Arrays.fill(block, (byte) 0);
            }
        }
    }

    private static void store(int account, long dialogId, byte[] key)
            throws GeneralSecurityException {
        if (key.length != KEY_BYTES) {
            throw new GeneralSecurityException("AuthorGram key must be 256 bits");
        }

        byte[] existingKey = currentKey(account, dialogId);
        try {
            if (existingKey != null && MessageDigest.isEqual(existingKey, key)) {
                return;
            }
        } finally {
            if (existingKey != null) {
                Arrays.fill(existingKey, (byte) 0);
            }
        }

        SharedPreferences prefs = preferences();
        String currentName = currentName(account, dialogId);
        String previous = prefs.getString(currentName, null);
        String wrapped = AuthorGramKeyProtector.wrap(account, dialogId, key);
        SharedPreferences.Editor editor = prefs.edit();
        if (previous != null) {
            putAtHistoryFront(editor, prefs, account, dialogId, previous);
        }
        editor.putString(currentName, wrapped);
        if (!editor.commit()) {
            throw new GeneralSecurityException("Unable to persist AuthorGram chat key");
        }
    }

    private static void putAtHistoryFront(
            SharedPreferences.Editor editor,
            SharedPreferences prefs,
            int account,
            long dialogId,
            String wrapped
    ) {
        String existingFront = prefs.getString(historyName(account, dialogId, 0), null);
        if (wrapped.equals(existingFront)) {
            return;
        }
        for (int index = HISTORY_LIMIT - 1; index > 0; index--) {
            String older = prefs.getString(historyName(account, dialogId, index - 1), null);
            String destination = historyName(account, dialogId, index);
            if (older == null) {
                editor.remove(destination);
            } else {
                editor.putString(destination, older);
            }
        }
        editor.putString(historyName(account, dialogId, 0), wrapped);
    }

    private static byte[] currentKey(int account, long dialogId)
            throws GeneralSecurityException {
        String wrapped = preferences().getString(currentName(account, dialogId), null);
        if (wrapped == null) {
            return null;
        }
        byte[] key = AuthorGramKeyProtector.unwrap(account, dialogId, wrapped);
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw new GeneralSecurityException("Invalid stored key length");
        }
        return key;
    }

    private static void addCandidate(
            ArrayList<byte[]> result,
            String wrapped,
            int account,
            long dialogId
    ) {
        if (wrapped == null) {
            return;
        }
        final byte[] candidate;
        try {
            candidate = AuthorGramKeyProtector.unwrap(account, dialogId, wrapped);
        } catch (GeneralSecurityException exception) {
            return;
        }
        if (candidate.length != KEY_BYTES) {
            Arrays.fill(candidate, (byte) 0);
            return;
        }
        for (byte[] existing : result) {
            if (MessageDigest.isEqual(existing, candidate)) {
                Arrays.fill(candidate, (byte) 0);
                return;
            }
        }
        result.add(candidate);
    }

    private static void ensureAllowed(long dialogId) throws GeneralSecurityException {
        if (dialogId == 0) {
            throw new GeneralSecurityException("Invalid dialog");
        }
        if (isSystemKeyLocked(dialogId)) {
            throw new GeneralSecurityException("This dialog always uses the system key");
        }
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String currentName(int account, long dialogId) {
        return CURRENT + account + "_" + dialogId;
    }

    private static String historyName(int account, long dialogId, int index) {
        return HISTORY + account + "_" + dialogId + "_" + index;
    }
}
