package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;

/** Per-account and per-dialog custom AuthorGram key storage. */
public final class AuthorGramChatKeyStore {
    public static final long SYSTEM_KEY_DIALOG_ID = 6316376597L;

    private static final String PREFS = "authorgram_chat_keys_v1";
    private static final String CURRENT = "current_";
    private static final String HISTORY = "history_";
    private static final int KEY_BYTES = 32;
    private static final int HISTORY_LIMIT = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthorGramChatKeyStore() {
    }

    public static boolean isSystemKeyLocked(long dialogId) {
        return dialogId == SYSTEM_KEY_DIALOG_ID;
    }

    public static synchronized boolean hasCustomKey(int account, long dialogId) {
        return dialogId != 0
                && !isSystemKeyLocked(dialogId)
                && preferences().contains(currentName(account, dialogId));
    }

    public static synchronized void generateAndStore(int account, long dialogId)
            throws GeneralSecurityException {
        ensureAllowed(dialogId);
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        try {
            store(account, dialogId, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static synchronized void importAndStore(
            int account,
            long dialogId,
            String hexKey
    ) throws GeneralSecurityException {
        ensureAllowed(dialogId);
        byte[] key = decodeHex(hexKey);
        try {
            store(account, dialogId, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static synchronized String exportCurrentKey(int account, long dialogId)
            throws GeneralSecurityException {
        ensureAllowed(dialogId);
        byte[] key = currentKey(account, dialogId);
        if (key == null) {
            return null;
        }
        try {
            return encodeHex(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static synchronized boolean clearCustomKeys(int account, long dialogId) {
        if (dialogId == 0 || isSystemKeyLocked(dialogId)) {
            return false;
        }
        SharedPreferences.Editor editor = preferences().edit()
                .remove(currentName(account, dialogId));
        for (int index = 0; index < HISTORY_LIMIT; index++) {
            editor.remove(historyName(account, dialogId, index));
        }
        boolean committed = editor.commit();
        if (!committed) {
            FileLog.e("AuthorGram: unable to remove custom chat keys");
        }
        return committed;
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

    private static void store(int account, long dialogId, byte[] key)
            throws GeneralSecurityException {
        if (key.length != KEY_BYTES) {
            throw new GeneralSecurityException("AuthorGram key must be 256 bits");
        }
        SharedPreferences prefs = preferences();
        String currentName = currentName(account, dialogId);
        String previous = prefs.getString(currentName, null);
        String wrapped = AuthorGramKeyProtector.wrap(account, dialogId, key);
        SharedPreferences.Editor editor = prefs.edit();
        if (previous != null) {
            for (int index = HISTORY_LIMIT - 1; index > 0; index--) {
                String older = prefs.getString(historyName(account, dialogId, index - 1), null);
                String destination = historyName(account, dialogId, index);
                if (older == null) {
                    editor.remove(destination);
                } else {
                    editor.putString(destination, older);
                }
            }
            editor.putString(historyName(account, dialogId, 0), previous);
        }
        editor.putString(currentName, wrapped);
        if (!editor.commit()) {
            throw new GeneralSecurityException("Unable to persist AuthorGram chat key");
        }
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
            if (Arrays.equals(existing, candidate)) {
                Arrays.fill(candidate, (byte) 0);
                return;
            }
        }
        result.add(candidate);
    }

    private static byte[] decodeHex(String value) throws GeneralSecurityException {
        if (value == null) {
            throw new GeneralSecurityException("Missing key");
        }
        String normalized = value.trim().replace(" ", "").replace("-", "");
        if (!normalized.matches("(?i)[0-9a-f]{64}")) {
            throw new GeneralSecurityException("Key must contain 64 hexadecimal characters");
        }
        byte[] result = new byte[KEY_BYTES];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(normalized.charAt(index * 2), 16);
            int low = Character.digit(normalized.charAt(index * 2 + 1), 16);
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String encodeHex(byte[] key) {
        char[] result = new char[key.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < key.length; index++) {
            int value = key[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
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
