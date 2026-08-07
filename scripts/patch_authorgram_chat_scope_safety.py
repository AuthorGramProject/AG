#!/usr/bin/env python3
"""Repair and validate ChatActivity iOS preview calls that depend on local scope.

The 12.9.2 release patch chain historically emitted two fragile ChatActivity
expressions after the surrounding local ChatScrimPopupContainerLayout variable
had left lexical scope.  This pass deliberately uses popupLayout, which is the
actual action layout available in the preview block, and resolves its direct
ChatScrimPopupContainerLayout parent at runtime.

The pre-apply check is read-only: it inventories only known legacy back-calls
and refuses unknown variants.  The apply pass rewrites those known variants,
restores Telegram's original popup-height math, and the final validation rejects
both compile-invalid legacy expressions.
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

# The bad correction was introduced only by the AuthorGram patch chain.  It is
# not part of upstream Telegram's popup-height formula.  Keep this deliberately
# single-line so a broad regex can never consume surrounding Java statements.
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


def pre_apply_check() -> None:
    """Inventory known stale calls before any UI patch mutates ChatActivity."""
    text = read_chat()

    if FORBIDDEN_OLD_RECEIVER in text:
        raise SystemExit(
            "pre-apply failed: obsolete chatActivityEnterView fixed-preview receiver remains"
        )

    fixed_total = text.count(UNSAFE_FIXED_PREFIX)
    fixed_known = len(UNSAFE_FIXED_RE.findall(text))
    if fixed_total != fixed_known:
        raise SystemExit(
            "pre-apply failed: an unknown scrimPopupContainerLayout fixed-preview call exists "
            f"(known={fixed_known}, total={fixed_total})"
        )
    if fixed_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one legacy fixed-preview call, found {fixed_known}"
        )

    bottom_total = text.count(UNSAFE_BOTTOM)
    bottom_known = len(UNSAFE_BOTTOM_RE.findall(text))
    if bottom_total != bottom_known:
        raise SystemExit(
            "pre-apply failed: an unknown scrimPopupContainerLayout bottom-offset call exists "
            f"(known={bottom_known}, total={bottom_total})"
        )
    if bottom_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one legacy bottom-offset call, found {bottom_known}"
        )

    if (fixed_known or bottom_known) and "popupLayout" not in text:
        raise SystemExit("pre-apply failed: popupLayout owner is unavailable")

    print(
        "AuthorGram ChatActivity pre-apply scope scan passed: "
        f"legacyFixedPreview={fixed_known}, legacyBottomOffset={bottom_known}"
    )


def _scope_safe_fixed_preview(match: re.Match[str]) -> str:
    indent = match.group("indent")
    preview = match.group("preview")
    return (
        f"{indent}// {SAFE_MARKER}\n"
        f"{indent}// popupLayout is in this lexical scope; its direct parent is the native\n"
        f"{indent}// ChatScrimPopupContainerLayout that owns reactions and fixed previews.\n"
        f"{indent}android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();\n"
        f"{indent}if (authorgramIosPreviewParent instanceof "
        "org.telegram.ui.Components.ChatScrimPopupContainerLayout) {\n"
        f"{indent}    ((org.telegram.ui.Components.ChatScrimPopupContainerLayout) "
        "authorgramIosPreviewParent)\n"
        f"{indent}            .setFixedMessagePreview({preview});\n"
        f"{indent}}} else {\n"
        f"{indent}    // Defensive fallback: keep the preview visible and reachable rather\n"
        f"{indent}    // than crash if an upstream layout wrapper ever changes parentage.\n"
        f"{indent}    LinearLayout.LayoutParams authorgramFallbackPreviewParams = "
        "LayoutHelper.createLinear(\n"
        f"{indent}            LayoutHelper.MATCH_PARENT,\n"
        f"{indent}            LayoutHelper.WRAP_CONTENT\n"
        f"{indent}    );\n"
        f"{indent}    popupLayout.addView({preview}, 0, authorgramFallbackPreviewParams);\n"
        f"{indent}}}"
    )


def apply() -> None:
    """Rewrite only the known scope-invalid calls, then validate the result."""
    pre_apply_check()
    text = read_chat()

    text, fixed_count = UNSAFE_FIXED_RE.subn(_scope_safe_fixed_preview, text)
    text, bottom_count = UNSAFE_BOTTOM_RE.subn("", text)

    # Idempotency is intentional.  A committed already-safe ChatActivity needs no
    # mutation, while a legacy generated source is repaired exactly once.
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
        failures.append("unscoped scrimPopupContainerLayout.setFixedMessagePreview remains")
    if UNSAFE_BOTTOM in text:
        failures.append("unscoped scrimPopupContainerLayout.getBottomOffset remains")
    if FORBIDDEN_OLD_RECEIVER in text:
        failures.append("obsolete chatActivityEnterView fixed-preview receiver remains")

    if "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER" in text:
        if text.count(SAFE_MARKER) != 1:
            failures.append(
                f"scope-safe preview marker count is {text.count(SAFE_MARKER)}, expected 1"
            )
        for required in (
            "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
            "authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout",
            ".setFixedMessagePreview(iosPreview);",
            "popupLayout.addView(iosPreview, 0, authorgramFallbackPreviewParams);",
        ):
            if required not in text:
                failures.append(f"scope-safe fixed-preview invariant missing: {required}")

    # The stale height correction must be completely gone.  Main iOS already used
    # zero there; Play/classic now keeps Telegram's original popup-height formula.
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
