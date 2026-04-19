package com.radolyn.ayugram;

import android.text.TextUtils;
import android.util.LongSparseArray;

import com.radolyn.ayugram.controllers.AyuMapper;
import com.radolyn.ayugram.utils.AyuMessageUtils;
import com.radolyn.ayugram.utils.seq.AyuSequentialUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class AyuForward {

    private static final DispatchQueue forwardQueue = new DispatchQueue("AyuForwardQueue");
    private static final ConcurrentHashMap<Long, AyuForward> activeForwards = new ConcurrentHashMap<>();

    private static final int STATUS_IDLE = 0;
    private static final int STATUS_LOADING = 1;
    private static final int STATUS_FORWARDING = 2;
    private static final int STATUS_STOPPING = 3;
    private static final int STATUS_REFRESH_MASK_BASE = 1 << 30;

    private static final class ForwardGroupState {
        final Long groupToken;
        final boolean finalItem;

        ForwardGroupState(Long groupToken, boolean finalItem) {
            this.groupToken = groupToken;
            this.finalItem = finalItem;
        }
    }

    public interface CompletionCallback {
        void onComplete(boolean shouldContinue);
    }

    private final ChatActivity parentFragment;
    private final int currentAccount;
    private final MessageObject replyToTopMessage;
    private final String quickReplyShortcut;
    private final int quickReplyShortcutId;
    private final long monoForumPeerId;
    private final MessageSuggestionParams suggestionParams;
    private final AyuMapper mapper;

    private volatile long activeTaskId;
    private volatile long targetDialogId;
    private volatile int currentStatus = STATUS_IDLE;
    private volatile int totalMessages;
    private volatile int sentMessages;
    private volatile int skippedMessages;
    private volatile int currentChunkIndex;
    private volatile int totalChunks = 1;
    private volatile String currentStatusDetail;
    private volatile String lastFailureReason;
    private volatile boolean stopRequested;
    private volatile boolean disposed;
    private volatile boolean detached;

    private int statusUpdateVersion;

    public AyuForward(ChatActivity fragment, int account) {
        this(
                fragment,
                account,
                fragment != null ? fragment.getThreadMessage() : null,
                fragment != null ? fragment.quickReplyShortcut : null,
                fragment != null ? fragment.getQuickReplyId() : 0,
                fragment != null ? fragment.getSendMonoForumPeerId() : 0,
                fragment != null ? fragment.getSendMessageSuggestionParams() : null
        );
    }

    public AyuForward(int account, MessageObject replyToTopMessage, int chatMode, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this(null, account, replyToTopMessage, quickReplyShortcut, quickReplyShortcutId, monoForumPeerId, suggestionParams);
    }

    private AyuForward(ChatActivity fragment, int account, MessageObject replyToTopMessage, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this.parentFragment = fragment;
        this.currentAccount = account;
        this.replyToTopMessage = replyToTopMessage;
        this.quickReplyShortcut = quickReplyShortcut;
        this.quickReplyShortcutId = quickReplyShortcutId;
        this.monoForumPeerId = monoForumPeerId;
        this.suggestionParams = suggestionParams;
        this.mapper = new AyuMapper(account);
    }

    public static boolean isChatNoForwards(MessageObject messageObject) {
        return AyuMessageUtils.isChatNoForwards(messageObject);
    }

    public static boolean isPeerNoForwards(MessageObject messageObject) {
        return AyuMessageUtils.isPeerNoForwards(messageObject);
    }

    public static boolean canForwardAyuDeletedMessage(MessageObject messageObject) {
        return AyuMessageUtils.canForwardAyuDeletedMessage(messageObject);
    }

    public static boolean isUnforwardable(MessageObject messageObject) {
        return AyuMessageUtils.isUnforwardable(messageObject);
    }

    public static boolean isFullAyuForwardsNeeded(MessageObject messageObject) {
        return AyuMessageUtils.isFullAyuForwardsNeeded(messageObject);
    }

    public static boolean isFullAyuForwardsNeeded(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (isFullAyuForwardsNeeded(messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAyuForwardNeeded(MessageObject messageObject) {
        return AyuMessageUtils.isAyuForwardNeeded(messageObject);
    }

    public static boolean isAyuForwardNeeded(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (isAyuForwardNeeded(messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isForwardingToDialog(long dialogId) {
        AyuForward forward = activeForwards.get(dialogId);
        return forward != null && forward.isForwarding();
    }

    public static String getStatusForDialog(long dialogId) {
        AyuForward forward = activeForwards.get(dialogId);
        return forward != null ? forward.getForwardingStatus() : null;
    }

    public static boolean stopForDialog(long dialogId) {
        AyuForward forward = activeForwards.get(dialogId);
        return forward != null && forward.stopCurrentRun();
    }

    public static String consumeFailureReasonForDialog(long dialogId) {
        AyuForward forward = activeForwards.get(dialogId);
        return forward != null ? forward.consumeLastFailureReason() : null;
    }

    public void dispose() {
        disposed = true;
        detached = true;
        stopRequested = true;
        synchronized (this) {
            if (activeTaskId == 0L) {
                clearRunStateLocked();
            }
        }
        lastFailureReason = null;
        notifyStatusChanged();
    }

    public void detachFromFragment() {
        detached = true;
    }

    public boolean isForwarding() {
        return activeTaskId != 0L;
    }

    public synchronized String consumeLastFailureReason() {
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
        notifyStatusChanged();
        return true;
    }

    public String getForwardingStatus() {
        if (!isForwarding()) {
            return null;
        }
        if (currentStatus == STATUS_LOADING) {
            return TextUtils.isEmpty(currentStatusDetail)
                    ? LocaleController.getString(R.string.ForceForwardStatusPreparingMedia)
                    : currentStatusDetail;
        }

        String progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, sentMessages, totalMessages);
        if (totalChunks > 1) {
            progress = progress + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks);
        }

        if (currentStatus == STATUS_FORWARDING) {
            String label = TextUtils.isEmpty(currentStatusDetail)
                    ? LocaleController.getString(R.string.ForceForwardStatusForwarding)
                    : currentStatusDetail;
            return label + " " + progress;
        }

        if (currentStatus == STATUS_STOPPING) {
            String label = TextUtils.isEmpty(currentStatusDetail)
                    ? LocaleController.getString(R.string.ForceForwardStatusStopping)
                    : currentStatusDetail;
            return totalChunks > 1
                    ? label + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks)
                    : label;
        }

        return null;
    }

    public void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, CompletionCallback onComplete) {
        forwardMessages(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, 0, 1, onComplete);
    }

    public void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, int chunkIndex, int chunkCount, CompletionCallback onComplete) {
        if (disposed || messagesToSend == null || messagesToSend.isEmpty() || (!detached && parentFragment != null && parentFragment.getParentActivity() == null)) {
            setFailureReason(null);
            runCompletion(onComplete, false);
            return;
        }

        ArrayList<MessageObject> request = new ArrayList<>(messagesToSend);
        long taskId = startRun(request.size(), targetDialogId, chunkIndex, chunkCount);
        forwardQueue.postRunnable(() -> executeForward(request, targetDialogId, hideCaption, notify, scheduleDate, payStars, taskId, onComplete));
    }

    private void executeForward(ArrayList<MessageObject> messages, long targetDialogId, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, CompletionCallback onComplete) {
        try {
            if (!ensureTaskCanProceed(taskId, onComplete)) {
                return;
            }

            if (hasUndownloadedAyuDeletedMedia(messages)) {
                failRun(taskId, LocaleController.getString(R.string.PleaseDownload), onComplete);
                return;
            }

            ArrayList<MessageObject> pendingDownloads = collectPendingDownloads(messages);
            if (!pendingDownloads.isEmpty()) {
                updateLoadingState(LocaleController.getString(R.string.ForceForwardStatusWaitingDownloads));
                if (!AyuSequentialUtils.loadDocumentsSync(currentAccount, pendingDownloads)) {
                    failRun(taskId, LocaleController.getString(R.string.ForceForwardMediaStalled), onComplete);
                    return;
                }
                if (!ensureTaskCanProceed(taskId, onComplete)) {
                    return;
                }
            }

            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStarting));

            LongSparseArray<Integer> groupRemaining = new LongSparseArray<>();
            LongSparseArray<Long> groupTokens = new LongSparseArray<>();
            prepareGroupState(messages, groupRemaining, groupTokens);

            for (int i = 0; i < messages.size(); i++) {
                if (!ensureTaskCanProceed(taskId, onComplete)) {
                    return;
                }
                MessageObject messageObject = messages.get(i);
                if (messageObject == null || messageObject.messageOwner == null) {
                    onMessageSkipped();
                    continue;
                }

                ForwardGroupState groupState = consumeGroupState(messageObject, groupRemaining, groupTokens);
                if (!forwardSingleMessage(messageObject, targetDialogId, hideCaption, notify, scheduleDate, payStars, groupState)) {
                    onMessageSkipped();
                    continue;
                }
                onMessageSent();
            }

            finishRun(taskId, true, onComplete);
        } catch (Exception e) {
            FileLog.e(e);
            failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
        }
    }

    private boolean forwardSingleMessage(MessageObject messageObject, long targetDialogId, boolean hideCaption, boolean notify, int scheduleDate, long payStars, ForwardGroupState groupState) {
        String caption = null;
        CharSequence captionSequence = hideCaption ? null : getForwardCaption(messageObject);
        if (captionSequence != null) {
            caption = captionSequence.toString();
        }
        String textFallback = getTextFallback(messageObject, caption);

        if (messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji()) {
            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextCopy));
            return sendTextSync(messageObject, textFallback, targetDialogId, notify, scheduleDate, payStars);
        }

        if (messageObject.isSticker() || messageObject.isAnimatedSticker()) {
            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStickerCopy));
            return sendStickerSync(messageObject, targetDialogId, notify, scheduleDate, payStars);
        }

        boolean waitForMessage = groupState.groupToken == null || groupState.finalItem;
        boolean waitForUpload = groupState.groupToken == null;
        HashMap<String, String> groupParams = groupState.groupToken != null ? mapper.createGroupedParams(groupState.groupToken, groupState.finalItem) : null;

        if (messageObject.isPhoto()) {
            if (!hasLocalCopy(messageObject)) {
                return fallbackToText(messageObject, textFallback, targetDialogId, notify, scheduleDate, payStars);
            }
            updateForwardingState(groupState.groupToken != null
                    ? LocaleController.getString(R.string.ForceForwardStatusMediaGroup)
                    : LocaleController.getString(R.string.ForceForwardStatusPhotoCopy));
            SendMessagesHelper.SendMessageParams params = buildMappedPhotoParams(messageObject, caption, targetDialogId, notify, scheduleDate, groupParams);
            if (params == null) {
                return fallbackToText(messageObject, textFallback, targetDialogId, notify, scheduleDate, payStars);
            }
            return dispatchParamsSync(params, resolvePhotoUploadTrackingPath(params.photo), targetDialogId, payStars, waitForMessage, waitForUpload);
        }

        if (messageObject.getDocument() != null) {
            if (!hasLocalCopy(messageObject) && !TextUtils.isEmpty(textFallback)) {
                return fallbackToText(messageObject, textFallback, targetDialogId, notify, scheduleDate, payStars);
            }
            if (!hasLocalCopy(messageObject)) {
                return false;
            }

            updateForwardingState(groupState.groupToken != null
                    ? (messageObject.isVideo() || messageObject.isGif()
                    ? LocaleController.getString(R.string.ForceForwardStatusMediaGroup)
                    : LocaleController.getString(R.string.ForceForwardStatusDocumentGroup))
                    : (messageObject.isVideo() || messageObject.isGif()
                    ? LocaleController.getString(R.string.ForceForwardStatusMediaCopy)
                    : LocaleController.getString(R.string.ForceForwardStatusDocumentCopy)));

            SendMessagesHelper.SendMessageParams params = buildMappedDocumentParams(messageObject, caption, targetDialogId, notify, scheduleDate, groupParams);
            if (params == null) {
                params = buildDocumentFallbackParams(messageObject, caption, targetDialogId, notify, scheduleDate, groupParams);
                if (params == null) {
                    return fallbackToText(messageObject, textFallback, targetDialogId, notify, scheduleDate, payStars);
                }
                if (groupState.groupToken == null) {
                    updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentFallback));
                }
            }
            return dispatchParamsSync(params, params.path, targetDialogId, payStars, waitForMessage, waitForUpload);
        }

        return fallbackToText(messageObject, textFallback, targetDialogId, notify, scheduleDate, payStars);
    }

    private boolean fallbackToText(MessageObject messageObject, String text, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextFallback));
        return sendTextSync(messageObject, text, targetDialogId, notify, scheduleDate, payStars);
    }

    private void prepareGroupState(ArrayList<MessageObject> messages, LongSparseArray<Integer> groupRemaining, LongSparseArray<Long> groupTokens) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject == null) {
                continue;
            }
            long groupId = messageObject.getGroupId();
            if (groupId == 0L) {
                continue;
            }
            Integer remaining = groupRemaining.get(groupId);
            groupRemaining.put(groupId, remaining == null ? 1 : remaining + 1);
            if (groupTokens.get(groupId) == null) {
                long generatedGroupId;
                do {
                    generatedGroupId = Utilities.random.nextLong();
                } while (generatedGroupId == 0L);
                groupTokens.put(groupId, generatedGroupId);
            }
        }
    }

    private ForwardGroupState consumeGroupState(MessageObject messageObject, LongSparseArray<Integer> groupRemaining, LongSparseArray<Long> groupTokens) {
        if (messageObject == null) {
            return new ForwardGroupState(null, false);
        }
        long groupId = messageObject.getGroupId();
        if (groupId == 0L) {
            return new ForwardGroupState(null, false);
        }
        Integer remaining = groupRemaining.get(groupId);
        Long groupToken = groupTokens.get(groupId);
        if (remaining == null || groupToken == null) {
            return new ForwardGroupState(null, false);
        }
        remaining = remaining - 1;
        if (remaining <= 0) {
            groupRemaining.remove(groupId);
            return new ForwardGroupState(groupToken, true);
        }
        groupRemaining.put(groupId, remaining);
        return new ForwardGroupState(groupToken, false);
    }

    private ArrayList<MessageObject> collectPendingDownloads(ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (needsLocalCopy(messageObject) && !hasLocalCopy(messageObject) && !messageObject.isAyuDeleted()) {
                result.add(messageObject);
            }
        }
        return result;
    }

    private boolean hasUndownloadedAyuDeletedMedia(ArrayList<MessageObject> messages) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && messageObject.isAyuDeleted() && needsLocalCopy(messageObject) && !hasLocalCopy(messageObject)) {
                return true;
            }
        }
        return false;
    }

    private boolean needsLocalCopy(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji()) {
            return false;
        }
        if (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isGif()) {
            return true;
        }
        if (messageObject.isAyuDeleted()) {
            return messageObject.getDocument() != null;
        }
        return messageObject.getDocument() != null && !messageObject.isSticker() && !messageObject.isAnimatedSticker();
    }

    private boolean hasLocalCopy(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (AyuMessageUtils.hasLocalForwardCopy(messageObject)) {
            return true;
        }
        String path = resolvePath(messageObject);
        return !TextUtils.isEmpty(path) && new File(path).exists();
    }

    private String getTextFallback(MessageObject messageObject, String caption) {
        if (messageObject != null && messageObject.messageOwner != null && !TextUtils.isEmpty(messageObject.messageOwner.message)) {
            return messageObject.messageOwner.message;
        }
        return caption;
    }

    private CharSequence getForwardCaption(MessageObject messageObject) {
        return ChatActivity.getMessageCaption(messageObject, null, null);
    }

    private String resolvePath(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        return FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
    }

    private boolean sendTextSync(MessageObject messageObject, String sourceText, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (TextUtils.isEmpty(sourceText)) {
            return false;
        }

        ArrayList<TLRPC.MessageEntity> entities;
        if (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.entities != null && !messageObject.messageOwner.entities.isEmpty()) {
            entities = new ArrayList<>(messageObject.messageOwner.entities);
        } else {
            entities = MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{sourceText}, true);
            if (entities == null) {
                entities = new ArrayList<>();
            }
        }

        String text = prependPseudoReply(messageObject, sourceText, entities);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, false, entities.isEmpty() ? null : entities, null, null, notify, scheduleDate, 0, null, false);
        applySendContext(params, payStars);

        return AyuSequentialUtils.dispatchSendSync(currentAccount, targetDialogId, null, true, false, () ->
                SendMessagesHelper.getInstance(currentAccount).sendMessage(params));
    }

    private boolean sendStickerSync(MessageObject messageObject, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        MessageObject replyToMessage = replyToTopMessage;
        return AyuSequentialUtils.dispatchSendSync(currentAccount, targetDialogId, null, true, false, () ->
                SendMessagesHelper.getInstance(currentAccount).sendSticker(
                        messageObject.getDocument(),
                        null,
                        targetDialogId,
                        replyToMessage,
                        replyToMessage,
                        null,
                        null,
                        null,
                        notify,
                        scheduleDate,
                        0,
                        false,
                        null,
                        quickReplyShortcut,
                        quickReplyShortcutId,
                        payStars,
                        monoForumPeerId,
                        suggestionParams
                ));
    }

    private boolean dispatchParamsSync(SendMessagesHelper.SendMessageParams params, String uploadTrackingPath, long targetDialogId, long payStars, boolean waitForMessage, boolean waitForUpload) {
        if (params == null) {
            return false;
        }
        applySendContext(params, payStars);
        String effectiveUploadPath = !TextUtils.isEmpty(uploadTrackingPath) ? uploadTrackingPath : params.path;
        return AyuSequentialUtils.dispatchSendSync(currentAccount, targetDialogId, effectiveUploadPath, waitForMessage, waitForUpload && !TextUtils.isEmpty(effectiveUploadPath), () ->
                SendMessagesHelper.getInstance(currentAccount).sendMessage(params));
    }

    private String resolvePhotoUploadTrackingPath(TLRPC.TL_photo photo) {
        if (photo == null || photo.sizes == null || photo.sizes.isEmpty()) {
            return null;
        }
        TLRPC.PhotoSize photoSize = photo.sizes.get(photo.sizes.size() - 1);
        if (photoSize == null || photoSize.location == null) {
            photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
        }
        if (photoSize == null || photoSize.location == null) {
            return null;
        }
        return FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE) + "/" + photoSize.location.volume_id + "_" + photoSize.location.local_id + ".jpg";
    }

    private SendMessagesHelper.SendMessageParams buildMappedPhotoParams(MessageObject messageObject, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(messageObject);
        TLRPC.TL_photo photo = mapper.mapPhoto(messageObject, filePath);
        if (photo == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = copyEntities(messageObject);
        String effectiveCaption = prependPseudoReplyCaption(messageObject, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(
                photo,
                filePath,
                targetDialogId,
                null,
                null,
                effectiveCaption,
                entities.isEmpty() ? null : entities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                mapper.getMessageTtl(messageObject),
                messageObject,
                false,
                messageObject != null && messageObject.hasMediaSpoilers()
        );
    }

    private SendMessagesHelper.SendMessageParams buildMappedDocumentParams(MessageObject messageObject, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(messageObject);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.TL_document document = mapper.mapDocument(messageObject, filePath);
        if (document == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = copyEntities(messageObject);
        String effectiveCaption = prependPseudoReplyCaption(messageObject, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(
                document,
                null,
                filePath,
                targetDialogId,
                null,
                null,
                effectiveCaption,
                entities.isEmpty() ? null : entities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                mapper.getMessageTtl(messageObject),
                messageObject,
                null,
                false,
                messageObject != null && messageObject.hasMediaSpoilers()
        );
    }

    private SendMessagesHelper.SendMessageParams buildDocumentFallbackParams(MessageObject messageObject, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(messageObject);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        TLRPC.TL_document document = mapper.mapDocument(messageObject, filePath);
        if (document == null) {
            return null;
        }

        document.localPath = filePath;
        document.size = (int) file.length();
        document.date = (int) (System.currentTimeMillis() / 1000);

        ArrayList<TLRPC.MessageEntity> entities = copyEntities(messageObject);
        String effectiveCaption = prependPseudoReplyCaption(messageObject, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }

        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(
                document,
                null,
                filePath,
                targetDialogId,
                null,
                null,
                effectiveCaption,
                entities.isEmpty() ? null : entities,
                null,
                params,
                notify,
                scheduleDate,
                0,
                0,
                messageObject,
                null,
                false,
                messageObject != null && messageObject.hasMediaSpoilers()
        );
    }

    private ArrayList<TLRPC.MessageEntity> copyEntities(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.entities == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(messageObject.messageOwner.entities);
    }

    private void applySendContext(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        if (params.replyToMsg == null) {
            params.replyToMsg = replyToTopMessage;
        }
        if (params.replyToTopMsg == null) {
            params.replyToTopMsg = replyToTopMessage;
        }
        params.quick_reply_shortcut = quickReplyShortcut;
        params.quick_reply_shortcut_id = quickReplyShortcutId;
        params.payStars = payStars;
        params.monoForumPeer = monoForumPeerId;
        params.suggestionParams = suggestionParams;
    }

    private String prependPseudoReply(MessageObject messageObject, String text, ArrayList<TLRPC.MessageEntity> entities) {
        return AyuMessageUtils.prependPseudoReply(text, null, false, messageObject, entities, currentAccount, targetDialogId).text;
    }

    private String prependPseudoReplyCaption(MessageObject messageObject, String caption, ArrayList<TLRPC.MessageEntity> entities) {
        return AyuMessageUtils.prependPseudoReply(null, caption, messageObject != null && messageObject.isPhoto(), messageObject, entities, currentAccount, targetDialogId).caption;
    }

    private boolean ensureTaskCanProceed(long taskId, CompletionCallback onComplete) {
        if (disposed || !isTaskActive(taskId) || stopRequested) {
            finishRun(taskId, false, onComplete);
            return false;
        }
        return true;
    }

    private synchronized long startRun(int messageCount, long targetDialogId, int chunkIndex, int chunkCount) {
        activeTaskId++;
        this.targetDialogId = targetDialogId;
        this.currentChunkIndex = Math.max(chunkIndex, 0);
        this.totalChunks = Math.max(chunkCount, 1);
        this.currentStatus = STATUS_LOADING;
        this.totalMessages = Math.max(messageCount, 0);
        this.sentMessages = 0;
        this.skippedMessages = 0;
        this.currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusPreparingMedia);
        this.lastFailureReason = null;
        this.stopRequested = false;
        this.statusUpdateVersion = 0;
        activeForwards.put(targetDialogId, this);
        notifyStatusChanged();
        return activeTaskId;
    }

    private synchronized boolean isTaskActive(long taskId) {
        return activeTaskId == taskId;
    }

    private void updateLoadingState(String detail) {
        currentStatus = STATUS_LOADING;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private void updateForwardingState(String detail) {
        currentStatus = STATUS_FORWARDING;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private synchronized void setFailureReason(String failureReason) {
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
        if (sentMessages > totalMessages) {
            sentMessages = totalMessages;
        }
        notifyStatusChanged();
    }

    private void finishRun(long taskId, boolean shouldContinue, CompletionCallback onComplete) {
        boolean currentTask;
        synchronized (this) {
            currentTask = activeTaskId == taskId;
            if (currentTask) {
                if (skippedMessages > 0 && shouldContinue && lastFailureReason == null) {
                    lastFailureReason = LocaleController.formatString(R.string.ForceForwardSomeSkipped, skippedMessages);
                }
                clearRunStateLocked();
            }
        }

        if (currentTask) {
            notifyStatusChanged();
        }

        final boolean callbackResult = currentTask && shouldContinue;
        AndroidUtilities.runOnUIThread(() -> {
            if (detached) {
                if (currentTask) {
                    disposed = true;
                }
                return;
            }
            if (onComplete != null) {
                onComplete.onComplete(callbackResult);
            }
        });
    }

    private void clearRunStateLocked() {
        if (targetDialogId != 0L) {
            activeForwards.remove(targetDialogId);
        }
        activeTaskId = 0L;
        targetDialogId = 0L;
        currentStatus = STATUS_IDLE;
        totalMessages = 0;
        sentMessages = 0;
        skippedMessages = 0;
        currentStatusDetail = null;
        stopRequested = false;
        currentChunkIndex = 0;
        totalChunks = 1;
        statusUpdateVersion = 0;
    }

    private void notifyStatusChanged() {
        if (disposed) {
            return;
        }
        int updateMask = STATUS_REFRESH_MASK_BASE | ((statusUpdateVersion++ & 0x1FF) << 21);
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, updateMask));
    }

    private void runCompletion(CompletionCallback onComplete, boolean shouldContinue) {
        AndroidUtilities.runOnUIThread(() -> {
            if (onComplete != null) {
                onComplete.onComplete(shouldContinue);
            }
        });
    }
}
