package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** Per-account and per-dialog AuthorGram outgoing-encryption state. */
public final class AuthorGramChatState {
    private static final String PREFERENCES_NAME = "authorgram_crypto";
    private static final String KEY_PREFIX = "e2ee_enabled_";
    private static final boolean DEFAULT_ENABLED = false;

    private AuthorGramChatState() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }

    private static String buildKey(int account, long dialogId) {
        return KEY_PREFIX + account + "_" + dialogId;
    }

    public static boolean isEnabled(int account, long dialogId) {
        if (!AuthorGramPlayPolicy.canEnableEncryption(account, dialogId)) {
            return false;
        }
        return preferences().getBoolean(buildKey(account, dialogId), DEFAULT_ENABLED);
    }

    public static void setEnabled(int account, long dialogId, boolean enabled) {
        if (dialogId == 0) {
            return;
        }
        if (enabled && !AuthorGramPlayPolicy.canEnableEncryption(account, dialogId)) {
            preferences().edit().remove(buildKey(account, dialogId)).apply();
            return;
        }
        preferences().edit().putBoolean(buildKey(account, dialogId), enabled).apply();
    }

    public static boolean toggle(int account, long dialogId) {
        if (!AuthorGramPlayPolicy.canEnableEncryption(account, dialogId)) {
            setEnabled(account, dialogId, false);
            return false;
        }
        boolean newValue = !isEnabled(account, dialogId);
        setEnabled(account, dialogId, newValue);
        return newValue;
    }
}
