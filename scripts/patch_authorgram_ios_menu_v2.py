#!/usr/bin/env python3
"""Repair and validate AuthorGram's iOS message menu before the release build."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
POPUP = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"


def require(path: Path, *needles: str) -> str:
    text = path.read_text(encoding="utf-8")
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise SystemExit(f"{path.name} validation failed: {missing}")
    return text


chat = CHAT.read_text(encoding="utf-8")
block = (
    "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
    "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
    "                // Snapshot the actual long-pressed Telegram message cell.\n"
    "                if (selectedObject != null\n"
    "                        && v instanceof org.telegram.ui.Cells.ChatMessageCell\n"
    "                        && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
    "                        && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
    "                    org.telegram.ui.Cells.ChatMessageCell selectedMessageCell =\n"
    "                            (org.telegram.ui.Cells.ChatMessageCell) v;\n"
    "                    org.telegram.ui.Components.IOSMessageMenuPreview iosPreview =\n"
    "                            new org.telegram.ui.Components.IOSMessageMenuPreview(\n"
    "                                    getParentActivity(),\n"
    "                                    contentView,\n"
    "                                    selectedMessageCell,\n"
    "                                    themeDelegate\n"
    "                            );\n"
    "                    LinearLayout.LayoutParams iosPreviewParams = LayoutHelper.createLinear(\n"
    "                            LayoutHelper.MATCH_PARENT,\n"
    "                            LayoutHelper.WRAP_CONTENT\n"
    "                    );\n"
    "                    iosPreviewParams.leftMargin = 0;\n"
    "                    iosPreviewParams.rightMargin = 0;\n"
    "                    iosPreviewParams.topMargin = AndroidUtilities.dp(2);\n"
    "                    iosPreviewParams.bottomMargin = 0;\n"
    "                    popupLayout.addView(iosPreview, iosPreviewParams);\n"
    "\n"
    "                    org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView iosMessageGap =\n"
    "                            new org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView(\n"
    "                                    getParentActivity(),\n"
    "                                    android.graphics.Color.TRANSPARENT,\n"
    "                                    android.graphics.Color.TRANSPARENT\n"
    "                            );\n"
    "                    iosMessageGap.setTag(\"AUTHORGRAM_IOS_MESSAGE_ACTION_GAP\");\n"
    "                    popupLayout.addView(iosMessageGap, LayoutHelper.createLinear(\n"
    "                            LayoutHelper.MATCH_PARENT,\n"
    "                            8\n"
    "                    ));\n"
    "                }\n\n"
)
pattern = re.compile(
    r"                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
    r".*?"
    r"                \}\n\n"
    r"(?=                scrimPopupWindowItems = new ActionBarMenuSubItem\[items\.size\(\)\];)",
    re.DOTALL,
)
chat, count = pattern.subn(block, chat, count=1)
if count != 1:
    raise SystemExit(f"native message preview block count is {count}, expected 1")
CHAT.write_text(chat, encoding="utf-8", newline="")

chat = require(
    CHAT,
    "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
    "v instanceof org.telegram.ui.Cells.ChatMessageCell",
    "org.telegram.ui.Cells.ChatMessageCell selectedMessageCell",
    "contentView,\n                                    selectedMessageCell,",
    "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
    "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",
)
for invalid in (
    "contentView,\n                                    messageCell,",
    "currentAccount,\n                                    selectedObject,",
):
    if invalid in chat:
        raise SystemExit(f"invalid message preview constructor remains: {invalid!r}")

require(
    POPUP,
    "AUTHORGRAM_IOS_PREVIEW_TRANSPARENT_BACKGROUND",
    "authorGramNativeMessagePreview && a == 0",
    "start = preview.getBottom() - scrollOffset;",
    "end = gap.getBottom() - scrollOffset;",
)
require(
    ENTER,
    "AUTHORGRAM_TYPING_OVERLAY_GUARD_V2",
    "authorGramComposerHasText",
    "audioVideoButtonContainer.post(() -> {",
    "audioVideoButtonContainer.setVisibility(GONE);",
    "audioVideoButtonContainer.setClickable(false);",
    "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
    "audioVideoButtonContainer.setVisibility(VISIBLE);",
)
preview = require(
    PREVIEW,
    "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
    "ChatMessageCell sourceCell",
    "sourceCell.draw(canvas);",
    "findVisibleBounds(raw)",
    "BluredView",
    "AuthorGramPlayPolicy.canUseIosUi()",
)
for synthetic in ("MessagesController.getInstance", "new TextView", "Theme.createRoundRectDrawable"):
    if synthetic in preview:
        raise SystemExit(f"synthetic message preview code remains: {synthetic}")

print("Native Telegram message preview and Main/Play typing overlay validation passed")
