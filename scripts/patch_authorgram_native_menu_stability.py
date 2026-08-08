#!/usr/bin/env python3
"""Final native-renderer and geometry repair for AuthorGram Main message menus.

This pass intentionally runs after patch_authorgram_main_stability.py and
patch_authorgram_runtime_regressions.py.  It does not draw a second/synthetic
message UI.  Instead it preserves Telegram's own ChatMessageCell state and fixes
layout constraints introduced by the AuthorGram menu wrapper.

Invariants:
1. The selected-message preview copies the complete native ChatMessageCell
   context from the real on-screen cell.  Sender name/avatar, forum/thread,
   saved-chat and related rendering decisions therefore stay Telegram-owned.
2. The selected-message preview uses the same horizontal margins/gravity as the
   native popup card, so reaction-side offsets cannot clip the message.
3. Reparented bottom views keep their natural/declared height.  They are never
   compressed to an arbitrary 44dp strip, which previously clipped icons and
   could also clip Telegram informational bottom blocks.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"

NATIVE_CONTEXT_MARKER = "AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT"
ALIGNMENT_MARKER = "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT"
NATURAL_FOOTER_MARKER = "AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def patch_native_preview_context() -> None:
    text = read(PREVIEW)
    if NATIVE_CONTEXT_MARKER in text:
        return

    old = "        previewCell.isChat = sourceCell != null && sourceCell.isChat;\n"
    new = (
        "        // AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT\n"
        "        // Reuse the complete context of Telegram's real on-screen cell.\n"
        "        // ChatMessageCell itself remains the only owner of sender-name, avatar,\n"
        "        // forum/thread, saved-chat and related rendering decisions.\n"
        "        if (sourceCell != null) {\n"
        "            sourceCell.copyParamsTo(previewCell);\n"
        "        }\n"
    )
    if old not in text:
        raise SystemExit("IOSMessageMenuPreview native-context anchor is missing")
    text = text.replace(old, new, 1)
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
        "            // The popup gets asymmetric reaction-side margins in ChatActivity.\n"
        "            // Give the native selected-message cell the same horizontal footprint\n"
        "            // instead of laying it out from x=0 and clipping it at the screen edge.\n"
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
    if old not in text:
        raise SystemExit("ChatScrim fixed-preview alignment anchor is missing")
    text = text.replace(old, new, 1)
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
        "            // Preserve a real declared height and otherwise let the child measure\n"
        "            // naturally; never crop arbitrary bottom content to a 44dp strip.\n"
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? oldParams.height\n"
        "                    : LayoutHelper.WRAP_CONTENT;\n"
    )
    if old not in text:
        raise SystemExit("ChatScrim 44dp footer-cap anchor is missing")
    text = text.replace(old, new, 1)
    write(SCRIM, text)


def validate() -> None:
    preview = read(PREVIEW)
    scrim = read(SCRIM)

    for token in (
        NATIVE_CONTEXT_MARKER,
        "sourceCell.copyParamsTo(previewCell);",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
    ):
        if token not in preview:
            raise SystemExit(f"native selected-message renderer invariant missing: {token}")

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

    if "AndroidUtilities.dp(44);" in scrim and "AUTHORGRAM_COMPACT_IOS_MENU_FOOTER" in scrim:
        raise SystemExit("legacy compact-footer geometry survived")

    print("AuthorGram native iOS message renderer/alignment/footer stability passed")


def apply() -> None:
    patch_native_preview_context()
    patch_preview_card_alignment()
    patch_natural_footer_height()
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
