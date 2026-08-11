package org.telegram.messenger.authorgram;

/**
 * Play-Market compatibility facade.
 *
 * Custom AuthorGram encryption is not available in Play and no per-chat state is
 * persisted. Keeping these method signatures avoids invasive Telegram-core edits.
 */
public final class AuthorGramChatState {

    private AuthorGramChatState() {
    }

    public static boolean isEnabled(int account, long dialogId) {
        return false;
    }

    public static void setEnabled(int account, long dialogId, boolean enabled) {
    }

    public static boolean toggle(int account, long dialogId) {
        return false;
    }
}
