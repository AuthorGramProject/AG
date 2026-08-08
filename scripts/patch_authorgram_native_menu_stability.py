#!/usr/bin/env python3
"""Final native-renderer and geometry repair for AuthorGram Main message menus.

This pass intentionally runs after patch_authorgram_main_stability.py. It does not
invent a second/synthetic message renderer. Instead it uses the same cloning
sequence Telegram itself uses in PollItemMenu/TodoItemMenu for a live
ChatMessageCell: copyParamsTo(), copy spoiler attachment state, set the
non-interactive delegate, then bind the same MessageObject with the original
grouped/pinned/first-in-chat context.

Invariants:
1. The selected-message preview preserves Telegram's complete native
   ChatMessageCell context. Sender name/avatar, replies, grouped media, files,
   forum/thread and saved-chat decisions therefore stay Telegram-owned.
2. The selected-message preview uses the same horizontal margins/gravity as the
   native popup card, so reaction-side offsets cannot clip the message.
3. Reparented bottom views keep their natural/declared height. They are never
   compressed to an arbitrary 44dp strip.
4. DialogsAdapter compares two recent .me URL items against each other rather
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
ALIGNMENT_MARKER = "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT"
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
    if NATIVE_CONTEXT_MARKER in text:
        return

    # Match the exact canonical block emitted by patch_authorgram_main_stability.
    # Keep setFullyDraw(), but move Telegram's native context copy ahead of the
    # delegate exactly as PollItemMenu/TodoItemMenu do. A previous anchor assumed
    # isChat and setMessageObject were adjacent even though setFullyDraw/delegate
    # sit between them, so the release pass could never apply.
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
        "        // Telegram's own PollItemMenu/TodoItemMenu clones live message cells\n"
        "        // in this order: copyParamsTo(), copy spoiler attachment state, set\n"
        "        // a non-interactive delegate, then bind the MessageObject with the\n"
        "        // source cell's grouped/pinned context. Reuse exactly that native\n"
        "        // mechanism instead of synthesizing sender/avatar/reply/media UI.\n"
        "        if (sourceCell != null) {\n"
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
        "            // The action popup gets asymmetric reaction-side margins in\n"
        "            // ChatActivity. Give the native selected-message cell the same\n"
        "            // horizontal footprint instead of laying it out from x=0.\n"
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
        "sourceCell.copyParamsTo(previewCell);",
        "previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);",
        "previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()",
        "sourceCell.getCurrentMessagesGroup()",
        "sourceCell.pinnedBottom",
        "sourceCell.pinnedTop",
        "sourceCell.firstInChat",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
    )
    for token in required_preview:
        if token not in preview:
            raise SystemExit(f"native selected-message renderer invariant missing: {token}")

    # Enforce Telegram's clone ordering, not just token presence.
    clone_positions = [
        preview.find("sourceCell.copyParamsTo(previewCell);"),
        preview.find("previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);"),
        preview.find("previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()"),
        preview.find("sourceCell.getCurrentMessagesGroup()"),
    ]
    if any(position < 0 for position in clone_positions) or clone_positions != sorted(clone_positions):
        raise SystemExit("native ChatMessageCell clone order diverges from Telegram")

    if "previewCell.isChat = sourceCell != null && sourceCell.isChat;" in preview:
        raise SystemExit("partial ChatMessageCell context copy survived")

    for token in (
        ALIGNMENT_MARKER,
        "params.setMarginStart(popupParams.getMarginStart());",
        "params.setMarginEnd(popupParams.getMarginEnd());",
        "params.gravity = popupParams.gravity;",
        NATURAL_FOOTER_MARKER,
        "? oldParams.height",
        ": LayoutHelper.WRAP_CONTENT;",
    ):
        if token not in scrim:
            raise SystemExit(f"message-menu geometry invariant missing: {token}")

    if "Math.min(oldParams.height, AndroidUtilities.dp(44))" in scrim:
        raise SystemExit("44dp footer clipping cap survived")

    if "recentMeUrl.url.equals(recentMeUrl.url)" in dialogs:
        raise SystemExit("DialogsAdapter recent .me URL self-comparison survived")
    if "recentMeUrl.url.equals(itemInternal.recentMeUrl.url)" not in dialogs:
        raise SystemExit("DialogsAdapter recent .me URL DiffUtil fix missing")

    print("AuthorGram native iOS renderer/alignment/footer + Telegram .me DiffUtil stability passed")


def apply() -> None:
    patch_native_preview_context()
    patch_preview_card_alignment()
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
