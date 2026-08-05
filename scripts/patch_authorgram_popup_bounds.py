#!/usr/bin/env python3
"""Validate retained popup repairs and apply the final iOS menu v2 patch.

The adaptive popup bounds and grouped-action repairs are already canonical in
source. This preflight wrapper keeps those invariants strict, executes the new
native-message/typing repair before Android SDK setup, and then runs the badge
surface/token checks used by the verified release workflow.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Apply the final native ChatMessageCell preview, transparent panel segmentation
# and Main+Play typing-overlay repair before any expensive build setup.
runpy.run_path(str(ROOT / "scripts/patch_authorgram_ios_menu_v2.py"), run_name="__main__")

checks = {
    "adaptive popup bounds": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java",
        (
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
            "effectiveMaxHeight",
            "requestLayout();",
        ),
    ),
    "native grouped message actions": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        (
            "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS",
            "GroupedIconsView.useGroupedIcons()",
            "dimBehindView(v, true);",
            "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
        ),
    ),
    "input restore and overlay guard": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java",
        (
            "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
            "AUTHORGRAM_TYPING_OVERLAY_GUARD_V2",
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
