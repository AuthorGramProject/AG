#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(*args: str, check: bool = True):
    return subprocess.run(args, cwd=ROOT, check=check)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, text: str) -> None:
    (ROOT / relative).write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new and new in text and old not in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one old marker, found {count}")
    return text.replace(old, new, 1)


props = read("gradle.properties")
if "APP_PACKAGE=toss.authorgram.apk" not in props:
    raise RuntimeError("Refusing to modify non-Play source")

# Temporarily reuse the already-audited Play stubs from the contaminated branch.
# They are copied into the real Play source and the temporary tooling is deleted
# before the final commit. This is NOT build-time stripping.
run(
    "git",
    "fetch",
    "origin",
    "backup/play-market-before-clean-rollback-20260915:refs/remotes/origin/play-cleanup-backup",
)
run(
    "git",
    "checkout",
    "origin/play-cleanup-backup",
    "--",
    "scripts/play_stubs",
    "scripts/strip_authorgram_play_runtime.py",
)

# First pass intentionally may fail validation on the older clean Play lineage;
# all stub/source transformations happen before that validation.
run("python3", "scripts/strip_authorgram_play_runtime.py", check=False)

# ---------------------------------------------------------------------------
# Restore standard Telegram runtime semantics in the old Play lineage.
# ---------------------------------------------------------------------------

dialogs_path = "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
dialogs = read(dialogs_path)
dialogs = replace_once(
    dialogs,
    "            } else if (NekoConfig.unlimitedPinnedDialogs.Bool() || folderId != 0 || filter != null) {",
    "            } else if (folderId != 0 || filter != null) {",
    "DialogsActivity unlimited pinned dialogs bypass",
)
write(dialogs_path, dialogs)

messages_path = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
messages = read(messages_path)
messages = replace_once(
    messages,
    "        if (!premium && !NekoConfig.localPremium.Bool()) {",
    "        if (!premium) {",
    "MessagesController updatePremium local Premium bypass",
)
messages = replace_once(
    messages,
    "                    filter.locked = !NekoConfig.localPremium.Bool();",
    "                    filter.locked = true;",
    "MessagesController filter lock local Premium bypass",
)
messages = replace_once(
    messages,
    "        return !premiumFeaturesBlocked() && (currentUser.premium || currentUser.id == getUserConfig().getClientUserId() && NekoConfig.localPremium.Bool()) && !isSupportUser(currentUser);",
    "        return !premiumFeaturesBlocked() && currentUser.premium && !isSupportUser(currentUser);",
    "MessagesController isPremiumUser local Premium bypass",
)
messages = replace_once(
    messages,
    "                    setFolderTags(res.tags_enabled || !getUserConfig().isPremium() && NekoConfig.localPremium.Bool());",
    "                    setFolderTags(res.tags_enabled);",
    "MessagesController folder tags local Premium bypass",
)
messages = replace_once(
    messages,
    "                if (!NekoConfig.unlimitedPinnedDialogs.Bool()) getConnectionsManager().sendRequest(req, (response, error) -> {",
    "                getConnectionsManager().sendRequest(req, (response, error) -> {",
    "MessagesController pinned dialog server request bypass",
)
messages = replace_once(
    messages,
    """    public void loadPinnedDialogs(final int folderId, long newDialogId, ArrayList<Long> order) {
        if (NekoConfig.unlimitedPinnedDialogs.Bool()) {
            return;
        }
        if (loadingPinnedDialogs.indexOfKey(folderId) >= 0 || getUserConfig().isPinnedDialogsLoaded(folderId)) {""",
    """    public void loadPinnedDialogs(final int folderId, long newDialogId, ArrayList<Long> order) {
        if (loadingPinnedDialogs.indexOfKey(folderId) >= 0 || getUserConfig().isPinnedDialogsLoaded(folderId)) {""",
    "MessagesController pinned dialog loading bypass",
)
messages = replace_once(
    messages,
    """    public SponsoredMessagesInfo getSponsoredMessages(long dialogId) {
        if (NekoConfig.hideSponsoredMessage.Bool()) {
            return null;
        }
        SponsoredMessagesInfo info = sponsoredMessages.get(dialogId);""",
    """    public SponsoredMessagesInfo getSponsoredMessages(long dialogId) {
        SponsoredMessagesInfo info = sponsoredMessages.get(dialogId);""",
    "MessagesController sponsored messages early bypass",
)
messages = replace_once(
    messages,
    """                        if (NekoConfig.hideSponsoredMessage.Bool()) {
                            message.hide = true;
                        }
                        message.peer_id = getPeer(dialogId);""",
    """                        message.peer_id = getPeer(dialogId);""",
    "MessagesController sponsored messages hidden flag bypass",
)
messages = replace_once(
    messages,
    "        if (reasons.isEmpty() || NekoConfig.ignoreContentRestrictions.Bool()) {",
    "        if (reasons.isEmpty()) {",
    "MessagesController content restriction bypass",
)
write(messages_path, messages)

media_path = "TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java"
media = read(media_path)
media = replace_once(
    media,
    """        if (type == TYPE_FAVE && NekoConfig.unlimitedFavedStickers.Bool()) {
            return new ArrayList<>(recentStickers[type]);
        }
""",
    "",
    "MediaDataController unlimited favorites getter bypass",
)
media = replace_once(
    media,
    "                boolean replace = !NekoConfig.unlimitedFavedStickers.Bool() && recentStickers[type].size() > (getUserConfig().isPremium() ? 10 : getMessagesController().maxFaveStickersCount);",
    "                boolean replace = recentStickers[type].size() > (getUserConfig().isPremium() ? 10 : getMessagesController().maxFaveStickersCount);",
    "MediaDataController favorites replacement limit",
)
media = replace_once(
    media,
    """            if (!NekoConfig.unlimitedFavedStickers.Bool()) {
                TLRPC.TL_messages_faveSticker req = new TLRPC.TL_messages_faveSticker();
                req.id = new TLRPC.TL_inputDocument();
                req.id.id = document.id;
                req.id.access_hash = document.access_hash;
                req.id.file_reference = document.file_reference;
                if (req.id.file_reference == null) {
                    req.id.file_reference = new byte[0];
                }
                req.unfave = remove;
                getConnectionsManager().sendRequest(req, (response, error) -> {
                    if (error != null && FileRefController.isFileRefError(error.text) && parentObject != null) {
                        getFileRefController().requestReference(parentObject, req);
                    } else {
                        AndroidUtilities.runOnUIThread(() -> getMediaDataController().loadRecents(MediaDataController.TYPE_FAVE, false, false, true));
                    }
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> getMediaDataController().loadRecents(MediaDataController.TYPE_FAVE, false, true, false));
            }
            maxCount = NekoConfig.unlimitedFavedStickers.Bool() ? Integer.MAX_VALUE : getUserConfig().isPremium() ? 10 : getMessagesController().maxFaveStickersCount;""",
    """            TLRPC.TL_messages_faveSticker req = new TLRPC.TL_messages_faveSticker();
            req.id = new TLRPC.TL_inputDocument();
            req.id.id = document.id;
            req.id.access_hash = document.access_hash;
            req.id.file_reference = document.file_reference;
            if (req.id.file_reference == null) {
                req.id.file_reference = new byte[0];
            }
            req.unfave = remove;
            getConnectionsManager().sendRequest(req, (response, error) -> {
                if (error != null && FileRefController.isFileRefError(error.text) && parentObject != null) {
                    getFileRefController().requestReference(parentObject, req);
                } else {
                    AndroidUtilities.runOnUIThread(() -> getMediaDataController().loadRecents(MediaDataController.TYPE_FAVE, false, false, true));
                }
            });
            maxCount = getUserConfig().isPremium() ? 10 : getMessagesController().maxFaveStickersCount;""",
    "MediaDataController favorites server synchronization bypass",
)
media = replace_once(
    media,
    "        final ArrayList<TLRPC.Document>[] mergedDocumentsHolder = new ArrayList[1];\n",
    "",
    "MediaDataController unlimited favorites merge holder",
)
media = replace_once(
    media,
    "                            maxCount = NekoConfig.unlimitedFavedStickers.Bool() ? Integer.MAX_VALUE : getUserConfig().isPremium() ? 10 : getMessagesController().maxFaveStickersCount;",
    "                            maxCount = getUserConfig().isPremium() ? 10 : getMessagesController().maxFaveStickersCount;",
    "MediaDataController database favorites limit",
)
media = replace_once(
    media,
    """                    // For unlimited faved stickers, merge with existing database entries
                    ArrayList<TLRPC.Document> finalDocuments = documents;
                    if (type == TYPE_FAVE && NekoConfig.unlimitedFavedStickers.Bool() && replace) {
                        HashSet<Long> serverIds = new HashSet<>();
                        for (TLRPC.Document doc : documents) {
                            serverIds.add(doc.id);
                        }
                        SQLiteCursor cursor = database.queryFinalized("SELECT document FROM web_recent_v3 WHERE type = 5 ORDER BY date DESC");
                        ArrayList<TLRPC.Document> localStickers = new ArrayList<>();
                        while (cursor.next()) {
                            if (!cursor.isNull(0)) {
                                NativeByteBuffer data = cursor.byteBufferValue(0);
                                if (data != null) {
                                    TLRPC.Document document = TLRPC.Document.TLdeserialize(data, data.readInt32(false), false);
                                    if (document != null && !serverIds.contains(document.id)) {
                                        localStickers.add(document);
                                    }
                                    data.reuse();
                                }
                            }
                        }
                        cursor.dispose();
                        if (!localStickers.isEmpty()) {
                            finalDocuments = new ArrayList<>(documents);
                            finalDocuments.addAll(localStickers);
                        }
                    }
                    mergedDocumentsHolder[0] = finalDocuments;

""",
    "",
    "MediaDataController local unlimited favorites merge",
)
media = replace_once(
    media,
    """                                ArrayList<TLRPC.Document> documentsToUse = mergedDocumentsHolder[0] != null ? mergedDocumentsHolder[0] : documents;
                                recentStickers[type] = documentsToUse;""",
    """                                recentStickers[type] = documents;""",
    "MediaDataController merged favorites UI result",
)
media = replace_once(
    media,
    "                    int count = finalDocuments.size();",
    "                    int count = documents.size();",
    "MediaDataController merged favorites database count",
)
media = replace_once(
    media,
    "                    if (replace && (type != TYPE_FAVE || !NekoConfig.unlimitedFavedStickers.Bool())) {",
    "                    if (replace) {",
    "MediaDataController favorites database replace bypass",
)
media = replace_once(
    media,
    "                        TLRPC.Document document = finalDocuments.get(a);",
    "                        TLRPC.Document document = documents.get(a);",
    "MediaDataController merged favorites database item",
)
write(media_path, media)

# Re-run the audited sanitizer. It now must pass completely; if any dev-only
# runtime consumer remains, this script stops before a commit can happen.
run("python3", "scripts/strip_authorgram_play_runtime.py")

# The dev-only per-chat Spy policy should have no consumers after stubbing.
spy_policy = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramSpyPolicy.java"
if spy_policy.exists():
    references = []
    for base in (
        ROOT / "TMessagesProj/src/main/java",
        ROOT / "TMessagesProj/src/main/kotlin",
    ):
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path == spy_policy or path.suffix not in {".java", ".kt"}:
                continue
            try:
                source = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            if "AuthorGramSpyPolicy" in source:
                references.append(str(path.relative_to(ROOT)))
    if references:
        raise RuntimeError("AuthorGramSpyPolicy still has runtime consumers: " + ", ".join(references))
    spy_policy.unlink()

# ---------------------------------------------------------------------------
# Preserve ONLY the latest badge-vs-mute layout fix from dev commit e02f912.
# ---------------------------------------------------------------------------
badge_path = "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"
badge = read(badge_path)
badge = replace_once(
    badge,
    """        } else if (reserveMuteSlot) {
            int w = dp(6) + Theme.dialogs_muteDrawable.getIntrinsicWidth();
            if (drawPremium) {
                w += dp(6 + 24 + 6);
            }
            nameWidth -= w;
            nameAdditionalsForChannelSubscriber += w;
            if (LocaleController.isRTL) {
                nameLeft += w;
            }
        } else if (drawAuthorBadge) {
            int w = dp(6) + dp(18);
        } else if (drawVerified) {""",
    """        } else if (reserveMuteSlot) {
            int w = dp(6) + Theme.dialogs_muteDrawable.getIntrinsicWidth();
            if (drawAuthorBadge) {
                w += dp(6 + 18);
            } else if (drawPremium) {
                w += dp(6 + 24 + 6);
            }
            nameWidth -= w;
            nameAdditionalsForChannelSubscriber += w;
            if (LocaleController.isRTL) {
                nameLeft += w;
            }
        } else if (drawAuthorBadge) {
            int w = dp(6) + dp(18);
            nameWidth -= w;
            nameAdditionalsForChannelSubscriber += w;
            if (LocaleController.isRTL) {
                nameLeft += w;
            }
        } else if (drawVerified) {""",
    "DialogCell badge width reservation",
)
badge = replace_once(
    badge,
    """                if ((dialogMuted || drawUnmute || dialogMutedProgress > 0) && !drawVerified && drawScam == 0) {
                    if (drawPremium) {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(24));
                        nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                    } else {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx) - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth());
                    }
                } else if (drawVerified) {""",
    """                if ((dialogMuted || drawUnmute || dialogMutedProgress > 0) && !drawVerified && drawScam == 0) {
                    if (drawAuthorBadge) {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(18));
                        nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                    } else if (drawPremium) {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(24));
                        nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                    } else {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx) - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth());
                    }
                } else if (drawAuthorBadge) {
                    nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(18));
                    nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                } else if (drawVerified) {""",
    "DialogCell RTL badge/mute positioning",
)
badge = replace_once(
    badge,
    """                if ((dialogMuted || true) || drawUnmute || drawVerified || drawPremium || drawScam != 0) {
                    nameMuteLeft = (int) (nameLeft + left + dp(6));
                    if (drawPremium) {
                        nameMutedIconLeft = nameMuteLeft + dp(24 + 6);
                    }
                }""",
    """                if ((dialogMuted || true) || drawUnmute || drawVerified || drawPremium || drawAuthorBadge || drawScam != 0) {
                    nameMuteLeft = (int) (nameLeft + left + dp(6));
                    if (drawAuthorBadge) {
                        nameMutedIconLeft = nameMuteLeft + dp(18 + 6);
                    } else if (drawPremium) {
                        nameMutedIconLeft = nameMuteLeft + dp(24 + 6);
                    }
                }""",
    "DialogCell LTR badge/mute positioning",
)
badge = replace_once(
    badge,
    "                int muteAnchor = drawPremium ? nameMutedIconLeft : nameMuteLeft;",
    "                int muteAnchor = (drawPremium || drawAuthorBadge) ? nameMutedIconLeft : nameMuteLeft;",
    "DialogCell mute draw anchor",
)
write(badge_path, badge)

# ---------------------------------------------------------------------------
# Final source audit.
# ---------------------------------------------------------------------------
spy = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java")
privacy = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java")
ghost = read("TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java")
user_config = read("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
messages = read(messages_path)
media = read(media_path)
dialogs = read(dialogs_path)
badge = read(badge_path)

for label, source in (("Spy", spy), ("Privacy", privacy), ("Ghost", ghost)):
    if "Play-Market compatibility stub" not in source:
        raise RuntimeError(f"{label} settings class is not a Play compatibility stub")

for label, source, forbidden in (
    ("UserConfig", user_config, "NekoConfig.localPremium.Bool()"),
    ("MessagesController", messages, "NekoConfig.localPremium"),
    ("MessagesController", messages, "NekoConfig.unlimitedPinnedDialogs"),
    ("MessagesController", messages, "hideSponsoredMessage"),
    ("MessagesController", messages, "ignoreContentRestrictions"),
    ("DialogsActivity", dialogs, "NekoConfig.unlimitedPinnedDialogs"),
    ("MediaDataController", media, "NekoConfig.unlimitedFavedStickers"),
):
    if forbidden in source:
        raise RuntimeError(f"{label} still contains forbidden Play runtime token: {forbidden}")

if "return user != null && user.premium;" not in user_config:
    raise RuntimeError("UserConfig premium status is not server-authoritative")
if "return !premiumFeaturesBlocked() && currentUser.premium && !isSupportUser(currentUser);" not in messages:
    raise RuntimeError("MessagesController premium status is not server-authoritative")

for relative in (
    "TMessagesProj/src/main/java/com/radolyn/ayugram/ui/AyuViewDeleted.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/ui/AyuMessageHistory.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/proprietary/AyuHistoryHook.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramSpyPolicy.java",
):
    if (ROOT / relative).exists():
        raise RuntimeError(f"Forbidden/dev-only Play source remains: {relative}")

if "drawPremium || drawAuthorBadge" not in badge or "w += dp(6 + 18);" not in badge:
    raise RuntimeError("Latest badge/mute spacing fix is missing")

for strings_file in (ROOT / "TMessagesProj/src/main/res").rglob("strings*.xml"):
    content = strings_file.read_text(encoding="utf-8", errors="ignore")
    if '<string name="AppName">AuthorGram+</string>' in content:
        raise RuntimeError(f"AuthorGram+ AppName remains in {strings_file}")
    if '<string name="AppNameBeta">AuthorGram+</string>' in content:
        raise RuntimeError(f"AuthorGram+ AppNameBeta remains in {strings_file}")

# Remove temporary sanitizer/diagnostic machinery from the final Play tree.
shutil.rmtree(ROOT / "scripts/play_stubs", ignore_errors=True)
(ROOT / "scripts/strip_authorgram_play_runtime.py").unlink(missing_ok=True)
(ROOT / ".github/play_cleanup_diag.py").unlink(missing_ok=True)
(ROOT / ".github/workflows/play-cleanup-once.yml").unlink(missing_ok=True)
Path(__file__).unlink(missing_ok=True)

run("git", "diff", "--check")
print("Play source is clean; dev-only runtime removed; latest badge spacing preserved")
