package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Per-account + per-dialog AuthorGram encryption state.
 *
 * Step 3 intentionally defaults ordinary chats to ON so the
 * text E2EE pipeline can be tested before the UI toggle exists.
 *
 * The later UI step can change DEFAULT_ENABLED to false while
 * preserving explicit per-chat preferences.
 */
public final class AuthorGramChatState {

    private static final String PREFERENCES_NAME =
            "authorgram_crypto";

    private static final String KEY_PREFIX =
            "e2ee_enabled_";

    /*
     * Temporary Step 3 bootstrap behaviour.
     *
     * true:
     * Every ordinary Telegram chat uses AuthorGram encryption
     * unless explicitly disabled.
     *
     * Native Telegram Secret Chats are excluded separately by
     * AuthorGramCryptoInterceptor.
     */
    private static final boolean DEFAULT_ENABLED = false;

    private AuthorGramChatState() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                );
    }

    private static String buildKey(
            int account,
            long dialogId
    ) {
        return KEY_PREFIX
                + account
                + "_"
                + dialogId;
    }

    public static boolean isEnabled(
            int account,
            long dialogId
    ) {
        if (dialogId == 0) {
            return false;
        }

        return preferences().getBoolean(
                buildKey(account, dialogId),
                DEFAULT_ENABLED
        );
    }

    public static void setEnabled(
            int account,
            long dialogId,
            boolean enabled
    ) {
        if (dialogId == 0) {
            return;
        }

        preferences()
                .edit()
                .putBoolean(
                        buildKey(account, dialogId),
                        enabled
                )
                .apply();
    }

    public static boolean toggle(
            int account,
            long dialogId
    ) {
        boolean newValue =
                !isEnabled(account, dialogId);

        setEnabled(
                account,
                dialogId,
                newValue
        );

        return newValue;
    }
}
