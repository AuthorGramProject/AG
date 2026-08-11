#!/usr/bin/env python3
"""Physically remove policy-sensitive Main-only runtime from AuthorGram Play source.

This script is intentionally source-level rather than a runtime feature flag.  It
keeps only ABI-compatible no-op facades where shared Telegram code still needs a
class/method symbol.  The Play APK therefore cannot regain these features by
changing a preference or flipping one BuildConfig boolean.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAY_PACKAGE = "toss.authorgram.apk"


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"Missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def write_exact(relative: str, content: str) -> bool:
    path = ROOT / relative
    previous = path.read_text(encoding="utf-8") if path.exists() else None
    if previous == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="")
    return True


def replace_once(relative: str, old: str, new: str) -> bool:
    content = read(relative)
    if new in content:
        return False
    count = content.count(old)
    if count != 1:
        raise RuntimeError(
            f"Play sanitizer marker changed in {relative}: expected 1 occurrence, got {count}"
        )
    return write_exact(relative, content.replace(old, new, 1))


STUBS: dict[str, str] = {
    "TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java": """package toss.authorgram.settings;

/**
 * Play-Market compatibility stub.
 *
 * The Spy feature set is intentionally not implemented in the Play build.
 * Keeping the class symbol avoids fragile cross-version compile dependencies while
 * ensuring there is no hidden settings UI or runtime implementation to reactivate.
 */
public final class AGSpySettingsActivity extends BaseAGXSettingsActivity {
}
""",
    "TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java": """package toss.authorgram.settings;

/**
 * Play-Market compatibility stub.
 *
 * Deleted-message retention, edit-history retention, saved attachments and the
 * related Ayu/Spy database management UI are intentionally absent in Play.
 */
public final class AGPrivacySettingsActivity extends BaseAGXSettingsActivity {
}
""",
    "TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java": """package toss.authorgram.settings;

/**
 * Play-Market compatibility stub.
 *
 * Ghost Mode is not implemented in the Play build. The empty class preserves
 * source compatibility with upstream menu code without exposing a dormant UI.
 */
public final class GhostModeActivity extends BaseAGXSettingsActivity {
}
""",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuGhostUtils.java": """package com.radolyn.ayugram.utils;

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

    public static void markReadOnServer(int messageId, TLRPC.InputPeer peer, boolean internal) {
    }

    public static void markReadOnServer(MessageObject message, boolean internal) {
    }

    public static void performStatusRequest(Boolean offline) {
    }

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
""",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuSavePreferences.java": """package com.radolyn.ayugram.messages;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/** Play build: deleted-message retention policy is intentionally absent. */
public final class AyuSavePreferences {
    public static final String saveExclusionPrefix = \"saveDeletedExclusion_\";

    private final TLRPC.Message message;
    private final int accountId;
    private final long userId;
    private long dialogId = -1;
    private long topicId = -1;
    private int messageId = -1;
    private int requestCatchTime = -1;

    public AyuSavePreferences(TLRPC.Message msg, int accountId, long dialogId, long topicId, int messageId, int requestCatchTime) {
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

    public static boolean saveDeletedMessageFor(int accountId, long dialogId, MessageObject messageObject) { return false; }
    public static boolean saveDeletedMessageFor(int accountId, long dialogId, long userId) { return false; }
    public static void setSaveDeletedExclusion(long chatId, boolean value) { }
    public static boolean getSaveDeletedExclusion(long chatId) { return false; }
    public static void loadAllExclusions() { }
    public TLRPC.Message getMessage() { return message; }
    public int getAccountId() { return accountId; }
    public long getUserId() { return userId; }
    public long getDialogId() { return dialogId; }
    public void setDialogId(long dialogId) { if (dialogId != 0) this.dialogId = dialogId; }
    public long getTopicId() { return topicId; }
    public int getMessageId() { return messageId; }
    public int getRequestCatchTime() { return requestCatchTime; }
    public long getFromUserId() { return message == null || message.from_id == null ? 0 : message.from_id.user_id; }
}
""",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuMessagesController.java": """package com.radolyn.ayugram.messages;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.EditedMessage;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Play build: deleted/edit history and saved-attachment runtime are absent. */
public final class AyuMessagesController {
    public static final String attachmentsSubfolder = \"Saved Attachments\";
    public static File attachmentsPath = new File(\"\");
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
""",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/LastSeenHelper.java": """package com.radolyn.ayugram.utils;

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
""",
    "TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPremiumStatusHelper.kt": """package xyz.nextalone.nagram.helper

import org.telegram.tgnet.TLRPC

/** Play build: local Premium emoji-status emulation is absent. */
object LocalPremiumStatusHelper {
    const val KEY_PREFIX = \"useLocalEmojiStatusData_\"
    @JvmStatic fun getDocumentId(user: TLRPC.User?): Long? = null
    @JvmStatic fun initForUser(userId: Long, force: Boolean = false) { }
    @JvmStatic fun apply(status: TLRPC.EmojiStatus?) { }
}
""",
    "TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPeerColorHelper.kt": """package xyz.nextalone.nagram.helper

import org.telegram.tgnet.TLRPC

/** Play build: local Premium peer/profile color emulation is absent. */
object LocalPeerColorHelper {
    const val KEY_PREFIX = \"useLocalQuoteColorData_\"
    @JvmStatic fun getColorId(user: TLRPC.User): Int? = null
    @JvmStatic fun getEmojiId(user: TLRPC.User?): Long? = null
    @JvmStatic fun getProfileColorId(user: TLRPC.User): Int? = null
    @JvmStatic fun getProfileEmojiId(user: TLRPC.User?): Long? = null
    @JvmStatic fun initForUser(userId: Long, force: Boolean = false) { }
    @JvmStatic fun apply(colorId: Int, emojiId: Long, profileColorId: Int, profileEmojiId: Long) { }
}
""",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java": """package org.telegram.messenger.authorgram;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Play build: custom AuthorGram wire-format crypto is absent. */
public final class AuthorGramCryptoInterceptor {
    private AuthorGramCryptoInterceptor() { }
    public static boolean prepareOutgoingRequest(int account, TLObject request, MessageObject messageObject) { return true; }
    public static boolean decryptIncomingMessage(int account, TLRPC.Message message) { return false; }
}
""",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatState.java": """package org.telegram.messenger.authorgram;

/** Play build: custom AuthorGram encryption state is absent. */
public final class AuthorGramChatState {
    private AuthorGramChatState() { }
    public static boolean isEnabled(int account, long dialogId) { return false; }
    public static void setEnabled(int account, long dialogId, boolean enabled) { }
    public static boolean toggle(int account, long dialogId) { return false; }
}
""",
}


def patch_direct_local_premium() -> bool:
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"
    old = """    public boolean isPremiumOrLocal() {
        TLRPC.User user = currentUser;
        if (user == null) {
            return false;
        }
        return user.premium || NekoConfig.localPremium.Bool();
    }
"""
    new = """    public boolean isPremiumOrLocal() {
        TLRPC.User user = currentUser;
        return user != null && user.premium;
    }
"""
    changed = replace_once(relative, old, new)
    content = read(relative)
    if "NekoConfig." not in content and "import tw.nekomimi.nekogram.NekoConfig;\n" in content:
        content = content.replace("import tw.nekomimi.nekogram.NekoConfig;\n", "", 1)
        changed = write_exact(relative, content) or changed
    return changed


def validate_no_runtime_consumers() -> None:
    source_roots = [
        ROOT / "TMessagesProj/src/main/java",
        ROOT / "TMessagesProj/src/main/kotlin",
    ]
    allow = {
        "hideSponsoredMessage": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java",
        },
        "HideProxySponsorChannel": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java",
        },
        "ignoreContentRestrictions": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java",
        },
    }
    failures: list[str] = []
    for root in source_roots:
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix not in {".java", ".kt"}:
                continue
            relative = path.relative_to(ROOT).as_posix()
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for token, allowed_paths in allow.items():
                if token in content and relative not in allowed_paths:
                    failures.append(f"{token} has a Play runtime consumer in {relative}")
    if failures:
        raise RuntimeError("\n".join(failures))


def validate_stubs() -> None:
    for relative, expected in STUBS.items():
        actual = read(relative)
        if actual != expected:
            raise RuntimeError(f"Play runtime stub drifted: {relative}")

    user_config = read("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
    if "return user.premium || NekoConfig.localPremium.Bool();" in user_config:
        raise RuntimeError("Direct localPremium bypass remains in UserConfig")
    if "return user != null && user.premium;" not in user_config:
        raise RuntimeError("UserConfig Play premium check is not server-authoritative")

    ghost = read("TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuGhostUtils.java")
    for forbidden in ("Blocking read", "Blocking story", "Forcing offline", "sendFakeReadResponse"):
        if forbidden in ghost:
            raise RuntimeError(f"Ghost runtime marker remains: {forbidden}")

    retention = read("TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuMessagesController.java")
    for forbidden in ("AyuData", "DeletedMessageDao", "EditedMessageDao", ".insert(", "clearMediaPath"):
        if forbidden in retention:
            raise RuntimeError(f"Retention runtime marker remains: {forbidden}")

    crypto = read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java")
    for forbidden in ("AuthorGramChatCrypto", "AuthorGramCrypto", "encryptOutgoingText", "decryptTextOrNull"):
        if forbidden in crypto:
            raise RuntimeError(f"Custom crypto runtime marker remains: {forbidden}")

    validate_no_runtime_consumers()


def main() -> int:
    properties = read("gradle.properties")
    if f"APP_PACKAGE={PLAY_PACKAGE}" not in properties:
        raise RuntimeError("Refusing to strip Main/dev source: APP_PACKAGE is not the Play package")

    changed = 0
    for relative, content in STUBS.items():
        changed += int(write_exact(relative, content))
    changed += int(patch_direct_local_premium())
    validate_stubs()
    print(f"AuthorGram Play runtime sanitizer passed; changed files: {changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
