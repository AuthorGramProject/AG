#!/usr/bin/env python3
"""Read-only safety guard for AuthorGram Main iOS message-menu ownership.

The canonical UI generator is scripts/patch_authorgram_main_stability.py. This
file intentionally NEVER rewrites ChatActivity or IOSMessageMenuPreview.

There are two validation phases:
- pre-apply/basic: accepts known legacy compatibility markers that the canonical
  generator is explicitly responsible for replacing, but rejects active unsafe
  ownership/back-call forms that should never be present on the dev baseline;
- canonical/final: requires the reference structure and rejects every legacy
  marker/code path that could put the selected message inside the action card.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
STABILITY = ROOT / "scripts/patch_authorgram_main_stability.py"

SAFE_MARKER = "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT"
CANONICAL_MARKER = "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW"
BOUNDED_MARKER = "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW"
REFERENCE_MARKER = "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY"

# Active source forms that are unsafe even before canonicalization. These are
# not harmless comments: they indicate an obsolete receiver/ownership path.
BASIC_FORBIDDEN_CHAT_FORMS = (
    "scrimPopupContainerLayout.setFixedMessagePreview(",
    "chatActivityEnterView.setFixedMessagePreview(",
    "popupLayout.addView(iosPreview",
    "popupLayout.addView(popupMessagePreview",
)

# Known historical compatibility markers can exist in the committed baseline.
# main_stability replaces the whole owner block, so pre-apply must not reject a
# harmless marker before that repair has had a chance to run. Final validation
# does reject them absolutely.
CANONICAL_ONLY_FORBIDDEN_CHAT_FORMS = (
    "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
    "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
    "iosPreview.shouldScrollWithActions()",
)

FORBIDDEN_PREVIEW_FORMS = (
    "Bitmap.createBitmap",
    "sourceCell.draw(",
    "getPixels(",
    "NativeCellSnapshotView",
)


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def basic_failures() -> list[str]:
    chat = read(CHAT)
    preview = read(PREVIEW)
    failures: list[str] = []

    for forbidden in BASIC_FORBIDDEN_CHAT_FORMS:
        if forbidden in chat:
            failures.append(f"unsafe ChatActivity ownership form remains: {forbidden}")

    if "? 0 : scrimPopupContainerLayout" in chat:
        failures.append("legacy conditional scrim bottom-offset geometry remains")

    for forbidden in FORBIDDEN_PREVIEW_FORMS:
        if forbidden in preview:
            failures.append(f"bitmap/synthetic preview regression remains: {forbidden}")

    return failures


def validate() -> None:
    """Compatibility/basic validation used before the final stability pass.

    This intentionally does not require generated markers and intentionally
    permits known legacy comment markers. It still rejects active ownership
    code that the baseline should never contain.
    """
    failures = basic_failures()
    if failures:
        raise SystemExit("ChatActivity basic scope validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram ChatActivity basic scope validation passed")


def validate_canonical() -> None:
    failures = basic_failures()
    chat = read(CHAT)
    preview = read(PREVIEW)
    stability = read(STABILITY)

    for forbidden in CANONICAL_ONLY_FORBIDDEN_CHAT_FORMS:
        if forbidden in chat:
            failures.append(f"legacy final ChatActivity form remains: {forbidden}")

    for required in (
        CANONICAL_MARKER,
        SAFE_MARKER,
        REFERENCE_MARKER,
        "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
        "while (authorgramIosPreviewParent != null",
        "authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout",
        "((android.view.View) authorgramIosPreviewParent).getParent();",
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.setVisibility(android.view.View.GONE);",
        "AuthorGram: iOS preview owner not found",
    ):
        if required not in chat:
            failures.append(f"canonical ChatActivity invariant missing: {required}")

    for required in (
        BOUNDED_MARKER,
        REFERENCE_MARKER,
        "new ChatMessageCell(context, currentAccount)",
        "new ScrollView(context)",
        "previewScroll.setNestedScrollingEnabled(true);",
        "maxPreviewHeight",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
        "public boolean shouldScrollWithActions()",
        "return false;",
    ):
        if required not in preview:
            failures.append(f"canonical native preview invariant missing: {required}")

    # Cross-check the generator itself, not just the materialized Java source.
    for required in (
        CANONICAL_MARKER,
        SAFE_MARKER,
        BOUNDED_MARKER,
        REFERENCE_MARKER,
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.setVisibility(android.view.View.GONE);",
    ):
        if required not in stability:
            failures.append(f"stability generator invariant missing: {required}")

    if failures:
        raise SystemExit("ChatActivity canonical scope validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram canonical iOS preview ownership validation passed")


def pre_apply_check() -> None:
    """Read-only inventory used before any canonical generator runs."""
    validate()
    print("AuthorGram ChatActivity pre-apply safety scan passed")


def apply() -> None:
    """No mutation by design; require the canonical generator output."""
    validate_canonical()
    print("AuthorGram scope guard is read-only; no source rewrite performed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--mode",
        choices=("pre-apply", "apply", "validate"),
        default="apply",
    )
    args = parser.parse_args()

    if args.mode == "pre-apply":
        pre_apply_check()
    elif args.mode == "validate":
        validate_canonical()
    else:
        apply()


if __name__ == "__main__":
    main()
