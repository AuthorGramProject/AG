package org.telegram.messenger.authorgram;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.util.List;

/**
 * AuthorGram text interceptor.
 *
 * The per-chat toggle controls outgoing encryption only. Incoming AuthorGram
 * payload recognition and decryption remains marker-driven and always active.
 */
public final class AuthorGramCryptoInterceptor {

    private AuthorGramCryptoInterceptor() {
    }

    /**
     * Intercepts the final Telegram network request.
     *
     * false means encryption was required but failed. The caller must abort
     * sending so plaintext can never leak accidentally.
     */
    public static boolean prepareOutgoingRequest(
            int account,
            TLObject request,
            MessageObject messageObject
    ) {
        if (request == null || messageObject == null) {
            return true;
        }

        long dialogId = messageObject.getDialogId();

        /* Native Telegram Secret Chats use Telegram's own protocol. */
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return true;
        }

        /*
         * An authenticated AuthorGram message may be replied to normally, but
         * never with Telegram's plaintext quote payload. This rule is applied
         * before the outgoing-encryption toggle check, so it also protects
         * replies sent while AuthorGram encryption is temporarily disabled.
         *
         * Reflection is intentional here: Telegram has several send request
         * classes with the same public reply_to field. Handling the field once
         * covers text, media, albums and inline results without fragile casts.
         */
        sanitizeReplyToEncryptedSource(account, request, messageObject);

        /* Toggle controls encryption of future outgoing content only. */
        if (!AuthorGramChatState.isEnabled(account, dialogId)) {
            return true;
        }

        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage sendRequest =
                    (TLRPC.TL_messages_sendMessage) request;

            boolean success = encryptOutgoingText(
                    account,
                    dialogId,
                    sendRequest.message,
                    encrypted -> sendRequest.message = encrypted
            );

            if (success && AuthorGramCrypto.isAuthorGramPayload(sendRequest.message)) {
                /*
                 * Encrypted outgoing text can never carry any plaintext quote,
                 * even when the replied-to message itself was not encrypted.
                 */
                sanitizeEncryptedReply(sendRequest.reply_to);

                if (sendRequest.entities != null) {
                    sendRequest.entities.clear();
                }
                sendRequest.flags &= ~8;

                AuthorGramMessageMeta.markOutgoing(account, messageObject);
            }

            return success;
        }

        if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage editRequest =
                    (TLRPC.TL_messages_editMessage) request;

            boolean success = encryptOutgoingText(
                    account,
                    dialogId,
                    editRequest.message,
                    encrypted -> editRequest.message = encrypted
            );

            if (success && AuthorGramCrypto.isAuthorGramPayload(editRequest.message)) {
                if (editRequest.entities != null) {
                    editRequest.entities.clear();
                }
                editRequest.flags &= ~8;

                AuthorGramMessageMeta.markOutgoing(account, messageObject);
            }

            return success;
        }

        /* File bytes and media captions are handled by their dedicated phase. */
        return true;
    }

    /**
     * Marker-driven incoming decryption, independent of the outgoing toggle.
     *
     * AUTHORGRAM_PLAY_STABLE_REPLY_MODEL
     * Only the message currently owned by Telegram's load/update pipeline is
     * mutated here. Do not recursively mutate message.replyMessage: Telegram can
     * share that nested object across reply previews/history caches, and changing
     * it during outer-message processing can corrupt reply ownership or create
     * re-entrant ChatMessageCell updates. This intentionally matches the stable
     * Play path. Nested reply targets are decrypted only when they pass through
     * the normal incoming-message pipeline themselves.
     */
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
            FileLog.e("AuthorGram: incoming AES-GCM authentication failed");
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
            // This request type does not support replies.
        } catch (IllegalAccessException exception) {
            FileLog.e("AuthorGram: unable to sanitize outgoing reply metadata", exception);
        }

        sanitizeLocalReplyHeader(outgoingMessage);
    }

    /**
     * Keeps reply_to_msg_id but removes Telegram's optional plaintext quote.
     */
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

    /**
     * Clears the local outgoing model as well, preventing a transient quoted
     * preview from remaining visible after the request has become a normal reply.
     */
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
            /* MessageReplyHeader quote_text/entities/offset bits: 64, 128, 1024. */
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
            // Telegram schema variants may omit an optional field.
        }
    }

    private static boolean encryptOutgoingText(
            int account,
            long dialogId,
            String plaintext,
            EncryptedTextConsumer consumer
    ) {
        if (plaintext == null || plaintext.isEmpty()) {
            return true;
        }

        if (AuthorGramCrypto.isAuthorGramPayload(plaintext)) {
            return true;
        }

        String encrypted = AuthorGramChatCrypto.encryptText(
                account,
                dialogId,
                plaintext
        );

        if (encrypted == null) {
            FileLog.e("AuthorGram: outgoing AES-GCM encryption failed");
            return false;
        }

        consumer.accept(encrypted);
        return true;
    }

    private interface EncryptedTextConsumer {
        void accept(String encryptedText);
    }
}
