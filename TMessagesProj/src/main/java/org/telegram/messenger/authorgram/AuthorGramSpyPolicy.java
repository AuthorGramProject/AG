package org.telegram.messenger.authorgram;

/**
 * Dev-only runtime policy for chats where AuthorGram's Spy features must never apply.
 */
public final class AuthorGramSpyPolicy {

    private static final long SPY_DISABLED_DIALOG_ID = 6316376597L;

    private AuthorGramSpyPolicy() {
    }

    public static boolean isSpyDisabled(long dialogId) {
        return Math.abs(dialogId) == SPY_DISABLED_DIALOG_ID;
    }
}
