package com.radolyn.ayugram.messages;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.EditedMessage;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Play build: deleted/edit history and saved-attachment runtime are absent. */
public final class AyuMessagesController {
    public static final String attachmentsSubfolder = "Saved Attachments";
    public static File attachmentsPath = new File("");
    public static final long[] ATTACHMENT_SIZE_LIMIT_PRESETS = new long[]{Long.MAX_VALUE};
    private static final AyuMessagesController INSTANCE = new AyuMessagesController();
    private AyuMessagesController() { }
    public static synchronized void syncAttachmentsPathWithConfig() { }
    public static synchronized void setAttachmentFolderPath(File path) { }
    public static boolean isManagedAttachmentPath(String path) { return false; }
    public static synchronized AyuMessagesController getInstance() { return INSTANCE; }
    public static int clampAttachmentSizeLimitPreset(int preset) { return 0; }
    public static long getConfiguredAttachmentSizeLimit() { return Long.MAX_VALUE; }
    public static void refreshAfterDatabaseChange() { }
    public static long trimAttachmentsFolderToLimit() { return 0L; }
    public static synchronized long trimAttachmentsFolderToLimit(File keepFile) { return 0L; }
    public void onMessageEdited(AyuSavePreferences prefs, TLRPC.Message newMessage) { }
    public void onMessageEditedForce(AyuSavePreferences prefs) { }
    public void onMessageDeleted(AyuSavePreferences prefs) { }
    public void onMessageDeleted(AyuSavePreferences prefs, boolean useQueue) { }
    public boolean hasAnyRevisions(long userId, long dialogId, int messageId) { return false; }
    public List<EditedMessage> getRevisions(long userId, long dialogId, int messageId) { return new ArrayList<>(); }
    public DeletedMessageFull getMessage(long userId, long dialogId, int messageId) { return null; }
    public List<DeletedMessageFull> getMessages(long userId, long dialogId, long startId, long endId, int limit) { return new ArrayList<>(); }
    public List<DeletedMessageFull> getTopicMessages(long userId, long dialogId, long topicId, long startId, long endId, int limit) { return new ArrayList<>(); }
    public List<DeletedMessageFull> getThreadMessages(long userId, long dialogId, long threadMessageId, long startId, long endId, int limit) { return new ArrayList<>(); }
    public List<DeletedMessageFull> getMessagesGroupedIn(long userId, long dialogId, List<Long> groupedIds) { return new ArrayList<>(); }
    public List<Integer> getExistingMessageIds(long userId, long dialogId, List<Integer> messageIds) { return new ArrayList<>(); }
    public List<DeletedMessageFull> getMessagesByIds(long userId, long dialogId, List<Integer> messageIds) { return new ArrayList<>(); }
    public void delete(long userId, long dialogId, int messageId) { }
    public void deleteMessages(long userId, long dialogId, List<Integer> messageIds) { }
    public void deleteRevision(long fakeId) { }
    public void deleteCurrent(long dialogId, long mergeDialogId, Runnable callback) { if (callback != null) callback.run(); }
    public boolean isAyuDeletedMessageId(long userId, long dialogId, int messageId) { return false; }
    public int getDeletedCount(long userId, long dialogId) { return 0; }
    public List<DeletedMessageFull> getLatestMessages(long userId, long dialogId, int limit) { return new ArrayList<>(); }
    public List<DeletedMessageFull> getOlderMessagesBefore(long userId, long dialogId, int before, int limit) { return new ArrayList<>(); }
    public void updateMediaPath(long userId, long dialogId, int messageId, String newPath) { }
    public void clean() { }
    public static synchronized void clearDatabase() { }
    public static synchronized void clearAttachments() { }
}
