#!/usr/bin/env python3
"""Repair and validate AuthorGram's iOS message menu and composer menu state."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
ICON = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java"
POPUP = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"

PREVIEW_MARKER = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW"
BLUR_MARKER = "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR"
TYPING_MARKER = "AUTHORGRAM_TYPING_OVERLAY_GUARD_V3"
TYPING_HELPER_MARKER = "AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER"
ICON_MARKER = "AUTHORGRAM_IOS_STALE_MENU_GLYPH_GUARD"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def require(path: Path, *needles: str) -> str:
    text = read(path)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise SystemExit(f"{path.name} validation failed: {missing}")
    return text


def patch_native_message_preview() -> None:
    text = read(CHAT)
    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // Reactions remain in Telegram's native row above this block.\n"
        "                // The selected message preview is independent from the action card.\n"
        "                if (selectedObject != null\n"
        "                        && v instanceof org.telegram.ui.Cells.ChatMessageCell\n"
        "                        && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                        && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
        "                    org.telegram.ui.Cells.ChatMessageCell selectedMessageCell =\n"
        "                            (org.telegram.ui.Cells.ChatMessageCell) v;\n"
        "                    org.telegram.ui.Components.IOSMessageMenuPreview iosPreview =\n"
        "                            new org.telegram.ui.Components.IOSMessageMenuPreview(\n"
        "                                    getParentActivity(),\n"
        "                                    currentAccount,\n"
        "                                    selectedObject,\n"
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
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"native message preview block count is {count}, expected 1")
    write(CHAT, text)

    check = require(
        CHAT,
        PREVIEW_MARKER,
        "currentAccount,\n                                    selectedObject,",
        "selectedObject,\n                                    selectedMessageCell,",
        "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",
    )
    preview_index = check.index("popupLayout.addView(iosPreview, iosPreviewParams);")
    actions_index = check.index("scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];")
    if preview_index >= actions_index:
        raise SystemExit("message preview no longer precedes the action list")
    if "contentView,\n                                    selectedMessageCell," in check:
        raise SystemExit("obsolete locally blurred preview constructor remains")
    print("Avatar/name capable native message preview placement passed")


def patch_full_screen_blur() -> None:
    text = read(CHAT)
    normal = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            dimBehindView(v, true);\n"
        "            hideHints(false);\n"
    )
    elevated = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            // AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_SCRIM\n"
        "            dimBehindView(\n"
        "                    v,\n"
        "                    org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                            && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool(),\n"
        "                    true\n"
        "            );\n"
        "            hideHints(false);\n"
    )
    repaired = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            // AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR\n"
        "            // Passing no exempt source cell blurs the complete chat surface.\n"
        "            // The independent popup preview stays sharp above that background.\n"
        "            if (org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                    && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
        "                dimBehindView(null, true, true);\n"
        "            } else {\n"
        "                dimBehindView(v, true);\n"
        "            }\n"
        "            hideHints(false);\n"
    )

    if BLUR_MARKER not in text:
        if normal in text:
            text = text.replace(normal, repaired, 1)
        elif elevated in text:
            text = text.replace(elevated, repaired, 1)
        else:
            raise SystemExit("chat scrim anchor is missing")
        write(CHAT, text)

    require(
        CHAT,
        BLUR_MARKER,
        "dimBehindView(null, true, true);",
        "dimBehindView(v, true);",
    )
    print("Full-screen native blur without an exempt original message cell passed")


def patch_typing_overlay() -> None:
    text = read(ENTER)

    helper = (
        "    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER\n"
        "    private final Runnable authorGramInputMenuInvariantRunnable =\n"
        "            this::authorGramEnforceInputMenuInvariant;\n"
        "\n"
        "    private void authorGramEnforceInputMenuInvariant() {\n"
        "        if (!isIOSInputStyle()\n"
        "                || audioVideoButtonContainer == null\n"
        "                || recordingAudioVideo) {\n"
        "            return;\n"
        "        }\n"
        "\n"
        "        final boolean hasComposerText = messageEditText != null\n"
        "                && messageEditText.length() > 0;\n"
        "        audioVideoButtonContainer.animate().cancel();\n"
        "        audioVideoButtonContainer.clearAnimation();\n"
        "        audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "        audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "        audioVideoButtonContainer.setScaleX(1.0f);\n"
        "        audioVideoButtonContainer.setScaleY(1.0f);\n"
        "\n"
        "        if (hasComposerText) {\n"
        "            audioVideoButtonContainer.setVisibility(GONE);\n"
        "            audioVideoButtonContainer.setAlpha(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(false);\n"
        "            audioVideoButtonContainer.setEnabled(false);\n"
        "        } else if (editingMessageObject == null) {\n"
        "            // AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE\n"
        "            audioVideoButtonContainer.setVisibility(VISIBLE);\n"
        "            audioVideoButtonContainer.setAlpha(1.0f);\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private void authorGramScheduleInputMenuInvariant() {\n"
        "        authorGramEnforceInputMenuInvariant();\n"
        "        if (audioVideoButtonContainer == null) {\n"
        "            return;\n"
        "        }\n"
        "        audioVideoButtonContainer.removeCallbacks(authorGramInputMenuInvariantRunnable);\n"
        "        audioVideoButtonContainer.post(authorGramInputMenuInvariantRunnable);\n"
        "        audioVideoButtonContainer.postDelayed(authorGramInputMenuInvariantRunnable, 260L);\n"
        "    }\n"
        "\n"
    )

    method_anchor = "    public void checkSendButton(boolean animated) {\n"
    if TYPING_HELPER_MARKER not in text:
        count = text.count(method_anchor)
        if count != 1:
            raise SystemExit(f"checkSendButton method anchor count is {count}, expected 1")
        text = text.replace(method_anchor, helper + method_anchor, 1)

    top_old = (
        "    public void checkSendButton(boolean animated) {\n"
        "        if (editingMessageObject != null || recordingAudioVideo) {\n"
    )
    top_new = (
        "    public void checkSendButton(boolean animated) {\n"
        "        // Enforce before any animation-state early return.\n"
        "        authorGramEnforceInputMenuInvariant();\n"
        "        if (editingMessageObject != null || recordingAudioVideo) {\n"
    )
    if top_new not in text:
        count = text.count(top_old)
        if count != 1:
            raise SystemExit(f"early input-menu invariant anchor count is {count}, expected 1")
        text = text.replace(top_old, top_new, 1)

    guard = (
        "        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        "        // AUTHORGRAM_TYPING_OVERLAY_GUARD_V3\n"
        "        // Re-run after all send-button mutations and again after delayed\n"
        "        // icon animations. This also keeps the header menu touchable.\n"
        "        authorGramScheduleInputMenuInvariant();\n"
        "\n"
    )
    pattern = re.compile(
        r"        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        r".*?"
        r"(?=        if \(isStories && suggestButton != null\) \{)",
        re.DOTALL,
    )
    text, count = pattern.subn(guard, text, count=1)
    if count != 1:
        raise SystemExit(f"typing overlay guard count is {count}, expected 1")

    write(ENTER, text)
    check = require(
        ENTER,
        TYPING_HELPER_MARKER,
        TYPING_MARKER,
        "authorGramEnforceInputMenuInvariant();",
        "audioVideoButtonContainer.clearAnimation();",
        "audioVideoButtonContainer.postDelayed(authorGramInputMenuInvariantRunnable, 260L);",
        "audioVideoButtonContainer.setVisibility(GONE);",
        "audioVideoButtonContainer.setClickable(false);",
        "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
    )
    if "authorGramComposerHasText" in check:
        raise SystemExit("obsolete one-frame composer guard remains")
    print("Early-return and delayed-animation composer menu invariant passed")


def patch_stale_menu_glyph() -> None:
    text = read(ICON)
    old = (
        "        // The MENU glyph belongs to the classic input layout.  In iOS mode it\n"
        "        // could survive a delayed state update and appear over the chat avatar.\n"
        "        if (state == State.MENU && iosInput()) {\n"
        "            state = State.VOICE;\n"
        "            animate = false;\n"
        "        }\n"
    )
    new = (
        "        // AUTHORGRAM_IOS_STALE_MENU_GLYPH_GUARD\n"
        "        // MENU is a classic-layout glyph. Clear both View and Lottie state\n"
        "        // before resolving to VOICE so stale three-dots cannot be redrawn.\n"
        "        if (state == State.MENU && iosInput()) {\n"
        "            stopAnimation();\n"
        "            clearAnimation();\n"
        "            setImageDrawable(null);\n"
        "            state = State.VOICE;\n"
        "            animate = false;\n"
        "        }\n"
    )
    if ICON_MARKER not in text:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"stale MENU glyph anchor count is {count}, expected 1")
        text = text.replace(old, new, 1)
        write(ICON, text)

    require(
        ICON,
        ICON_MARKER,
        "clearAnimation();",
        "setImageDrawable(null);",
        "state = State.VOICE;",
    )
    print("Stale iOS composer MENU glyph reset passed")


def validate_popup_and_preview() -> None:
    require(
        POPUP,
        "AUTHORGRAM_IOS_PREVIEW_TRANSPARENT_BACKGROUND",
        "authorGramNativeMessagePreview && a == 0",
        "start = preview.getBottom() - scrollOffset;",
        "end = gap.getBottom() - scrollOffset;",
    )
    preview = require(
        PREVIEW,
        PREVIEW_MARKER,
        "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY",
        "MessageObject messageObject",
        "MessagesController.getInstance(currentAccount)",
        "BackupImageView avatarView",
        "AvatarDrawable avatarDrawable",
        "TextView senderNameView",
        "sourceCell.draw(canvas);",
        "findVisibleBounds(raw)",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "Do not add a local BluredView",
    )
    if "new BluredView(" in preview:
        raise SystemExit("preview-local blur remains; full-screen blur would still be incomplete")
    print("Sender avatar/name, native message snapshot and popup segmentation passed")


def main() -> None:
    patch_native_message_preview()
    patch_full_screen_blur()
    patch_typing_overlay()
    patch_stale_menu_glyph()
    validate_popup_and_preview()


if __name__ == "__main__":
    main()
