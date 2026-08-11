package com.radolyn.ayugram.utils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Play-Market compatibility surface for former Ghost Mode hooks.
 *
 * The Play build never blocks, rewrites or fabricates Telegram network requests.
 * This class intentionally contains no Ghost Mode implementation; public method
 * signatures are retained only so shared Telegram code can compile unchanged.
 */
public final class AyuGhostUtils {

    private AyuGhostUtils() {
    }

    public static Long getDialogId(TLRPC.InputPeer peer) {
        if (peer == null) {
            return null;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        return peer.user_id;
    }

    public static Long getDialogId(TLRPC.InputChannel peer) {
        return peer == null ? null : -peer.channel_id;
    }

    public static Long getDialogId(TLRPC.TL_inputEncryptedChat peer) {
        return peer == null ? null : (long) DialogObject.getEncryptedChatId(peer.chat_id);
    }

    public static ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount);
    }

    public static MessagesController getMessagesController() {
        return MessagesController.getInstance(UserConfig.selectedAccount);
    }

    public static MessagesStorage getMessagesStorage() {
        return MessagesStorage.getInstance(UserConfig.selectedAccount);
    }

    public static void markReadOnServer(int messageId, TLRPC.InputPeer peer, boolean internal) {
        // Intentionally unavailable in Play. Telegram's native read path remains authoritative.
    }

    public static void markReadOnServer(MessageObject message, boolean internal) {
        // Intentionally unavailable in Play. Telegram's native read path remains authoritative.
    }

    public static void performStatusRequest(Boolean offline) {
        // Intentionally unavailable in Play. Telegram controls presence normally.
    }

    public static InterceptResult interceptRequest(TLObject object, RequestDelegate onCompleteOrig) {
        return InterceptResult.Proceed(onCompleteOrig);
    }

    public record InterceptResult(boolean blockRequest, RequestDelegate effectiveOnComplete) {
        public static InterceptResult Blocked(RequestDelegate originalOnComplete) {
            // Compatibility only: Play never requests this result itself.
            return new InterceptResult(false, originalOnComplete);
        }

        public static InterceptResult Proceed(RequestDelegate effectiveOnComplete) {
            return new InterceptResult(false, effectiveOnComplete);
        }
    }
}
