#!/usr/bin/env python3
"""Static runtime-safety audit for AuthorGram-owned Android code.

This does not build the APK. It enforces high-value invariants around the custom
AuthorGram layer and selected Telegram-core repairs so known UI/reply/lifecycle,
ANR and resource regressions cannot silently return. Run it after the canonical
stability generators and before Gradle.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SETTINGS_PREVIEW = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram/ui/cells/MessageSettingsPreviewCell.java"
INTERCEPTOR = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"
SYNC_WAITER = ROOT / "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/SyncWaiter.java"
MESSAGE_WAITER = ROOT / "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/DummyMessageWaiter.java"
AYU_SEQUENTIAL = ROOT / "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/AyuSequentialUtils.java"
DIALOGS_ADAPTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java"
SCOPE_GUARD = ROOT / "scripts/patch_authorgram_chat_scope_safety.py"
STABILITY = ROOT / "scripts/patch_authorgram_main_stability.py"
RUNTIME_REPAIR = ROOT / "scripts/patch_authorgram_runtime_regressions.py"
NATIVE_MENU_PATCH = ROOT / "scripts/patch_authorgram_native_menu_stability.py"
BLUR_PATCH = ROOT / "scripts/patch_authorgram_full_screen_ios_blur.py"
RELEASE_SCRIPT = ROOT / "scripts/final_main_stable_release_12_9_2.sh"
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"

CUSTOM_ROOTS = (
    ROOT / "TMessagesProj/src/main/java/toss/authorgram",
    ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram",
)

DEPRECATED_UI_GENERATORS = (
    "patch_authorgram_ios_menu_v2.py",
    "patch_authorgram_final_menu_voice.py",
    "patch_authorgram_unified_message_menu.py",
    "patch_authorgram_adaptive_ios_preview.py",
    "patch_authorgram_final_chat_ui.py",
    "patch_authorgram_ui_12_9_2.py",
)


def read(path: Path) -> str:
    if not path.is_file():
        raise RuntimeError(f"Missing required source: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(text: str, tokens: tuple[str, ...], label: str, failures: list[str]) -> None:
    for token in tokens:
        if token not in text:
            failures.append(f"{label}: missing invariant {token!r}")


def forbid(text: str, tokens: tuple[str, ...], label: str, failures: list[str]) -> None:
    for token in tokens:
        if token in text:
            failures.append(f"{label}: forbidden regression {token!r}")


def audit_ios_message_preview(failures: list[str]) -> None:
    preview = read(PREVIEW)
    chat = read(CHAT)
    scope = read(SCOPE_GUARD)
    stability = read(STABILITY)
    runtime_repair = read(RUNTIME_REPAIR)
    native_patch = read(NATIVE_MENU_PATCH)
    blur_patch = read(BLUR_PATCH)
    scrim = read(SCRIM)

    require(
        preview,
        (
            "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT",
            "new ChatMessageCell(context, currentAccount)",
            "new ScrollView(context)",
            "previewScroll.setNestedScrollingEnabled(true);",
            "maxPreviewHeight",
            "sourceCell.copyParamsTo(previewCell);",
            "previewCell.setMessageObject(messageObject, null, false, false, false);",
            "public boolean shouldScrollWithActions()",
            "return false;",
        ),
        "iOS selected-message native preview",
        failures,
    )
    forbid(
        preview,
        (
            "Bitmap.createBitmap",
            "sourceCell.draw(",
            "getPixels(",
            "NativeCellSnapshotView",
            "BackupImageView avatarView",
            "TextView senderNameView",
            "new BluredView(",
            "previewCell.isChat = sourceCell != null && sourceCell.isChat;",
        ),
        "iOS selected-message native preview",
        failures,
    )

    require(
        chat,
        (
            "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
            "AUTHORGRAM_DEFERRED_IOS_PREVIEW_ATTACH",
            "authorGramIosPreviewAnchor.post(() -> {",
            ".setFixedMessagePreview(iosPreview);",
            "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR",
            "dimBehindView(null, true, true);",
        ),
        "iOS selected-message owner / full-screen blur",
        failures,
    )
    forbid(
        chat,
        (
            "popupLayout.addView(iosPreview",
            "popupLayout.addView(popupMessagePreview",
            "iosPreview.shouldScrollWithActions()",
            "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
            "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
            "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
        ),
        "iOS selected-message owner",
        failures,
    )

    require(
        scrim,
        (
            "private View fixedMessagePreview",
            "public void setFixedMessagePreview(View preview)",
            "AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT",
            "AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT",
            "params.setMarginStart(popupParams.getMarginStart());",
            "params.setMarginEnd(popupParams.getMarginEnd());",
            "params.gravity = popupParams.gravity;",
            "? oldParams.height",
            ": LayoutHelper.WRAP_CONTENT;",
            "AUTHORGRAM_MENU_FOOTER_SEPARATOR",
            "Theme.getColor(Theme.key_divider)",
            "popupWindowLayout.addView(bottomView, footerParams);",
            "bottomView.setBackground(null);",
        ),
        "ChatScrim final action-card geometry",
        failures,
    )
    forbid(
        scrim,
        (
            "Math.min(oldParams.height, AndroidUtilities.dp(44))",
            "int footerHeight = AndroidUtilities.dp(44)",
        ),
        "ChatScrim final footer geometry",
        failures,
    )

    require(
        blur_patch,
        (
            "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR",
            "dimBehindView(null, true, true);",
            "Unable to locate a known ChatActivity context-menu blur anchor; refusing fuzzy patch",
            "--mode",
            "validate",
        ),
        "canonical full-screen blur patch",
        failures,
    )

    require(
        scope,
        (
            "Read-only safety guard",
            "validate_canonical",
            "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "scope guard is read-only; no source rewrite performed",
        ),
        "iOS preview scope guard",
        failures,
    )
    forbid(
        scope,
        (
            "write_chat(",
            "write_preview(",
            "PREVIEW_SOURCE =",
        ),
        "iOS preview scope guard",
        failures,
    )

    # The canonical generator intentionally emits an intermediate compatibility
    # shape. The runtime/native post-pass owns the final native-cell context and
    # natural footer geometry, so the audit must validate both stages separately.
    require(
        stability,
        (
            "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW",
            "params.topMargin = AndroidUtilities.dp(8);",
            "params.bottomMargin = AndroidUtilities.dp(8);",
        ),
        "canonical iOS menu generator",
        failures,
    )
    require(
        runtime_repair,
        (
            "NATIVE_MENU_PATCH",
            "apply_native_menu_patch()",
            "validate_native_menu_patch()",
            "AUTHORGRAM_DEFERRED_IOS_PREVIEW_ATTACH",
            "AUTHORGRAM_STRICT_IOS_MENU_VIEWPORT",
        ),
        "final runtime/native menu repair wiring",
        failures,
    )
    require(
        native_patch,
        (
            "AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT",
            "sourceCell.copyParamsTo(previewCell);",
            "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT",
            "params.setMarginStart(popupParams.getMarginStart());",
            "params.setMarginEnd(popupParams.getMarginEnd());",
            "AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT",
            "AUTHORGRAM_TELEGRAM_ME_URL_DIFF_FIX",
        ),
        "native menu stability post-patch",
        failures,
    )


def audit_reply_integrity(failures: list[str]) -> None:
    settings = read(SETTINGS_PREVIEW)
    interceptor = read(INTERCEPTOR)

    require(
        settings,
        (
            "AUTHORGRAM_SETTINGS_PREVIEW_VALID_REPLY",
            "AUTHORGRAM_SETTINGS_PREVIEW_REPLY_INVARIANT",
            "AUTHORGRAM_SETTINGS_PREVIEW_SINGLE_AVATAR_DRAW",
            "message.reply_to = new TLRPC.TL_messageReplyHeader();",
            "message.reply_to.reply_to_msg_id = replyMessage.id;",
            "message.replyMessage = replyMessage;",
            "messageObject.replyMessageObject = replyMessageObject;",
            "messageObject.messageOwner.replyMessage = replyMessageObject.messageOwner;",
        ),
        "AuthorGram Chat settings preview",
        failures,
    )
    forbid(
        settings,
        (
            "message.flags = 33027;",
            "message.peer_id.user_id = 0;",
            "getAvatarImage().draw(canvas);",
        ),
        "AuthorGram Chat settings preview",
        failures,
    )

    require(
        interceptor,
        (
            "AUTHORGRAM_PLAY_STABLE_REPLY_MODEL",
            "public static boolean decryptIncomingMessage(int account, TLRPC.Message message)",
            "MessageObject.getDialogId(message)",
            "AuthorGramMessageMeta.markDecrypted(account, message);",
        ),
        "AuthorGram incoming reply ownership",
        failures,
    )
    forbid(
        interceptor,
        (
            "AUTHORGRAM_REPLY_TARGET_DECRYPTION",
            "TLRPC.Message nestedReply = message.replyMessage;",
            "decryptSingleIncomingMessage(account, nestedReply)",
            "decryptSingleIncomingMessage(account, message)",
            "message.replyMessage.message =",
        ),
        "AuthorGram incoming reply ownership",
        failures,
    )


def audit_ayu_waiter_safety(failures: list[str]) -> None:
    sync_waiter = read(SYNC_WAITER)
    message_waiter = read(MESSAGE_WAITER)
    sequential = read(AYU_SEQUENTIAL)

    require(
        sync_waiter,
        (
            "AUTHORGRAM_NO_UI_THREAD_SYNC_WAIT",
            "refusing to block UI thread in SyncWaiter.await()",
            "ApplicationLoader.applicationHandler.getLooper().getThread()",
            "timedOut = true;",
            "return false;",
        ),
        "Ayu SyncWaiter UI-thread guard",
        failures,
    )
    require(
        message_waiter,
        (
            "AUTHORGRAM_NO_UI_THREAD_MESSAGE_POLL",
            "AUTHORGRAM_BOUNDED_MESSAGE_WATCHER",
            "AUTHORGRAM_BOUNDED_WATCHER_LOGGING",
            "WATCHER_TIMEOUT_MS = 15000L",
            "POLL_INTERVAL_MS = 100L",
            "boolean lookupFailureLogged = false;",
            "if (!lookupFailureLogged)",
            "failed = true;",
            "unsubscribe();",
        ),
        "Ayu bounded message polling guard",
        failures,
    )
    forbid(
        message_waiter,
        (
            "WATCHER_TIMEOUT_MS = 300000L",
            "POLL_INTERVAL_MS = 25L",
        ),
        "Ayu message watcher resource bounds",
        failures,
    )
    require(
        sequential,
        (
            "AUTHORGRAM_PROPAGATE_SYNC_SEND_FAILURE",
            "boolean uploadCompleted = uploadWaiter.await();",
            "success = uploadCompleted && !uploadWaiter.hasFailed();",
            "boolean messageCompleted = messageWaiter.await();",
            "messageCompleted && !messageWaiter.hasFailed();",
            "return success;",
        ),
        "Ayu synchronous send failure propagation",
        failures,
    )


def audit_telegram_core_repairs(failures: list[str]) -> None:
    dialogs = read(DIALOGS_ADAPTER)
    require(
        dialogs,
        (
            "AUTHORGRAM_TELEGRAM_ME_URL_DIFF_FIX",
            "recentMeUrl.url.equals(itemInternal.recentMeUrl.url)",
        ),
        "Telegram DialogsAdapter recent .me URL DiffUtil repair",
        failures,
    )
    forbid(
        dialogs,
        ("recentMeUrl.url.equals(recentMeUrl.url)",),
        "Telegram DialogsAdapter recent .me URL DiffUtil repair",
        failures,
    )


def audit_release_chain(failures: list[str]) -> None:
    release = read(RELEASE_SCRIPT)
    require(
        release,
        (
            "patch_authorgram_full_screen_ios_blur.py --mode apply",
            "patch_authorgram_main_stability.py",
            "patch_authorgram_runtime_regressions.py --mode apply",
            "patch_authorgram_ios_input_geometry.py --mode apply",
            "patch_authorgram_chat_scope_safety.py --mode apply",
            "audit_authorgram_runtime_stability.py",
            "patch_authorgram_full_screen_ios_blur.py --mode validate",
            "patch_authorgram_runtime_regressions.py --mode validate",
            "patch_authorgram_ios_input_geometry.py --mode validate",
            "patch_authorgram_chat_scope_safety.py --mode validate",
        ),
        "final Main release chain",
        failures,
    )
    for deprecated in DEPRECATED_UI_GENERATORS:
        if deprecated in release:
            failures.append(
                f"final Main release chain invokes deprecated UI generator {deprecated}"
            )


def audit_lifecycle(failures: list[str]) -> None:
    enter = read(ENTER)
    require(
        enter,
        (
            "AUTHORGRAM_STABLE_IOS_INPUT_LIFECYCLE",
            "removeCallbacks(authorGramInputMenuInvariantRunnable);",
            "!isAttachedToWindow()",
        ),
        "iOS composer lifecycle",
        failures,
    )


def audit_custom_blocking_calls(failures: list[str]) -> None:
    # AuthorGram-owned code must not intentionally block the UI/runtime thread.
    # Upstream Telegram/Ayu/WebRTC code is deliberately outside this scan. Ayu's
    # synchronous forwarding is covered separately with explicit UI-thread guards.
    forbidden_patterns = (
        (re.compile(r"\bThread\.sleep\s*\("), "Thread.sleep"),
        (re.compile(r"\bSystem\.gc\s*\("), "System.gc"),
        (re.compile(r"\bLooper\.loop\s*\("), "nested Looper.loop"),
        (re.compile(r"\brunBlocking\s*\{"), "Kotlin runBlocking"),
    )

    for root in CUSTOM_ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".kt"}:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for pattern, description in forbidden_patterns:
                if pattern.search(text):
                    failures.append(
                        f"{path.relative_to(ROOT)}: AuthorGram-owned source contains {description}"
                    )


def main() -> int:
    failures: list[str] = []
    audit_ios_message_preview(failures)
    audit_reply_integrity(failures)
    audit_ayu_waiter_safety(failures)
    audit_telegram_core_repairs(failures)
    audit_release_chain(failures)
    audit_lifecycle(failures)
    audit_custom_blocking_calls(failures)

    if failures:
        raise SystemExit(
            "AuthorGram runtime stability audit failed:\n - " + "\n - ".join(failures)
        )

    print("AuthorGram runtime stability audit passed")
    print(
        "Checked: full-screen blur, native iOS message identity/geometry, "
        "action-card/footer reachability, settings reply/draw, Play-stable reply "
        "ownership, bounded Ayu waiters, Telegram .me DiffUtil correctness, "
        "release-chain isolation, lifecycle and AuthorGram-owned blocking calls"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
