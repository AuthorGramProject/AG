package com.radolyn.ayugram.utils;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import java.util.ArrayList;
import java.util.List;

/** Play build: local last-seen inference and persistence are absent. */
public final class LastSeenHelper {
    private LastSeenHelper() { }
    public static void preload() { }
    public static void saveLastSeen(long userId, int timestamp) { }
    public static void saveLastSeen(int currentAccount, long userId, int timestamp) { }
    public static int getLastSeen(long userId) { return 0; }
    public static String getFormattedLastSeenOrDefault(TLRPC.User user, boolean[] madeShorter, String defaultValue) { return defaultValue; }
    public static void saveLastSeenFromLoadedMessages(int currentAccount, long userId, long selfUserId, ArrayList<MessageObject> messages, ChatActivity.ChatActivityAdapter chatAdapter) { }
    public static void saveLastSeenFromMessageReactions(int currentAccount, TLRPC.TL_messageReactions reactions, long selfUserId) { }
    public static void saveLastSeenFromPeerReactions(int currentAccount, List<TLRPC.MessagePeerReaction> reactions, long selfUserId) { }
}
