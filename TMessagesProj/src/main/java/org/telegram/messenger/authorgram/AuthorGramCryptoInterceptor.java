package org.telegram.messenger.authorgram;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Play-Market compatibility facade.
 *
 * AuthorGram's custom wire-format encryption/decryption is Main-only. The Play
 * implementation is deliberately a pass-through so no preference or policy-bit
 * modification can reactivate custom Telegram payload handling.
 */
public final class AuthorGramCryptoInterceptor {

    private AuthorGramCryptoInterceptor() {
    }

    public static boolean prepareOutgoingRequest(
            int account,
            TLObject request,
            MessageObject messageObject
    ) {
        return true;
    }

    public static boolean decryptIncomingMessage(int account, TLRPC.Message message) {
        return false;
    }
}
