#!/usr/bin/env python3
"""Read-only safety guard for AuthorGram Main iOS message-menu ownership.

The canonical UI generator is scripts/patch_authorgram_main_stability.py and the
final lifecycle/viewport/native-cell repair is
scripts/patch_authorgram_runtime_regressions.py. This file intentionally NEVER
rewrites ChatActivity or IOSMessageMenuPreview.

There are two validation phases:
- pre-apply/basic: accepts the known compatibility baseline but rejects active
  unsafe ownership/back-call forms;
- canonical/final: requires deferred popup ownership plus an exact native source
  ChatMessageCell clone. The action popup may not own, resize or horizontally
  offset the selected-message preview.

The production release may subsequently replace that audited compatibility clone
with AUTHORGRAM_FINAL_IOS_SENDER_HEADER_POSTPASS. Basic/pre-apply validation
accepts its explicit sender widgets only when that final marker is present; the
legacy bitmap/snapshot renderer remains forbidden.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
STABILITY = ROOT / "scripts/patch_authorgram_main_stability.py"
RUNTIME_REPAIR = ROOT / "scripts/patch_authorgram_runtime_regressions.py"
NATIVE_MENU_PATCH = ROOT / "scripts/patch_authorgram_native_menu_stability.py"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"

SAFE_MARKER = "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT"
CANONICAL_MARKER = "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW"
BOUNDED_MARKER = "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW"
REFERENCE_MARKER = "AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY"
DEFERRED_MARKER = "AUTHORGRAM_DEFERRED_IOS_PREVIEW_ATTACH"
STRICT_VIEWPORT_MARKER = "AUTHORGRAM_STRICT_IOS_MENU_VIEWPORT"
SOURCE_GEOMETRY_MARKER = "AUTHORGRAM_NATIVE_SOURCE_CELL_GEOMETRY"
WORKAREA_OWNER_MARKER = "AUTHORGRAM_IOS_PREVIEW_CHAT_WORKAREA_OWNER"
NO_POPUP_WIDTH_MARKER = "AUTHORGRAM_IOS_PREVIEW_NATIVE_SOURCE_GEOMETRY"
FINAL_HEADER_MARKER = "AUTHORGRAM_FINAL_IOS_SENDER_HEADER_POSTPASS"

BASIC_FORBIDDEN_CHAT_FORMS = (
    "scrimPopupContainerLayout.setFixedMessagePreview(",
    "chatActivityEnterView.setFixedMessagePreview(",
    "popupLayout.addView(iosPreview",
    "popupLayout.addView(popupMessagePreview",
)

CANONICAL_ONLY_FORBIDDEN_CHAT_FORMS = (
    "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
    "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
    "iosPreview.shouldScrollWithActions()",
    "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
)

FORBIDDEN_PREVIEW_FORMS = (
    "Bitmap.createBitmap",
    "sourceCell.draw(",
    "getPixels(",
    "NativeCellSnapshotView",
    "BackupImageView avatarView",
    "TextView senderNameView",
)

FINAL_HEADER_ALLOWED_FORMS = (
    "BackupImageView avatarView",
    "TextView senderNameView",
)

FORBIDDEN_FINAL_SCRIM_FORMS = (
    "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT",
    "AUTHORGRAM_IOS_PREVIEW_FULL_WIDTH_MEASURE",
    "params.setMarginStart(popupParams.getMarginStart());",
    "params.setMarginEnd(popupParams.getMarginEnd());",
    "params.gravity = popupParams.gravity;",
    "previewParams.width = popupWidthForPreview;",
    "previewParams.width = previewWidth;",
    "int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();",
    "int parentWidthForPreview = MeasureSpec.getSize(adjustedWidthSpec);",
)


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def basic_failures() -> list[str]:
    chat = read(CHAT)
    preview = read(PREVIEW)
    failures: list[str] = []
    final_header_present = FINAL_HEADER_MARKER in preview

    for forbidden in BASIC_FORBIDDEN_CHAT_FORMS:
        if forbidden in chat:
            failures.append(f"unsafe ChatActivity ownership form remains: {forbidden}")

    if "? 0 : scrimPopupContainerLayout" in chat:
        failures.append("legacy conditional scrim bottom-offset geometry remains")

    for forbidden in FORBIDDEN_PREVIEW_FORMS:
        if forbidden not in preview:
            continue
        if final_header_present and forbidden in FINAL_HEADER_ALLOWED_FORMS:
            continue
        failures.append(f"bitmap/synthetic preview regression remains: {forbidden}")

    return failures


def validate() -> None:
    """Compatibility/basic validation used before the final stability pass."""
    failures = basic_failures()
    if failures:
        raise SystemExit("ChatActivity basic scope validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram ChatActivity basic scope validation passed")


def validate_canonical() -> None:
    failures = basic_failures()
    chat = read(CHAT)
    preview = read(PREVIEW)
    stability = read(STABILITY)
    runtime_repair = read(RUNTIME_REPAIR)
    native_patch = read(NATIVE_MENU_PATCH)
    scrim = read(SCRIM)

    for forbidden in CANONICAL_ONLY_FORBIDDEN_CHAT_FORMS:
        if forbidden in chat:
            failures.append(f"legacy final ChatActivity form remains: {forbidden}")

    for required in (
        CANONICAL_MARKER,
        SAFE_MARKER,
        REFERENCE_MARKER,
        DEFERRED_MARKER,
        "final android.view.View authorGramIosPreviewAnchor = popupLayout;",
        "authorGramIosPreviewAnchor.post(() -> {",
        "authorGramIosPreviewAnchor.getParent();",
        "while (authorgramIosPreviewParent != null",
        "authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout",
        "((android.view.View) authorgramIosPreviewParent).getParent();",
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.setVisibility(android.view.View.GONE);",
        "AuthorGram: iOS preview owner not found after attach",
    ):
        if required not in chat:
            failures.append(f"canonical ChatActivity invariant missing: {required}")

    for required in (
        BOUNDED_MARKER,
        REFERENCE_MARKER,
        SOURCE_GEOMETRY_MARKER,
        "new ChatMessageCell(",
        "sourceCell.getResourcesProvider()",
        "sourceCell.getWidth()",
        "sourceCell.getHeight()",
        "setMeasuredDimension(sourceCellWidth, sourceCellHeight);",
        "new ScrollView(context)",
        "previewScroll.setNestedScrollingEnabled(true);",
        "sourceCell.copyVisiblePartTo(previewCell);",
        "sourceCell.copyParamsTo(previewCell);",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
        "public boolean shouldScrollWithActions()",
        "return false;",
    ):
        if required not in preview:
            failures.append(f"canonical native source-cell preview invariant missing: {required}")

    for required in (
        WORKAREA_OWNER_MARKER,
        NO_POPUP_WIDTH_MARKER,
        "params.setMarginStart(0);",
        "params.setMarginEnd(0);",
    ):
        if required not in scrim:
            failures.append(f"canonical source-cell container invariant missing: {required}")

    for forbidden in FORBIDDEN_FINAL_SCRIM_FORMS:
        if forbidden in scrim:
            failures.append(f"action-popup clipping geometry remains: {forbidden}")

    # The generator owns the intermediate compatibility shape; final native/menu
    # patch owns exact source geometry and runtime repair owns attachment timing.
    for required in (
        CANONICAL_MARKER,
        SAFE_MARKER,
        BOUNDED_MARKER,
        REFERENCE_MARKER,
        ".setFixedMessagePreview(iosPreview);",
    ):
        if required not in stability:
            failures.append(f"stability generator invariant missing: {required}")

    for required in (
        DEFERRED_MARKER,
        STRICT_VIEWPORT_MARKER,
        "authorGramIosPreviewAnchor.post(() -> {",
        "authorGramIosPreviewAnchor.getParent();",
        "pre-attach popupLayout.getParent() lookup remains",
        "AndroidUtilities.dp(96)",
    ):
        if required not in runtime_repair:
            failures.append(f"runtime repair invariant missing: {required}")

    for required in (
        SOURCE_GEOMETRY_MARKER,
        WORKAREA_OWNER_MARKER,
        NO_POPUP_WIDTH_MARKER,
        "sourceCell.copyVisiblePartTo(previewCell);",
        "setMeasuredDimension(sourceCellWidth, sourceCellHeight);",
    ):
        if required not in native_patch:
            failures.append(f"native menu patch invariant missing: {required}")

    if failures:
        raise SystemExit("ChatActivity canonical scope validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram canonical deferred native source-cell preview ownership validation passed")


def pre_apply_check() -> None:
    validate()
    print("AuthorGram ChatActivity pre-apply safety scan passed")


def apply() -> None:
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
