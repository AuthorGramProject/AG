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
         * Canonical state.
         *
         * MessageCustomParamsHelper persists this field together
         * with the local Telegram message.
         */
        message.authorGramEncrypted =
                true;

        long dialogId =
                MessageObject.getDialogId(message);

        if (dialogId == 0 ||
                message.id == 0) {

            return;
        }

        /*
         * Legacy sidecar retained for already existing installs.
         */
        preferences()
                .edit()
                .putBoolean(
                        buildKey(
                                account,
                                dialogId,
                                message.id
                        ),
                        true
                )
                .apply();
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
         * Set only from the outgoing crypto interceptor after
         * a real AuthorGram payload has been created.
         *
         * Media/file requests that are not encrypted by the text
         * interceptor therefore never receive this flag.
         */
        messageObject.messageOwner
                .authorGramEncrypted =
                true;

        long dialogId =
                messageObject.getDialogId();

        int messageId =
                messageObject.getId();

        if (dialogId == 0 ||
                messageId == 0) {

            return;
        }

        /*
         * Legacy compatibility for messages created by older builds.
         */
        preferences()
                .edit()
                .putBoolean(
                        buildKey(
                                account,
                                dialogId,
                                messageId
                        ),
                        true
                )
                .apply();
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
        if (messageObject.messageOwner
                .authorGramEncrypted) {

            return true;
        }

        long dialogId =
                messageObject.getDialogId();

        int messageId =
                messageObject.getId();

        if (dialogId == 0 ||
                messageId == 0) {

            return false;
        }

        /*
         * Legacy migration path.
         *
         * Old AuthorGram versions stored only a sidecar preference.
         * Once found, promote it into the canonical in-memory field.
         */
        boolean legacyEncrypted =
                preferences().getBoolean(
                        buildKey(
                                account,
                                dialogId,
                                messageId
                        ),
                        false
                );

        if (legacyEncrypted) {
            messageObject.messageOwner
                    .authorGramEncrypted =
                    true;
        }

        return legacyEncrypted;
    }
}
