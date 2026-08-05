#!/usr/bin/env python3
"""Finalize AuthorGram's premium iOS message menu and typing overlay repair.

Runs after the existing 12.9.2 UI patches. The script is deliberately narrow,
idempotent and validates every invariant before Android/Gradle setup.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
POPUP = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"

PREVIEW_MARKER = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW"
BACKGROUND_MARKER = "AUTHORGRAM_IOS_PREVIEW_TRANSPARENT_BACKGROUND"
TYPING_MARKER = "AUTHORGRAM_TYPING_OVERLAY_GUARD_V2"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def patch_native_message_preview() -> None:
    text = read(CHAT)

    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // The selected message is a snapshot of the real ChatMessageCell.\n"
        "                // It stays independent between reactions and the action panel.\n"
        "                if (selectedObject != null\n"
        "                        && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                        && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
        "                    org.telegram.ui.Components.IOSMessageMenuPreview iosPreview =\n"
        "                            new org.telegram.ui.Components.IOSMessageMenuPreview(\n"
        "                                    getParentActivity(),\n"
        "                                    contentView,\n"
        "                                    messageCell,\n"
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

    if PREVIEW_MARKER not in text:
        text, count = pattern.subn(replacement, text, count=1)
        if count != 1:
            raise SystemExit(f"native message preview block count is {count}, expected 1")
        write(CHAT, text)

    check = read(CHAT)
    required = (
        PREVIEW_MARKER,
        "contentView,\n                                    messageCell,",
        "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
        "Color.TRANSPARENT",
        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",
    )
    missing = [item for item in required if item not in check]
    if missing:
        raise SystemExit(f"native message preview validation failed: {missing}")
    if "currentAccount,\n                                    selectedObject," in check:
        raise SystemExit("synthetic MessageObject preview constructor remains")
    print("Native Telegram message-cell preview placement passed")


def patch_popup_background_segmentation() -> None:
    text = read(POPUP)
    if BACKGROUND_MARKER not in text:
        old = (
            "                for (int a = 0; a < 2; a++) {\n"
            "                    if (a == 1 && start < -dp(16)) {\n"
        )
        new = (
            "                // AUTHORGRAM_IOS_PREVIEW_TRANSPARENT_BACKGROUND\n"
            "                // A native message preview is not part of the submenu card.\n"
            "                // Skip the first background segment and let the transparent\n"
            "                // GapView start a fresh rounded action panel underneath it.\n"
            "                boolean authorGramNativeMessagePreview = linearLayout.getChildCount() > 1\n"
            "                        && \"AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\".equals(\n"
            "                                String.valueOf(linearLayout.getChildAt(0).getTag())\n"
            "                        );\n"
            "                if (authorGramNativeMessagePreview) {\n"
            "                    View preview = linearLayout.getChildAt(0);\n"
            "                    View gap = linearLayout.getChildAt(1);\n"
            "                    int scrollOffset = scrollView == null ? 0 : scrollView.getScrollY();\n"
            "                    start = preview.getBottom() - scrollOffset;\n"
            "                    end = gap.getBottom() - scrollOffset;\n"
            "                }\n"
            "                for (int a = 0; a < 2; a++) {\n"
            "                    if (authorGramNativeMessagePreview && a == 0) {\n"
            "                        continue;\n"
            "                    }\n"
            "                    if (a == 1 && start < -dp(16)) {\n"
        )
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"popup background loop anchor count is {count}, expected 1")
        text = text.replace(old, new, 1)
        write(POPUP, text)

    check = read(POPUP)
    required = (
        BACKGROUND_MARKER,
        "authorGramNativeMessagePreview && a == 0",
        "start = preview.getBottom() - scrollOffset;",
        "end = gap.getBottom() - scrollOffset;",
    )
    missing = [item for item in required if item not in check]
    if missing:
        raise SystemExit(f"transparent preview background validation failed: {missing}")
    print("Independent rounded action-panel background passed")


def patch_typing_overlay() -> None:
    text = read(ENTER)

    replacement = (
        "        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        "        // AUTHORGRAM_TYPING_OVERLAY_GUARD_V2\n"
        "        // The media/menu icon must never survive or intercept the chat header\n"
        "        // after the composer receives text. Apply this to Main and Play.\n"
        "        final boolean authorGramComposerHasText = messageEditText != null\n"
        "                && messageEditText.length() > 0;\n"
        "        if (authorGramComposerHasText && audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.animate().cancel();\n"
        "            audioVideoButtonContainer.setVisibility(GONE);\n"
        "            audioVideoButtonContainer.setAlpha(0.0f);\n"
        "            audioVideoButtonContainer.setScaleX(1.0f);\n"
        "            audioVideoButtonContainer.setScaleY(1.0f);\n"
        "            audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "            audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(false);\n"
        "            audioVideoButtonContainer.setEnabled(false);\n"
        "            // Some icon-state animations finish after checkSendButton(). Enforce\n"
        "            // the invariant once more on the next UI frame.\n"
        "            audioVideoButtonContainer.post(() -> {\n"
        "                if (messageEditText != null && messageEditText.length() > 0\n"
        "                        && audioVideoButtonContainer != null) {\n"
        "                    audioVideoButtonContainer.animate().cancel();\n"
        "                    audioVideoButtonContainer.setVisibility(GONE);\n"
        "                    audioVideoButtonContainer.setAlpha(0.0f);\n"
        "                    audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "                    audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "                    audioVideoButtonContainer.setClickable(false);\n"
        "                    audioVideoButtonContainer.setEnabled(false);\n"
        "                }\n"
        "            });\n"
        "        } else if (audioVideoButtonContainer != null) {\n"
        "            // AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE\n"
        "            audioVideoButtonContainer.animate().cancel();\n"
        "            audioVideoButtonContainer.setVisibility(VISIBLE);\n"
        "            audioVideoButtonContainer.setAlpha(1.0f);\n"
        "            audioVideoButtonContainer.setScaleX(1.0f);\n"
        "            audioVideoButtonContainer.setScaleY(1.0f);\n"
        "            audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "            audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        }\n\n"
    )

    pattern = re.compile(
        r"        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        r".*?"
        r"(?=        if \(isStories && suggestButton != null\) \{)",
        re.DOTALL,
    )

    if TYPING_MARKER not in text:
        text, count = pattern.subn(replacement, text, count=1)
        if count != 1:
            raise SystemExit(f"typing overlay guard count is {count}, expected 1")
        write(ENTER, text)

    check = read(ENTER)
    required = (
        TYPING_MARKER,
        "authorGramComposerHasText",
        "audioVideoButtonContainer.post(() -> {",
        "audioVideoButtonContainer.setVisibility(GONE);",
        "audioVideoButtonContainer.setClickable(false);",
        "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
        "audioVideoButtonContainer.setVisibility(VISIBLE);",
    )
    missing = [item for item in required if item not in check]
    if missing:
        raise SystemExit(f"typing overlay validation failed: {missing}")
    print("Main and Play typing/header overlay guard passed")


def validate_preview_component() -> None:
    text = read(PREVIEW)
    required = (
        PREVIEW_MARKER,
        "ChatMessageCell sourceCell",
        "sourceCell.draw(canvas);",
        "findVisibleBounds(raw)",
        "BluredView",
        "AuthorGramPlayPolicy.canUseIosUi()",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"native preview component validation failed: {missing}")
    forbidden = (
        "MessagesController.getInstance",
        "new TextView",
        "Theme.createRoundRectDrawable",
    )
    remains = [item for item in forbidden if item in text]
    if remains:
        raise SystemExit(f"synthetic preview implementation remains: {remains}")
    print("Native ChatMessageCell snapshot implementation passed")


def main() -> None:
    patch_native_message_preview()
    patch_popup_background_segmentation()
    patch_typing_overlay()
    validate_preview_component()


if __name__ == "__main__":
    main()
