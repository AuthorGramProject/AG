package org.telegram.messenger.authorgram;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Play-Market crypto compatibility boundary.
 *
 * Play can recognize/decrypt incoming AuthorGram payloads for compatibility, but
 * contains no outgoing-encryption path. Replies to encrypted messages still drop
 * optional plaintext quote metadata so a normal Play reply cannot leak quoted text.
 */
public final class AuthorGramCryptoInterceptor {

    private AuthorGramCryptoInterceptor() {
    }

    public static boolean prepareOutgoingRequest(
            int account,
            TLObject request,
            MessageObject messageObject
    ) {
        if (request != null && messageObject != null) {
            sanitizeReplyToEncryptedSource(account, request, messageObject);
        }
        return true;
    }

    public static boolean decryptIncomingMessage(int account, TLRPC.Message message) {
        if (message == null
                || message.message == null
                || !AuthorGramCrypto.isAuthorGramPayload(message.message)) {
            return false;
        }

        String plaintext = AuthorGramChatCrypto.decryptTextOrNull(
                account,
                MessageObject.getDialogId(message),
                message.message
        );
        if (plaintext == null) {
            return false;
        }

        message.message = plaintext;
        if (message.entities != null) {
            message.entities.clear();
        }
        AuthorGramMessageMeta.markDecrypted(account, message);
        return true;
    }

    private static void sanitizeReplyToEncryptedSource(
            int account,
            TLObject request,
            MessageObject outgoingMessage
    ) {
        MessageObject repliedMessage = outgoingMessage.replyMessageObject;
        if (!AuthorGramMessageMeta.isKnownEncrypted(account, repliedMessage)) {
            return;
        }

        try {
            Field replyField = request.getClass().getField("reply_to");
            Object replyValue = replyField.get(request);
            if (replyValue instanceof TLRPC.InputReplyTo) {
                sanitizeEncryptedReply((TLRPC.InputReplyTo) replyValue);
            }
        } catch (NoSuchFieldException ignored) {
            // Request type does not support replies.
        } catch (IllegalAccessException exception) {
            FileLog.e("AuthorGram: unable to sanitize outgoing reply metadata", exception);
        }

        sanitizeLocalReplyHeader(outgoingMessage);
    }

    private static void sanitizeEncryptedReply(TLRPC.InputReplyTo replyTo) {
        if (!(replyTo instanceof TLRPC.TL_inputReplyToMessage)) {
            return;
        }
        replyTo.flags &= ~(TLObject.FLAG_2 | TLObject.FLAG_3 | TLObject.FLAG_4);
        replyTo.quote_text = null;
        if (replyTo.quote_entities != null) {
            replyTo.quote_entities.clear();
        }
        replyTo.quote_offset = 0;
    }

    private static void sanitizeLocalReplyHeader(MessageObject outgoingMessage) {
        if (outgoingMessage.messageOwner == null || outgoingMessage.messageOwner.reply_to == null) {
            return;
        }

        Object header = outgoingMessage.messageOwner.reply_to;
        setFieldValue(header, "quote_text", null);
        setFieldValue(header, "quote_offset", 0);
        setFieldValue(header, "quote", false);

        Object entities = getFieldValue(header, "quote_entities");
        if (entities instanceof List) {
            ((List<?>) entities).clear();
        }

        Object flags = getFieldValue(header, "flags");
        if (flags instanceof Integer) {
            setFieldValue(header, "flags", ((Integer) flags) & ~(64 | 128 | 1024));
        }
    }

    private static Object getFieldValue(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static void setFieldValue(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getField(name);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // Telegram schema variants may omit optional quote fields.
        }
    }
}
