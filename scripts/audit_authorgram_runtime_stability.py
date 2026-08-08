#!/usr/bin/env python3
"""Static runtime-safety audit for AuthorGram-owned Android code.

This does not build the APK. It enforces high-value invariants around the custom
AuthorGram layer so known UI/reply/lifecycle regressions cannot silently return.
Run it before a release build after all patch generators have been applied.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SETTINGS_PREVIEW = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram/ui/cells/MessageSettingsPreviewCell.java"
INTERCEPTOR = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"
SCOPE_GUARD = ROOT / "scripts/patch_authorgram_chat_scope_safety.py"
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
    scope = read(SCOPE_GUARD)
    scrim = read(SCRIM)

    require(
        preview,
        (
            "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW",
            "new ChatMessageCell(context, currentAccount)",
            "new ScrollView(context)",
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
        ),
        "iOS selected-message preview",
        failures,
    )

    require(
        scope,
        (
            "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
            "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
            ".setFixedMessagePreview(iosPreview);",
            "iosPreview.setVisibility(android.view.View.GONE);",
        ),
        "iOS preview owner guard",
        failures,
    )
    forbid(
        scope,
        (
            '"popupLayout.addView(iosPreview',
            '"popupLayout.addView(popupMessagePreview',
            "authorgramFallbackPreviewParams",
        ),
        "iOS preview owner guard",
        failures,
    )

    require(
        scrim,
        (
            "private View fixedMessagePreview",
            "public void setFixedMessagePreview(View preview)",
            "AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
        ),
        "ChatScrim preview container",
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
    # Upstream Telegram/WebRTC code is deliberately outside this scan.
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
    print("Checked: iOS menu ownership, native preview, reply integrity, lifecycle, custom blocking calls")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
