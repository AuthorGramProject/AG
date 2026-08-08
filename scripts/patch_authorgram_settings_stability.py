#!/usr/bin/env python3
"""Harden AuthorGram settings deep-link lifecycle and null handling."""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings/BaseAGSettingsActivity.java"
ROUTER = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java"
BASE_MARKER = "AUTHORGRAM_SAFE_SETTINGS_ROW_SCROLL"
ROUTER_MARKER = "AUTHORGRAM_SAFE_SETTINGS_DEEP_LINK"

BASE_OLD = '''    public void scrollToRow(String key, Runnable unknown) {
        if (rowMap.containsKey(key)) {
            listView.highlightRow(() -> {
                // noinspection ConstantConditions
                int position = rowMap.get(key);
                layoutManager.scrollToPositionWithOffset(position, dp(60));
                return position;
            });
        } else {
            unknown.run();
        }
    }
'''

BASE_NEW = '''    public void scrollToRow(String key, Runnable unknown) {
        // AUTHORGRAM_SAFE_SETTINGS_ROW_SCROLL
        // Deep links schedule this after fragment presentation, but the fragment
        // can still be detached or not have its RecyclerView ready. Fail closed
        // instead of dereferencing a stale/null listView or layoutManager.
        Integer position = rowMap.get(key);
        if (position == null) {
            if (unknown != null) {
                unknown.run();
            }
            return;
        }
        if (listView == null || layoutManager == null || listView.getLayoutManager() == null) {
            return;
        }
        listView.highlightRow(() -> {
            layoutManager.scrollToPositionWithOffset(position, dp(60));
            return position;
        });
    }
'''


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: anchor count is {count}, expected 1")
    return text.replace(old, new, 1)


def patch_base() -> None:
    text = read(BASE)
    if BASE_MARKER not in text:
        text = replace_once(text, BASE_OLD, BASE_NEW, "settings row scroll")
        write(BASE, text)


def patch_router() -> None:
    text = read(ROUTER)
    if ROUTER_MARKER in text:
        return

    old_signature = '''    public static void processDeepLink(Activity activity, Uri uri, Callback callback, Runnable unknown) {
        if (uri == null) {
            unknown.run();
            return;
        }
'''
    new_signature = '''    public static void processDeepLink(Activity activity, Uri uri, Callback callback, Runnable unknown) {
        // AUTHORGRAM_SAFE_SETTINGS_DEEP_LINK
        final Runnable safeUnknown = unknown != null ? unknown : () -> { };
        if (uri == null || callback == null) {
            safeUnknown.run();
            return;
        }
'''
    text = replace_once(text, old_signature, new_signature, "settings router entry")

    # All fallbacks in processDeepLink must be safe, including Play-only gates.
    process_start = text.find("    public static void processDeepLink(")
    process_end = text.find("\n    public interface Callback", process_start)
    if process_start < 0 or process_end < 0:
        raise SystemExit("unable to isolate processDeepLink")
    prefix = text[:process_start]
    method = text[process_start:process_end]
    suffix = text[process_end:]
    method = method.replace("unknown.run();", "safeUnknown.run();")
    method = method.replace("scrollToRow(rowFinal, unknown)", "scrollToRow(rowFinal, safeUnknown)")
    method = method.replace("importToRow(rowFinal, finalValue, unknown)", "importToRow(rowFinal, finalValue, safeUnknown)")
    text = prefix + method + suffix

    text = text.replace(
        "PasscodeHelper.getSettingsKey().equals(segments.get(1))",
        "TextUtils.equals(PasscodeHelper.getSettingsKey(), segments.get(1))",
        1,
    )
    write(ROUTER, text)


def validate() -> None:
    base = read(BASE)
    router = read(ROUTER)

    for token in (
        BASE_MARKER,
        "Integer position = rowMap.get(key);",
        "if (unknown != null)",
        "listView == null || layoutManager == null || listView.getLayoutManager() == null",
    ):
        if token not in base:
            raise SystemExit(f"settings scroll invariant missing: {token}")

    for token in (
        ROUTER_MARKER,
        "final Runnable safeUnknown = unknown != null ? unknown : () -> { };",
        "if (uri == null || callback == null)",
        "TextUtils.equals(PasscodeHelper.getSettingsKey(), segments.get(1))",
        "scrollToRow(rowFinal, safeUnknown)",
    ):
        if token not in router:
            raise SystemExit(f"settings router invariant missing: {token}")

    method_start = router.find("public static void processDeepLink(")
    method_end = router.find("public interface Callback", method_start)
    method = router[method_start:method_end]
    if "unknown.run();" in method:
        raise SystemExit("unsafe deep-link fallback remains")
    if "PasscodeHelper.getSettingsKey().equals(" in router:
        raise SystemExit("nullable passcode settings key comparison remains")
    print("AuthorGram settings deep-link stability passed")


def apply() -> None:
    patch_base()
    patch_router()
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
