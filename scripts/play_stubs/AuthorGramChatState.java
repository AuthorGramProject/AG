package org.telegram.messenger.authorgram;

/** Play build: outgoing AuthorGram encryption state is intentionally absent. */
public final class AuthorGramChatState {
    private AuthorGramChatState() { }
    public static boolean isEnabled(int account, long dialogId) { return false; }
    public static void setEnabled(int account, long dialogId, boolean enabled) { }
    public static boolean toggle(int account, long dialogId) { return false; }
}
