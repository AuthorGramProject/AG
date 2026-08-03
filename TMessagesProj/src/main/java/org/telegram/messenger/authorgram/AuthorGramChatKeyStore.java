package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

/** Per-account and per-dialog AuthorGram key storage and passphrase derivation. */
public final class AuthorGramChatKeyStore {
    public static final long SYSTEM_KEY_DIALOG_ID = AuthorGramPlayPolicy.OWNER_DIALOG_ID;

    private static final String PREFS = "authorgram_chat_keys_v1";
    private static final String CURRENT = "current_";
    private static final String HISTORY = "history_";
    private static final int KEY_BYTES = AuthorGramPassphraseKdf.KEY_BYTES;
    private static final int HISTORY_LIMIT = 5;

    private AuthorGramChatKeyStore() {
    }

    public static boolean isSystemKeyLocked(long dialogId) {
        return dialogId == SYSTEM_KEY_DIALOG_ID;
    }

    public static int getMaxPassphraseCodePoints() {
        return AuthorGramPassphraseKdf.MAX_CODE_POINTS;
    }

    public static synchronized boolean hasCustomKey(int account, long dialogId) {
        return dialogId != 0
                && !AuthorGramPlayPolicy.isEncryptionForbidden(dialogId)
                && !isSystemKeyLocked(dialogId)
                && preferences().contains(currentName(account, dialogId));
    }

    public static void deriveAndStore(int account, long dialogId, char[] passphrase)
            throws GeneralSecurityException {
        ensureAllowed(dialogId);
        if (passphrase == null) {
            throw new GeneralSecurityException("Missing passphrase");
        }

        byte[] key = null;
        try {
            key = deriveKey(account, dialogId, passphrase);
            synchronized (AuthorGramChatKeyStore.class) {
                store(account, dialogId, key);
            }
        } finally {
            Arrays.fill(passphrase, '\0');
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    public static synchronized boolean useSystemKey(int account, long dialogId) {
        if (AuthorGramPlayPolicy.isPlayBuild()
                || dialogId == 0
                || isSystemKeyLocked(dialogId)) {
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

    @Deprecated
    public static synchronized boolean clearCustomKeys(int account, long dialogId) {
        return useSystemKey(account, dialogId);
    }

    static synchronized byte[] getCurrentKey(int account, long dialogId) {
        if (dialogId == 0
                || AuthorGramPlayPolicy.isEncryptionForbidden(dialogId)
                || isSystemKeyLocked(dialogId)) {
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
        if (dialogId == 0
                || AuthorGramPlayPolicy.isEncryptionForbidden(dialogId)
                || isSystemKeyLocked(dialogId)) {
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
        return AuthorGramPassphraseKdf.derive(
                passphrase,
                stableKdfScope(account, dialogId)
        );
    }

    private static String stableKdfScope(int account, long dialogId)
            throws GeneralSecurityException {
        if (dialogId > 0) {
            UserConfig config = UserConfig.getInstance(account);
            long ownUserId = config.getClientUserId();
            if (ownUserId <= 0) {
                TLRPC.User currentUser = config.getCurrentUser();
                if (currentUser != null) {
                    ownUserId = currentUser.id;
                }
            }
            if (ownUserId <= 0) {
                throw new GeneralSecurityException(
                        "AuthorGram account identity is unavailable"
                );
            }
            long low = Math.min(ownUserId, dialogId);
            long high = Math.max(ownUserId, dialogId);
            return AuthorGramPassphraseKdf.DOMAIN
                    + "|private|" + low + "|" + high;
        }
        return AuthorGramPassphraseKdf.DOMAIN + "|dialog|" + dialogId;
    }

    private static void store(int account, long dialogId, byte[] key)
            throws GeneralSecurityException {
        if (key.length != KEY_BYTES) {
            throw new GeneralSecurityException("AuthorGram key must be 256 bits");
        }

        byte[] existingKey = null;
        boolean staleStoredKey = false;
        try {
            existingKey = currentKey(account, dialogId);
        } catch (GeneralSecurityException exception) {
            staleStoredKey = true;
            FileLog.e(
                    "AuthorGram: replacing an unreadable stored chat key",
                    exception
            );
        }

        try {
            if (existingKey != null && MessageDigest.isEqual(existingKey, key)) {
                return;
            }
        } finally {
            if (existingKey != null) {
                Arrays.fill(existingKey, (byte) 0);
            }
        }

        if (staleStoredKey && !removeStoredKeys(account, dialogId)) {
            throw new GeneralSecurityException(
                    "Unable to remove unreadable AuthorGram chat key"
            );
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

    private static boolean removeStoredKeys(int account, long dialogId) {
        SharedPreferences.Editor editor = preferences().edit()
                .remove(currentName(account, dialogId));
        for (int index = 0; index < HISTORY_LIMIT; index++) {
            editor.remove(historyName(account, dialogId, index));
        }
        return editor.commit();
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
        if (AuthorGramPlayPolicy.isEncryptionForbidden(dialogId)) {
            throw new GeneralSecurityException(
                    "Encryption is unavailable for this Play Market dialog"
            );
        }
        if (isSystemKeyLocked(dialogId)) {
            throw new GeneralSecurityException(
                    "This dialog always uses the private Main system key"
            );
        }
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    private static String currentName(int account, long dialogId) {
        return CURRENT + account + "_" + dialogId;
    }

    private static String historyName(int account, long dialogId, int index) {
        return HISTORY + account + "_" + dialogId + "_" + index;
    }
}
