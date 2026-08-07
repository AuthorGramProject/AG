#!/usr/bin/env python3
"""Repair and validate ChatActivity iOS preview calls that depend on local scope.

The 12.9.2 UI patch chain historically emitted a fixed-preview call through a
local ``scrimPopupContainerLayout`` variable after that variable had left lexical
scope.  The canonical repair now resolves the real ChatScrimPopupContainerLayout
by walking upward from ``popupLayout``, which is the stable view available in the
selected-message menu block.

The pre-apply mode is deliberately read-only.  It inventories only the exact
legacy back-calls we know how to repair and refuses any unknown variant before a
UI generator is allowed to touch ChatActivity.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"

SAFE_MARKER = "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT"
UNSAFE_FIXED_PREFIX = "scrimPopupContainerLayout.setFixedMessagePreview("
UNSAFE_BOTTOM = "scrimPopupContainerLayout.getBottomOffset()"
FORBIDDEN_OLD_RECEIVER = "chatActivityEnterView.setFixedMessagePreview("

UNSAFE_FIXED_RE = re.compile(
    r"(?m)^(?P<indent>[ \t]*)scrimPopupContainerLayout\.setFixedMessagePreview\("
    r"(?P<preview>iosPreview|popupMessagePreview)\);[ \t]*$"
)

UNSAFE_BOTTOM_RE = re.compile(
    r"[ \t]*-[ \t]*\(\([ \t]*iosMenuMode[ \t]*&&[ \t]*!BUILD_FOR_PLAY_MARKET"
    r"[ \t]*\)[ \t]*\?[ \t]*0[ \t]*:[ \t]*"
    r"scrimPopupContainerLayout\.getBottomOffset\(\)[ \t]*\)"
)


def read_chat() -> str:
    if not CHAT.is_file():
        raise SystemExit(f"Missing ChatActivity.java: {CHAT}")
    return CHAT.read_text(encoding="utf-8")


def write_chat(text: str) -> None:
    CHAT.write_text(text, encoding="utf-8", newline="")


def inventory_legacy_calls(text: str) -> tuple[int, int]:
    if FORBIDDEN_OLD_RECEIVER in text:
        raise SystemExit(
            "pre-apply failed: obsolete chatActivityEnterView fixed-preview receiver remains"
        )

    fixed_total = text.count(UNSAFE_FIXED_PREFIX)
    fixed_known = len(UNSAFE_FIXED_RE.findall(text))
    if fixed_total != fixed_known:
        raise SystemExit(
            "pre-apply failed: unknown scrimPopupContainerLayout fixed-preview back-call "
            f"exists (known={fixed_known}, total={fixed_total})"
        )
    if fixed_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one legacy fixed-preview call, found {fixed_known}"
        )

    bottom_total = text.count(UNSAFE_BOTTOM)
    bottom_known = len(UNSAFE_BOTTOM_RE.findall(text))
    if bottom_total != bottom_known:
        raise SystemExit(
            "pre-apply failed: unknown scrimPopupContainerLayout bottom-offset back-call "
            f"exists (known={bottom_known}, total={bottom_total})"
        )
    if bottom_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one legacy bottom-offset call, found {bottom_known}"
        )

    if (fixed_known or bottom_known) and "popupLayout" not in text:
        raise SystemExit("pre-apply failed: popupLayout owner anchor is unavailable")

    return fixed_known, bottom_known


def pre_apply_check() -> None:
    """Read-only guard that runs before any patch generator mutates ChatActivity."""
    fixed_known, bottom_known = inventory_legacy_calls(read_chat())
    print(
        "AuthorGram ChatActivity pre-apply legacy scan passed: "
        f"legacyFixedPreview={fixed_known}, legacyBottomOffset={bottom_known}"
    )


def _scope_safe_fixed_preview(match: re.Match[str]) -> str:
    indent = match.group("indent")
    preview = match.group("preview")
    return (
        f"{indent}// {SAFE_MARKER}\n"
        f"{indent}// popupLayout is the stable local view in this createMenu block. Walk\n"
        f"{indent}// its actual parent chain until the native scrim owner is reached.\n"
        f"{indent}android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();\n"
        f"{indent}while (authorgramIosPreviewParent != null\n"
        f"{indent}        && !(authorgramIosPreviewParent instanceof "
        "org.telegram.ui.Components.ChatScrimPopupContainerLayout)) {\n"
        f"{indent}    if (authorgramIosPreviewParent instanceof android.view.View) {\n"
        f"{indent}        authorgramIosPreviewParent =\n"
        f"{indent}                ((android.view.View) authorgramIosPreviewParent).getParent();\n"
        f"{indent}    }} else {{\n"
        f"{indent}        authorgramIosPreviewParent = null;\n"
        f"{indent}    }}\n"
        f"{indent}}}\n"
        f"{indent}if (authorgramIosPreviewParent instanceof "
        "org.telegram.ui.Components.ChatScrimPopupContainerLayout) {\n"
        f"{indent}    ((org.telegram.ui.Components.ChatScrimPopupContainerLayout) "
        "authorgramIosPreviewParent)\n"
        f"{indent}            .setFixedMessagePreview({preview});\n"
        f"{indent}}} else {{\n"
        f"{indent}    // Upstream hierarchy changed unexpectedly. Keep the preview reachable\n"
        f"{indent}    // instead of dereferencing an out-of-scope/nonexistent owner.\n"
        f"{indent}    LinearLayout.LayoutParams authorgramFallbackPreviewParams = "
        "LayoutHelper.createLinear(\n"
        f"{indent}            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT\n"
        f"{indent}    );\n"
        f"{indent}    popupLayout.addView({preview}, 0, authorgramFallbackPreviewParams);\n"
        f"{indent}}}"
    )


def apply() -> None:
    """Repair only known legacy calls and immediately validate the generated Java."""
    pre_apply_check()
    text = read_chat()

    text, fixed_count = UNSAFE_FIXED_RE.subn(_scope_safe_fixed_preview, text)
    text, bottom_count = UNSAFE_BOTTOM_RE.subn("", text)

    if fixed_count or bottom_count:
        write_chat(text)
        print(
            "AuthorGram ChatActivity scope repair applied: "
            f"fixedPreview={fixed_count}, bottomOffset={bottom_count}"
        )
    else:
        print("AuthorGram ChatActivity scope repair already applied")

    validate()


def validate() -> None:
    text = read_chat()
    failures: list[str] = []

    if UNSAFE_FIXED_PREFIX in text:
        failures.append("legacy out-of-scope scrim fixed-preview call remains")
    if UNSAFE_BOTTOM in text:
        failures.append("legacy out-of-scope scrim bottom-offset call remains")
    if FORBIDDEN_OLD_RECEIVER in text:
        failures.append("obsolete chatActivityEnterView fixed-preview receiver remains")

    if "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER" in text:
        if text.count(SAFE_MARKER) != 1:
            failures.append(
                f"scope-safe preview marker count is {text.count(SAFE_MARKER)}, expected 1"
            )
        for required in (
            "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
            "while (authorgramIosPreviewParent != null",
            "authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout",
            "((android.view.View) authorgramIosPreviewParent).getParent();",
            ".setFixedMessagePreview(iosPreview);",
        ):
            if required not in text:
                failures.append(f"scope-safe fixed-preview invariant missing: {required}")

    if "? 0 : scrimPopupContainerLayout" in text:
        failures.append("legacy conditional scrim bottom-offset geometry remains")

    if failures:
        raise SystemExit("ChatActivity scope validation failed:\n - " + "\n - ".join(failures))

    print("AuthorGram ChatActivity scope validation passed")


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
        validate()
    else:
        apply()


if __name__ == "__main__":
    main()
