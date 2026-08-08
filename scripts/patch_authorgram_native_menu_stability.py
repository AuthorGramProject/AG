#!/usr/bin/env python3
"""Final native-renderer and geometry repair for AuthorGram Main message menus.

This pass intentionally runs after patch_authorgram_main_stability.py. It does not
invent a second/synthetic message renderer. Instead it uses the same cloning
sequence Telegram itself uses for the full live-cell clone in
PollItemMenu/TodoItemMenu: copyVisiblePartTo(), copyParamsTo(), copy spoiler
attachment state, set the non-interactive delegate, then bind the same
MessageObject with the original grouped/pinned/first-in-chat context.

Invariants:
1. The selected-message preview preserves Telegram's complete native
   ChatMessageCell context. Sender name/avatar, replies, grouped media, files,
   forum/thread and saved-chat decisions therefore stay Telegram-owned.
2. The selected-message viewport is not narrowed to the action-card width.
   Telegram gets the real available chat width, so an already-laid-out native
   message cannot be clipped horizontally by the narrower popup menu.
3. The selected-message preview uses the same horizontal margins/gravity as the
   native popup card for placement, while its measured width remains bounded by
   the real parent work area rather than popupWindowLayout.getMeasuredWidth().
4. Reparented bottom views keep their natural/declared height. They are never
   compressed to an arbitrary 44dp strip.
5. DialogsAdapter compares two recent .me URL items against each other rather
   than self-comparing the old URL, preserving RecyclerView DiffUtil identity.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
DIALOGS = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java"

NATIVE_CONTEXT_MARKER = "AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT"
VISIBLE_CONTEXT_MARKER = "AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT"
ALIGNMENT_MARKER = "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT"
FULL_WIDTH_MARKER = "AUTHORGRAM_IOS_PREVIEW_FULL_WIDTH_MEASURE"
NATURAL_FOOTER_MARKER = "AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT"
ME_URL_DIFF_MARKER = "AUTHORGRAM_TELEGRAM_ME_URL_DIFF_FIX"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label} count is {count}, expected 1")
    return text.replace(old, new, 1)


def patch_native_preview_context() -> None:
    text = read(PREVIEW)

    # Give long native messages a larger but still bounded independent viewport.
    # The action card keeps its own scrolling; this only affects the selected
    # message block above it.
    text = text.replace(
        "        int viewportHeight = Math.max(AndroidUtilities.dp(320), AndroidUtilities.displaySize.y);\n",
        "        int viewportHeight = Math.max(AndroidUtilities.dp(360), AndroidUtilities.displaySize.y);\n",
        1,
    )
    text = text.replace(
        "                AndroidUtilities.dp(120),\n"
        "                Math.min(AndroidUtilities.dp(300), Math.round(viewportHeight * 0.34f))\n",
        "                AndroidUtilities.dp(140),\n"
        "                Math.min(AndroidUtilities.dp(420), Math.round(viewportHeight * 0.46f))\n",
        1,
    )

    # An isolated Main pass can receive a tree that already has the native
    # context marker. Upgrade that state in-place instead of treating the marker
    # as proof that the complete Telegram clone sequence is present.
    if NATIVE_CONTEXT_MARKER in text:
        if "sourceCell.copyVisiblePartTo(previewCell);" not in text:
            old = "            sourceCell.copyParamsTo(previewCell);\n"
            new = (
                "            // AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT\n"
                "            sourceCell.copyVisiblePartTo(previewCell);\n"
                "            sourceCell.copyParamsTo(previewCell);\n"
            )
            if old not in text:
                raise SystemExit("native preview context marker exists but copyParamsTo anchor is missing")
            text = text.replace(old, new, 1)
        elif VISIBLE_CONTEXT_MARKER not in text:
            text = text.replace(
                "            sourceCell.copyVisiblePartTo(previewCell);\n",
                "            // AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT\n"
                "            sourceCell.copyVisiblePartTo(previewCell);\n",
                1,
            )
        write(PREVIEW, text)
        return

    # Match the exact canonical block emitted by patch_authorgram_main_stability.
    # Telegram's full PollItemMenu/TodoItemMenu clone first copies the visible
    # native-cell state, then the remaining params/spoiler state, then installs
    # the non-interactive delegate and finally binds the same MessageObject.
    old = (
        "        previewCell.isChat = sourceCell != null && sourceCell.isChat;\n"
        "        previewCell.setFullyDraw(true);\n"
        "        previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {\n"
        "            @Override\n"
        "            public boolean canPerformActions() {\n"
        "                return false;\n"
        "            }\n"
        "        });\n"
        "        previewCell.setMessageObject(messageObject, null, false, false, false);\n"
    )
    new = (
        "        previewCell.setFullyDraw(true);\n"
        "        // AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT\n"
        "        // Telegram's full PollItemMenu/TodoItemMenu clone copies the live\n"
        "        // visible cell state before params/spoilers, then installs a\n"
        "        // non-interactive delegate and binds the original MessageObject.\n"
        "        if (sourceCell != null) {\n"
        "            // AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT\n"
        "            sourceCell.copyVisiblePartTo(previewCell);\n"
        "            sourceCell.copyParamsTo(previewCell);\n"
        "            previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);\n"
        "        }\n"
        "        previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {\n"
        "            @Override\n"
        "            public boolean canPerformActions() {\n"
        "                return false;\n"
        "            }\n"
        "        });\n"
        "        if (sourceCell != null) {\n"
        "            previewCell.setMessageObject(\n"
        "                    messageObject,\n"
        "                    sourceCell.getCurrentMessagesGroup(),\n"
        "                    sourceCell.pinnedBottom,\n"
        "                    sourceCell.pinnedTop,\n"
        "                    sourceCell.firstInChat\n"
        "            );\n"
        "        } else {\n"
        "            previewCell.setMessageObject(messageObject, null, false, false, false);\n"
        "        }\n"
    )
    text = replace_once(text, old, new, "IOSMessageMenuPreview canonical native-context anchor")
    write(PREVIEW, text)


def patch_preview_card_alignment() -> None:
    text = read(SCRIM)
    if ALIGNMENT_MARKER in text:
        return

    old = (
        "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
        "                    LayoutHelper.WRAP_CONTENT,\n"
        "                    LayoutHelper.WRAP_CONTENT\n"
        "            );\n"
        "            // AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY\n"
        "            params.topMargin = AndroidUtilities.dp(8);\n"
        "            params.bottomMargin = AndroidUtilities.dp(8);\n"
    )
    new = (
        "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
        "                    LayoutHelper.WRAP_CONTENT,\n"
        "                    LayoutHelper.WRAP_CONTENT\n"
        "            );\n"
        "            // AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT\n"
        "            // Keep the selected native message anchored with the action card,\n"
        "            // but do not later force its width down to the action-card width.\n"
        "            if (popupWindowLayout != null\n"
        "                    && popupWindowLayout.getLayoutParams() instanceof LinearLayout.LayoutParams) {\n"
        "                LinearLayout.LayoutParams popupParams =\n"
        "                        (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();\n"
        "                params.leftMargin = popupParams.leftMargin;\n"
        "                params.rightMargin = popupParams.rightMargin;\n"
        "                params.setMarginStart(popupParams.getMarginStart());\n"
        "                params.setMarginEnd(popupParams.getMarginEnd());\n"
        "                params.gravity = popupParams.gravity;\n"
        "            }\n"
        "            // AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY\n"
        "            params.topMargin = AndroidUtilities.dp(8);\n"
        "            params.bottomMargin = AndroidUtilities.dp(8);\n"
    )
    text = replace_once(text, old, new, "ChatScrim fixed-preview alignment anchor")
    write(SCRIM, text)


def patch_preview_full_width_measure() -> None:
    text = read(SCRIM)
    if FULL_WIDTH_MARKER in text:
        return

    old = (
        "        if (fixedMessagePreview != null) {\n"
        "            int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();\n"
        "            LinearLayout.LayoutParams previewParams =\n"
        "                    (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();\n"
        "            if (popupWidthForPreview > 0 && previewParams.width != popupWidthForPreview) {\n"
        "                previewParams.width = popupWidthForPreview;\n"
        "                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
        "            }\n"
        "        }\n"
    )
    new = (
        "        if (fixedMessagePreview != null) {\n"
        "            LinearLayout.LayoutParams previewParams =\n"
        "                    (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();\n"
        "            // AUTHORGRAM_IOS_PREVIEW_FULL_WIDTH_MEASURE\n"
        "            // ChatMessageCell text/name/avatar geometry is calculated for the\n"
        "            // chat work area, not for the narrower action popup. Giving the\n"
        "            // preview only popupWindowLayout.getMeasuredWidth() clips native\n"
        "            // text and can suppress sender-side layout. Preserve placement\n"
        "            // margins, but measure against the real parent work area.\n"
        "            int parentWidthForPreview = MeasureSpec.getSize(adjustedWidthSpec);\n"
        "            int horizontalMargins = Math.max(0, previewParams.leftMargin)\n"
        "                    + Math.max(0, previewParams.rightMargin);\n"
        "            int previewWidth = Math.max(\n"
        "                    1,\n"
        "                    parentWidthForPreview\n"
        "                            - getPaddingLeft()\n"
        "                            - getPaddingRight()\n"
        "                            - horizontalMargins\n"
        "            );\n"
        "            if (parentWidthForPreview > 0 && previewParams.width != previewWidth) {\n"
        "                previewParams.width = previewWidth;\n"
        "                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
        "            }\n"
        "        }\n"
    )
    text = replace_once(text, old, new, "ChatScrim popup-width preview clamp anchor")
    write(SCRIM, text)


def patch_natural_footer_height() -> None:
    text = read(SCRIM)
    if NATURAL_FOOTER_MARKER in text:
        return

    old = (
        "            // AUTHORGRAM_COMPACT_IOS_MENU_FOOTER\n"
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? Math.min(oldParams.height, AndroidUtilities.dp(44))\n"
        "                    : AndroidUtilities.dp(44);\n"
    )
    new = (
        "            // AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT\n"
        "            // applyViewBottom() is also used by Telegram informational blocks.\n"
        "            // Preserve a declared height and otherwise let the child measure\n"
        "            // naturally; never crop arbitrary bottom content to 44dp.\n"
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? oldParams.height\n"
        "                    : LayoutHelper.WRAP_CONTENT;\n"
    )
    text = replace_once(text, old, new, "ChatScrim 44dp footer-cap anchor")
    write(SCRIM, text)


def patch_recent_me_url_diff() -> None:
    text = read(DIALOGS)
    if ME_URL_DIFF_MARKER in text:
        return

    old = (
        "                return recentMeUrl != null && itemInternal.recentMeUrl != null "
        "&& recentMeUrl.url != null && recentMeUrl.url.equals(recentMeUrl.url);\n"
    )
    new = (
        "                // AUTHORGRAM_TELEGRAM_ME_URL_DIFF_FIX\n"
        "                // Compare the old and new URL items. Self-comparison makes\n"
        "                // distinct .me/t.me hints look identical to DiffUtil.\n"
        "                return recentMeUrl != null && itemInternal.recentMeUrl != null "
        "&& recentMeUrl.url != null && recentMeUrl.url.equals(itemInternal.recentMeUrl.url);\n"
    )
    if old not in text:
        already_fixed = "recentMeUrl.url != null && recentMeUrl.url.equals(itemInternal.recentMeUrl.url)"
        if already_fixed in text:
            return
        raise SystemExit("DialogsAdapter recent .me URL self-comparison anchor is missing")
    text = text.replace(old, new, 1)
    write(DIALOGS, text)


def validate() -> None:
    preview = read(PREVIEW)
    scrim = read(SCRIM)
    dialogs = read(DIALOGS)

    required_preview = (
        NATIVE_CONTEXT_MARKER,
        VISIBLE_CONTEXT_MARKER,
        "sourceCell.copyVisiblePartTo(previewCell);",
        "sourceCell.copyParamsTo(previewCell);",
        "previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);",
        "previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()",
        "sourceCell.getCurrentMessagesGroup()",
        "sourceCell.pinnedBottom",
        "sourceCell.pinnedTop",
        "sourceCell.firstInChat",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
        "Math.min(AndroidUtilities.dp(420), Math.round(viewportHeight * 0.46f))",
    )
    for token in required_preview:
        if token not in preview:
            raise SystemExit(f"native selected-message renderer invariant missing: {token}")

    # Enforce Telegram's full live-cell clone ordering, not just token presence.
    clone_positions = [
        preview.find("sourceCell.copyVisiblePartTo(previewCell);"),
        preview.find("sourceCell.copyParamsTo(previewCell);"),
        preview.find("previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);"),
        preview.find("previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()"),
        preview.find("sourceCell.getCurrentMessagesGroup()"),
    ]
    if any(position < 0 for position in clone_positions) or clone_positions != sorted(clone_positions):
        raise SystemExit("native ChatMessageCell clone order diverges from Telegram full-cell clone")

    if "previewCell.isChat = sourceCell != null && sourceCell.isChat;" in preview:
        raise SystemExit("partial ChatMessageCell context copy survived")

    for token in (
        ALIGNMENT_MARKER,
        "params.setMarginStart(popupParams.getMarginStart());",
        "params.setMarginEnd(popupParams.getMarginEnd());",
        "params.gravity = popupParams.gravity;",
        FULL_WIDTH_MARKER,
        "int parentWidthForPreview = MeasureSpec.getSize(adjustedWidthSpec);",
        "previewParams.width = previewWidth;",
        NATURAL_FOOTER_MARKER,
        "? oldParams.height",
        ": LayoutHelper.WRAP_CONTENT;",
    ):
        if token not in scrim:
            raise SystemExit(f"message-menu geometry invariant missing: {token}")

    if "previewParams.width = popupWidthForPreview;" in scrim:
        raise SystemExit("selected-message preview is still narrowed to action-card width")
    if "Math.min(oldParams.height, AndroidUtilities.dp(44))" in scrim:
        raise SystemExit("44dp footer clipping cap survived")

    if "recentMeUrl.url.equals(recentMeUrl.url)" in dialogs:
        raise SystemExit("DialogsAdapter recent .me URL self-comparison survived")
    if "recentMeUrl.url.equals(itemInternal.recentMeUrl.url)" not in dialogs:
        raise SystemExit("DialogsAdapter recent .me URL DiffUtil fix missing")

    print("AuthorGram native iOS sender/visible-cell renderer + full-width geometry/footer + Telegram .me DiffUtil stability passed")


def apply() -> None:
    patch_native_preview_context()
    patch_preview_card_alignment()
    patch_preview_full_width_measure()
    patch_natural_footer_height()
    patch_recent_me_url_diff()
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
