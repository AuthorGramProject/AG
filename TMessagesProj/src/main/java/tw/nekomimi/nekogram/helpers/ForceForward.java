package tw.nekomimi.nekogram.helpers;

import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class ForceForward implements NotificationCenter.NotificationCenterDelegate {

    // Static state map: target dialogId -> ForceForward instance
    // This allows any ChatActivity to check if its dialog is the target of a forwarding operation
    private static final ConcurrentHashMap<Long, ForceForward> activeForwards = new ConcurrentHashMap<>();

    private static class GroupedMediaItem {
        final MessageObject messageObject;
        final String caption;

        GroupedMediaItem(MessageObject messageObject, String caption) {
            this.messageObject = messageObject;
            this.caption = caption;
        }
    }

    public interface CompletionCallback {
        void onComplete(boolean shouldContinue);
    }

    private static final long MAX_MEDIA_WAIT_MS = 300_000L;
    private static final long MAX_MEDIA_STALL_TIMEOUT_MS = 30_000L;
    private static final long STALL_CHECK_INTERVAL_MS = 5_000L;
    private static final long SEND_STEP_DELAY_MS = 16L;
    private static final long SEND_PHASE_FINISH_DELAY_MS = 180L;
    private static final long SEND_WAIT_POLL_INTERVAL_MS = 25L;
    private static final long MAX_SEND_WAIT_MS = 600_000L;
    private static final int STATUS_REFRESH_MASK_BASE = 1 << 30;
    private static final int MAX_GROUP_BATCH_SIZE = 10;
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_LOADING = 1;
    private static final int STATUS_FORWARDING = 2;
    private static final int STATUS_STOPPING = 3;

    private final ChatActivity parentFragment;
    private final int currentAccount;
    private final MessageObject replyToTopMessage;
    private final int chatMode;
    private final String quickReplyShortcut;
    private final int quickReplyShortcutId;
    private final long monoForumPeerId;
    private final MessageSuggestionParams suggestionParams;
    private long targetDialogId; // The target dialog where messages are being forwarded to
    private long activeTaskId;
    private int currentStatus = STATUS_IDLE;
    private int totalMessages;
    private int sentMessages;
    private int skippedMessages;
    private int totalMediaToPrepare;
    private int pendingMedia;
    private String currentStatusDetail;
    private String lastFailureReason;
    private boolean stopRequested;
    private long mediaWaitStartTime;
    private long mediaLastProgressTime;
    private long mediaLastObservedBytes;
    private int mediaLastMissingCount;
    private final HashSet<String> pendingDownloadKeys = new HashSet<>();
    private boolean disposed;
    private boolean detached; // True after source fragment is destroyed while forwarding
    private boolean notificationSubscribed;
    // Pending run state for event-driven media wait
    private ArrayList<MessageObject> pendingMessages;
    private long pendingTargetDialogId;
    private boolean pendingShowUndo;
    private boolean pendingHideCaption;
    private boolean pendingNotify;
    private int pendingScheduleDate;
    private long pendingPayStars;
    private long pendingTaskId;
    private CompletionCallback pendingOnComplete;
    private Runnable stallCheckRunnable;
    private int currentChunkIndex;
    private int totalChunks = 1;
    private int statusUpdateVersion;
    private Runnable sendWaitRunnable;
    private ArrayList<SendStep> activeSendSteps;
    private int activeSendStepIndex;
    private CompletionCallback activeSendOnComplete;
    private long sendWaitTaskId;
    private int sendWaitExpectedCount;
    private int sendWaitCompletedCount;
    private long sendWaitStartTime;
    private long sendWaitLastActivityTime;
    private final HashSet<Integer> sendWaitBaselineIds = new HashSet<>();
    private final HashSet<Integer> sendWaitTrackedIds = new HashSet<>();
    private final HashSet<Integer> sendWaitHandledIds = new HashSet<>();
    private final HashSet<Integer> sendWaitAckedIds = new HashSet<>();
    private final HashSet<Integer> sendWaitPendingErrorIds = new HashSet<>();
    private final HashSet<String> sendWaitUploadPaths = new HashSet<>();

    private static class MediaPreparationResult {
        int missingMediaCount;
        int activeDownloadCount;
        long downloadedBytes;
        boolean cancelledByUser;
        boolean hasUndownloadedAyuDeletedMedia;
    }

    private static class SendDispatchResult {
        final int expectedCompletions;
        final ArrayList<String> uploadPaths;

        private SendDispatchResult(int expectedCompletions, ArrayList<String> uploadPaths) {
            this.expectedCompletions = expectedCompletions;
            this.uploadPaths = uploadPaths;
        }

        static SendDispatchResult none() {
            return new SendDispatchResult(0, null);
        }

        static SendDispatchResult dispatched(int expectedCompletions, ArrayList<String> uploadPaths) {
            return new SendDispatchResult(expectedCompletions, uploadPaths);
        }
    }

    @FunctionalInterface
    private interface SendStep {
        SendDispatchResult dispatch();
    }
    
    public ForceForward(ChatActivity fragment, int account) {
        this(
                fragment,
                account,
                fragment != null ? fragment.getThreadMessage() : null,
                fragment != null ? fragment.getChatMode() : 0,
                fragment != null ? fragment.quickReplyShortcut : null,
                fragment != null ? fragment.getQuickReplyId() : 0,
                fragment != null ? fragment.getSendMonoForumPeerId() : 0,
                fragment != null ? fragment.getSendMessageSuggestionParams() : null
        );
    }

    public ForceForward(int account, MessageObject replyToTopMessage, int chatMode, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this(null, account, replyToTopMessage, chatMode, quickReplyShortcut, quickReplyShortcutId, monoForumPeerId, suggestionParams);
    }

    private ForceForward(ChatActivity fragment, int account, MessageObject replyToTopMessage, int chatMode, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this.parentFragment = fragment;
        this.currentAccount = account;
        this.replyToTopMessage = replyToTopMessage;
        this.chatMode = chatMode;
        this.quickReplyShortcut = quickReplyShortcut;
        this.quickReplyShortcutId = quickReplyShortcutId;
        this.monoForumPeerId = monoForumPeerId;
        this.suggestionParams = suggestionParams;
    }

    public static boolean isChatNoForwards(MessageObject messageObject) {
        if (messageObject == null || messageObject.currentAccount < 0) {
            return false;
        }
        long chatId = messageObject.getChatId();
        if (chatId < 0) {
            chatId = -chatId;
        }
        if (chatId == 0) {
            return false;
        }
        MessagesController controller = MessagesController.getInstance(messageObject.currentAccount);
        TLRPC.Chat chat = controller.getChat(chatId);
        if (chat == null) {
            return false;
        }
        // Forbidden channels/chats — noforwards by definition
        if (chat instanceof TLRPC.TL_channelForbidden || chat instanceof TLRPC.TL_chatForbidden) {
            return true;
        }
        // Banned members who cannot view messages
        if (chat.banned_rights != null && chat.banned_rights.view_messages) {
            return true;
        }
        if (chat.noforwards) {
            return true;
        }
        // Follow migrated_to group
        if (chat.migrated_to != null) {
            TLRPC.Chat migratedTo = controller.getChat(chat.migrated_to.channel_id);
            if (migratedTo != null) {
                if (migratedTo instanceof TLRPC.TL_channelForbidden || migratedTo instanceof TLRPC.TL_chatForbidden) {
                    return true;
                }
                if (migratedTo.banned_rights != null && migratedTo.banned_rights.view_messages) {
                    return true;
                }
                return migratedTo.noforwards;
            }
        }
        return false;
    }

    public static boolean isPeerNoForwards(MessageObject messageObject) {
        if (messageObject == null || messageObject.currentAccount < 0) {
            return false;
        }
        long dialogId = messageObject.getDialogId();
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (dialogId > 0) {
            // User noforwards — check both directions
            if (MessagesController.getInstance(messageObject.currentAccount).isUserNoForwards(dialogId)) {
                return true;
            }
            TLRPC.UserFull userFull = MessagesController.getInstance(messageObject.currentAccount).getUserFull(dialogId);
            if (userFull != null && (userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled)) {
                return true;
            }
            return false;
        }
        return isChatNoForwards(messageObject);
    }

    private static boolean hasLocalForwardCopy(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        messageObject.checkMediaExistance();
        if (messageObject.attachPathExists || messageObject.mediaExists()) {
            return true;
        }
        return !TextUtils.isEmpty(messageObject.messageOwner.attachPath) && new File(messageObject.messageOwner.attachPath).exists();
    }

    public static boolean canForwardAyuDeletedMessage(MessageObject messageObject) {
        if (messageObject == null || !messageObject.isAyuDeleted() || messageObject.messageOwner == null) {
            return false;
        }
        if (messageObject.isQuickReply() || messageObject.needDrawBluredPreview() || messageObject.isLiveLocation() || messageObject.type == MessageObject.TYPE_PHONE_CALL || messageObject.isSponsored()) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji()) {
            return !TextUtils.isEmpty(messageObject.messageOwner.message);
        }
        if (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isGif() || messageObject.getDocument() != null) {
            return hasLocalForwardCopy(messageObject);
        }
        return false;
    }

    public static boolean isUnforwardable(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (message.noforwards) {
            return true;
        }
        if (message instanceof TLRPC.TL_message_secret || message instanceof TLRPC.TL_message_secret_layer72 || messageObject.isSecretMedia()) {
            return true;
        }
        if (message.ttl != 0) {
            return true;
        }
        if (message.media != null && message.media.ttl_seconds != 0) {
            return true;
        }
        return messageObject.type == MessageObject.TYPE_PAID_MEDIA || message.media instanceof TLRPC.TL_messageMediaPaidMedia;
    }

    public static boolean isForceForwardNeeded(MessageObject messageObject) {
        return canForwardAyuDeletedMessage(messageObject) || isPeerNoForwards(messageObject) || isUnforwardable(messageObject);
    }

    /** Check if a dialog is currently the target of a force-forward operation */
    public static boolean isForwardingToDialog(long dialogId) {
        ForceForward ff = activeForwards.get(dialogId);
        if (ff == null) return false;
        return ff.isForwarding();
    }

    /** Get the forwarding status string for a target dialog, or null if not forwarding */
    public static String getStatusForDialog(long dialogId) {
        ForceForward ff = activeForwards.get(dialogId);
        if (ff == null) return null;
        return ff.getForwardingStatus();
    }

    /** Stop the force-forward operation targeting a dialog */
    public static boolean stopForDialog(long dialogId) {
        ForceForward ff = activeForwards.get(dialogId);
        if (ff == null) return false;
        return ff.stopCurrentRun();
    }

    /** Consume the last failure reason for a target dialog */
    public static String consumeFailureReasonForDialog(long dialogId) {
        ForceForward ff = activeForwards.get(dialogId);
        if (ff == null) return null;
        return ff.consumeLastFailureReason();
    }

    private static void shiftEntities(ArrayList<TLRPC.MessageEntity> entities, int offset) {
        if (entities == null || entities.isEmpty() || offset == 0) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            entities.get(i).offset += offset;
        }
    }

    private static String shortifyText(CharSequence text, int maxLen) {
        if (TextUtils.isEmpty(text) || text.length() <= maxLen) {
            return text != null ? text.toString() : "";
        }
        return text.subSequence(0, maxLen - 1) + "…";
    }

    private String getSenderName(MessageObject mo) {
        if (mo == null) return "";
        TLObject fromPeer = mo.getFromPeerObject();
        if (fromPeer instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) fromPeer).title;
        } else if (fromPeer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) fromPeer;
            return ContactsController.formatName(user.first_name, user.last_name);
        }
        return "";
    }

    private static class PseudoReplyResult {
        final String text;
        final String caption;

        private PseudoReplyResult(String text, String caption) {
            this.text = text;
            this.caption = caption;
        }
    }

    private PseudoReplyResult prependPseudoReplyAyuGram(String text, String caption, boolean photoMarker, MessageObject mo, ArrayList<TLRPC.MessageEntity> entities) {
        if ((TextUtils.isEmpty(text) && TextUtils.isEmpty(caption) && !photoMarker) || mo == null) {
            return new PseudoReplyResult(text, caption);
        }

        CharSequence messageText = mo.messageText;
        if (TextUtils.isEmpty(messageText) || "null".contentEquals(messageText)) {
            try {
                mo.updateMessageText();
                messageText = mo.messageText;
            } catch (Exception ignored) {
            }
            if (TextUtils.isEmpty(messageText) || "null".contentEquals(messageText)) {
                return new PseudoReplyResult(text, caption);
            }
        }

        String senderName = "";
        boolean isSelfChat = DialogObject.isUserDialog(targetDialogId)
                && Math.abs(mo.getDialogId()) == Math.abs(targetDialogId);
        if (!isSelfChat) {
            senderName = getSenderName(mo);
            if (!TextUtils.isEmpty(senderName)) {
                senderName = senderName + "\n";
            }
        }

        long senderId = mo.getSenderId();
        String summary = senderName + shortifyText(messageText, 100);
        int shift = 0;

        if (!TextUtils.isEmpty(text)) {
            text = summary + "\n" + text;
            shift = summary.length() + 1;
        } else if (!TextUtils.isEmpty(caption)) {
            caption = summary + "\n" + caption;
            shift = summary.length() + 1;
        } else if (photoMarker) {
            caption = summary;
            shift = summary.length();
        } else {
            return new PseudoReplyResult(text, caption);
        }

        shiftEntities(entities, shift);

        if (!TextUtils.isEmpty(senderName)) {
            TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
            bold.offset = 0;
            bold.length = senderName.length();
            entities.add(bold);

            TLRPC.InputUser inputUser = MessagesController.getInstance(currentAccount).getInputUser(senderId);
            if (inputUser != null) {
                TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
                mention.user_id = inputUser;
                mention.offset = 0;
                mention.length = senderName.length();
                entities.add(mention);
            }
        }

        TLRPC.TL_messageEntityBlockquote blockquote = new TLRPC.TL_messageEntityBlockquote();
        blockquote.offset = 0;
        blockquote.length = summary.length();
        entities.add(blockquote);

        return new PseudoReplyResult(text, caption);
    }

    private String prependPseudoReply(MessageObject mo, String text, ArrayList<TLRPC.MessageEntity> entities) {
        return prependPseudoReplyAyuGram(text, null, false, mo, entities).text;
    }

    private String prependPseudoReplyCaption(MessageObject mo, String caption, ArrayList<TLRPC.MessageEntity> entities) {
        return prependPseudoReplyAyuGram(null, caption, mo != null && mo.isPhoto(), mo, entities).caption;
    }

    private void clearRunState() {
        clearActiveSendQueue();
        unsubscribeFromNotifications();
        if (targetDialogId != 0) {
            activeForwards.remove(targetDialogId);
        }
        activeTaskId = 0L;
        currentStatus = STATUS_IDLE;
        totalMessages = 0;
        sentMessages = 0;
        skippedMessages = 0;
        totalMediaToPrepare = 0;
        pendingMedia = 0;
        currentStatusDetail = null;
        stopRequested = false;
        mediaWaitStartTime = 0L;
        mediaLastProgressTime = 0L;
        mediaLastObservedBytes = 0L;
        mediaLastMissingCount = 0;
        pendingDownloadKeys.clear();
        currentChunkIndex = 0;
        totalChunks = 1;
        statusUpdateVersion = 0;
    }

    private void clearPendingRunState() {
        pendingMessages = null;
        pendingTargetDialogId = 0;
        pendingShowUndo = false;
        pendingHideCaption = false;
        pendingNotify = false;
        pendingScheduleDate = 0;
        pendingPayStars = 0;
        pendingTaskId = 0;
        pendingOnComplete = null;
    }

    private void subscribeToNotifications() {
        if (notificationSubscribed) return;
        notificationSubscribed = true;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.httpFileDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.httpFileDidFailedLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messageReceivedByAck);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploadFailed);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.filePreparingFailed);
    }

    private void unsubscribeFromNotifications() {
        if (!notificationSubscribed) return;
        notificationSubscribed = false;
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.httpFileDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.httpFileDidFailedLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageReceivedByAck);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploadFailed);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.filePreparingFailed);
    }

    private void scheduleStallCheck() {
        cancelStallCheck();
        stallCheckRunnable = () -> {
            if (disposed || !isTaskActive(pendingTaskId)) return;
            MediaPreparationResult mediaState = prepareMedia(pendingMessages);
            if (shouldAbortMediaWait(mediaState, pendingTaskId, pendingOnComplete)) {
                unsubscribeFromNotifications();
                clearPendingRunState();
                return;
            }
            // Schedule next stall check
            scheduleStallCheck();
        };
        AndroidUtilities.runOnUIThread(stallCheckRunnable, STALL_CHECK_INTERVAL_MS);
    }

    private void cancelStallCheck() {
        if (stallCheckRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(stallCheckRunnable);
            stallCheckRunnable = null;
        }
    }

    private void didReceivedNotificationLegacy(int id, int account, Object... args) {
        if (disposed) {
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
            // A file finished downloading — re-check media readiness
            AndroidUtilities.runOnUIThread(() -> {
                if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                resumeMediaWait();
            });
            }
        } else if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
            // A file failed to download — re-check (some files may have failed but others succeeded)
            AndroidUtilities.runOnUIThread(() -> {
                if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                resumeMediaWait();
            });
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (disposed) {
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                    resumeMediaWait();
                });
            }
        } else if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                    resumeMediaWait();
                });
            }
        } else if (id == NotificationCenter.messageReceivedByAck) {
            handleSendAcknowledged(args);
        } else if (id == NotificationCenter.messageReceivedByServer) {
            handleSendConfirmedByServer(args);
        } else if (id == NotificationCenter.messageSendError) {
            handleSendError(args);
        } else if (id == NotificationCenter.fileUploaded) {
            handleUploadStateChanged(args, false);
        } else if (id == NotificationCenter.fileUploadFailed || id == NotificationCenter.filePreparingFailed) {
            handleUploadStateChanged(args, true);
        }
    }

    private void resumeMediaWait() {
        if (disposed || pendingMessages == null || !isTaskActive(pendingTaskId)) return;

        MediaPreparationResult mediaState = prepareMedia(pendingMessages);
        if (mediaState.hasUndownloadedAyuDeletedMedia) {
            unsubscribeFromNotifications();
            cancelStallCheck();
            failRun(pendingTaskId, LocaleController.getString(R.string.PleaseDownload), pendingOnComplete);
            clearPendingRunState();
            return;
        }

        int missingMediaCount = mediaState.missingMediaCount;
        updateLoadingState(missingMediaCount);

        if (missingMediaCount > 0) {
            if (shouldAbortMediaWait(mediaState, pendingTaskId, pendingOnComplete)) {
                unsubscribeFromNotifications();
                cancelStallCheck();
                clearPendingRunState();
                return;
            }
            // Still missing — keep waiting for next notification / stall check
            return;
        }

        // All media ready — proceed to send
        unsubscribeFromNotifications();
        cancelStallCheck();
        ArrayList<MessageObject> messages = pendingMessages;
        long targetDialogId = pendingTargetDialogId;
        boolean hideCaption = pendingHideCaption;
        boolean notify = pendingNotify;
        int scheduleDate = pendingScheduleDate;
        long payStars = pendingPayStars;
        long taskId = pendingTaskId;
        CompletionCallback onComplete = pendingOnComplete;
        clearPendingRunState();
        executeSendPhase(messages, targetDialogId, hideCaption, notify, scheduleDate, payStars, taskId, onComplete);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        detached = true;
        unsubscribeFromNotifications();
        cancelStallCheck();
        cancelPendingDownloads();
        clearRunState();
        clearPendingRunState();
        lastFailureReason = null;
    }

    /**
     * Detach from the source ChatActivity so the forwarding operation continues
     * independently after the fragment is destroyed.
     * The progress bar in the target chat remains functional via activeForwards.
     */
    public void detachFromFragment() {
        if (detached) return;
        detached = true;
    }

    public boolean isForwarding() {
        return activeTaskId != 0L;
    }

    public String consumeLastFailureReason() {
        String reason = lastFailureReason;
        lastFailureReason = null;
        return reason;
    }

    public boolean stopCurrentRun() {
        if (!isForwarding()) {
            return false;
        }
        stopRequested = true;
        currentStatus = STATUS_STOPPING;
        currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusStoppingCurrentBatch);
        lastFailureReason = null;
        unsubscribeFromNotifications();
        cancelStallCheck();
        cancelPendingDownloads();
        if (pendingMessages != null) {
            long taskId = pendingTaskId != 0 ? pendingTaskId : activeTaskId;
            CompletionCallback onComplete = pendingOnComplete;
            clearPendingRunState();
            finishRun(taskId, false, onComplete);
            return true;
        }
        if (activeSendSteps != null || isSendWaitActive()) {
            long taskId = sendWaitTaskId != 0 ? sendWaitTaskId : activeTaskId;
            CompletionCallback onComplete = activeSendOnComplete;
            clearActiveSendQueue();
            finishRun(taskId, false, onComplete);
            return true;
        }
        notifyStatusChanged();
        return true;
    }

    public String getForwardingStatus() {
        if (!isForwarding()) {
            return null;
        }
        if (currentStatus == STATUS_LOADING) {
            String label = TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusPreparingMedia) : currentStatusDetail;
            String progress;
            if (totalMediaToPrepare > 0) {
                progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, Math.max(totalMediaToPrepare - pendingMedia, 0), totalMediaToPrepare);
            } else {
                progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, sentMessages, totalMessages);
            }
            if (totalChunks > 1) {
                progress = progress + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks);
            }
            return label + " " + progress;
        }
        String progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, sentMessages, totalMessages);
        if (totalChunks > 1) {
            progress = progress + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks);
        }
        if (currentStatus == STATUS_FORWARDING) {
            String label = TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusForwarding) : currentStatusDetail;
            return label + " " + progress;
        }
        if (currentStatus == STATUS_STOPPING) {
            String label = TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusStopping) : currentStatusDetail;
            return totalChunks > 1 ? label + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks) : label;
        }
        return null;
    }

    private void notifyStatusChanged() {
        if (disposed) {
            return;
        }
        int updateMask = STATUS_REFRESH_MASK_BASE | ((statusUpdateVersion++ & 0x1FF) << 21);
        Runnable action = () -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, updateMask);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            AndroidUtilities.runOnUIThread(action);
        }
    }

    private long startRun(int messageCount, int mediaCount, long targetDid, int chunkIndex, int chunkCount) {
        activeTaskId++;
        this.targetDialogId = targetDid;
        this.currentChunkIndex = Math.max(chunkIndex, 0);
        this.totalChunks = Math.max(chunkCount, 1);
        currentStatus = STATUS_LOADING;
        totalMessages = Math.max(messageCount, 0);
        sentMessages = 0;
        skippedMessages = 0;
        totalMediaToPrepare = Math.max(mediaCount, 0);
        pendingMedia = totalMediaToPrepare;
        currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusPreparingMedia);
        lastFailureReason = null;
        stopRequested = false;
        mediaWaitStartTime = 0L;
        mediaLastProgressTime = 0L;
        mediaLastObservedBytes = 0L;
        mediaLastMissingCount = 0;
        statusUpdateVersion = 0;
        activeForwards.put(targetDid, this);
        notifyStatusChanged();
        return activeTaskId;
    }

    private boolean isTaskActive(long taskId) {
        return activeTaskId == taskId;
    }

    private void updateLoadingState(int missingMedia) {
        currentStatus = STATUS_LOADING;
        pendingMedia = Math.min(Math.max(missingMedia, 0), totalMediaToPrepare);
        currentStatusDetail = missingMedia > 0 ? LocaleController.getString(R.string.ForceForwardStatusWaitingDownloads) : LocaleController.getString(R.string.ForceForwardStatusMediaReady);
        notifyStatusChanged();
    }

    private void updateForwardingState() {
        updateForwardingState(null);
    }

    private void updateForwardingState(String detail) {
        currentStatus = STATUS_FORWARDING;
        pendingMedia = 0;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private void setFailureReason(String failureReason) {
        lastFailureReason = failureReason;
    }

    private void failRun(long taskId, String failureReason, CompletionCallback onComplete) {
        setFailureReason(failureReason);
        finishRun(taskId, false, onComplete);
    }

    private void onMessageSent() {
        sentMessages++;
        notifyStatusChanged();
    }

    private void onMessageSkipped() {
        if (totalMessages > 0) {
            totalMessages--;
        }
        skippedMessages++;
        pendingMedia = Math.min(pendingMedia, totalMediaToPrepare);
        sentMessages = Math.min(sentMessages, totalMessages);
        notifyStatusChanged();
    }

    private void finishRun(long taskId, boolean shouldContinue, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        boolean isCurrentTask = activeTaskId == taskId;
        if (isCurrentTask) {
            // If some messages were skipped, inform the user
            if (skippedMessages > 0 && shouldContinue && lastFailureReason == null) {
                lastFailureReason = LocaleController.formatString(R.string.ForceForwardSomeSkipped, skippedMessages);
            }
            clearRunState();
            notifyStatusChanged();
        }
        if (shouldContinue) {
            // Don't clear failure reason if it's a partial skip warning — it's informational
        }
        // When detached from source fragment, skip the callback (fragment is destroyed)
        // and auto-dispose since no one will clean us up
        if (detached) {
            if (isCurrentTask) {
                disposed = true;
            }
            return;
        }
        if (onComplete != null) {
            onComplete.onComplete(isCurrentTask && shouldContinue);
        }
    }

    private void finishRunAfterUiRefresh(long taskId, CompletionCallback onComplete) {
        AndroidUtilities.runOnUIThread(() -> {
            if (disposed) {
                return;
            }
            finishRun(taskId, !stopRequested, onComplete);
        }, SEND_PHASE_FINISH_DELAY_MS);
    }

    private void cancelSendWaitPoll() {
        if (sendWaitRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(sendWaitRunnable);
            sendWaitRunnable = null;
        }
    }

    private void clearSendWaitState() {
        cancelSendWaitPoll();
        sendWaitTaskId = 0L;
        sendWaitExpectedCount = 0;
        sendWaitCompletedCount = 0;
        sendWaitStartTime = 0L;
        sendWaitLastActivityTime = 0L;
        sendWaitBaselineIds.clear();
        sendWaitTrackedIds.clear();
        sendWaitHandledIds.clear();
        sendWaitAckedIds.clear();
        sendWaitPendingErrorIds.clear();
        sendWaitUploadPaths.clear();
    }

    private void clearActiveSendQueue() {
        clearSendWaitState();
        activeSendSteps = null;
        activeSendStepIndex = 0;
        activeSendOnComplete = null;
    }

    private boolean isSendWaitActive() {
        return sendWaitTaskId != 0L;
    }

    private void prepareSendWait(ArrayList<SendStep> sendSteps, int index, long taskId, CompletionCallback onComplete) {
        clearSendWaitState();
        activeSendSteps = sendSteps;
        activeSendStepIndex = index;
        activeSendOnComplete = onComplete;
        sendWaitTaskId = taskId;
        sendWaitStartTime = SystemClock.elapsedRealtime();
        sendWaitLastActivityTime = sendWaitStartTime;
        sendWaitBaselineIds.addAll(SendMessagesHelper.getInstance(currentAccount).getSendingMessageIds(targetDialogId));
    }

    private void markSendWaitActivity() {
        sendWaitLastActivityTime = SystemClock.elapsedRealtime();
    }

    private boolean finalizeCompletedSend(int localId) {
        if (sendWaitBaselineIds.contains(localId) || !sendWaitHandledIds.add(localId)) {
            return false;
        }
        sendWaitTrackedIds.add(localId);
        sendWaitAckedIds.remove(localId);
        sendWaitPendingErrorIds.remove(localId);
        markSendWaitActivity();
        sendWaitCompletedCount++;
        onMessageSent();
        return sendWaitExpectedCount > 0 && sendWaitCompletedCount >= sendWaitExpectedCount;
    }

    private boolean completeAckedMessagesMissingFromQueue(HashSet<Integer> currentIds) {
        if (sendWaitAckedIds.isEmpty()) {
            return true;
        }
        ArrayList<Integer> completedIds = null;
        for (Integer ackedId : sendWaitAckedIds) {
            if (ackedId == null || sendWaitHandledIds.contains(ackedId) || sendWaitBaselineIds.contains(ackedId) || currentIds.contains(ackedId)) {
                continue;
            }
            if (completedIds == null) {
                completedIds = new ArrayList<>();
            }
            completedIds.add(ackedId);
        }
        if (completedIds == null) {
            return true;
        }
        for (int i = 0; i < completedIds.size(); i++) {
            if (finalizeCompletedSend(completedIds.get(i))) {
                completeCurrentSendStep();
                return false;
            }
        }
        return true;
    }

    private boolean refreshTrackedSendIds() {
        if (!isSendWaitActive()) {
            return true;
        }
        ArrayList<Integer> currentIds = SendMessagesHelper.getInstance(currentAccount).getSendingMessageIds(targetDialogId);
        HashSet<Integer> currentIdSet = new HashSet<>(currentIds.size());
        boolean observedNewId = false;
        for (int i = 0; i < currentIds.size(); i++) {
            int localId = currentIds.get(i);
            currentIdSet.add(localId);
            if (sendWaitBaselineIds.contains(localId)) {
                continue;
            }
            if (sendWaitTrackedIds.add(localId)) {
                observedNewId = true;
                if (sendWaitPendingErrorIds.remove(localId)) {
                    failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
                    return false;
                }
            }
        }
        if (observedNewId) {
            markSendWaitActivity();
        }
        return completeAckedMessagesMissingFromQueue(currentIdSet);
    }

    private void scheduleSendWaitPoll() {
        cancelSendWaitPoll();
        sendWaitRunnable = () -> {
            sendWaitRunnable = null;
            if (disposed || !isSendWaitActive()) {
                return;
            }
            if (!isTaskActive(sendWaitTaskId)) {
                clearSendWaitState();
                return;
            }
            if (!refreshTrackedSendIds()) {
                return;
            }
            if (sendWaitExpectedCount > 0 && sendWaitCompletedCount >= sendWaitExpectedCount) {
                completeCurrentSendStep();
                return;
            }
            if (SystemClock.elapsedRealtime() - sendWaitLastActivityTime >= MAX_SEND_WAIT_MS) {
                failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
                return;
            }
            scheduleSendWaitPoll();
        };
        AndroidUtilities.runOnUIThread(sendWaitRunnable, SEND_WAIT_POLL_INTERVAL_MS);
    }

    private void completeCurrentSendStep() {
        ArrayList<SendStep> sendSteps = activeSendSteps;
        int nextIndex = activeSendStepIndex + 1;
        long taskId = sendWaitTaskId;
        CompletionCallback onComplete = activeSendOnComplete;
        clearSendWaitState();
        if (disposed || sendSteps == null) {
            return;
        }
        runSendQueue(sendSteps, nextIndex, taskId, onComplete);
    }

    private void failCurrentSendStep(String failureReason) {
        long taskId = sendWaitTaskId;
        CompletionCallback onComplete = activeSendOnComplete;
        clearActiveSendQueue();
        if (taskId == 0L) {
            return;
        }
        failRun(taskId, failureReason, onComplete);
    }

    private void handleSendAcknowledged(Object... args) {
        if (!isSendWaitActive() || !isTaskActive(sendWaitTaskId) || args == null || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int localId = (Integer) args[0];
        if (sendWaitHandledIds.contains(localId) || sendWaitBaselineIds.contains(localId)) {
            return;
        }
        if (sendWaitPendingErrorIds.remove(localId)) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
            return;
        }
        sendWaitTrackedIds.add(localId);
        sendWaitAckedIds.add(localId);
        markSendWaitActivity();
    }

    private void handleSendConfirmedByServer(Object... args) {
        if (!isSendWaitActive() || !isTaskActive(sendWaitTaskId) || args == null || args.length < 4 || !(args[0] instanceof Integer) || !(args[3] instanceof Long)) {
            return;
        }
        int localId = (Integer) args[0];
        long dialogId = (Long) args[3];
        if (dialogId != targetDialogId) {
            return;
        }
        if (finalizeCompletedSend(localId)) {
            completeCurrentSendStep();
        }
    }

    private void handleSendError(Object... args) {
        if (!isSendWaitActive() || !isTaskActive(sendWaitTaskId) || args == null || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int localId = (Integer) args[0];
        if (sendWaitHandledIds.contains(localId) || sendWaitBaselineIds.contains(localId)) {
            return;
        }
        if (sendWaitTrackedIds.contains(localId)) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
        } else {
            sendWaitPendingErrorIds.add(localId);
        }
    }

    private boolean matchesTrackedUploadPath(String path) {
        if (TextUtils.isEmpty(path) || sendWaitUploadPaths.isEmpty()) {
            return false;
        }
        if (sendWaitUploadPaths.contains(path)) {
            return true;
        }
        for (String trackedPath : sendWaitUploadPaths) {
            if (TextUtils.isEmpty(trackedPath)) {
                continue;
            }
            if (trackedPath.contains(path) || path.contains(trackedPath) || trackedPath.endsWith(path) || path.endsWith(trackedPath)) {
                return true;
            }
        }
        return false;
    }

    private void handleUploadStateChanged(Object[] args, boolean failed) {
        if (!isSendWaitActive() || !isTaskActive(sendWaitTaskId) || args == null || args.length == 0) {
            return;
        }
        Object value = args[0];
        if (failed && args.length > 1 && args[1] instanceof String) {
            value = args[1];
        }
        if (!(value instanceof String)) {
            return;
        }
        String path = (String) value;
        if (!matchesTrackedUploadPath(path)) {
            return;
        }
        markSendWaitActivity();
        if (failed) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
        }
    }

    private void runSendQueue(ArrayList<SendStep> sendSteps, int index, long taskId, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        if (!isTaskActive(taskId) || stopRequested) {
            finishRun(taskId, false, onComplete);
            return;
        }
        if (index >= sendSteps.size()) {
            finishRunAfterUiRefresh(taskId, onComplete);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (disposed) {
                return;
            }
            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }
            prepareSendWait(sendSteps, index, taskId, onComplete);
            SendDispatchResult dispatchResult;
            try {
                dispatchResult = sendSteps.get(index).dispatch();
            } catch (Exception e) {
                FileLog.e(e);
                clearActiveSendQueue();
                failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
                return;
            }
            if (dispatchResult == null || dispatchResult.expectedCompletions <= 0) {
                clearSendWaitState();
                runSendQueue(sendSteps, index + 1, taskId, onComplete);
                return;
            }
            sendWaitExpectedCount = dispatchResult.expectedCompletions;
            if (dispatchResult.uploadPaths != null) {
                for (int i = 0; i < dispatchResult.uploadPaths.size(); i++) {
                    String path = dispatchResult.uploadPaths.get(i);
                    if (!TextUtils.isEmpty(path)) {
                        sendWaitUploadPaths.add(path);
                    }
                }
            }
            if (!refreshTrackedSendIds()) {
                return;
            }
            if (sendWaitCompletedCount >= sendWaitExpectedCount) {
                completeCurrentSendStep();
                return;
            }
            scheduleSendWaitPoll();
        }, index == 0 ? 0 : SEND_STEP_DELAY_MS);
    }
    
    private CharSequence getMessageCaption(MessageObject mo) {
        return ChatActivity.getMessageCaption(mo, null, null);
    }

    private CharSequence getForwardCaption(MessageObject mo) {
        if (mo == null) {
            return null;
        }
        if (mo.getGroupId() != 0) {
            return ChatActivity.getMessageCaption(mo, null, null);
        }
        return getMessageCaption(mo);
    }

    private String resolvePath(MessageObject mo) {
        if (mo.messageOwner == null) return null;
        return FileLoader.getInstance(currentAccount).getPathToMessage(mo.messageOwner).toString();
    }

    private void trackPendingDownload(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            pendingDownloadKeys.add(fileName);
        }
    }

    private void clearPendingDownload(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            pendingDownloadKeys.remove(fileName);
        }
    }

    private void cancelPendingDownloads() {
        if (pendingDownloadKeys.isEmpty()) {
            return;
        }
        FileLoader.getInstance(currentAccount).cancelLoadFiles(new ArrayList<>(pendingDownloadKeys));
        pendingDownloadKeys.clear();
    }

    private void resetDownloadCancellationFlags(ArrayList<MessageObject> messagesToSend) {
        if (messagesToSend == null) {
            return;
        }
        for (int i = 0; i < messagesToSend.size(); i++) {
            MessageObject messageObject = messagesToSend.get(i);
            if (messageObject != null) {
                messageObject.loadingCancelled = false;
            }
        }
    }

    private boolean ensureDownloaded(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) return false;
        if (mo.loadingCancelled) return false;
        
        String path = resolvePath(mo);
        if (!TextUtils.isEmpty(path) && new File(path).exists()) return true;
        if (mo.isAyuDeleted()) return false;

        if (mo.getDocument() != null) {
            String fileName = FileLoader.getAttachFileName(mo.getDocument());
            trackPendingDownload(fileName);
            if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            }
            return false;
        }
        
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (size != null) {
                    String fileName = FileLoader.getAttachFileName(size);
                    trackPendingDownload(fileName);
                    if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                        ImageLocation imageLocation = ImageLocation.getForObject(size, mo.messageOwner);
                        if (imageLocation != null) {
                            FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                        }
                    }
                }
            }
            return false;
        }
        
        if (mo.isVideo()) {
            // Modern videos are already handled by getDocument() check above
            // This branch only handles legacy videos without document
            // Note: Legacy API limitation - can only load thumbnail, not the actual video file
            if (mo.getDocument() == null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
                TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
                 if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                     String fileName = FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
                     trackPendingDownload(fileName);
                     if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                         ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, mo.messageOwner);
                         if (imageLocation != null) {
                             FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                         }
                     }
                 }
            }
            return false;
        }
        
        return false;
    }

    private String getDownloadTrackingKey(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return null;
        }
        if (mo.getDocument() != null) {
            return FileLoader.getAttachFileName(mo.getDocument());
        }
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
            return size != null ? FileLoader.getAttachFileName(size) : null;
        }
        if (mo.isVideo() && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
            if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                return FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
            }
        }
        return null;
    }

    private boolean needsLocalCopy(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return false;
        }
        if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji()) {
            return false;
        }
        if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
            return true;
        }
        if (mo.isAyuDeleted()) {
            return mo.getDocument() != null;
        }
        return mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker();
    }

    private int countMediaToPrepare(ArrayList<MessageObject> messagesToSend) {
        if (messagesToSend == null || messagesToSend.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < messagesToSend.size(); i++) {
            if (needsLocalCopy(messagesToSend.get(i))) {
                count++;
            }
        }
        return count;
    }

    private MediaPreparationResult prepareMedia(ArrayList<MessageObject> messagesToSend) {
        MediaPreparationResult result = new MediaPreparationResult();
        for (int i = 0; i < messagesToSend.size(); i++) {
            MessageObject messageObject = messagesToSend.get(i);
            if (!needsLocalCopy(messageObject)) {
                continue;
            }
            if (messageObject.loadingCancelled) {
                result.cancelledByUser = true;
                return result;
            }
            if (messageObject.isAyuDeleted() && !hasLocalForwardCopy(messageObject)) {
                result.hasUndownloadedAyuDeletedMedia = true;
                return result;
            }
            if (!ensureDownloaded(messageObject)) {
                result.missingMediaCount++;
                String trackingKey = getDownloadTrackingKey(messageObject);
                if (!TextUtils.isEmpty(trackingKey)) {
                    trackPendingDownload(trackingKey);
                    if (FileLoader.getInstance(currentAccount).isLoadingFile(trackingKey)) {
                        result.activeDownloadCount++;
                    }
                    long[] progress = ImageLoader.getInstance().getFileProgressSizes(trackingKey);
                    if (progress != null) {
                        result.downloadedBytes += progress[0];
                    }
                }
            }
        }
        return result;
    }

    private void sendMediaBatch(ArrayList<SendMessagesHelper.SendingMediaInfo> list, long targetDialogId, boolean isDocument, boolean isGrouping, boolean notify, int scheduleDate, long payStars) {
        MessageObject replyToMsg = replyToTopMessage;
        MessageObject replyToTopMsg = replyToTopMessage;
        SendMessagesHelper.prepareSendingMedia(
                AccountInstance.getInstance(currentAccount),
                list,
                targetDialogId,
                replyToMsg,
                replyToTopMsg,
                null,
                null,
                isDocument,
                isGrouping,
                null,
                notify,
                scheduleDate,
                0,
                chatMode,
                false,
                null,
                quickReplyShortcut,
                quickReplyShortcutId,
                0,
                false,
                payStars,
                monoForumPeerId,
                suggestionParams
        );
    }

    private void applySendContext(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        MessageObject replyToMsg = replyToTopMessage;
        if (params.replyToMsg == null) {
            params.replyToMsg = replyToMsg;
        }
        if (params.replyToTopMsg == null) {
            params.replyToTopMsg = replyToMsg;
        }
        params.quick_reply_shortcut = quickReplyShortcut;
        params.quick_reply_shortcut_id = quickReplyShortcutId;
        params.payStars = payStars;
        params.monoForumPeer = monoForumPeerId;
        params.suggestionParams = suggestionParams;
    }

    private void addUploadPath(ArrayList<String> uploadPaths, String path) {
        if (uploadPaths == null || TextUtils.isEmpty(path) || uploadPaths.contains(path)) {
            return;
        }
        uploadPaths.add(path);
    }

    private ArrayList<String> createUploadPaths(SendMessagesHelper.SendMessageParams params) {
        if (params == null) {
            return null;
        }
        ArrayList<String> uploadPaths = new ArrayList<>(1);
        addUploadPath(uploadPaths, params.path);
        return uploadPaths.isEmpty() ? null : uploadPaths;
    }

    private ArrayList<String> createUploadPaths(ArrayList<SendMessagesHelper.SendingMediaInfo> mediaInfos) {
        if (mediaInfos == null || mediaInfos.isEmpty()) {
            return null;
        }
        ArrayList<String> uploadPaths = new ArrayList<>(mediaInfos.size());
        for (int i = 0; i < mediaInfos.size(); i++) {
            SendMessagesHelper.SendingMediaInfo info = mediaInfos.get(i);
            if (info != null) {
                addUploadPath(uploadPaths, info.path);
            }
        }
        return uploadPaths.isEmpty() ? null : uploadPaths;
    }

    private int getMessageTtl(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || mo.messageOwner.media == null) {
            return 0;
        }
        return mo.messageOwner.media.ttl_seconds;
    }

    private TLRPC.TL_photo mapPhoto(MessageObject mo, String filePath) {
        if (mo == null || mo.messageOwner == null || TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.Photo source = MessageObject.getPhoto(mo.messageOwner);
        if (source == null) {
            return null;
        }
        TLRPC.TL_photo mapped = new TLRPC.TL_photo();
        mapped.flags = source.flags;
        mapped.has_stickers = source.has_stickers;
        mapped.date = (int) (System.currentTimeMillis() / 1000);
        mapped.dc_id = Integer.MIN_VALUE;
        mapped.user_id = source.user_id;
        mapped.geo = source.geo;
        mapped.caption = source.caption;
        mapped.video_sizes = new ArrayList<>(source.video_sizes);
        mapped = SendMessagesHelper.getInstance(currentAccount).generatePhotoSizes(mapped, filePath, null, false);
        if (mapped == null || mapped.sizes == null || mapped.sizes.isEmpty()) {
            return null;
        }
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        return mapped;
    }

    private TLRPC.TL_document mapDocument(MessageObject mo) {
        TLRPC.Document source = mo != null ? mo.getDocument() : null;
        if (source == null) {
            return null;
        }
        String localPath = resolvePath(mo);
        boolean hasLocalFile = !TextUtils.isEmpty(localPath) && new File(localPath).exists();

        TLRPC.TL_document mapped = new TLRPC.TL_document();
        mapped.flags = source.flags;
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        mapped.user_id = source.user_id;
        mapped.date = (int) (System.currentTimeMillis() / 1000);
        mapped.file_name = source.file_name;
        mapped.mime_type = source.mime_type;
        mapped.size = hasLocalFile ? (int) new File(localPath).length() : source.size;
        mapped.thumbs = new ArrayList<>(source.thumbs);
        mapped.video_thumbs = new ArrayList<>(source.video_thumbs);
        mapped.version = source.version;
        mapped.dc_id = hasLocalFile ? source.dc_id : Integer.MIN_VALUE;
        mapped.key = null;
        mapped.iv = null;
        mapped.attributes = new ArrayList<>(source.attributes);
        mapped.file_name_fixed = source.file_name_fixed;
        mapped.localPath = hasLocalFile ? localPath : source.localPath;
        mapped.localThumbPath = source.localThumbPath;
        // Force correct mime_type for GIF — AyuGram does this to ensure server handles it as animation
        if (mo != null && mo.isGif()) {
            mapped.mime_type = "video/mp4";
        }
        return mapped;
    }

    private HashMap<String, String> createGroupedParams(long groupId, boolean isFinal) {
        HashMap<String, String> params = new HashMap<>();
        if (groupId != 0) {
            params.put("groupId", String.valueOf(groupId));
        }
        if (isFinal) {
            params.put("final", "1");
        }
        return params.isEmpty() ? null : params;
    }

    private SendMessagesHelper.SendMessageParams buildMappedPhotoParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(mo);
        TLRPC.TL_photo photo = mapPhoto(mo, filePath);
        if (photo == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        String effectiveCaption = prependPseudoReplyCaption(mo, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(photo, filePath, targetDialogId, null, null, effectiveCaption, entities.isEmpty() ? null : entities, null, params, notify, scheduleDate, 0, getMessageTtl(mo), mo, false, mo != null && mo.hasMediaSpoilers());
    }

    private SendMessagesHelper.SendMessageParams buildMappedDocumentParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(mo);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.TL_document document = mapDocument(mo);
        if (document == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        String effectiveCaption = prependPseudoReplyCaption(mo, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(document, null, filePath, targetDialogId, null, null, effectiveCaption, entities.isEmpty() ? null : entities, null, params, notify, scheduleDate, 0, getMessageTtl(mo), mo, null, false, mo != null && mo.hasMediaSpoilers());
    }

    private void sendMappedParams(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        applySendContext(params, payStars);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
    }

    private SendMessagesHelper.SendMessageParams buildDocumentFallbackParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate) {
        String filePath = resolvePath(mo);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        TLRPC.TL_document document = mapDocument(mo);
        if (document == null) {
            return null;
        }
        document.localPath = filePath;
        document.size = (int) file.length();
        document.date = (int) (System.currentTimeMillis() / 1000);
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        String effectiveCaption = prependPseudoReplyCaption(mo, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        return SendMessagesHelper.SendMessageParams.of(
                document, null, filePath, targetDialogId, null, null, effectiveCaption, entities.isEmpty() ? null : entities,
                null, null, notify, scheduleDate, 0, 0, mo, null, false, mo != null && mo.hasMediaSpoilers());
    }

    private ArrayList<SendMessagesHelper.SendingMediaInfo> createMediaInfoList(ArrayList<GroupedMediaItem> items) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
        if (items == null) {
            return list;
        }
        for (int i = 0; i < items.size(); i++) {
            GroupedMediaItem item = items.get(i);
            SendMessagesHelper.SendingMediaInfo info = createMediaInfo(item.messageObject, item.caption);
            if (item.messageObject != null) {
                info.isVideo = item.messageObject.isVideo() || item.messageObject.isGif();
            }
            list.add(info);
        }
        return list;
    }

    private void enqueueMappedGroup(ArrayList<SendStep> sendSteps, ArrayList<GroupedMediaItem> group, long targetDialogId, boolean notify, int scheduleDate, long payStars, boolean documentFallback) {
        if (group == null || group.isEmpty()) {
            return;
        }

        if (group.size() > MAX_GROUP_BATCH_SIZE) {
            for (int start = 0; start < group.size(); start += MAX_GROUP_BATCH_SIZE) {
                int end = Math.min(start + MAX_GROUP_BATCH_SIZE, group.size());
                enqueueMappedGroup(sendSteps, new ArrayList<>(group.subList(start, end)), targetDialogId, notify, scheduleDate, payStars, documentFallback);
            }
            return;
        }

        final String groupLabel = documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentGroup) : LocaleController.getString(R.string.ForceForwardStatusMediaGroup);
        if (group.size() == 1) {
            GroupedMediaItem item = group.get(0);
            MessageObject messageObject = item.messageObject;
            sendSteps.add(() -> {
                updateForwardingState(groupLabel + " 1/1");
                SendMessagesHelper.SendMessageParams params = messageObject != null && messageObject.isPhoto()
                        ? buildMappedPhotoParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, null)
                        : buildMappedDocumentParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, null);
                if (params == null) {
                    updateForwardingState(documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentFallback) : LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                    ArrayList<SendMessagesHelper.SendingMediaInfo> fallbackInfo = createMediaInfoList(group);
                    sendMediaBatch(fallbackInfo, targetDialogId, documentFallback, false, notify, scheduleDate, payStars);
                    return SendDispatchResult.dispatched(1, createUploadPaths(fallbackInfo));
                }
                sendMappedParams(params, payStars);
                return SendDispatchResult.dispatched(1, createUploadPaths(params));
            });
            return;
        }

        long generatedGroupId;
        do {
            generatedGroupId = Utilities.random.nextLong();
        } while (generatedGroupId == 0);

        ArrayList<SendMessagesHelper.SendMessageParams> paramsList = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            GroupedMediaItem item = group.get(i);
            MessageObject messageObject = item.messageObject;
            HashMap<String, String> groupParams = createGroupedParams(generatedGroupId, i == group.size() - 1);
            SendMessagesHelper.SendMessageParams params = messageObject != null && messageObject.isPhoto()
                    ? buildMappedPhotoParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, groupParams)
                    : buildMappedDocumentParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, groupParams);
            if (params == null) {
                ArrayList<GroupedMediaItem> fallbackGroup = new ArrayList<>(group);
                sendSteps.add(() -> {
                    updateForwardingState(documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentGroupFallback) : LocaleController.getString(R.string.ForceForwardStatusMediaGroupFallback));
                    ArrayList<SendMessagesHelper.SendingMediaInfo> fallbackInfo = createMediaInfoList(fallbackGroup);
                    sendMediaBatch(fallbackInfo, targetDialogId, documentFallback, !documentFallback, notify, scheduleDate, payStars);
                    return SendDispatchResult.dispatched(fallbackGroup.size(), createUploadPaths(fallbackInfo));
                });
                return;
            }
            paramsList.add(params);
        }

        for (int i = 0; i < paramsList.size(); i++) {
            final int stepIndex = i;
            final int stepCount = paramsList.size();
            final SendMessagesHelper.SendMessageParams params = paramsList.get(i);
            sendSteps.add(() -> {
                updateForwardingState(groupLabel + " " + (stepIndex + 1) + "/" + stepCount);
                sendMappedParams(params, payStars);
                return SendDispatchResult.dispatched(1, createUploadPaths(params));
            });
        }
    }

    private void enqueueLeftoverGroupAsSingles(ArrayList<SendStep> sendSteps, ArrayList<GroupedMediaItem> group, long targetDialogId, boolean notify, int scheduleDate, long payStars, boolean documentFallback) {
        if (group == null || group.isEmpty()) {
            return;
        }
        FileLog.w("ForceForward: degraded leftover " + (documentFallback ? "document" : "media") + " group to singles");
        for (int i = 0; i < group.size(); i++) {
            GroupedMediaItem item = group.get(i);
            MessageObject messageObject = item.messageObject;
            if (messageObject == null) {
                sendSteps.add(() -> {
                    onMessageSkipped();
                    return SendDispatchResult.none();
                });
                continue;
            }
            final String caption = item.caption;
            sendSteps.add(() -> {
                if (documentFallback) {
                    updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentCopy));
                    SendMessagesHelper.SendMessageParams params = buildMappedDocumentParams(messageObject, caption, targetDialogId, notify, scheduleDate, null);
                    if (params == null) {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentFallback));
                        params = buildDocumentFallbackParams(messageObject, caption, targetDialogId, notify, scheduleDate);
                        if (params == null) {
                            onMessageSkipped();
                            return SendDispatchResult.none();
                        }
                    }
                    sendMappedParams(params, payStars);
                    return SendDispatchResult.dispatched(1, createUploadPaths(params));
                } else {
                    updateForwardingState(messageObject.isPhoto()
                            ? LocaleController.getString(R.string.ForceForwardStatusPhotoCopy)
                            : LocaleController.getString(R.string.ForceForwardStatusMediaCopy));
                    SendMessagesHelper.SendMessageParams params = messageObject.isPhoto()
                            ? buildMappedPhotoParams(messageObject, caption, targetDialogId, notify, scheduleDate, null)
                            : buildMappedDocumentParams(messageObject, caption, targetDialogId, notify, scheduleDate, null);
                    if (params == null) {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                        ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>(1);
                        SendMessagesHelper.SendingMediaInfo info = createMediaInfo(messageObject, caption);
                        info.isVideo = messageObject.isVideo() || messageObject.isGif();
                        one.add(info);
                        sendMediaBatch(one, targetDialogId, false, false, notify, scheduleDate, payStars);
                        return SendDispatchResult.dispatched(1, createUploadPaths(one));
                    }
                    sendMappedParams(params, payStars);
                    return SendDispatchResult.dispatched(1, createUploadPaths(params));
                }
            });
        }
    }

    private void addToGroup(long gid,
                            GroupedMediaItem item,
                            HashMap<Long, ArrayList<GroupedMediaItem>> map,
                            HashMap<Long, Integer> remain,
                            ArrayList<SendStep> sendSteps,
                            boolean document,
                            long targetDialogId,
                            boolean notify,
                            int scheduleDate,
                            long payStars) {
        ArrayList<GroupedMediaItem> list = map.computeIfAbsent(gid, k -> new ArrayList<>());
        list.add(item);
        Integer r = remain.get(gid);
        if (r != null) {
            r = r - 1;
            if (r <= 0) {
                map.remove(gid);
                remain.remove(gid);
                enqueueMappedGroup(sendSteps, new ArrayList<>(list), targetDialogId, notify, scheduleDate, payStars, document);
            } else {
                remain.put(gid, r);
            }
        }
    }
    
    public void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, CompletionCallback onComplete) {
        runForceForward(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, 0, 1, onComplete);
    }

    public void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, int chunkIndex, int chunkCount, CompletionCallback onComplete) {
        if (disposed || messagesToSend == null || messagesToSend.isEmpty() || (!detached && parentFragment != null && parentFragment.getParentActivity() == null)) {
            setFailureReason(null);
            if (onComplete != null) {
                onComplete.onComplete(false);
            }
            return;
        }
        resetDownloadCancellationFlags(messagesToSend);
        long taskId = startRun(messagesToSend.size(), countMediaToPrepare(messagesToSend), targetDialogId, chunkIndex, chunkCount);
        runForceForward(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, taskId, 0, onComplete);
    }

    private void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, int retryCount, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        if (!isTaskActive(taskId)) {
            finishRun(taskId, false, onComplete);
            return;
        }
        if (stopRequested) {
            finishRun(taskId, false, onComplete);
            return;
        }

        MediaPreparationResult mediaState = prepareMedia(messagesToSend);
        if (mediaState.hasUndownloadedAyuDeletedMedia) {
            failRun(taskId, LocaleController.getString(R.string.PleaseDownload), onComplete);
            return;
        }
        int missingMediaCount = mediaState.missingMediaCount;
        updateLoadingState(missingMediaCount);
        if (!isTaskActive(taskId)) {
            finishRun(taskId, false, onComplete);
            return;
        }

        if (missingMediaCount > 0) {
            if (shouldAbortMediaWait(mediaState, taskId, onComplete)) {
                return;
            }
            // Store pending state and subscribe to file download notifications
            pendingMessages = messagesToSend;
            pendingTargetDialogId = targetDialogId;
            pendingShowUndo = showUndo;
            pendingHideCaption = hideCaption;
            pendingNotify = notify;
            pendingScheduleDate = scheduleDate;
            pendingPayStars = payStars;
            pendingTaskId = taskId;
            pendingOnComplete = onComplete;
            subscribeToNotifications();
            scheduleStallCheck();
            return;
        }

        executeSendPhase(messagesToSend, targetDialogId, hideCaption, notify, scheduleDate, payStars, taskId, onComplete);
    }

    private void executeSendPhase(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, CompletionCallback onComplete) {
        subscribeToNotifications();
        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStarting));
        try {
            HashMap<Long, ArrayList<GroupedMediaItem>> albumMap = new HashMap<>();
            HashMap<Long, ArrayList<GroupedMediaItem>> docAlbumMap = new HashMap<>();
            HashMap<Long, Integer> albumRemain = new HashMap<>();
            ArrayList<SendStep> sendSteps = new ArrayList<>();

            // 1. Calculate grouping counts
            for (MessageObject mo : messagesToSend) {
                long gid = mo.getGroupId();
                if (gid == 0) continue;
                
                boolean groupedMedia = mo.isPhoto() || mo.isVideo() || mo.isGif();
                boolean groupedDoc = mo.getDocument() != null && !mo.isVideo() && !MessageObject.isGifMessage(mo.messageOwner) && !mo.isSticker() && !mo.isAnimatedSticker();
                
                if (groupedMedia || groupedDoc) {
                    albumRemain.put(gid, albumRemain.getOrDefault(gid, 0) + 1);
                }
            }

            // 2. Process Messages
            for (MessageObject mo : messagesToSend) {
                if (!isTaskActive(taskId) || stopRequested) {
                    finishRun(taskId, false, onComplete);
                    return;
                }
                CharSequence captionCs = hideCaption ? null : getForwardCaption(mo);
                String caption = captionCs != null ? captionCs.toString() : null;
                boolean hasMessage = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message);

                // Text Messages Check
                if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji()) {
                    MessageObject messageObject = mo;
                    String messageCaption = caption;
                    sendSteps.add(() -> {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextCopy));
                        sendTextMessage(messageObject, messageCaption, targetDialogId, notify, scheduleDate, payStars);
                        return SendDispatchResult.dispatched(1, null);
                    });
                    continue;
                }
                
                // Group ID
                long gid = mo.getGroupId();

                // Media: Photo / Video / Gif
                if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
                    if (!ensureDownloaded(mo)) {
                        onMessageSkipped();
                        continue;
                    }

                    if (gid != 0) {
                        addToGroup(gid, new GroupedMediaItem(mo, caption), albumMap, albumRemain, sendSteps, false, targetDialogId, notify, scheduleDate, payStars);
                    } else {
                        MessageObject messageObject = mo;
                        String messageCaption = caption;
                        sendSteps.add(() -> {
                            updateForwardingState(messageObject.isPhoto() ? LocaleController.getString(R.string.ForceForwardStatusPhotoCopy) : LocaleController.getString(R.string.ForceForwardStatusMediaCopy));
                            SendMessagesHelper.SendMessageParams params = messageObject.isPhoto()
                                    ? buildMappedPhotoParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate, null)
                                    : buildMappedDocumentParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate, null);
                            if (params == null) {
                                updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                                ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>(1);
                                SendMessagesHelper.SendingMediaInfo info = createMediaInfo(messageObject, messageCaption);
                                info.isVideo = messageObject.isVideo() || messageObject.isGif();
                                one.add(info);
                                sendMediaBatch(one, targetDialogId, false, false, notify, scheduleDate, payStars);
                                return SendDispatchResult.dispatched(1, createUploadPaths(one));
                            }
                            sendMappedParams(params, payStars);
                            return SendDispatchResult.dispatched(1, createUploadPaths(params));
                        });
                    }
                    continue;
                }

                // Documents
                if (mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker()) {
                    if (!ensureDownloaded(mo)) {
                        onMessageSkipped();
                        continue;
                    }
                    
                    if (gid != 0) {
                        addToGroup(gid, new GroupedMediaItem(mo, caption), docAlbumMap, albumRemain, sendSteps, true, targetDialogId, notify, scheduleDate, payStars);
                    } else {
                        MessageObject messageObject = mo;
                        String messageCaption = caption;
                        sendSteps.add(() -> {
                            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentCopy));
                            SendMessagesHelper.SendMessageParams params = buildMappedDocumentParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate, null);
                            if (params == null) {
                                updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentFallback));
                                params = buildDocumentFallbackParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate);
                                if (params == null) {
                                    onMessageSkipped();
                                    return SendDispatchResult.none();
                                }
                            }
                            sendMappedParams(params, payStars);
                            return SendDispatchResult.dispatched(1, createUploadPaths(params));
                        });
                    }
                    continue;
                }

                // Stickers
                if (mo.isSticker() || mo.isAnimatedSticker()) {
                    if (mo.getDocument() != null) {
                        MessageObject messageObject = mo;
                        sendSteps.add(() -> {
                            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStickerCopy));
                            MessageObject replyToMsg = replyToTopMessage;
                            SendMessagesHelper.getInstance(currentAccount).sendSticker(messageObject.getDocument(), null, targetDialogId, replyToMsg, replyToMsg, null, null, null, notify, scheduleDate, 0, false, null, quickReplyShortcut, quickReplyShortcutId, payStars, monoForumPeerId, suggestionParams);
                            return SendDispatchResult.dispatched(1, null);
                        });
                    } else {
                        onMessageSkipped();
                    }
                    continue;
                }
                
                // Fallback Text (e.g. text message with obscure type or just failsafe)
                if (hasMessage) {
                    MessageObject messageObject = mo;
                    sendSteps.add(() -> {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextFallback));
                        sendTextMessage(messageObject, null, targetDialogId, notify, scheduleDate, payStars);
                        return SendDispatchResult.dispatched(1, null);
                    });
                } else {
                    onMessageSkipped();
                }
            }

            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }

            // 3. Process Leftover Groups
            for (ArrayList<GroupedMediaItem> group : albumMap.values()) {
                enqueueLeftoverGroupAsSingles(sendSteps, new ArrayList<>(group), targetDialogId, notify, scheduleDate, payStars, false);
            }
            for (ArrayList<GroupedMediaItem> group : docAlbumMap.values()) {
                enqueueLeftoverGroupAsSingles(sendSteps, new ArrayList<>(group), targetDialogId, notify, scheduleDate, payStars, true);
            }

            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }

            if (sendSteps.isEmpty()) {
                finishRunAfterUiRefresh(taskId, onComplete);
                return;
            }
            runSendQueue(sendSteps, 0, taskId, onComplete);
        } catch (Exception e) {
            FileLog.e(e);
            failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
        }
    }
    
    private SendMessagesHelper.SendingMediaInfo createMediaInfo(MessageObject mo, String caption) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = resolvePath(mo);
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        info.caption = prependPseudoReplyCaption(mo, caption, entities);
        info.entities = entities.isEmpty() ? null : entities;
        if (mo.messageOwner != null && mo.messageOwner.media != null) {
            info.ttl = mo.messageOwner.media.ttl_seconds;
        }
        info.hasMediaSpoilers = mo.hasMediaSpoilers();
        return info;
    }

    private void sendTextMessage(MessageObject mo, String captionOverride, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (mo == null) return; // Defensive null check

        String text = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message)
                ? mo.messageOwner.message
                : captionOverride;

        if (TextUtils.isEmpty(text)) return;

        ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner != null && mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                ? new ArrayList<>(mo.messageOwner.entities)
                : MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);

        // Prepend pseudo-reply header with original sender info
        text = prependPseudoReply(mo, text, entities);

        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, false, entities, null, null, notify, scheduleDate, 0, null, false);
        applySendContext(params, payStars);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
    }

    private boolean shouldAbortMediaWait(MediaPreparationResult mediaState, long taskId, CompletionCallback onComplete) {
        if (mediaState.cancelledByUser) {
            setFailureReason(null);
            finishRun(taskId, false, onComplete);
            return true;
        }

        long now = SystemClock.elapsedRealtime();
        if (mediaWaitStartTime == 0L) {
            mediaWaitStartTime = now;
            mediaLastProgressTime = now;
            mediaLastObservedBytes = mediaState.downloadedBytes;
            mediaLastMissingCount = mediaState.missingMediaCount;
            return false;
        }

        boolean progressAdvanced = mediaState.missingMediaCount < mediaLastMissingCount || mediaState.downloadedBytes > mediaLastObservedBytes;
        if (progressAdvanced) {
            mediaLastProgressTime = now;
            mediaLastObservedBytes = mediaState.downloadedBytes;
            mediaLastMissingCount = mediaState.missingMediaCount;
            return false;
        }

        if (now - mediaWaitStartTime >= MAX_MEDIA_WAIT_MS) {
            FileLog.w("ForceForward: media wait exceeded hard timeout");
            failRun(taskId, LocaleController.getString(R.string.ForceForwardMediaTimedOut), onComplete);
            return true;
        }

        if (now - mediaLastProgressTime >= MAX_MEDIA_STALL_TIMEOUT_MS) {
            String failureReason = mediaState.activeDownloadCount > 0
                    ? LocaleController.getString(R.string.ForceForwardMediaStalled)
                    : LocaleController.getString(R.string.ForceForwardMediaDidNotStart);
            FileLog.w("ForceForward: " + failureReason);
            failRun(taskId, failureReason, onComplete);
            return true;
        }

        return false;
    }

}
