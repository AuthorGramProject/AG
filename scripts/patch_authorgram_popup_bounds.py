#!/usr/bin/env python3
"""Apply and validate AuthorGram's final popup, input and chat-header repairs.

The existing native iOS repair is applied first. The final repair then moves the
selected-message preview outside the actions ScrollView, preserves the standard
chat header in both builds, stabilizes the iOS composer and hardens scrolling.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

for relative in (
    "scripts/patch_authorgram_ios_menu_v2.py",
    "scripts/patch_authorgram_final_chat_ui.py",
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
    "adaptive popup bounds": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java",
        (
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
            "AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_MENU_FOOTER_WIDTH_PARITY",
            "setFixedMessagePreview(View preview)",
            "availableForActions",
            "int newWidth = popupWidth > 0 ? popupWidth : foregroundWidth;",
        ),
    ),
    "native grouped actions, fixed preview and full blur": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        (
            "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS",
            "GroupedIconsView.useGroupedIcons()",
            "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
            "AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER",
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
        ),
    ),
    "iOS send icon and media-slot ownership": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java",
        (
            "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
            "AUTHORGRAM_TYPING_OVERLAY_GUARD_V3",
            "AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER",
            "AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT",
            "sendButtonView.setVisibility(VISIBLE);",
            "sendButtonView.setAlpha(1.0f);",
            "audioVideoButtonContainer.setVisibility(VISIBLE);",
            "audioVideoButtonContainer.setVisibility(GONE);",
        ),
    ),
    "unified fixed sender/message preview": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java",
        (
            "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK",
            "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY",
            "BackupImageView avatarView",
            "TextView senderNameView",
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
    raise SystemExit("scroll-owned iOS preview gap remains")

for relative in (
    "scripts/patch_authorgram_badge_surfaces.py",
    "scripts/verify_authorgram_badge_tokens.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")
