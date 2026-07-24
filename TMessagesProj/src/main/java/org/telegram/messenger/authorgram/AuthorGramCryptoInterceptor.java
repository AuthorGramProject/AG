package org.telegram.messenger.authorgram;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * AuthorGram text interceptor.
 *
 * IMPORTANT:
 *
 * The per-chat toggle controls OUTGOING encryption only.
 *
 * Incoming AuthorGram payload recognition and decryption is always
 * marker-driven and therefore independent of the toggle.
 *
 * Wire format:
 *
 *     🛡AG:Base64(IV || AES-GCM ciphertext || authentication tag)
 *
 * The first 12 decoded bytes are always the public GCM IV.
 */
public final class AuthorGramCryptoInterceptor {

    private AuthorGramCryptoInterceptor() {
    }

    /**
     * Intercepts the final Telegram network request.
     *
     * AuthorGram only encrypts outgoing plaintext when protection
     * is enabled specifically for this dialog.
     *
     * false means encryption was required but failed. The caller
     * must abort sending so plaintext can never leak accidentally.
     */
    public static boolean prepareOutgoingRequest(
            int account,
            TLObject request,
            MessageObject messageObject
    ) {
        if (request == null ||
                messageObject == null) {

            return true;
        }

        long dialogId =
                messageObject.getDialogId();

        /*
         * Native Telegram Secret Chats use Telegram's own protocol.
         */
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return true;
        }

        /*
         * Toggle controls SEND only.
         */
        if (!AuthorGramChatState.isEnabled(
                account,
                dialogId
        )) {
            return true;
        }

        if (request instanceof
                TLRPC.TL_messages_sendMessage) {

            TLRPC.TL_messages_sendMessage sendRequest =
                    (TLRPC.TL_messages_sendMessage) request;

            boolean success =
                    encryptOutgoingText(
                            account,
                            dialogId,
                            sendRequest.message,
                            encrypted ->
                                    sendRequest.message =
                                            encrypted
                    );

            if (success &&
                    AuthorGramCrypto.isAuthorGramPayload(
                            sendRequest.message
                    )) {

                // AUTHORGRAM_STEP5_SANITIZE_REPLY_CALL
                sanitizeEncryptedReply(
                        sendRequest.reply_to
                );


                /*
                 * Plaintext entity offsets and metadata must never
                 * accompany the encrypted wire payload.
                 *
                 * The entity list and its request flag are both
                 * cleared explicitly below.
                 */
                if (sendRequest.entities != null) {
                    sendRequest.entities.clear();
                }
                sendRequest.flags &= ~8;

                AuthorGramMessageMeta.markOutgoing(
                        account,
                        messageObject
                );
            }

            return success;
        }

        if (request instanceof
                TLRPC.TL_messages_editMessage) {

            TLRPC.TL_messages_editMessage editRequest =
                    (TLRPC.TL_messages_editMessage) request;

            boolean success =
                    encryptOutgoingText(
                            account,
                            dialogId,
                            editRequest.message,
                            encrypted ->
                                    editRequest.message =
                                            encrypted
                    );

            if (success &&
                    AuthorGramCrypto.isAuthorGramPayload(
                            editRequest.message
                    )) {

                /*
                 * Edited plaintext entities are invalid once the
                 * edited message body has been encrypted.
                 */
                if (editRequest.entities != null) {
                    editRequest.entities.clear();
                }
                editRequest.flags &= ~8;

                AuthorGramMessageMeta.markOutgoing(
                        account,
                        messageObject
                );
            }

            return success;
        }

        /*
         * File bytes and media captions are handled in the
         * dedicated AuthorGram file/media phase.
         */
        return true;
    }

    /**
     * Marker-driven incoming decryption.
     *
     * This method intentionally does NOT check AuthorGramChatState.
     *
     * Toggle ON:
     *     outgoing messages are encrypted.
     *
     * Toggle OFF:
     *     outgoing messages are plaintext.
     *
     * In BOTH states:
     *     every valid 🛡AG: payload is automatically decrypted.
     */
    public static boolean decryptIncomingMessage(
            int account,
            TLRPC.Message message
    ) {
        if (message == null ||
                message.message == null ||
                !AuthorGramCrypto.isAuthorGramPayload(
                        message.message
                )) {

            return false;
        }

        String plaintext =
                AuthorGramChatCrypto.decryptTextOrNull(
                        account,
                        MessageObject.getDialogId(message),
                        message.message
                );

        if (plaintext == null) {
            /*
             * Invalid Base64, malformed IV or failed AES-GCM tag.
             *
             * Never replace the original payload if authentication
             * did not succeed.
             */
            FileLog.e(
                    "AuthorGram: incoming AES-GCM authentication failed"
            );

            return false;
        }

        /*
         * Replace server ciphertext with authenticated plaintext
         * before the normal Telegram/Nagram UI and storage pipeline.
         */
        message.message =
                plaintext;

        /*
         * Server entities refer to offsets inside ciphertext.
         * They are invalid after replacing the text with plaintext.
         */
        if (message.entities != null) {
            message.entities.clear();
        }

        /*
         * Remember locally that this specific plaintext message came
         * from an authenticated AuthorGram encrypted payload.
         */
        AuthorGramMessageMeta.markDecrypted(
                account,
                message
        );

        /*
         * IMPORTANT:
         *
         * Receiving an encrypted message does NOT automatically
         * enable outgoing AuthorGram encryption for this chat.
         */
        return true;
    }

    // AUTHORGRAM_STEP5_REPLY_SANITIZER
    private static void sanitizeEncryptedReply(
            TLRPC.InputReplyTo replyTo
    ) {
        if (!(replyTo instanceof
                TLRPC.TL_inputReplyToMessage)) {

            return;
        }

        /*
         * Keep the actual reply relationship:
         *
         *     reply_to_msg_id
         *
         * but never send a plaintext quote extracted from the
         * encrypted message body.
         */
        replyTo.flags &=
                ~(
                        TLObject.FLAG_2
                                | TLObject.FLAG_3
                                | TLObject.FLAG_4
                );

        replyTo.quote_text =
                null;

        if (replyTo.quote_entities != null) {
            replyTo.quote_entities.clear();
        }

        replyTo.quote_offset =
                0;
    }


    private static boolean encryptOutgoingText(
            int account,
            long dialogId,
            String plaintext,
            EncryptedTextConsumer consumer
    ) {
        if (plaintext == null ||
                plaintext.isEmpty()) {

            return true;
        }

        /*
         * Protect retries from double encryption.
         */
        if (AuthorGramCrypto.isAuthorGramPayload(
                plaintext
        )) {
            return true;
        }

        String encrypted =
                AuthorGramChatCrypto.encryptText(
                        account,
                        dialogId,
                        plaintext
                );

        if (encrypted == null) {
            /*
             * Fail closed.
             */
            FileLog.e(
                    "AuthorGram: outgoing AES-GCM encryption failed"
            );

            return false;
        }

        consumer.accept(
                encrypted
        );

        return true;
    }

    private interface EncryptedTextConsumer {
        void accept(
                String encryptedText
        );
    }
}
