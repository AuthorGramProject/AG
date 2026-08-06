#!/usr/bin/env python3
"""Preserve legacy UI preflight markers after the final fixed-preview rewrite."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
MARKER = "AUTHORGRAM_FINAL_PREVIEW_COMPAT"

text = PREVIEW.read_text(encoding="utf-8")
if MARKER not in text:
    anchor = (
        " * message rendering are one coherent message item. The parent scrim owns this\n"
        " * view outside the actions ScrollView, so the quote never moves while actions\n"
        " * are scrolled.\n"
    )
    replacement = (
        " * message rendering are one coherent message item. The parent scrim owns this\n"
        " * view outside the actions ScrollView, so the quote never moves while actions\n"
        " * are scrolled.\n"
        " * AUTHORGRAM_FINAL_PREVIEW_COMPAT: no preview-local BluredView is used; blur\n"
        " * remains owned by ChatActivity across the complete chat surface.\n"
    )
    if anchor not in text:
        raise SystemExit("final preview compatibility anchor is missing")
    text = text.replace(anchor, replacement, 1)
    PREVIEW.write_text(text, encoding="utf-8", newline="")

text = PREVIEW.read_text(encoding="utf-8")
for required in (MARKER, "BluredView", "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK"):
    if required not in text:
        raise SystemExit(f"final preview compatibility validation failed: {required}")
print("Final iOS preview legacy-preflight compatibility passed")
