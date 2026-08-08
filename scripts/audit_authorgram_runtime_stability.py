#!/usr/bin/env python3
"""Static runtime-safety audit for AuthorGram-owned Android code.

This does not build the APK. It enforces high-value invariants around the custom
AuthorGram layer so known UI/reply/lifecycle regressions cannot silently return.
Run it after the canonical stability generator and before Gradle.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SETTINGS_PREVIEW = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram/ui/cells/MessageSettingsPreviewCell.java"
INTERCEPTOR = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"
SCOPE_GUARD = ROOT / "scripts/patch_authorgram_chat_scope_safety.py"
STABILITY = ROOT / "scripts/patch_authorgram_main_stability.py"
BLUR_PATCH = ROOT / "scripts/patch_authorgram_full_screen_ios_blur.py"
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"

CUSTOM_ROOTS = (
    ROOT / "TMessagesProj/src/main/java/toss/authorgram",
    ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram",
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
    blur_patch = read(BLUR_PATCH)
    scrim = read(SCRIM)

    require(
        preview,
        (
            "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "new ChatMessageCell(context, currentAccount)",
            "new ScrollView(context)",
            "previewScroll.setNestedScrollingEnabled(true);",
            "maxPreviewHeight",
            "previewCell.setMessageObject(messageObject, null, false, false, false);",
            "public boolean shouldScrollWithActions()",
            "return false;",
        ),
        "iOS selected-message preview",
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
        ),
        "iOS selected-message preview",
        failures,
    )

    require(
        chat,
        (
            "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
            ".setFixedMessagePreview(iosPreview);",
            "iosPreview.setVisibility(android.view.View.GONE);",
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
        ),
        "iOS selected-message owner",
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

    # Scope safety is deliberately read-only. The stability pass is the sole
    # source generator and the guard only rejects bad ownership after that pass.
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

    require(
        stability,
        (
            "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
            "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY",
            "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW",
            "Math.min(oldParams.height, AndroidUtilities.dp(44))",
            "params.topMargin = AndroidUtilities.dp(8);",
            "params.bottomMargin = AndroidUtilities.dp(8);",
        ),
        "canonical iOS menu generator",
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
            "Math.min(oldParams.height, AndroidUtilities.dp(44))",
            "params.topMargin = AndroidUtilities.dp(8);",
            "params.bottomMargin = AndroidUtilities.dp(8);",
            "AUTHORGRAM_MENU_FOOTER_SEPARATOR",
            "Theme.getColor(Theme.key_divider)",
            "popupWindowLayout.addView(bottomView, footerParams);",
            "bottomView.setBackground(null);",
        ),
        "ChatScrim reference action-card geometry",
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
        ),
        "AuthorGram Chat settings preview",
        failures,
    )

    require(
        interceptor,
        (
            "AUTHORGRAM_REPLY_TARGET_DECRYPTION",
            "TLRPC.Message nestedReply = message.replyMessage;",
            "decryptSingleIncomingMessage(account, nestedReply)",
            "decryptSingleIncomingMessage(account, message)",
        ),
        "AuthorGram incoming reply decryption",
        failures,
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
    # Upstream Telegram/Ayu/WebRTC code is deliberately outside this scan.
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
    audit_lifecycle(failures)
    audit_custom_blocking_calls(failures)

    if failures:
        raise SystemExit(
            "AuthorGram runtime stability audit failed:\n - " + "\n - ".join(failures)
        )

    print("AuthorGram runtime stability audit passed")
    print(
        "Checked: full-screen blur, reference iOS menu geometry/ownership, "
        "native action-card/footer structure, native preview, reply integrity, "
        "lifecycle and AuthorGram-owned blocking calls"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
