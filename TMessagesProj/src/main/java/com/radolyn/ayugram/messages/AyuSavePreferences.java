package com.radolyn.ayugram.messages;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/**
 * Play-Market compatibility value object.
 *
 * Deleted-message retention is deliberately not implemented in Play. All policy
 * entry points return false/no-op, so changing a preference cannot reactivate it.
 */
public final class AyuSavePreferences {
    public static final String saveExclusionPrefix = "saveDeletedExclusion_";

    private final TLRPC.Message message;
    private final int accountId;
    private final long userId;
    private long dialogId = -1;
    private long topicId = -1;
    private int messageId = -1;
    private int requestCatchTime = -1;

    public AyuSavePreferences(
            TLRPC.Message msg,
            int accountId,
            long dialogId,
            long topicId,
            int messageId,
            int requestCatchTime
    ) {
        this.message = msg;
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
        this.requestCatchTime = requestCatchTime;
    }

    public AyuSavePreferences(TLRPC.Message msg, int accountId) {
        this.message = msg;
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();
        if (msg != null) {
            this.dialogId = msg.dialog_id;
            this.topicId = MessageObject.getTopicId(accountId, msg, false);
            this.messageId = msg.id;
            this.requestCatchTime = (int) (System.currentTimeMillis() / 1000L);
        }
    }

    public static boolean saveDeletedMessageFor(
            int accountId,
            long dialogId,
            MessageObject messageObject
    ) {
        return false;
    }

    public static boolean saveDeletedMessageFor(int accountId, long dialogId, long userId) {
        return false;
    }

    public static void setSaveDeletedExclusion(long chatId, boolean value) {
        // No retention exclusions exist because retention itself is absent in Play.
    }

    public static boolean getSaveDeletedExclusion(long chatId) {
        return false;
    }

    public static void loadAllExclusions() {
        // No persistent retention state is loaded in Play.
    }

    public TLRPC.Message getMessage() {
        return message;
    }

    public int getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public long getDialogId() {
        return dialogId;
    }

    public void setDialogId(long dialogId) {
        if (dialogId != 0) {
            this.dialogId = dialogId;
        }
    }

    public long getTopicId() {
        return topicId;
    }

    public int getMessageId() {
        return messageId;
    }

    public int getRequestCatchTime() {
        return requestCatchTime;
    }

    public long getFromUserId() {
        if (message == null || message.from_id == null) {
            return 0;
        }
        return message.from_id.user_id;
    }
}
