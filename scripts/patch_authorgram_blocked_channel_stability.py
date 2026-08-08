#!/usr/bin/env python3
"""Remove synchronous Telegram storage reads from AuthorGram blocked-channel UI paths."""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILTER = ROOT / "TMessagesProj/src/main/java/toss/authorgram/filters/AGFilter.java"
MARKER = "AUTHORGRAM_NO_UI_THREAD_BLOCKED_CHAT_LOOKUP"

OLD = '''    public static ArrayList<Long> checkBlockedChannels(HashSet<Long> blockedChannels) {
        if (blockedChannels == null || blockedChannels.isEmpty()) return new ArrayList<>();
        ArrayList<Long> filtered = new ArrayList<>();
        try {
            final MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);
            final MessagesStorage ms = MessagesStorage.getInstance(UserConfig.selectedAccount);
            for (Long did : blockedChannels) {
                if (did == null) continue;
                if (did < 0) {
                    TLRPC.Chat chat = mc.getChat(-did);
                    if (chat == null) {
                        chat = ms.getChatSync(-did);
                    }
                    if (chat != null) {
                        filtered.add(did);
                        mc.putChat(chat, true);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return filtered;
    }
'''

NEW = '''    public static ArrayList<Long> checkBlockedChannels(HashSet<Long> blockedChannels) {
        // AUTHORGRAM_NO_UI_THREAD_BLOCKED_CHAT_LOOKUP
        // This method is consumed by settings/list rendering. Never call
        // MessagesStorage.getChatSync() here: one DB read per peer can stall the
        // main thread. The stored dialog id is already sufficient to preserve the
        // blocked-channel entry; callers can resolve display metadata lazily.
        ArrayList<Long> filtered = new ArrayList<>();
        if (blockedChannels == null || blockedChannels.isEmpty()) {
            return filtered;
        }
        for (Long did : blockedChannels) {
            if (did != null && did < 0) {
                filtered.add(did);
            }
        }
        return filtered;
    }
'''


def read() -> str:
    if not FILTER.is_file():
        raise SystemExit(f"Missing required source: {FILTER}")
    return FILTER.read_text(encoding="utf-8")


def write(text: str) -> None:
    FILTER.write_text(text, encoding="utf-8", newline="")


def apply() -> None:
    text = read()
    if MARKER not in text:
        count = text.count(OLD)
        if count != 1:
            raise SystemExit(f"blocked-channel anchor count is {count}, expected 1")
        text = text.replace(OLD, NEW, 1)
        write(text)
    validate()


def validate() -> None:
    text = read()
    for token in (
        MARKER,
        "if (did != null && did < 0)",
        "filtered.add(did);",
    ):
        if token not in text:
            raise SystemExit(f"blocked-channel stability invariant missing: {token}")

    method_start = text.find("public static ArrayList<Long> checkBlockedChannels")
    method_end = text.find("public static void onMessageEdited", method_start)
    if method_start < 0 or method_end < 0:
        raise SystemExit("unable to locate patched blocked-channel method")
    method = text[method_start:method_end]
    for forbidden in (
        "MessagesStorage",
        "getChatSync(",
        "mc.putChat(",
    ):
        if forbidden in method:
            raise SystemExit(f"synchronous blocked-channel lookup remains: {forbidden}")
    print("AuthorGram blocked-channel UI stability passed")


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
