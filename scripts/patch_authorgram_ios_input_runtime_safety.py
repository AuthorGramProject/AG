#!/usr/bin/env python3
"""Prevent Main-only iOS composer stabilization from feeding a zero-width post loop."""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
MARKER = "AUTHORGRAM_BOUNDED_IOS_SIDE_BUBBLE_UPDATE"

OLD = '''        updateSideBubbles();
    }

    private void authorGramScheduleInputGeometryInvariant() {
'''

NEW = '''        // AUTHORGRAM_BOUNDED_IOS_SIDE_BUBBLE_UPDATE
        // updateSideBubbles() inherits Telegram's retry-via-View.post behavior
        // while side controls still have zero width. Calling it from every
        // Main-only onLayout can therefore feed the queue indefinitely when a
        // side control remains GONE/zero-width. Only update after real geometry
        // exists; normal Telegram lifecycle callbacks handle later visibility.
        if (attachBubble != null
                && sendButtonContainer != null
                && attachBubble.getWidth() > 0
                && sendButtonContainer.getWidth() > 0) {
            updateSideBubbles();
        }
    }

    private void authorGramScheduleInputGeometryInvariant() {
'''


def read() -> str:
    if not ENTER.is_file():
        raise SystemExit(f"Missing required source: {ENTER}")
    return ENTER.read_text(encoding="utf-8")


def write(text: str) -> None:
    ENTER.write_text(text, encoding="utf-8", newline="")


def apply() -> None:
    text = read()
    if MARKER not in text:
        count = text.count(OLD)
        if count != 1:
            raise SystemExit(f"iOS geometry side-bubble anchor count is {count}, expected 1")
        text = text.replace(OLD, NEW, 1)
        write(text)
    validate()


def validate() -> None:
    text = read()
    required = (
        MARKER,
        "attachBubble.getWidth() > 0",
        "sendButtonContainer.getWidth() > 0",
        "updateSideBubbles();",
        "removeCallbacks(authorGramInputGeometryRunnable);",
    )
    for token in required:
        if token not in text:
            raise SystemExit(f"iOS input runtime invariant missing: {token}")

    helper_start = text.find("private void authorGramStabilizeIOSInputGeometry()")
    helper_end = text.find("private void authorGramScheduleInputGeometryInvariant()", helper_start)
    if helper_start < 0 or helper_end < 0:
        raise SystemExit("unable to isolate AuthorGram iOS geometry helper")
    helper = text[helper_start:helper_end]
    if helper.count("updateSideBubbles();") != 1:
        raise SystemExit("unexpected number of side-bubble updates in geometry helper")
    if "getWidth() > 0" not in helper:
        raise SystemExit("side-bubble update remains unguarded against zero-width retry loop")
    print("AuthorGram iOS input runtime safety passed")


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
