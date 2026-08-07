#!/usr/bin/env python3
"""Repair the 12.9.2 chat UI patch-chain validator before release finalization.

The committed ChatActivity already uses the scope-safe parent-chain owner for the
Main-only iOS selected-message preview. The legacy final-chat generator still
validated the old local variable receiver verbatim, which made an otherwise
canonical source tree fail before Gradle started.

This repair is intentionally narrow and idempotent: it changes only that stale
validator expectation. The generator may still emit the known legacy form while
upgrading old source; patch_authorgram_chat_scope_safety.py immediately rewrites
that form to the canonical parent-chain implementation and validates the result.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "scripts/patch_authorgram_final_chat_ui.py"

OLD = '''    for required in (\n        marker,\n        "scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);",\n        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",\n    ):\n'''
NEW = '''    for required in (\n        marker,\n        ".setFixedMessagePreview(iosPreview);",\n        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",\n    ):\n'''


def main() -> None:
    text = TARGET.read_text(encoding="utf-8")
    if NEW in text:
        print("AuthorGram patch-chain validator is already scope-compatible")
        return
    count = text.count(OLD)
    if count != 1:
        raise SystemExit(
            "Unexpected final-chat validator shape: "
            f"expected exactly one stale block, found {count}"
        )
    TARGET.write_text(text.replace(OLD, NEW, 1), encoding="utf-8", newline="")
    check = TARGET.read_text(encoding="utf-8")
    if NEW not in check:
        raise SystemExit("Scope-compatible final-chat validator repair did not persist")
    print("AuthorGram patch-chain validator repaired for canonical scope-safe preview ownership")


if __name__ == "__main__":
    main()
