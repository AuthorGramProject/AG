package com.radolyn.ayugram.messages;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.EditedMessage;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Play-Market compatibility facade.
 *
 * The Play build has no deleted-message database, edit-history capture or saved
 * attachment retention runtime. Public signatures remain only to keep shared
 * Telegram code source-compatible across Main and Play.
 */
public final class AyuMessagesController {
    public static final String attachmentsSubfolder = "Saved Attachments";
    public static File attachmentsPath = new File("");
    public static final long[] ATTACHMENT_SIZE_LIMIT_PRESETS = new long[]{Long.MAX_VALUE};

    private static final AyuMessagesController INSTANCE = new AyuMessagesController();

    private AyuMessagesController() {
    }

    public static synchronized void syncAttachmentsPathWithConfig() {
        // Attachment retention is absent in Play.
    }

    public static synchronized void setAttachmentFolderPath(File path) {
        // Attachment retention is absent in Play.
    }

    public static boolean isManagedAttachmentPath(String path) {
        return false;
    }

    public static synchronized AyuMessagesController getInstance() {
        return INSTANCE;
    }

    public static int clampAttachmentSizeLimitPreset(int preset) {
        return 0;
    }

    public static long getConfiguredAttachmentSizeLimit() {
        return Long.MAX_VALUE;
    }

    public static void refreshAfterDatabaseChange() {
        // No Ayu retention database exists in Play runtime.
    }

    public static long trimAttachmentsFolderToLimit() {
        return 0L;
    }

    public static synchronized long trimAttachmentsFolderToLimit(File keepFile) {
        return 0L;
    }

    public void onMessageEdited(AyuSavePreferences prefs, TLRPC.Message newMessage) {
        // Edit-history capture is absent in Play.
    }

    public void onMessageEditedForce(AyuSavePreferences prefs) {
        // Edit-history capture is absent in Play.
    }

    public void onMessageDeleted(AyuSavePreferences prefs) {
        // Deleted-message retention is absent in Play.
    }

    public void onMessageDeleted(AyuSavePreferences prefs, boolean useQueue) {
        // Deleted-message retention is absent in Play.
    }

    public boolean hasAnyRevisions(long userId, long dialogId, int messageId) {
        return false;
    }

    public List<EditedMessage> getRevisions(long userId, long dialogId, int messageId) {
        return new ArrayList<>();
    }

    public DeletedMessageFull getMessage(long userId, long dialogId, int messageId) {
        return null;
    }

    public List<DeletedMessageFull> getMessages(
            long userId,
            long dialogId,
            long startId,
            long endId,
            int limit
    ) {
        return new ArrayList<>();
    }

    public List<DeletedMessageFull> getTopicMessages(
            long userId,
            long dialogId,
            long topicId,
            long startId,
            long endId,
            int limit
    ) {
        return new ArrayList<>();
    }

    public List<DeletedMessageFull> getThreadMessages(
            long userId,
            long dialogId,
            long threadMessageId,
            long startId,
            long endId,
            int limit
    ) {
        return new ArrayList<>();
    }

    public List<DeletedMessageFull> getMessagesGroupedIn(
            long userId,
            long dialogId,
            List<Long> groupedIds
    ) {
        return new ArrayList<>();
    }

    public List<Integer> getExistingMessageIds(
            long userId,
            long dialogId,
            List<Integer> messageIds
    ) {
        return new ArrayList<>();
    }

    public List<DeletedMessageFull> getMessagesByIds(
            long userId,
            long dialogId,
            List<Integer> messageIds
    ) {
        return new ArrayList<>();
    }

    public void delete(long userId, long dialogId, int messageId) {
        // No retention database exists in Play.
    }

    public void deleteMessages(long userId, long dialogId, List<Integer> messageIds) {
        // No retention database exists in Play.
    }

    public void deleteRevision(long fakeId) {
        // No edit-history database exists in Play.
    }

    public void deleteCurrent(long dialogId, long mergeDialogId, Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    public boolean isAyuDeletedMessageId(long userId, long dialogId, int messageId) {
        return false;
    }

    public int getDeletedCount(long userId, long dialogId) {
        return 0;
    }

    public List<DeletedMessageFull> getLatestMessages(long userId, long dialogId, int limit) {
        return new ArrayList<>();
    }

    public List<DeletedMessageFull> getOlderMessagesBefore(
            long userId,
            long dialogId,
            int before,
            int limit
    ) {
        return new ArrayList<>();
    }

    public void updateMediaPath(long userId, long dialogId, int messageId, String newPath) {
        // Attachment retention is absent in Play.
    }

    public void clean() {
        // Nothing to clean.
    }

    public static synchronized void clearDatabase() {
        // No Ayu retention database exists in Play runtime.
    }

    public static synchronized void clearAttachments() {
        // No attachment retention exists in Play runtime.
    }
}
