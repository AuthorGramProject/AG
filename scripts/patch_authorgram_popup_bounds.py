#!/usr/bin/env python3
"""Compatibility preflight for the retired AuthorGram popup generator.

Historically this script rewrote ChatActivity and IOSMessageMenuPreview with an
older adaptive model that could place long selected-message previews inside the
action ScrollView. The canonical Main UI is now owned exclusively by
patch_authorgram_main_stability.py plus patch_authorgram_chat_scope_safety.py.

Keep this file as a harmless compatibility entry point because older release
scripts may still invoke it. It is deliberately read-only and must never emit
legacy UI source again.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
STABILITY = ROOT / "scripts/patch_authorgram_main_stability.py"
SCOPE = ROOT / "scripts/patch_authorgram_chat_scope_safety.py"

RETIRED_MARKER = "AUTHORGRAM_RETIRED_LEGACY_POPUP_GENERATOR"


def main() -> None:
    if not STABILITY.is_file() or not SCOPE.is_file():
        raise SystemExit("Canonical AuthorGram stability generators are missing")

    scope = runpy.run_path(str(SCOPE), run_name="authorgram_scope_preflight")
    scope["pre_apply_check"]()

    stability_text = STABILITY.read_text(encoding="utf-8")
    for required in (
        "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW",
        "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW",
        "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
        "new ChatMessageCell(context, currentAccount)",
    ):
        if required not in stability_text:
            raise SystemExit(f"canonical stability generator missing {required}")

    preview_text = PREVIEW.read_text(encoding="utf-8") if PREVIEW.is_file() else ""
    for forbidden in (
        "Bitmap.createBitmap",
        "sourceCell.draw(",
        "getPixels(",
        "NativeCellSnapshotView",
    ):
        if forbidden in preview_text:
            raise SystemExit(
                f"retired popup preflight found obsolete bitmap preview code: {forbidden}"
            )

    chat_text = CHAT.read_text(encoding="utf-8")
    if "popupLayout.addView(iosPreview" in chat_text:
        raise SystemExit(
            "retired popup preflight found selected-message preview inside action card"
        )

    print(
        f"{RETIRED_MARKER}: legacy popup generator is disabled; "
        "canonical stability pass owns final Main menu UI"
    )


if __name__ == "__main__":
    main()
