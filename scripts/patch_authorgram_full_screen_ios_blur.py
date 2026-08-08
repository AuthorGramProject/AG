#!/usr/bin/env python3
"""Canonicalize full-screen blur for AuthorGram Main iOS message menu.

The reference menu keeps reactions, selected message and the action card sharp
while the complete chat surface behind them is blurred/dimmed. Passing the
selected source cell as the exempt view leaves an unblurred island, so iOS mode
must use dimBehindView(null, true, true).
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"

MARKER = "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR"
FULL_BLUR = "dimBehindView(null, true, true);"


def read() -> str:
    if not CHAT.is_file():
        raise SystemExit(f"Missing ChatActivity.java: {CHAT}")
    return CHAT.read_text(encoding="utf-8")


def write(text: str) -> None:
    CHAT.write_text(text, encoding="utf-8", newline="")


def validate() -> None:
    text = read()
    failures: list[str] = []
    for required in (
        MARKER,
        FULL_BLUR,
        "NekoConfig.iOSMessageMenu.Bool()",
    ):
        if required not in text:
            failures.append(f"missing full-screen blur invariant: {required}")

    # A preview-local BluredView would create a second, differently scoped blur
    # surface and make the result diverge from the reference composition.
    preview_path = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
    if preview_path.is_file() and "new BluredView(" in preview_path.read_text(encoding="utf-8"):
        failures.append("preview-local BluredView remains")

    if failures:
        raise SystemExit("AuthorGram iOS full-screen blur validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram full-screen iOS message-menu blur validation passed")


def apply() -> None:
    text = read()
    if MARKER in text:
        validate()
        return

    # Known 12.9.2 baseline: regular context menu dims relative to the selected
    # cell. Replace only this exact anchor; never perform a broad fuzzy rewrite.
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
    canonical = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            // AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR\n"
        "            // iOS reference: blur/dim the complete chat surface. Reactions,\n"
        "            // native selected-message preview and action card are separate\n"
        "            // overlay siblings and therefore remain sharp above it.\n"
        "            if (org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                    && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
        "                dimBehindView(null, true, true);\n"
        "            } else {\n"
        "                dimBehindView(v, true);\n"
        "            }\n"
        "            hideHints(false);\n"
    )

    if normal in text:
        text = text.replace(normal, canonical, 1)
    elif elevated in text:
        text = text.replace(elevated, canonical, 1)
    elif FULL_BLUR in text:
        # A prior patch may already have the correct behavior but no marker.
        target = FULL_BLUR
        text = text.replace(
            target,
            "// AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR\n                " + target,
            1,
        )
    else:
        raise SystemExit(
            "Unable to locate a known ChatActivity context-menu blur anchor; refusing fuzzy patch"
        )

    write(text)
    validate()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("apply", "validate"), default="apply")
    args = parser.parse_args()
    if args.mode == "validate":
        validate()
    else:
        apply()


if __name__ == "__main__":
    main()
