package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

/**
 * Local metadata for messages that were actually encrypted
 * or successfully authenticated and decrypted by AuthorGram.
 */
public final class AuthorGramMessageMeta {

    private static final String PREFERENCES_NAME =
            "authorgram_message_meta";

    private static final String KEY_PREFIX =
            "encrypted_";

    private AuthorGramMessageMeta() {
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
            long dialogId,
            int messageId
    ) {
        return KEY_PREFIX
                + account
                + "_"
                + dialogId
                + "_"
                + messageId;
    }

    public static void markDecrypted(
            int account,
            TLRPC.Message message
    ) {
        if (message == null) {
            return;
        }

        /*
         * Canonical state. MessageCustomParamsHelper persists this
         * field together with the local Telegram message.
         *
         * Older AuthorGram builds also wrote one SharedPreferences
         * entry per message. New writes intentionally stop here so
         * that the legacy sidecar can no longer grow without bound.
         */
        message.authorGramEncrypted = true;
    }

    public static void markOutgoing(
            int account,
            MessageObject messageObject
    ) {
        if (messageObject == null ||
                messageObject.messageOwner == null) {

            return;
        }

        /*
         * Set only from the outgoing crypto interceptor after a real
         * AuthorGram payload has been created. Media/file requests
         * that are not encrypted by the text interceptor therefore
         * never receive this flag.
         *
         * The canonical custom parameter is persisted by Telegram;
         * no new per-message preference entry is created.
         */
        messageObject.messageOwner.authorGramEncrypted = true;
    }

    public static boolean isKnownEncrypted(
            int account,
            MessageObject messageObject
    ) {
        if (messageObject == null ||
                messageObject.messageOwner == null) {

            return false;
        }

        /*
         * Primary source of truth.
         */
        if (messageObject.messageOwner.authorGramEncrypted) {
            return true;
        }

        long dialogId = messageObject.getDialogId();
        int messageId = messageObject.getId();

        if (dialogId == 0 || messageId == 0) {
            return false;
        }

        /*
         * Read-only legacy migration path.
         *
         * Old AuthorGram versions stored only a sidecar preference.
         * Once found, promote it into the canonical in-memory field.
         * The sidecar is no longer extended by current builds.
         */
        boolean legacyEncrypted = preferences().getBoolean(
                buildKey(account, dialogId, messageId),
                false
        );

        if (legacyEncrypted) {
            messageObject.messageOwner.authorGramEncrypted = true;
        }

        return legacyEncrypted;
    }
}
