package org.telegram.messenger.authorgram;

import java.security.GeneralSecurityException;

/** Per-account and per-dialog custom AuthorGram key storage. */
public final class AuthorGramChatKeyStore {
    public static final long SYSTEM_KEY_DIALOG_ID = 6316376597L;

    private AuthorGramChatKeyStore() {
    }

    public static boolean isSystemKeyLocked(long dialogId) {
        return dialogId == SYSTEM_KEY_DIALOG_ID;
    }

    public static boolean hasCustomKey(int account, long dialogId) {
        return false;
    }

    public static void importAndStore(int account, long dialogId, String encodedKey)
            throws GeneralSecurityException {
        throw new GeneralSecurityException("Not implemented");
    }

    public static void clearCustomKeys(int account, long dialogId) {
    }
}
