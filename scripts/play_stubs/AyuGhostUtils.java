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

/** Play build: Ghost request interception is intentionally absent. */
public final class AyuGhostUtils {
    private AyuGhostUtils() { }

    public static Long getDialogId(TLRPC.InputPeer peer) {
        if (peer == null) return null;
        if (peer.chat_id != 0) return -peer.chat_id;
        if (peer.channel_id != 0) return -peer.channel_id;
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

    public static void markReadOnServer(int messageId, TLRPC.InputPeer peer, boolean internal) { }
    public static void markReadOnServer(MessageObject message, boolean internal) { }
    public static void performStatusRequest(Boolean offline) { }

    public static InterceptResult interceptRequest(TLObject object, RequestDelegate onCompleteOrig) {
        return InterceptResult.Proceed(onCompleteOrig);
    }

    public record InterceptResult(boolean blockRequest, RequestDelegate effectiveOnComplete) {
        public static InterceptResult Blocked(RequestDelegate originalOnComplete) {
            return new InterceptResult(false, originalOnComplete);
        }
        public static InterceptResult Proceed(RequestDelegate effectiveOnComplete) {
            return new InterceptResult(false, effectiveOnComplete);
        }
    }
}
