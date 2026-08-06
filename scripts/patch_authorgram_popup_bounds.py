#!/usr/bin/env python3
"""Apply and validate AuthorGram's final iOS menu/input repairs.

The adaptive popup bounds and grouped-action repairs are canonical in source.
This wrapper applies the sender identity, full-screen blur and composer-menu
invariant before Android SDK setup, then runs badge verification.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

runpy.run_path(
    str(ROOT / "scripts/patch_authorgram_ios_menu_v2.py"),
    run_name="__main__",
)

checks = {
    "adaptive popup bounds": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java",
        (
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
            "effectiveMaxHeight",
            "requestLayout();",
        ),
    ),
    "native grouped message actions and full blur": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        (
            "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS",
            "GroupedIconsView.useGroupedIcons()",
            "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
            "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR",
            "dimBehindView(null, true, true);",
        ),
    ),
    "input restore and persistent overlay guard": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java",
        (
            "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
            "AUTHORGRAM_TYPING_OVERLAY_GUARD_V3",
            "AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER",
            "authorGramEnforceInputMenuInvariant();",
            "audioVideoButtonContainer.clearAnimation();",
            "audioVideoButtonContainer.setVisibility(VISIBLE);",
            "audioVideoButtonContainer.setVisibility(GONE);",
        ),
    ),
    "independent action-panel background": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java",
        (
            "AUTHORGRAM_IOS_PREVIEW_TRANSPARENT_BACKGROUND",
            "authorGramNativeMessagePreview && a == 0",
        ),
    ),
    "sender identity preview": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java",
        (
            "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY",
            "BackupImageView avatarView",
            "TextView senderNameView",
            "MessagesController.getInstance(currentAccount)",
        ),
    ),
}

for label, (path, required) in checks.items():
    text = path.read_text(encoding="utf-8")
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"{label} validation failed: {missing}")
    print(f"{label} validation passed")

for relative in (
    "scripts/patch_authorgram_badge_surfaces.py",
    "scripts/verify_authorgram_badge_tokens.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")
