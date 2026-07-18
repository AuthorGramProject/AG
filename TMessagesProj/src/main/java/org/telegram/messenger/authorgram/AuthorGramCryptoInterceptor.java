package org.telegram.messenger.authorgram;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Isolated AuthorGram text interceptor.
 *
 * Local MessageObject:
 *     plaintext
 *
 * Telegram request:
 *     🛡AG:<encrypted payload>
 *
 * Incoming Telegram message:
 *     ciphertext
 *         ↓
 *     authenticated AES-GCM decrypt
 *         ↓
 *     plaintext before normal UI/storage processing
 */
public final class AuthorGramCryptoInterceptor {

    private AuthorGramCryptoInterceptor() {
    }

    /**
     * Called immediately before Telegram sends the final request.
     *
     * true:
     *     request can continue
     *
     * false:
     *     encryption was required but failed;
     *     caller must abort the network send to prevent plaintext leak
     */
    public static boolean prepareOutgoingRequest(
            int account,
            TLObject request,
            MessageObject messageObject
    ) {
        if (request == null || messageObject == null) {
            return true;
        }

        long dialogId =
                messageObject.getDialogId();

        /*
         * Never interfere with Telegram's own native
         * Secret Chat implementation.
         */
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return true;
        }

        if (!AuthorGramChatState.isEnabled(
                account,
                dialogId
        )) {
            return true;
        }

        /*
         * Normal outgoing text message.
         */
        if (request instanceof
                TLRPC.TL_messages_sendMessage) {

            TLRPC.TL_messages_sendMessage sendRequest =
                    (TLRPC.TL_messages_sendMessage) request;

            return encryptOutgoingText(
                    sendRequest.message,
                    encrypted ->
                            sendRequest.message = encrypted
            );
        }

        /*
         * Edited text message.
         *
         * This keeps edited AuthorGram messages encrypted
         * on Telegram as well.
         */
        if (request instanceof
                TLRPC.TL_messages_editMessage) {

            TLRPC.TL_messages_editMessage editRequest =
                    (TLRPC.TL_messages_editMessage) request;

            return encryptOutgoingText(
                    editRequest.message,
                    encrypted ->
                            editRequest.message = encrypted
            );
        }

        /*
         * Step 3 intentionally does not encrypt:
         *
         * - file bytes
         * - media captions
         * - grouped media
         *
         * Those receive their dedicated pipeline later.
         */
        return true;
    }

    /**
     * Called as soon as an incoming TLRPC.Message is extracted
     * from a Telegram Update.
     *
     * Incoming decryption is marker-driven and does NOT depend
     * on the local toggle state.
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
                AuthorGramCrypto.decryptTextOrNull(
                        message.message
                );

        /*
         * Authentication failure:
         *
         * leave the original ciphertext untouched.
         */
        if (plaintext == null) {
            FileLog.e(
                    "AuthorGram: incoming AES-GCM authentication failed"
            );

            return false;
        }

        message.message = plaintext;

        /*
         * Telegram entities were calculated for the wire-level
         * ciphertext, not for the decrypted plaintext.
         *
         * Keeping those old offsets can produce incorrect formatting
         * or out-of-range spans in the UI.
         *
         * Formatting-preserving encrypted payloads can be added in a
         * later protocol version.
         */
        if (message.entities != null) {
            message.entities.clear();
        }

        /*
         * An authenticated AuthorGram message proves this dialog is
         * participating in the AuthorGram protocol.
         */
        long dialogId =
                MessageObject.getDialogId(message);

        if (dialogId != 0 &&
                !DialogObject.isEncryptedDialog(dialogId)) {

            AuthorGramChatState.setEnabled(
                    account,
                    dialogId,
                    true
            );
        }

        return true;
    }

    private static boolean encryptOutgoingText(
            String plaintext,
            EncryptedTextConsumer consumer
    ) {
        if (plaintext == null ||
                plaintext.isEmpty()) {

            return true;
        }

        /*
         * File-reference retries and delayed requests can re-enter
         * the final send method.
         *
         * Never encrypt an AuthorGram payload twice.
         */
        if (AuthorGramCrypto.isAuthorGramPayload(
                plaintext
        )) {
            return true;
        }

        String encrypted =
                AuthorGramCrypto.encryptText(plaintext);

        if (encrypted == null) {
            /*
             * Fail closed.
             *
             * Returning false prevents the caller from sending the
             * original plaintext to Telegram.
             */
            FileLog.e(
                    "AuthorGram: outgoing AES-GCM encryption failed"
            );

            return false;
        }

        consumer.accept(encrypted);

        return true;
    }

    private interface EncryptedTextConsumer {
        void accept(String encryptedText);
    }
}
