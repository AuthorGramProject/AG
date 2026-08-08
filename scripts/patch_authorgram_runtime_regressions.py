#!/usr/bin/env python3
"""Final post-generator repair for AuthorGram Main runtime regressions.

This runs after patch_authorgram_main_stability.py. It deliberately does not
invent another UI implementation; it fixes two lifecycle/geometry defects in the
canonical implementation and enforces the Play-stable reply ownership model:

1. The selected-message preview must not resolve popupLayout.getParent() while
   the popup is still being constructed. View.post() defers the lookup until the
   popup has actually attached, so the preview cannot disappear merely because
   construction happened before attachment.
2. The action-card viewport must never reserve a synthetic minimum that is
   larger than the real remaining work area. ActionBarPopupWindowLayout already
   owns scrolling, so a one-pixel minimum is sufficient and prevents the footer
   from being measured below the screen.
3. Incoming decryption may mutate only the current TLRPC.Message, matching the
   stable Play implementation. Nested replyMessage objects can be shared by
   Telegram caches and are never recursively mutated here.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
INTERCEPTOR = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"

DEFERRED_MARKER = "AUTHORGRAM_DEFERRED_IOS_PREVIEW_ATTACH"
STRICT_VIEWPORT_MARKER = "AUTHORGRAM_STRICT_IOS_MENU_VIEWPORT"
REPLY_MARKER = "AUTHORGRAM_PLAY_STABLE_REPLY_MODEL"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def patch_chat_preview_attach() -> None:
    text = read(CHAT)
    if DEFERRED_MARKER in text:
        return

    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_STABLE_FIXED_IOS_PREVIEW\n"
        "                // AUTHORGRAM_WEB_PREVIEW_SAFE_IOS_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW\n"
        "                // AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY\n"
        "                // AUTHORGRAM_DEFERRED_IOS_PREVIEW_ATTACH\n"
        "                // popupLayout has no reliable parent while action rows are still\n"
        "                // being constructed. View.post() runs once the popup is attached,\n"
        "                // preserving the separate reactions -> message -> action-card owner.\n"
        "                if (selectedObject != null\n"
        "                        && v instanceof org.telegram.ui.Cells.ChatMessageCell\n"
        "                        && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                        && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
        "                    final org.telegram.ui.Cells.ChatMessageCell selectedMessageCell =\n"
        "                            (org.telegram.ui.Cells.ChatMessageCell) v;\n"
        "                    final org.telegram.ui.Components.IOSMessageMenuPreview iosPreview =\n"
        "                            new org.telegram.ui.Components.IOSMessageMenuPreview(\n"
        "                                    getParentActivity(),\n"
        "                                    currentAccount,\n"
        "                                    selectedObject,\n"
        "                                    selectedMessageCell,\n"
        "                                    themeDelegate\n"
        "                            );\n"
        "                    final android.view.View authorGramIosPreviewAnchor = popupLayout;\n"
        "                    authorGramIosPreviewAnchor.post(() -> {\n"
        "                        // AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT\n"
        "                        android.view.ViewParent authorgramIosPreviewParent =\n"
        "                                authorGramIosPreviewAnchor.getParent();\n"
        "                        while (authorgramIosPreviewParent != null\n"
        "                                && !(authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout)) {\n"
        "                            if (authorgramIosPreviewParent instanceof android.view.View) {\n"
        "                                authorgramIosPreviewParent =\n"
        "                                        ((android.view.View) authorgramIosPreviewParent).getParent();\n"
        "                            } else {\n"
        "                                authorgramIosPreviewParent = null;\n"
        "                            }\n"
        "                        }\n"
        "                        if (authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout) {\n"
        "                            ((org.telegram.ui.Components.ChatScrimPopupContainerLayout) authorgramIosPreviewParent)\n"
        "                                    .setFixedMessagePreview(iosPreview);\n"
        "                        } else {\n"
        "                            // A detached/closed popup must fail closed instead of\n"
        "                            // reparenting a stale ChatMessageCell or crashing.\n"
        "                            iosPreview.setVisibility(android.view.View.GONE);\n"
        "                            org.telegram.messenger.FileLog.e(\"AuthorGram: iOS preview owner not found after attach\");\n"
        "                        }\n"
        "                    });\n"
        "                }\n\n"
    )

    pattern = re.compile(
        r"                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        r".*?"
        r"(?=                scrimPopupWindowItems = new ActionBarMenuSubItem\[items\.size\(\)\];)",
        re.DOTALL,
    )
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"deferred iOS preview block count is {count}, expected 1")
    write(CHAT, text)


def patch_strict_menu_viewport() -> None:
    text = read(SCRIM)
    if STRICT_VIEWPORT_MARKER in text:
        return

    old = (
        "        int availableForActions = Math.max(\n"
        "                AndroidUtilities.dp(96),\n"
        "                effectiveMaxHeight - occupiedHeight\n"
        "        );\n"
    )
    new = (
        "        // AUTHORGRAM_STRICT_IOS_MENU_VIEWPORT\n"
        "        // The action card gets only the real remaining work-area height.\n"
        "        // Its native ScrollView owns overflow, including the footer.\n"
        "        int availableForActions = Math.max(\n"
        "                1,\n"
        "                effectiveMaxHeight - occupiedHeight\n"
        "        );\n"
    )
    if old not in text:
        raise SystemExit("canonical 96dp action viewport anchor is missing")
    text = text.replace(old, new, 1)
    write(SCRIM, text)


def validate_reply_model() -> None:
    text = read(INTERCEPTOR)
    required = (
        REPLY_MARKER,
        "public static boolean decryptIncomingMessage(int account, TLRPC.Message message)",
        "MessageObject.getDialogId(message)",
        "AuthorGramMessageMeta.markDecrypted(account, message);",
    )
    forbidden = (
        "AUTHORGRAM_REPLY_TARGET_DECRYPTION",
        "TLRPC.Message nestedReply = message.replyMessage;",
        "decryptSingleIncomingMessage(account, nestedReply)",
        "decryptSingleIncomingMessage(account, message)",
    )
    for token in required:
        if token not in text:
            raise SystemExit(f"Play-stable reply invariant missing: {token}")
    for token in forbidden:
        if token in text:
            raise SystemExit(f"recursive reply mutation regression remains: {token}")


def validate() -> None:
    chat = read(CHAT)
    scrim = read(SCRIM)

    for token in (
        DEFERRED_MARKER,
        "authorGramIosPreviewAnchor.post(() -> {",
        "authorGramIosPreviewAnchor.getParent();",
        ".setFixedMessagePreview(iosPreview);",
        "iOS preview owner not found after attach",
    ):
        if token not in chat:
            raise SystemExit(f"deferred selected-message preview invariant missing: {token}")

    if "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();" in chat:
        raise SystemExit("pre-attach popupLayout.getParent() lookup remains")

    for token in (
        STRICT_VIEWPORT_MARKER,
        "effectiveMaxHeight - occupiedHeight",
        "                1,\n                effectiveMaxHeight - occupiedHeight",
        "popupWindowLayout.addView(bottomView, footerParams);",
    ):
        if token not in scrim:
            raise SystemExit(f"strict action-card viewport invariant missing: {token}")

    if "AndroidUtilities.dp(96),\n                effectiveMaxHeight - occupiedHeight" in scrim:
        raise SystemExit("artificial 96dp action viewport minimum remains")

    validate_reply_model()
    print("AuthorGram final runtime regression repair passed")


def apply() -> None:
    patch_chat_preview_attach()
    patch_strict_menu_viewport()
    validate()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("apply", "validate"), default="apply")
    args = parser.parse_args()
    if args.mode == "apply":
        apply()
    else:
        validate()


if __name__ == "__main__":
    main()
