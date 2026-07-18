package org.telegram.messenger.authorgram;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

/**
 * AuthorGram local message metadata.
 *
 * The AuthorGram flag is stored directly on TLRPC.Message and
 * persisted through the existing messages_v2.custom_params column.
 */
public final class AuthorGramMessageMeta {

    private AuthorGramMessageMeta() {
    }

    public static void markDecrypted(
            int account,
            TLRPC.Message message
    ) {
        if (message != null) {
            message.authorGramEncrypted =
                    true;
        }
    }

    public static void markOutgoing(
            int account,
            MessageObject messageObject
    ) {
        if (messageObject != null
                && messageObject.messageOwner != null) {

            messageObject.messageOwner.authorGramEncrypted =
                    true;
        }
    }

    public static boolean isKnownEncrypted(
            int account,
            MessageObject messageObject
    ) {
        return messageObject != null
                && messageObject.messageOwner != null
                && messageObject.messageOwner.authorGramEncrypted;
    }
}
