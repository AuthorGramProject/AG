#!/usr/bin/env python3
"""Apply and validate AuthorGram's final popup, input and chat-header repairs.

The canonical final patch first restores the shared baseline. The adaptive patch
then keeps short Main-only iOS message previews fixed, moves long previews into
the action ScrollView, caps the popup viewport, and repairs composer ownership.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
FINAL_MARKER = "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK"

# The legacy repair validates legacy preview wording. Run it only while upgrading
# old source; committed final source is handled by the canonical final patches.
if FINAL_MARKER not in PREVIEW.read_text(encoding="utf-8"):
    runpy.run_path(
        str(ROOT / "scripts/patch_authorgram_ios_menu_v2.py"),
        run_name="__main__",
    )

for relative in (
    "scripts/patch_authorgram_final_chat_ui.py",
    "scripts/patch_authorgram_final_ui_compat.py",
    "scripts/patch_authorgram_adaptive_ios_preview.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")

checks = {
    "standard chat header with global centering preserved": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java",
        (
            "AUTHORGRAM_STANDARD_CHAT_HEADER",
            "parentFragment instanceof org.telegram.ui.ChatActivity",
            "return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();",
        ),
    ),
    "adaptive popup bounds and message ownership": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java",
        (
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
            "AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_SCROLL",
            "AUTHORGRAM_MENU_FOOTER_WIDTH_PARITY",
            "setFixedMessagePreview(View preview)",
            "availableForActions",
            "popupParams.height = availableForActions;",
            "int newWidth = popupWidth > 0 ? popupWidth : foregroundWidth;",
        ),
    ),
    "native grouped actions, adaptive preview and full blur": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        (
            "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS",
            "GroupedIconsView.useGroupedIcons()",
            "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
            "AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER",
            "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER",
            "iosPreview.shouldScrollWithActions()",
            "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
            "scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);",
            "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR",
            "dimBehindView(null, true, true);",
        ),
    ),
    "reliable normal and iOS popup scrolling": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java",
        (
            "AUTHORGRAM_RELIABLE_POPUP_SCROLL",
            "scrollView.setFillViewport(false);",
            "scrollView.setScrollContainer(true);",
            "scrollView.setNestedScrollingEnabled(true);",
            "scrollView.setPadding(0, 0, 0, dp(8));",
        ),
    ),
    "iOS send icon and media-slot ownership": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java",
        (
            "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
            "AUTHORGRAM_TYPING_OVERLAY_GUARD_V3",
            "AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER",
            "AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT",
            "AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX",
            "View sendButtonView = sendButton;",
            "sendButtonView.setVisibility(VISIBLE);",
            "sendButtonView.setAlpha(1.0f);",
            "audioVideoButtonContainer.setVisibility(VISIBLE);",
            "audioVideoButtonContainer.setVisibility(GONE);",
        ),
    ),
    "adaptive unified sender/message preview": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java",
        (
            "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK",
            "AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_FINAL_PREVIEW_COMPAT",
            "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY",
            "BackupImageView avatarView",
            "TextView senderNameView",
            "public boolean shouldScrollWithActions()",
            "Theme.key_chat_outBubble",
            "Theme.key_chat_inBubble",
            "sourceCell.draw(canvas);",
        ),
    ),
}

for label, (path, required) in checks.items():
    text = path.read_text(encoding="utf-8")
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"{label} validation failed: {missing}")
    print(f"{label} validation passed")

chat_activity = (
    ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
).read_text(encoding="utf-8")
if "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP" in chat_activity:
    raise SystemExit("obsolete unconditional iOS preview gap remains")
if "getSendButtonInternal()" in (
    ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
).read_text(encoding="utf-8"):
    raise SystemExit("undefined composer send-button helper remains")

for relative in (
    "scripts/patch_authorgram_badge_surfaces.py",
    "scripts/verify_authorgram_badge_tokens.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")
